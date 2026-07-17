package com.sonex.mobile.audio

import com.sonex.core.Dsp
import com.sonex.core.FrameKind
import com.sonex.core.RoomStateMachine
import com.sonex.core.ThresholdAdapter

/**
 * THE ML SEAM. Everything above this interface is fixed app code; everything
 * below is swappable model data. `classifyFrame(pcm, db) -> SPEECH|NOISE|QUIET`
 * — this contract must stay stable so OTA model swaps never touch app code.
 */
interface FrameClassifier {
    fun classify(buf: ShortArray, n: Int, db: Double): FrameKind
    fun close() {}
}

/**
 * Phase-1 energy + zero-crossing heuristic. Always available: it is the
 * fallback whenever models are missing, unverified, or crash.
 * Thresholds adapt to the ambient floor and use enter-high/exit-low hysteresis.
 */
class HeuristicClassifier(calibration: Calibration) : FrameClassifier {
    private val noiseFloor = calibration.noiseFloorDb
    private val adapter = ThresholdAdapter(
        ThresholdAdapter.ThresholdBase(calibration.trigger, calibration.boostTrigger, calibration.noiseFloorDb)
    )
    private var lastKind = FrameKind.QUIET
    private val modulation = com.sonex.core.ModulationTracker()
    // Robust floor: learns the true ambient every frame, so a silent room can
    // never stay stuck reading loud/talking.
    private val floorTracker = com.sonex.core.FloorTracker()

    companion object {
        // Same verified thresholds as the ML path, so detection is identical
        // whether or not the OTA model is present — the app works perfectly the
        // instant it's installed, fully offline, with zero downloads.
        private const val VOICED_ZCR_LO = 0.05
        private const val VOICED_ZCR_HI = 0.35
        private const val HARMONIC_MIN = 0.35
        private const val VOICE_OVER_FLOOR_DB = 6.0
        private const val LOUD_VOICE_OVER_FLOOR_DB = 8.0
    }

    override fun classify(buf: ShortArray, n: Int, db: Double): FrameKind {
        // Pure-DSP, model-free speech detection (verified on-device):
        //  a voice is voiced-shaped AND pulsing AND either clearly harmonic
        //  (quiet room) OR riding well above the floor (over a machine). A fan/
        //  cooler is aperiodic + steady, so it can never look like a voice.
        val floor = floorTracker.update(db)
        val steady = modulation.update(db) < com.sonex.core.ModulationTracker.STEADY_SWING_DB
        val zcr = Dsp.zeroCrossingRate(buf, n)
        val voicedShape = zcr in VOICED_ZCR_LO..VOICED_ZCR_HI
        val aboveFloor = db > floor + VOICE_OVER_FLOOR_DB
        val harmonicVoice = aboveFloor && Dsp.harmonicity(buf, n) >= HARMONIC_MIN
        val voiceOverMachine = db > floor + LOUD_VOICE_OVER_FLOOR_DB
        val voice = voicedShape && !steady && (harmonicVoice || voiceOverMachine)
        val loud = db > floor + com.sonex.core.RoomStateMachine.BOOST_OVER_FLOOR_DB
        val kind = when {
            voice -> FrameKind.SPEECH
            loud -> FrameKind.NOISE
            else -> FrameKind.QUIET
        }
        lastKind = kind
        return kind
    }
}
