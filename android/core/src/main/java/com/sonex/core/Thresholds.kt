package com.sonex.core

/**
 * Pure threshold math, framework-free so it is unit-testable and shared.
 * The Android Calibration class delegates to these.
 */
object Thresholds {
    /** Trigger sits between media-only and media+talk, scaled by sensitivity (0..1). */
    fun trigger(mediaBaselineDb: Double, mediaPlusTalkDb: Double, sensitivity: Double): Double =
        mediaBaselineDb + (mediaPlusTalkDb - mediaBaselineDb) * (1 - sensitivity)

    /** Boost fires when non-speech noise rises well above the media baseline. */
    fun boostTrigger(mediaBaselineDb: Double, marginDb: Double = 8.0): Double =
        mediaBaselineDb + marginDb
}
