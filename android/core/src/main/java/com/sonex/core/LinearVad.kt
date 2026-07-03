package com.sonex.core

import kotlinx.serialization.Serializable
import kotlin.math.exp

/**
 * A tiny LEARNED speech/noise/quiet/whisper classifier — the "more advanced
 * algorithm" that replaces the fixed heuristic when a trained model is present.
 *
 * It's a multinomial-logistic (softmax) model over three cheap features the app
 * already computes for every frame:
 *   f0 = level above the room's noise floor, in dB   (loudness)
 *   f1 = zero-crossing rate                          (voiced vs breath vs hum)
 *   f2 = level modulation swing, in dB (p90-p10 ~1s) (a person pulses; machines don't)
 *
 * Because it's linear over standardised features it is trained server-side in
 * pure numpy on consented clips + dataset priors, exported as this small JSON,
 * and shipped OTA. Robust to loud steady machines in a way ZCR thresholds aren't:
 * the model learns that high modulation + voice-band ZCR = speech even when the
 * absolute level is dominated by a cooler. Falls back to the heuristic if absent.
 */
@Serializable
data class LinearVad(
    val version: String = "0",
    /** Class order matches the rows of [weights] / entries of [bias]. */
    val classes: List<String> = listOf("QUIET", "SPEECH", "NOISE", "WHISPER"),
    /** [nClasses][nFeatures] learned weights. */
    val weights: List<List<Double>> = emptyList(),
    /** [nClasses] intercepts. */
    val bias: List<Double> = emptyList(),
    /** Optional feature standardisation (same order as the feature vector). */
    val mean: List<Double> = emptyList(),
    val std: List<Double> = emptyList()
) {
    val isUsable: Boolean
        get() = weights.isNotEmpty() && weights.size == classes.size &&
            bias.size == classes.size && weights.all { it.size == NUM_FEATURES }

    /** Build the standardised feature vector for one frame. Raw 4 measurements
     *  are expanded to 6 engineered features — MUST match server training.expand(). */
    private fun features(rmsOverFloorDb: Double, zcr: Double, swingDb: Double, zcrFlux: Double): DoubleArray {
        val voiced = if (zcr in 0.05..0.35) 1.0 else 0.0
        // "changing" — level swings OR ZCR fluctuates (masking-robust person cue).
        val modulated = if (swingDb >= ModulationTracker.STEADY_SWING_DB ||
            zcrFlux >= ZcrTracker.SPEECH_FLUX) 1.0 else 0.0
        val raw = doubleArrayOf(rmsOverFloorDb, zcr, swingDb, zcrFlux, voiced, modulated)
        if (mean.size == NUM_FEATURES && std.size == NUM_FEATURES) {
            for (i in raw.indices) {
                val s = std[i]; if (s > 1e-9) raw[i] = (raw[i] - mean[i]) / s
            }
        }
        return raw
    }

    /** Softmax probabilities per class, in [classes] order. */
    fun probabilities(rmsOverFloorDb: Double, zcr: Double, swingDb: Double, zcrFlux: Double): DoubleArray {
        val f = features(rmsOverFloorDb, zcr, swingDb, zcrFlux)
        val logits = DoubleArray(classes.size) { c ->
            var s = bias[c]
            val w = weights[c]
            for (i in f.indices) s += w[i] * f[i]
            s
        }
        val max = logits.max()
        var sum = 0.0
        for (i in logits.indices) { logits[i] = exp(logits[i] - max); sum += logits[i] }
        for (i in logits.indices) logits[i] /= sum
        return logits
    }

    /** Winning label + its probability. */
    fun predict(rmsOverFloorDb: Double, zcr: Double, swingDb: Double, zcrFlux: Double): Pair<String, Double> {
        val p = probabilities(rmsOverFloorDb, zcr, swingDb, zcrFlux)
        var best = 0
        for (i in p.indices) if (p[i] > p[best]) best = i
        return classes[best] to p[best]
    }

    companion object {
        const val NUM_FEATURES = 6   // rms-over-floor, zcr, swing, zcr-flux, voiced-band, modulated

        fun labelToKind(label: String): FrameKind = when (label) {
            "SPEECH" -> FrameKind.SPEECH
            "NOISE" -> FrameKind.NOISE
            "WHISPER" -> FrameKind.WHISPER
            else -> FrameKind.QUIET
        }
    }
}
