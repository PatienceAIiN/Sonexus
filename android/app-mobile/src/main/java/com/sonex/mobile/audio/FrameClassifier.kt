package com.sonex.mobile.audio

import com.sonex.core.Dsp
import com.sonex.core.FrameKind
import com.sonex.core.RoomStateMachine

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
 */
class HeuristicClassifier(private val calibration: Calibration) : FrameClassifier {
    override fun classify(buf: ShortArray, n: Int, db: Double): FrameKind =
        RoomStateMachine.classify(
            db,
            speechShaped = Dsp.isSpeechShaped(buf, n),
            trigger = calibration.trigger,
            boostTrigger = calibration.boostTrigger
        )
}
