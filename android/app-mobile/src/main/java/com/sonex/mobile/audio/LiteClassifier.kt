package com.sonex.mobile.audio

import android.util.Log
import com.sonex.core.Dsp
import com.sonex.core.FrameKind
import com.sonex.core.LinearVad
import com.sonex.core.ModulationTracker
import com.sonex.core.RoomStateMachine
import com.sonex.core.ThresholdAdapter
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Applies the OTA-trained lightweight model ([LinearVad]) to each frame — the
 * "more advanced algorithm" that separates a talking person from a loud steady
 * machine better than fixed ZCR thresholds. Pure JSON model, no native runtime.
 *
 * Features fed to the model per frame: level above the (adaptive) noise floor,
 * zero-crossing rate, and modulation swing. If the model is missing/garbage or
 * unsure about a frame, it defers to the heuristic — so behaviour never regresses.
 */
class LiteClassifier(
    modelFile: File,
    calibration: Calibration,
    private val fallback: FrameClassifier
) : FrameClassifier {
    private val model: LinearVad? = runCatching {
        Json { ignoreUnknownKeys = true }
            .decodeFromString(LinearVad.serializer(), modelFile.readText())
            .takeIf { it.isUsable }
    }.getOrElse { Log.w("SonexLite", "Bad lite model, using heuristic", it); null }

    private val adapter = ThresholdAdapter(
        ThresholdAdapter.ThresholdBase(calibration.trigger, calibration.boostTrigger, calibration.noiseFloorDb)
    )
    private val baseFloor = calibration.noiseFloorDb
    private val modulation = ModulationTracker()

    /** Below this confidence we trust the heuristic instead of the model. */
    private val minConfidence = 0.45

    override fun classify(buf: ShortArray, n: Int, db: Double): FrameKind {
        val m = model ?: return fallback.classify(buf, n, db)
        val floor = baseFloor + adapter.shiftDb
        val rmsOverFloor = db - floor
        val zcr = Dsp.zeroCrossingRate(buf, n)
        val swing = modulation.update(db)

        val (label, prob) = m.predict(rmsOverFloor, zcr, swing)
        var kind = if (prob < minConfidence) fallback.classify(buf, n, db)
        else LinearVad.labelToKind(label)

        // Louder whispers = several people => group whisper (gentle duck), same
        // rule the heuristic uses, so downstream policy is identical.
        if (kind == FrameKind.WHISPER &&
            rmsOverFloor >= RoomStateMachine.WHISPER_MARGIN_DB + RoomStateMachine.WHISPER_GROUP_GAP_DB
        ) kind = FrameKind.WHISPER_GROUP

        adapter.observe(db, kind)
        return kind
    }
}
