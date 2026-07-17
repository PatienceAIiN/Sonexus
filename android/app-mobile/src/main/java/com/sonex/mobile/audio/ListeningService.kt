package com.sonex.mobile.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
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
    private var collector: ClipCollector? = null
    @Volatile private var lastClipMs = 0L
    private val lastClipByLabel = HashMap<String, Long>()
    private var clipsToday = 0
    private var clipDayStart = 0L

    companion object {
        const val CHANNEL = "sonex_listening"
        val stateFlow = kotlinx.coroutines.flow.MutableStateFlow(RoomState.QUIET to -60.0)

        /** True while the service (mic) is running. Only the user stops it. */
        val running = kotlinx.coroutines.flow.MutableStateFlow(false)

        /** Human-readable action for the CURRENT state on the active output —
         *  reflects the user's per-device rule (muted / paused / lowered / …). */
        val actionLabel = kotlinx.coroutines.flow.MutableStateFlow("Ready")

        /** Max consented training clips uploaded per day (keeps it light). */
        const val DAILY_CLIP_CAP = 120
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Go foreground IMMEDIATELY (Android 14 kills a mic FGS that doesn't) and
        // mark running right away, so the UI shows "listening" and NOTHING below
        // can leave us stuck on "Ready". Every optional part is fault-isolated.
        startForegroundSafely()
        running.value = true
        // Show a live "listening" status immediately — NOT the stale "Ready" —
        // even before the room state first changes, so the app never looks dead.
        stateFlow.value = RoomState.QUIET to stateFlow.value.second
        actionLabel.value = statusText(RoomState.QUIET)

        pairing = PairingClient(this)
        router = OutputRouter(
            ruleFor = { id -> Prefs.targetRule(this, id) },
            duckPercent = { Prefs.duckLevel(this) }
        ).apply {
            runCatching { register(PhoneSpeakerTarget(audio)) }
            runCatching { register(BluetoothTarget(audio)) }
            runCatching { register(WiredHeadsetTarget(audio)) }
            runCatching { pairing?.let { register(TvTarget(it)) } }
            // Cast needs Play Services — can throw on some devices; must not abort.
            runCatching { register(CastTarget(this@ListeningService)) }
        }

        scope.launch { runCatching { pairing?.discover() } }
        scope.launch {
            runCatching {
                val url = Prefs.serverUrl(this@ListeningService) ?: return@launch
                val key = Prefs.deviceKey(this@ListeningService) ?: return@launch
                val id = Prefs.deviceId(this@ListeningService) ?: return@launch
                ModelStore(this@ListeningService).sync(url, key, id)
            }
        }
        runCatching { callMonitor = CallMonitor(this) { active -> onCallState(active) }.also { it.start() } }
    }

    private fun startForegroundSafely() {
        val notif = buildNotification(RoomState.QUIET)
        runCatching {
            androidx.core.app.ServiceCompat.startForeground(
                this, 1, notif,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
        }.onFailure { runCatching { startForeground(1, notif) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (engine == null) runCatching {
            val cal = Prefs.currentCalibration(this)
            val col = ClipCollector(DetectionEngine.SAMPLE_RATE)
            collector = col
            engine = DetectionEngine(cal, buildClassifier(cal), MicSource.best(this),
                    Prefs.micDeviceId(this), audio,
                    onLevel = { db -> onLevel(db) }) { state, db -> onState(state, db) }
                .also {
                    // Keep only a 1.5s rolling buffer in RAM; it is only ever
                    // uploaded when the user opted into "Let SoNex learn my home".
                    it.pcmTap = { b, n -> col.append(b, n) }
                    it.start()
                }
        }.onFailure { android.util.Log.w("SonexSvc", "engine start failed", it) }
        return START_STICKY
    }

    /** ML models when present + verified; energy heuristic otherwise. */
    private fun buildClassifier(cal: Calibration): FrameClassifier {
        val store = ModelStore(this)
        val fallback = HeuristicClassifier(cal)
        // Type-based detection: Silero VAD answers "is this a human voice?" —
        // independent of loudness — which the ZCR heuristic can't do reliably.
        val vad = store.verifiedFile("vad")
        if (vad != null) return MlClassifier(vad, store.verifiedFile("sound"), cal, fallback)
        store.verifiedFile("lite")?.let { return LiteClassifier(it, cal, fallback) }
        return fallback
    }

    /** Upload a short labelled training clip — only with consent. Gathers a rich,
     *  BALANCED spread across every category (speech/discussion/gossip/fighting =>
     *  SPEECH, vehicles/coolers/machinery/external => NOISE, murmuring => WHISPER,
     *  and quiet) via per-label cooldowns, with a small overall gap and a daily
     *  cap. The server trains on it then deletes it. */
    private fun maybeCollectClip(state: RoomState) {
        if (!Prefs.consentTraining(this) || !Prefs.consentUploadClips(this)) return
        val now = System.currentTimeMillis()
        // Reset the daily counter each 24h so collection keeps refreshing over time.
        if (now - clipDayStart > 86_400_000L) { clipDayStart = now; clipsToday = 0 }
        if (clipsToday >= DAILY_CLIP_CAP) return
        if (now - lastClipMs < 15_000) return  // don't burst
        val label = when (state) {
            RoomState.TALKING -> "SPEECH"
            RoomState.BOOST -> "NOISE"
            RoomState.WHISPER, RoomState.WHISPER_GROUP -> "WHISPER"
            RoomState.QUIET -> "QUIET"
        }
        // Per-label cooldown keeps the dataset balanced (quiet is sampled sparsely).
        val cooldown = if (label == "QUIET") 300_000L else 60_000L
        if (now - (lastClipByLabel[label] ?: 0L) < cooldown) return
        val pcm = collector?.snapshot(DetectionEngine.SAMPLE_RATE) ?: return  // need >= 1s
        lastClipMs = now
        lastClipByLabel[label] = now
        clipsToday++
        com.sonex.mobile.data.ServerSync.uploadClip(
            this, Wav.encode(pcm, DetectionEngine.SAMPLE_RATE), label, state.name
        )
    }

    /** The rule for whichever output the media is actually playing on now. */
    private fun activeMediaRule(): com.sonex.core.TargetRule {
        val wired = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val id = when {
            audio.isBluetoothA2dpOn -> "bt"
            wired -> "wired"
            else -> "phone"
        }
        return Prefs.targetRule(this, id)
    }

    /** What SoNex is actually doing right now, in the user's words — honours the
     *  per-device rule so "Muted"/"Paused" show instead of always "lowered". */
    private fun statusText(state: RoomState): String {
        if (state == RoomState.QUIET) return "Listening · mic active"
        val cmd = com.sonex.core.RulePolicy.commandFor(
            state, activeMediaRule(), Prefs.duckLevel(this), 100
        )
        val verb = when (cmd?.action) {
            com.sonex.core.Action.MUTE -> "muted"
            com.sonex.core.Action.PAUSE -> "paused"
            com.sonex.core.Action.DUCK -> "volume lowered"
            com.sonex.core.Action.BOOST -> "volume raised"
            else -> "volume untouched"
        }
        return when (state) {
            RoomState.TALKING -> if (callActive) "On a call — $verb" else "Talking — $verb"
            RoomState.BOOST -> "Room got loud — $verb"
            RoomState.WHISPER -> "Whispering — volume untouched"
            RoomState.WHISPER_GROUP -> "Whispering — $verb"
            RoomState.QUIET -> "Listening · mic active"
        }
    }

    /** Live loudness between state changes: moves the on-screen room level so the
     *  user can SEE SoNex is listening. Keeps the current state's colour; never
     *  touches routing or the notification (those change only on real states). */
    private fun onLevel(db: Double) {
        if (callActive) return
        stateFlow.value = stateFlow.value.first to db
    }

    private fun onState(state: RoomState, db: Double) {
        lastRoomState = state
        // Same-device media guard: if THIS phone is playing media (reels/music),
        // the mic mostly hears its own speaker — don't fight your own audio. Hold
        // volume normal instead of ducking it. (A paired TV over LAN isn't local,
        // so it still ducks for real conversation.)
        if (audio.isMusicActive() && !callActive) {
            stateFlow.value = RoomState.QUIET to db
            actionLabel.value = "Your media is playing — volume normal"
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(1, buildNotification(RoomState.QUIET))
            return
        }
        // During a call the room is forced-ducked; ignore engine-driven restores.
        if (callActive && state == RoomState.QUIET) return
        stateFlow.value = state to db
        actionLabel.value = statusText(state)
        logEvent(state, db)
        maybeCollectClip(state)
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
            actionLabel.value = statusText(RoomState.TALKING)   // "On a call — …"
            scope.launch { router.onState(RoomState.TALKING) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(1, buildNotification(RoomState.TALKING))
        } else if (lastRoomState == RoomState.QUIET) {
            // Call over and the room is already quiet => restore now.
            stateFlow.value = RoomState.QUIET to stateFlow.value.second
            actionLabel.value = statusText(RoomState.QUIET)     // "Listening · mic active"
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
        val text = statusText(state)
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
