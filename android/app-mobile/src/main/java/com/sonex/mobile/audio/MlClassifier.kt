package com.sonex.mobile.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.sonex.core.FrameKind
import com.sonex.core.MlDecision
import com.sonex.core.SpeechProbSmoother
import com.sonex.core.ThresholdAdapter
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Phase-2 classifier: Silero VAD (ONNX) answers "is someone speaking?",
 * YAMNet (TFLite) answers "speech or non-speech noise?" so DUCK vs BOOST is
 * decided correctly. Any model failure permanently drops to the heuristic for
 * this session — detection must never die because a model file is bad.
 */
class MlClassifier(
    vadModel: File?,
    soundModel: File?,
    private val calibration: Calibration,
    private val fallback: FrameClassifier
) : FrameClassifier {

    companion object {
        private const val TAG = "SonexMl"
        /** Flip to log per-frame cue values (db/floor/voiced/harm/decision) to
         *  logcat tag "SonexMl" for on-device detection tuning. Off in shipping. */
        private const val DEBUG = false
        private const val VAD_WINDOW = 512              // Silero v5 fixed window @16k
        private const val YAMNET_WINDOW = 15_600        // 0.975s @16k
        private const val YAMNET_EVERY_FRAMES = 16      // run YAMNet ~every 0.5s
        /** AudioSet classes 0..24 are the human-voice group (speech, shout, etc.). */
        private const val LAST_SPEECH_CLASS = 24
        /** Tiny margin over ambient so Silero's near-silence jitter isn't "voice".
         *  NOT a loudness gate — real speech clears this by a mile. */
        private const val VOICE_OVER_FLOOR_DB = 6.0
        /** Voiced-speech zero-crossing band (matches Dsp.isSpeechShaped). Airflow
         *  and hiss ride ABOVE 0.35; steady hum sits BELOW 0.05 — both fail this,
         *  voiced speech passes. This alone rejects the reported cooler/fan case. */
        private const val VOICED_ZCR_LO = 0.05
        private const val VOICED_ZCR_HI = 0.35
        /** Peak normalized autocorrelation above which a sound is periodic (voiced).
         *  On-device measurement: voiced speech reads 0.38-0.65, a quiet/ambient
         *  room 0.01-0.30. 0.35 sits in that gap — catches speech, rejects noise. */
        private const val HARMONIC_MIN = 0.35
        /** A voice riding this far above the room floor is a person talking OVER a
         *  machine. A loud cooler destroys harmonicity (broadband masks the voice),
         *  so when speech is well above the floor we accept it on the level
         *  excursion instead — the cooler itself sits AT the floor, not above it. */
        private const val LOUD_VOICE_OVER_FLOOR_DB = 8.0
    }

    private var broken = false
    private var dbgTick = 0

    // Adaptive thresholds + smoothed VAD verdict with hangover (see :core).
    private val adapter = ThresholdAdapter(
        ThresholdAdapter.ThresholdBase(calibration.trigger, calibration.boostTrigger, calibration.noiseFloorDb)
    )
    // Sensitive enough for mild gossip; false positives are held off by the
    // floor-relative dB gate + the heuristic machine-veto in classify().
    private val smoother = SpeechProbSmoother(onThreshold = 0.6, offThreshold = 0.35, hangoverWindows = 6)
    private var vadSeen = false
    private val floorTracker = com.sonex.core.FloorTracker()
    private val modulation = com.sonex.core.ModulationTracker()  // steady machine vs pulsing person

    // ---- Silero VAD ----
    private var ort: OrtEnvironment? = null
    private var vad: OrtSession? = null
    private var vadState = Array(2) { Array(1) { FloatArray(128) } }
    private val vadBuf = FloatArray(VAD_WINDOW)
    private var vadFill = 0

    // ---- YAMNet ----
    private var yamnet: Interpreter? = null
    private val soundRing = FloatArray(YAMNET_WINDOW)
    private var soundWritten = 0
    private var framesSinceYamnet = 0
    private var lastSoundIsSpeech: Boolean? = null

    init {
        try {
            if (vadModel?.isFile == true) {
                ort = OrtEnvironment.getEnvironment()
                vad = ort!!.createSession(vadModel.absolutePath)
            }
            if (soundModel?.isFile == true) {
                yamnet = Interpreter(soundModel)
            }
            if (vad == null && yamnet == null) broken = true // nothing to run
        } catch (t: Throwable) {
            Log.w(TAG, "Model init failed, using heuristic", t)
            broken = true
        }
    }

    override fun classify(buf: ShortArray, n: Int, db: Double): FrameKind {
        if (broken) return fallback.classify(buf, n, db)
        return try {
            feed(buf, n)
            fallback.classify(buf, n, db)       // keep heuristic trackers warm
            val floor = floorTracker.update(db) // robust ambient, every frame
            val steady = modulation.update(db) < com.sonex.core.ModulationTracker.STEADY_SWING_DB

            // TYPE-BASED, not level-based. SPEECH now requires SEVERAL INDEPENDENT
            // voice cues to AGREE, so broadband airflow/hiss that fools ONE model
            // (Silero has been seen to false-fire on a running cooler) can't fool
            // all of them:
            //  (1) Silero temporal VAD says "speaking" (smoothed + hangover)
            val vadSpeaking = vadSeen && smoother.speaking
            //  (2) spectral shape is voiced (mid-band ZCR) — not hiss/hum/airflow
            val zcr = com.sonex.core.Dsp.zeroCrossingRate(buf, n)
            val voicedShape = zcr in VOICED_ZCR_LO..VOICED_ZCR_HI
            //  (3) YAMNet's dominant AudioSet class is human-voice. null (not yet
            //      warmed up) does NOT veto — only an explicit non-speech verdict
            //      does, so real early speech is never blocked at cold start.
            val soundSaysSpeech = lastSoundIsSpeech != false
            //  (4) level PULSES like syllables, not a flat machine (!steady), and
            //      (5) clears a tiny floor margin so near-silence jitter isn't voice.
            val aboveFloor = db > floor + VOICE_OVER_FLOOR_DB
            //  (6) periodic like a voice (harmonic) — the cue airflow CAN'T fake.
            //      Computed last (&&-short-circuit) so it only runs when it matters.
            val harm = com.sonex.core.Dsp.harmonicity(buf, n)
            // Silero VAD proved BROKEN on-device — it returns "not speaking" even
            // for loud, clearly voiced, harmonic speech (verified on-device). So it
            // is NO LONGER REQUIRED. A human voice is detected TWO ways so it works
            // both in a quiet room AND over a running cooler:
            //  (a) harmonic voice — clearly periodic, above the floor (quiet room)
            //  (b) voice over a machine — a loud cooler masks harmonicity, but the
            //      voice still rides well above the floor (the cooler sits AT the
            //      floor). The state machine's density requirement stops the
            //      cooler's own sporadic spikes from latching TALKING.
            val harmonicVoice = aboveFloor && harm >= HARMONIC_MIN
            val voiceOverMachine = db > floor + LOUD_VOICE_OVER_FLOOR_DB
            val voice = voicedShape && soundSaysSpeech && !steady &&
                (harmonicVoice || voiceOverMachine)

            val loud = db > floor + com.sonex.core.RoomStateMachine.BOOST_OVER_FLOOR_DB
            val kind = when {
                voice -> FrameKind.SPEECH   // a real person -> duck
                loud -> FrameKind.NOISE     // loud non-voice -> boost (machine/vehicle)
                else -> FrameKind.QUIET
            }
            if (DEBUG && ++dbgTick >= 15) {
                dbgTick = 0
                Log.d(TAG, "CUES db=%.1f floor=%.1f | vad=%b voiced=%b(zcr=%.2f) sound=%b !steady=%b above=%b harm=%.2f => %s"
                    .format(db, floor, vadSpeaking, voicedShape, zcr, soundSaysSpeech, !steady, aboveFloor, harm, kind))
            }
            kind
        } catch (t: Throwable) {
            Log.w(TAG, "Inference failed, dropping to heuristic", t)
            broken = true
            fallback.classify(buf, n, db)
        }
    }

    private fun feed(buf: ShortArray, n: Int) {
        for (i in 0 until n) {
            val f = buf[i] / 32768f
            // VAD window
            vadBuf[vadFill++] = f
            if (vadFill == VAD_WINDOW) {
                runVad()?.let { smoother.update(it); vadSeen = true }
                vadFill = 0
            }
            // YAMNet ring
            soundRing[soundWritten % YAMNET_WINDOW] = f
            soundWritten++
        }
        if (++framesSinceYamnet >= YAMNET_EVERY_FRAMES && soundWritten >= YAMNET_WINDOW) {
            framesSinceYamnet = 0
            lastSoundIsSpeech = runYamnet()
        }
    }

    private fun runVad(): Double? {
        val session = vad ?: return null
        val env = ort ?: return null
        OnnxTensor.createTensor(env, FloatBuffer.wrap(vadBuf), longArrayOf(1, VAD_WINDOW.toLong())).use { input ->
        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16_000)), longArrayOf(1)).use { sr ->
        OnnxTensor.createTensor(env, vadState).use { state ->
            session.run(mapOf("input" to input, "sr" to sr, "state" to state)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val prob = (out[0].value as Array<FloatArray>)[0][0].toDouble()
                @Suppress("UNCHECKED_CAST")
                (out.get("stateN").orElse(null)?.value as? Array<Array<FloatArray>>)?.let { vadState = it }
                return prob
            }
        }}}
    }

    private fun runYamnet(): Boolean? {
        val interp = yamnet ?: return null
        // Unroll the ring into chronological order.
        val start = soundWritten % YAMNET_WINDOW
        val input = FloatArray(YAMNET_WINDOW) { soundRing[(start + it) % YAMNET_WINDOW] }
        val scores = Array(1) { FloatArray(521) }
        interp.run(input, scores)
        var top = 0
        for (i in scores[0].indices) if (scores[0][i] > scores[0][top]) top = i
        return top <= LAST_SPEECH_CLASS
    }

    override fun close() {
        runCatching { vad?.close(); ort = null }
        runCatching { yamnet?.close() }
    }
}
