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
        zeroCrossingRate(buf, n) in 0.05..0.35
}
