package com.sonex.mobile.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.sonex.core.Dsp
import kotlinx.serialization.Serializable

/**
 * Room + phone-position tuned thresholds. Because the phone might sit in a
 * corner, we don't use global constants — we measure this exact spot.
 *
 * trigger      = level above which speech-shaped sound counts as TALKING
 * boostTrigger = level of non-speech ambient noise that triggers a volume BOOST
 */
@Serializable
data class Calibration(
    val name: String = "Default",
    val noiseFloorDb: Double = -55.0,
    val mediaBaselineDb: Double = -35.0,
    val mediaPlusTalkDb: Double = -25.0,
    val sensitivity: Double = 0.6,   // 0..1 — a touch sensitive out-of-box (before calibration)
    val restoreDelaySec: Int = 3
) {
    /** Trigger sits between "media only" and "media + talk". */
    val trigger: Double
        get() = com.sonex.core.Thresholds.trigger(mediaBaselineDb, mediaPlusTalkDb, sensitivity)

    /** Boost when non-speech noise rises well above the media baseline. */
    val boostTrigger: Double
        get() = com.sonex.core.Thresholds.boostTrigger(mediaBaselineDb)
}

/**
 * Guided 3-step capture. Each step averages loudness for a few seconds and
 * writes the anchor. Position compensation is implicit: a corner phone simply
 * measures lower numbers, and because we use the *gaps* between anchors, the
 * decision logic stays valid regardless of placement.
 */
class Calibrator(
    /** Same RAW source the detector uses, so calibrated levels match runtime. */
    private val source: Int = MediaRecorder.AudioSource.UNPROCESSED
) {
    /** One calibration measurement: the chosen percentile + how steady it was. */
    data class StepResult(val levelDb: Double, val spreadDb: Double)

    fun measureDb(seconds: Int, onProgress: (Float) -> Unit): Double =
        measureStep(seconds, 50.0, onProgress).levelDb

    fun measureStep(seconds: Int, percentile: Double, onProgress: (Float) -> Unit): StepResult {
        val minBuf = AudioRecord.getMinBufferSize(
            DetectionEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            source,
            DetectionEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, DetectionEngine.FRAME_SAMPLES * 2 * 4)
        )
        val buf = ShortArray(DetectionEngine.FRAME_SAMPLES)
        // Discard the first ~0.3s: AGC ramp-up and touch-tap transients would
        // otherwise poison the anchor.
        val warmup = 10
        val totalFrames = seconds * 1000 / DetectionEngine.FRAME_MS + warmup
        val frames = mutableListOf<Double>()
        rec.startRecording()
        try {
            repeat(totalFrames) { i ->
                val n = rec.read(buf, 0, buf.size)
                if (n > 0 && i >= warmup) frames += Dsp.rmsDb(buf, n)
                onProgress((i + 1f) / totalFrames)
            }
        } finally { rec.stop(); rec.release() }
        if (frames.isEmpty()) return StepResult(-60.0, 0.0)
        return StepResult(
            com.sonex.core.Stats.percentile(frames, percentile),
            com.sonex.core.Stats.iqr(frames)
        )
    }
}
