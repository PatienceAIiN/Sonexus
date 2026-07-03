package com.sonex.mobile.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sonex.core.RoomState
import com.sonex.core.TargetRule
import com.sonex.mobile.R
import com.sonex.mobile.data.ModelStore
import com.sonex.mobile.data.Prefs
import com.sonex.mobile.output.BluetoothTarget
import com.sonex.mobile.output.CastTarget
import com.sonex.mobile.output.OutputRouter
import com.sonex.mobile.output.PhoneSpeakerTarget
import com.sonex.mobile.output.TvTarget
import com.sonex.mobile.output.WiredHeadsetTarget
import com.sonex.mobile.pairing.PairingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Always-on foreground service. Runs the DetectionEngine and, on each state
 * change, fans commands out to every active output (phone/BT/TV/Cast) via the
 * OutputRouter with per-device rules. An active phone call forces TALKING and
 * blocks restore until the call ends AND the room is quiet. Voice control
 * (when consented) shares the same mic stream via a PCM tap.
 * A persistent notification + mic indicator keeps the always-on mic honest.
 */
class ListeningService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: DetectionEngine? = null
    private lateinit var audio: AudioManager
    private lateinit var router: OutputRouter
    private var pairing: PairingClient? = null
    private var callMonitor: CallMonitor? = null

    @Volatile private var callActive = false
    @Volatile private var lastRoomState = RoomState.QUIET

    companion object {
        const val CHANNEL = "sonex_listening"
        val stateFlow = kotlinx.coroutines.flow.MutableStateFlow(RoomState.QUIET to -60.0)

        /** True while the service (mic) is running. Only the user stops it. */
        val running = kotlinx.coroutines.flow.MutableStateFlow(false)
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        pairing = PairingClient(this)

        router = OutputRouter(
            ruleFor = { id -> Prefs.targetRule(this, id) },
            duckPercent = { Prefs.duckLevel(this) }
        ).apply {
            register(PhoneSpeakerTarget(audio))
            register(BluetoothTarget(audio))
            register(WiredHeadsetTarget(audio))
            register(TvTarget(pairing!!))
            register(CastTarget(this@ListeningService))
        }

        // Re-resolve the TV's LAN address; DHCP may have moved it since pairing.
        scope.launch { pairing?.discover() }
        // OTA model check (cheap no-op if unchanged).
        scope.launch {
            val url = Prefs.serverUrl(this@ListeningService) ?: return@launch
            val key = Prefs.deviceKey(this@ListeningService) ?: return@launch
            val id = Prefs.deviceId(this@ListeningService) ?: return@launch
            ModelStore(this@ListeningService).sync(url, key, id)
        }

        callMonitor = CallMonitor(this) { active -> onCallState(active) }.also { it.start() }
        startForeground(1, buildNotification(RoomState.QUIET))
        running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (engine == null) {
            val cal = Prefs.currentCalibration(this)
            engine = DetectionEngine(cal, buildClassifier(cal)) { state, db -> onState(state, db) }
                .also { it.start() }
        }
        return START_STICKY
    }

    /** ML models when present + verified; energy heuristic otherwise. */
    private fun buildClassifier(cal: Calibration): FrameClassifier {
        val store = ModelStore(this)
        val fallback = HeuristicClassifier(cal)
        val vad = store.verifiedFile("vad")
        val sound = store.verifiedFile("sound")
        return if (vad != null || sound != null) MlClassifier(vad, sound, cal, fallback) else fallback
    }

    private fun onState(state: RoomState, db: Double) {
        lastRoomState = state
        // During a call the room is forced-ducked; ignore engine-driven restores.
        if (callActive && state == RoomState.QUIET) return
        stateFlow.value = state to db
        logEvent(state, db)
        // Solo WHISPER = hold everything (RulePolicy returns null anyway) — just
        // show it. WHISPER_GROUP still routes: it gets a gentle duck.
        if (state != RoomState.WHISPER) scope.launch { router.onState(state) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1, buildNotification(state))
    }

    // ---- Learning loop: remember what happened, contribute it when the user
    // stops listening (only with the "learn my home" consent ON). ----
    private val events = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun logEvent(state: RoomState, db: Double) {
        if (events.size < 500) events += """{"ts":"${java.time.Instant.now()}","type":"STATE_CHANGE","room_state":"$state","db":${"%.1f".format(db)},"source":"engine"}"""
    }

    private fun submitEvents() {
        if (!Prefs.consentTraining(this) || events.isEmpty()) return
        val batch = events.toList().also { events.clear() }
        val base = (Prefs.serverUrl(this) ?: "").removeSuffix("/")
        val key = Prefs.deviceKey(this) ?: return
        if (base.isBlank()) return
        // GlobalScope-style fire-and-forget: the service scope dies with us.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val conn = java.net.URL("$base/v1/events").openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-Device-Key", key)
                conn.outputStream.use { it.write("""{"events":[${batch.joinToString(",")}]}""".toByteArray()) }
                conn.responseCode; conn.disconnect()
            }
        }
    }

    private fun onCallState(active: Boolean) {
        callActive = active
        if (active) {
            stateFlow.value = RoomState.TALKING to stateFlow.value.second
            scope.launch { router.onState(RoomState.TALKING) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(1, buildNotification(RoomState.TALKING))
        } else if (lastRoomState == RoomState.QUIET) {
            // Call over and the room is already quiet => restore now.
            stateFlow.value = RoomState.QUIET to stateFlow.value.second
            scope.launch { router.onState(RoomState.QUIET) }
        } // else: the engine will restore once the room actually goes quiet.
    }

    private fun buildNotification(state: RoomState): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "SoNex listening", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val text = when (state) {
            RoomState.TALKING -> if (callActive) "On a call — volume lowered"
                                 else "Someone's talking — volume lowered"
            RoomState.BOOST -> "Room got loud — volume raised"
            RoomState.WHISPER -> "Whispering — volume untouched"
            RoomState.WHISPER_GROUP -> "Whispering — volume eased down"
            RoomState.QUIET -> "Listening · mic active"
        }
        // Tapping opens the app on the live animated state screen.
        val openApp = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, com.sonex.mobile.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("SoNex")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)  // update in place: no re-buzz, no flicker
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }

    override fun onDestroy() {
        submitEvents() // contribute this session's data for training (consent-gated)
        running.value = false
        stateFlow.value = RoomState.QUIET to -60.0
        engine?.stop()
        callMonitor?.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
