package com.sonex.core

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Framework-free signal math shared by the DetectionEngine and Calibrator.
 * Operating on raw 16-bit PCM frames keeps it unit-testable on the JVM.
 */
object Dsp {
    /** dBFS floor reported for an all-zero frame. */
    const val SILENCE_DB = -100.0

    /** Root-mean-square level of the first [n] samples, in dBFS (<= 0). */
    fun rmsDb(buf: ShortArray, n: Int = buf.size): Double {
        if (n <= 0) return SILENCE_DB
        var sum = 0.0
        for (i in 0 until n) { val s = buf[i].toDouble(); sum += s * s }
        val rms = sqrt(sum / n)
        return if (rms <= 0) SILENCE_DB else 20 * log10(rms / 32767.0)
    }

    /** Fraction of adjacent sample pairs that cross zero (0..1). */
    fun zeroCrossingRate(buf: ShortArray, n: Int = buf.size): Double {
        if (n <= 1) return 0.0
        var crossings = 0
        for (i in 1 until n) if ((buf[i] >= 0) != (buf[i - 1] >= 0)) crossings++
        return crossings.toDouble() / n
    }

    /**
     * Zero-crossing heuristic: speech is mid-band and bursty. Excludes steady
     * hum (low ZCR) and hiss (high ZCR). SEAM for ML models (Silero/YAMNet).
     */
    fun isSpeechShaped(buf: ShortArray, n: Int = buf.size): Boolean =
        zeroCrossingRate(buf, n) in 0.05..0.35   // voiced band

    /**
     * Whispers are UNVOICED — all breath, no vocal-cord tone — so their ZCR
     * sits higher than voiced speech. A separate, wider band catches them.
     * The upper part (above the voiced band) is a whisper even when it's loud.
     */
    fun isWhisperShaped(buf: ShortArray, n: Int = buf.size): Boolean =
        zeroCrossingRate(buf, n) in 0.12..0.55

    /**
     * Harmonicity: peak normalized autocorrelation in the human-pitch lag range.
     * Voiced speech is periodic (glottal pulses ~80-350 Hz) so it self-correlates
     * strongly; airflow / hiss / fan / broadband machine noise is APERIODIC and
     * scores near zero. Level-independent (normalized by energy), so it survives
     * loud-machine masking. Returns 0..~1; ~>0.3 means voiced. @16 kHz.
     *
     * This is the one cue a fan/cooler physically cannot fake — the exact fix for
     * "airflow shows Talking": Silero can mis-fire on broadband noise, but noise
     * has no fundamental to correlate against.
     */
    fun harmonicity(buf: ShortArray, n: Int = buf.size, sampleRate: Int = 16_000): Double {
        if (n < 64) return 0.0
        val minLag = sampleRate / 350                       // 350 Hz -> lag 45 @16k
        val maxLag = (sampleRate / 80).coerceAtMost(n - 1)  // 80 Hz  -> lag 200
        if (maxLag <= minLag) return 0.0
        var energy = 0.0
        for (i in 0 until n) { val s = buf[i].toDouble(); energy += s * s }
        if (energy < 1e-6) return 0.0
        var best = 0.0
        for (lag in minLag..maxLag) {
            var corr = 0.0
            for (i in lag until n) corr += buf[i].toDouble() * buf[i - lag].toDouble()
            val norm = corr / energy
            if (norm > best) best = norm
        }
        return best
    }
}
