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
        private const val VAD_WINDOW = 512              // Silero v5 fixed window @16k
        private const val YAMNET_WINDOW = 15_600        // 0.975s @16k
        private const val YAMNET_EVERY_FRAMES = 16      // run YAMNet ~every 0.5s
        /** AudioSet classes 0..24 are the human-voice group (speech, shout, etc.). */
        private const val LAST_SPEECH_CLASS = 24
    }

    private var broken = false

    // Adaptive thresholds + smoothed VAD verdict with hangover (see :core).
    private val adapter = ThresholdAdapter(
        ThresholdAdapter.ThresholdBase(calibration.trigger, calibration.boostTrigger, calibration.noiseFloorDb)
    )
    private val smoother = SpeechProbSmoother()
    private var vadSeen = false
    private val floorTracker = com.sonex.core.FloorTracker()

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
            val floor = floorTracker.update(db)  // robust ambient, every frame
            // Silero decides the HARD part — is this a person speaking? — robustly
            // in noise. Gate it floor-relatively so a quiet phone mic still clears
            // it. Everything that is NOT confident speech is handed to the proven
            // heuristic (quiet / machine-boost), which avoids boosting on media.
            val talkGate = minOf(adapter.trigger, floor + com.sonex.core.RoomStateMachine.TALK_OVER_FLOOR_DB)
            if (vadSeen && smoother.speaking && db > talkGate) FrameKind.SPEECH
            else fallback.classify(buf, n, db)
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
