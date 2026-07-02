package com.sonex.mobile.audio

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {

    @Test fun trigger_sits_between_anchors() {
        val cal = Calibration(mediaBaselineDb = -35.0, mediaPlusTalkDb = -25.0, sensitivity = 0.5)
        assertTrue(cal.trigger in -35.0..-25.0)
    }

    @Test fun boost_trigger_is_above_media_baseline() {
        val cal = Calibration(mediaBaselineDb = -35.0)
        assertTrue(cal.boostTrigger > cal.mediaBaselineDb)
    }

    @Test fun corner_placement_shifts_thresholds_with_the_room() {
        // A corner phone reads everything ~10dB lower; triggers must follow.
        val centre = Calibration(mediaBaselineDb = -35.0, mediaPlusTalkDb = -25.0)
        val corner = Calibration(mediaBaselineDb = -45.0, mediaPlusTalkDb = -35.0)
        assertEquals(10.0, centre.trigger - corner.trigger, 0.001)
        assertEquals(10.0, centre.boostTrigger - corner.boostTrigger, 0.001)
    }

    @Test fun persisted_calibration_roundtrips_as_json() {
        val json = Json { ignoreUnknownKeys = true }
        val cal = Calibration("Living room", -52.1, -33.7, -24.2, 0.7, 5)
        assertEquals(cal, json.decodeFromString<Calibration>(json.encodeToString(cal)))
    }

    @Test fun defaults_are_sane_for_an_uncalibrated_phone() {
        val cal = Calibration()
        assertTrue("noise floor below media baseline", cal.noiseFloorDb < cal.mediaBaselineDb)
        assertTrue("media baseline below media+talk", cal.mediaBaselineDb < cal.mediaPlusTalkDb)
        assertTrue("trigger above media baseline", cal.trigger > cal.mediaBaselineDb)
        assertTrue(cal.restoreDelaySec > 0)
    }
}
