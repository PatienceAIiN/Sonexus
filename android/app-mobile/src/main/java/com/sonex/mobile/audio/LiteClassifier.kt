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
    private val modulation = ModulationTracker()
    private val zcrFlux = com.sonex.core.ZcrTracker()
    private val floorTracker = com.sonex.core.FloorTracker()

    /** Below this confidence we trust the heuristic instead of the model. */
    private val minConfidence = 0.45

    override fun classify(buf: ShortArray, n: Int, db: Double): FrameKind {
        // The deterministic heuristic decides everything (and keeps its trackers
        // warm). It correctly sends loud non-speech -> boost, ambient -> quiet.
        val h = fallback.classify(buf, n, db)
        val m = model ?: return h
        // The trained model ONLY refines a "Talking" call: if it confidently says
        // this frame is NOT speech, cancel the false positive. It can never invent
        // speech, so a cooler/vehicle can't become "Talking". More clips = fewer
        // false positives over time (that's the self-improvement).
        if (h != FrameKind.SPEECH) return h
        val rmsOverFloor = db - floorTracker.update(db)
        val zcr = Dsp.zeroCrossingRate(buf, n)
        val (label, prob) = m.predict(rmsOverFloor, zcr, modulation.update(db), zcrFlux.update(zcr))
        val kind = LinearVad.labelToKind(label)
        return if (prob >= minConfidence && kind != FrameKind.SPEECH)
            (if (kind == FrameKind.NOISE) FrameKind.NOISE else FrameKind.QUIET)
        else FrameKind.SPEECH
    }
}
