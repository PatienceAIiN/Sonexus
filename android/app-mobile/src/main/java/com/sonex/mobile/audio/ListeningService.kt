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
import com.sonex.core.IntentParser
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
import com.sonex.mobile.voice.VoiceController
import com.sonex.mobile.voice.VoskTranscriptSource
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
    private var voice: VoskTranscriptSource? = null

    @Volatile private var callActive = false
    @Volatile private var lastRoomState = RoomState.QUIET

    companion object {
        const val CHANNEL = "sonex_listening"
        const val WAKE_CHANNEL = "sonex_wake"
        val stateFlow = kotlinx.coroutines.flow.MutableStateFlow(RoomState.QUIET to -60.0)

        /** Manual device controls from the UI: (targetId, command). "all" broadcasts. */
        val manualCommands =
            kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, com.sonex.core.Command>>(
                extraBufferCapacity = 16
            )

        /** True while the service (mic) is running. Only the user stops it. */
        val running = kotlinx.coroutines.flow.MutableStateFlow(false)

        /** True while the wake word is armed ("SoNex…" heard, awaiting a command). */
        val wakeActive = kotlinx.coroutines.flow.MutableStateFlow(false)

        /** Last voice intent that fired (for the UI toast/animation). */
        val lastVoiceIntent =
            kotlinx.coroutines.flow.MutableStateFlow<com.sonex.core.VoiceIntent?>(null)
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

        // Manual per-device controls from the Home screen.
        scope.launch {
            manualCommands.collect { (id, cmd) ->
                if (id == "all") router.broadcast(cmd)
                else router.activeTargets().find { it.id == id }?.send(cmd)
            }
        }

        callMonitor = CallMonitor(this) { active -> onCallState(active) }.also { it.start() }
        startForeground(1, buildNotification(RoomState.QUIET))
        running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (engine == null) {
            val cal = Prefs.currentCalibration(this)
            engine = DetectionEngine(cal, buildClassifier(cal)) { state, db -> onState(state, db) }
                .also {
                    attachVoiceControl(it)
                    it.start()
                }
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

    /** Voice control rides the same mic: active whenever listening is on. */
    private fun attachVoiceControl(engine: DetectionEngine) {
        val models = VoskTranscriptSource.installedModels(filesDir)
        if (models.isEmpty()) return
        var disarm: kotlinx.coroutines.Job? = null
        val controller = VoiceController(
            onWake = {
                wakeActive.value = true
                playWakeTone()
                showWakeNotification()
                disarm?.cancel()
                disarm = scope.launch {
                    kotlinx.coroutines.delay(8_000) // mirrors WakeWordGate's window
                    wakeActive.value = false
                    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(2)
                }
            }
        ) { intent ->
            wakeActive.value = false
            lastVoiceIntent.value = intent
            scope.launch {
                router.broadcast(
                    IntentParser.toCommand(intent, Prefs.duckLevel(this@ListeningService), 100)
                )
            }
        }
        val source = VoskTranscriptSource(models) { text -> controller.onTranscript(text) }
        if (source.available) {
            voice = source
            engine.pcmTap = { buf, n -> source.accept(buf, n) }
        }
    }

    private fun onState(state: RoomState, db: Double) {
        lastRoomState = state
        // During a call the room is forced-ducked; ignore engine-driven restores.
        if (callActive && state == RoomState.QUIET) return
        stateFlow.value = state to db
        scope.launch { router.onState(state) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1, buildNotification(state))
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

    /** Short acknowledge chirp, like "OK Google". */
    private fun playWakeTone() {
        runCatching {
            val tone = android.media.ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 180)
            scope.launch { kotlinx.coroutines.delay(400); runCatching { tone.release() } }
        }
    }

    /** Heads-up "listening" cue that works even when the app isn't open. */
    private fun showWakeNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(WAKE_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(WAKE_CHANNEL, "SoNex wake word", NotificationManager.IMPORTANCE_HIGH)
                    .apply { setSound(null, null) } // we already chirped
            )
        }
        nm.notify(2, NotificationCompat.Builder(this, WAKE_CHANNEL)
            .setContentTitle("🎙 SoNex is listening…")
            .setContentText("Say a command — \"lower volume\", \"stop\", \"awaaz kam karo\"")
            .setSmallIcon(R.drawable.ic_mic)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(8_000)
            .setAutoCancel(true)
            .build())
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
            RoomState.QUIET -> "Listening · mic active"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("SoNex")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running.value = false
        stateFlow.value = RoomState.QUIET to -60.0
        engine?.stop()
        voice?.close()
        callMonitor?.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
