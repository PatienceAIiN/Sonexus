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
    private val zcrFlux = com.sonex.core.ZcrTracker()

    override fun classify(buf: ShortArray, n: Int, db: Double): FrameKind {
        val kind = RoomStateMachine.classify(
            db,
            speechShaped = Dsp.isSpeechShaped(buf, n),
            trigger = adapter.trigger,
            boostTrigger = adapter.boostTrigger,
            inSpeechState = lastKind == FrameKind.SPEECH,
            noiseFloorDb = noiseFloor + adapter.shiftDb,
            dbSwingDb = modulation.update(db),
            whisperShaped = Dsp.isWhisperShaped(buf, n),
            zcrFluxRatio = zcrFlux.update(Dsp.zeroCrossingRate(buf, n))
        )
        adapter.observe(db, kind)
        lastKind = kind
        return kind
    }
}
