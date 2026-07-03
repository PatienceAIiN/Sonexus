package com.sonex.core

/** Small numeric helpers shared by detection + calibration. */
object Stats {
    /** Median — robust to a door slam or cough in the middle of a measurement. */
    fun median(values: List<Double>): Double = percentile(values, 50.0)

    /**
     * Percentile with linear interpolation. Calibration uses different bands
     * per step: the silence step wants the QUIET part of the recording (p20,
     * ignoring rustles), media wants the typical level (p50), and media+talk
     * wants the talk bursts riding on top of the TV (p75) — a plain median
     * there can land on TV-only gaps and collapse the talk gap.
     */
    fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val rank = (p / 100.0) * (s.size - 1)
        val lo = rank.toInt().coerceIn(0, s.size - 1)
        val hi = (lo + 1).coerceAtMost(s.size - 1)
        val frac = rank - lo
        return s[lo] * (1 - frac) + s[hi] * frac
    }

    /** Interquartile range — how unsteady a measurement was. */
    fun iqr(values: List<Double>): Double =
        percentile(values, 75.0) - percentile(values, 25.0)
}

/**
 * Tracks the room's noise floor while SoNex runs and shifts the calibrated
 * thresholds with it. If the AC kicks in (+6dB ambient), the speech trigger
 * rises too, so airflow never reads as conversation — and when the house goes
 * dead quiet at night, sensitivity gently improves.
 *
 * Asymmetric EMA: the floor falls quickly (trust silence) but rises slowly
 * (don't let one noisy evening deafen the detector).
 */
class ThresholdAdapter(
    private val calibration: ThresholdBase,
    private val riseAlpha: Double = 0.002,
    private val fallAlpha: Double = 0.01,
    private val maxShiftDb: Double = 10.0,
    /** Downward drift is capped hard: a silent night must not make breathing
     *  read as TALKING (the "false talking after a while" bug). */
    private val maxDownShiftDb: Double = 3.0
) {
    data class ThresholdBase(val trigger: Double, val boostTrigger: Double, val noiseFloorDb: Double)

    companion object {
        /**
         * Quiet frames louder than calibration-floor + this are media playing
         * (TV at its baseline sits well above the silent-room floor), NOT
         * ambient noise — tracking them would desensitise speech detection.
         */
        const val MAX_TRACKABLE_RISE_DB = 12.0
    }

    private var floor = calibration.noiseFloorDb

    /** Current shift of the ambient floor vs. calibration time, clamped. */
    val shiftDb: Double
        get() = (floor - calibration.noiseFloorDb).coerceIn(-maxDownShiftDb, maxShiftDb)

    val trigger: Double get() = calibration.trigger + shiftDb
    val boostTrigger: Double get() = calibration.boostTrigger + shiftDb

    /** Feed every frame's level + how it was classified. */
    fun observe(db: Double, kind: FrameKind) {
        if (kind != FrameKind.QUIET) return // only quiet frames describe the floor
        if (db > calibration.noiseFloorDb + MAX_TRACKABLE_RISE_DB) return // that's media, not ambience
        val alpha = if (db < floor) fallAlpha else riseAlpha
        floor += alpha * (db - floor)
    }
}

/**
 * Smooths Silero VAD's per-window speech probability and adds a hangover:
 * natural speech has tiny gaps between words, and a raw threshold flickers
 * through them. The EMA rides over jitter; the hangover keeps "speaking" true
 * for a few windows after the last confident hit (standard VAD practice).
 */
class SpeechProbSmoother(
    private val alpha: Double = 0.4,
    private val onThreshold: Double = 0.6,
    private val offThreshold: Double = 0.35,
    private val hangoverWindows: Int = 6
) {
    private var smoothed = 0.0
    private var hangover = 0
    var speaking = false
        private set

    /** Feed one raw probability; returns the current speaking verdict. */
    fun update(rawProb: Double): Boolean {
        smoothed += alpha * (rawProb - smoothed)
        when {
            smoothed >= onThreshold -> { speaking = true; hangover = hangoverWindows }
            smoothed < offThreshold -> {
                if (hangover > 0) hangover-- else speaking = false
            }
            // between thresholds: hold the current state (hysteresis)
        }
        return speaking
    }

    val probability: Double get() = smoothed
}

/**
 * Room geometry -> detection sensitivity. In a bigger hall, a person talking
 * across the room reaches the phone quieter, so the trigger must sit closer
 * to the media baseline (higher sensitivity). Small dens get a calmer trigger
 * so nearby rustling doesn't duck the TV. Log scale: doubling the area adds a
 * fixed sensitivity step, mirroring how sound pressure falls with distance.
 */
object RoomProfile {
    const val REFERENCE_AREA_M2 = 12.0   // typical living-room reference
    const val STEP_PER_DOUBLING = 0.12
    val SENSITIVITY_RANGE = 0.30..0.85

    /** Pick-a-room presets — nobody measures their hall with a tape. */
    enum class Preset(val label: String, val widthM: Double, val lengthM: Double) {
        SMALL("Small room", 3.0, 2.5),
        BEDROOM("Bedroom", 3.5, 3.5),
        LIVING("Living room", 4.0, 3.5),
        HALL("Hall", 6.0, 5.0),
        OPEN("Open space", 9.0, 7.0);

        val sensitivity: Double get() = sensitivityFor(widthM, lengthM)
        val restoreDelaySec: Int get() = restoreDelaySecFor(widthM, lengthM)
    }

    fun sensitivityFor(widthM: Double, lengthM: Double): Double {
        val area = (widthM * lengthM).coerceAtLeast(1.0)
        val doublings = kotlin.math.ln(area / REFERENCE_AREA_M2) / kotlin.math.ln(2.0)
        return (0.5 + STEP_PER_DOUBLING * doublings)
            .coerceIn(SENSITIVITY_RANGE.start, SENSITIVITY_RANGE.endInclusive)
    }

    /** Larger rooms echo longer — wait a touch more before restoring. */
    fun restoreDelaySecFor(widthM: Double, lengthM: Double): Int {
        val area = widthM * lengthM
        return when {
            area >= 30 -> 5
            area >= 18 -> 4
            else -> 3
        }
    }
}

/**
 * Sanity-checks a calibration before it's saved. A corner phone measuring low
 * is fine — decisions use gaps — but *collapsed* gaps mean the measurement
 * itself failed (TV muted during step 2, nobody spoke in step 3, …).
 */
object CalibrationQuality {
    const val MIN_MEDIA_GAP_DB = 5.0   // media must sit clearly above the floor
    const val MIN_TALK_GAP_DB = 2.0    // talking must add measurably to media
    /** IQR above this means the room changed mid-measurement (steps 1-2). */
    const val UNSTEADY_IQR_DB = 12.0

    fun isUnsteady(spreadDb: Double) = spreadDb > UNSTEADY_IQR_DB

    fun issues(noiseFloorDb: Double, mediaBaselineDb: Double, mediaPlusTalkDb: Double): List<String> {
        val problems = mutableListOf<String>()
        if (mediaBaselineDb - noiseFloorDb < MIN_MEDIA_GAP_DB)
            problems += "The TV was barely louder than the silent room — raise the TV volume and redo step 2"
        if (mediaPlusTalkDb - mediaBaselineDb < MIN_TALK_GAP_DB)
            problems += "Talking didn't register above the TV — sit closer to the phone and redo step 3"
        if (noiseFloorDb > -20.0)
            problems += "The room wasn't quiet during step 1 — turn everything off and redo it"
        return problems
    }

    fun isUsable(noiseFloorDb: Double, mediaBaselineDb: Double, mediaPlusTalkDb: Double) =
        issues(noiseFloorDb, mediaBaselineDb, mediaPlusTalkDb).isEmpty()
}


/**
 * Smooth volume transitions: instead of snapping to the target, step through
 * intermediate levels so ducks fade in and restores swell back. Pure —
 * callers walk the list with a short delay between steps.
 */
object VolumeRamp {
    fun steps(from: Int, to: Int, maxSteps: Int = 8): List<Int> {
        if (from == to) return emptyList()
        val n = minOf(maxSteps, kotlin.math.abs(to - from))
        return (1..n).map { from + (to - from) * it / n }
    }
}


/**
 * Distinguishes talking from machinery: speech pulses with syllables (the
 * level swings several dB within a second) while coolers, fans and motors sit
 * at a near-constant level. Rolling p90-p10 swing over ~1s of frames.
 */
class ModulationTracker(private val window: Int = 34) {
    private val ring = ArrayDeque<Double>()

    /** Feed a frame level; returns the current swing (dB). Big while warming up. */
    fun update(db: Double): Double {
        ring.addLast(db)
        if (ring.size > window) ring.removeFirst()
        if (ring.size < window / 2) return 99.0 // not enough data: don't reclassify yet
        val sorted = ring.sorted()
        return sorted[(sorted.size * 9) / 10 - 1] - sorted[sorted.size / 10]
    }

    companion object {
        /** Below this swing a "speech-shaped" sound is machinery, not a person. */
        const val STEADY_SWING_DB = 4.0
    }
}
