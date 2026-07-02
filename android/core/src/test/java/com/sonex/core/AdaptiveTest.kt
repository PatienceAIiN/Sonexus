package com.sonex.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdAdapterTest {
    private fun adapter() = ThresholdAdapter(
        ThresholdAdapter.ThresholdBase(trigger = -30.0, boostTrigger = -27.0, noiseFloorDb = -55.0)
    )

    @Test fun starts_at_calibrated_thresholds() {
        val a = adapter()
        assertEquals(-30.0, a.trigger, 0.001)
        assertEquals(-27.0, a.boostTrigger, 0.001)
    }

    @Test fun rising_ambient_floor_raises_the_trigger() {
        val a = adapter()
        // AC turns on: sustained quiet frames at -45dB instead of -55dB.
        repeat(5000) { a.observe(-45.0, FrameKind.QUIET) }
        assertTrue("trigger should rise with the floor", a.trigger > -30.0)
        assertEquals("boost trigger shifts by the same amount",
            a.trigger - -30.0, a.boostTrigger - -27.0, 0.001)
    }

    @Test fun quiet_night_lowers_the_trigger() {
        val a = adapter()
        repeat(200) { a.observe(-70.0, FrameKind.QUIET) }
        assertTrue("dead-quiet house should improve sensitivity", a.trigger < -30.0)
    }

    @Test fun speech_frames_do_not_move_the_floor() {
        val a = adapter()
        repeat(5000) { a.observe(-10.0, FrameKind.SPEECH) }
        assertEquals("a long conversation is not ambient noise", -30.0, a.trigger, 0.001)
    }

    @Test fun shift_is_clamped() {
        val a = adapter()
        repeat(100_000) { a.observe(-20.0, FrameKind.QUIET) }
        assertTrue("shift must be capped at +-10dB", a.trigger <= -30.0 + 10.0 + 0.001)
    }

    @Test fun floor_falls_faster_than_it_rises() {
        val up = adapter(); val down = adapter()
        repeat(50) { up.observe(-45.0, FrameKind.QUIET) }    // rising: slow
        repeat(50) { down.observe(-65.0, FrameKind.QUIET) }  // falling: fast
        assertTrue(kotlin.math.abs(down.shiftDb) > kotlin.math.abs(up.shiftDb))
    }
}

class SpeechProbSmootherTest {

    @Test fun sustained_high_probability_turns_speaking_on() {
        val s = SpeechProbSmoother()
        repeat(6) { s.update(0.95) }
        assertTrue(s.speaking)
    }

    @Test fun single_spike_does_not_flip() {
        val s = SpeechProbSmoother()
        s.update(0.95)
        assertFalse("one noisy window is not speech", s.speaking)
    }

    @Test fun hangover_rides_over_pauses_between_words() {
        val s = SpeechProbSmoother(hangoverWindows = 6)
        repeat(8) { s.update(0.95) }
        // Short gap between words: a few near-zero windows.
        repeat(3) { s.update(0.05) }
        assertTrue("brief word gap must not drop the verdict", s.speaking)
    }

    @Test fun long_silence_turns_speaking_off() {
        val s = SpeechProbSmoother(hangoverWindows = 6)
        repeat(8) { s.update(0.95) }
        repeat(40) { s.update(0.02) }
        assertFalse(s.speaking)
    }

    @Test fun mid_band_probability_holds_current_state() {
        val s = SpeechProbSmoother(onThreshold = 0.6, offThreshold = 0.35)
        repeat(8) { s.update(0.95) }
        repeat(50) { s.update(0.5) } // between off and on: hold
        assertTrue(s.speaking)
    }
}

class StatsTest {
    @Test fun median_odd_and_even() {
        assertEquals(3.0, Stats.median(listOf(1.0, 3.0, 9.0)), 0.0)
        assertEquals(2.5, Stats.median(listOf(1.0, 2.0, 3.0, 9.0)), 0.0)
        assertEquals(0.0, Stats.median(emptyList()), 0.0)
    }

    @Test fun median_resists_outliers_where_mean_does_not() {
        // 5s of quiet room at -55dB with one door slam at -5dB.
        val frames = List(160) { -55.0 } + listOf(-5.0)
        assertEquals(-55.0, Stats.median(frames), 0.001)
        val mean = frames.sum() / frames.size
        assertTrue("mean gets dragged by the slam", mean > -55.0 + 0.2)
    }
}

class CalibrationQualityTest {
    @Test fun good_calibration_has_no_issues() {
        assertTrue(CalibrationQuality.issues(-55.0, -35.0, -25.0).isEmpty())
        assertTrue(CalibrationQuality.isUsable(-55.0, -35.0, -25.0))
    }

    @Test fun muted_tv_during_step2_is_flagged() {
        val issues = CalibrationQuality.issues(-55.0, -53.0, -30.0)
        assertTrue(issues.any { it.contains("TV") })
    }

    @Test fun silent_step3_is_flagged() {
        val issues = CalibrationQuality.issues(-55.0, -35.0, -34.5)
        assertTrue(issues.any { it.contains("Talking") })
    }

    @Test fun noisy_step1_is_flagged() {
        val issues = CalibrationQuality.issues(-15.0, -8.0, -2.0)
        assertTrue(issues.any { it.contains("quiet") })
    }
}

class RoomProfileTest {
    @Test fun reference_room_gets_default_sensitivity() {
        assertEquals(0.5, RoomProfile.sensitivityFor(4.0, 3.0), 0.01)
    }

    @Test fun bigger_hall_gets_higher_sensitivity() {
        val small = RoomProfile.sensitivityFor(3.0, 3.0)
        val hall = RoomProfile.sensitivityFor(8.0, 6.0)
        assertTrue(hall > small)
    }

    @Test fun sensitivity_is_clamped_to_sane_range() {
        assertTrue(RoomProfile.sensitivityFor(0.5, 0.5) >= 0.30)
        assertTrue(RoomProfile.sensitivityFor(50.0, 50.0) <= 0.85)
    }

    @Test fun bigger_rooms_wait_longer_before_restoring() {
        assertEquals(3, RoomProfile.restoreDelaySecFor(3.0, 3.0))
        assertEquals(4, RoomProfile.restoreDelaySecFor(5.0, 4.0))
        assertEquals(5, RoomProfile.restoreDelaySecFor(8.0, 6.0))
    }
}

class HysteresisTest {
    @Test fun trailing_off_speech_stays_speech_inside_hysteresis_band() {
        // -32dB is below the -30 trigger but inside the 3dB exit band.
        assertEquals(FrameKind.SPEECH,
            RoomStateMachine.classify(-32.0, true, -30.0, -27.0, inSpeechState = true))
        assertEquals("not yet speaking => full trigger applies", FrameKind.QUIET,
            RoomStateMachine.classify(-32.0, true, -30.0, -27.0, inSpeechState = false))
    }

    @Test fun below_the_exit_band_speech_ends() {
        assertEquals(FrameKind.QUIET,
            RoomStateMachine.classify(-34.0, true, -30.0, -27.0, inSpeechState = true))
    }
}

class RoomPresetTest {
    @org.junit.Test fun presets_scale_sensitivity_with_size() {
        val s = RoomProfile.Preset.entries.map { it.sensitivity }
        org.junit.Assert.assertEquals("presets must be ordered small -> large", s.sorted(), s)
        org.junit.Assert.assertTrue(RoomProfile.Preset.OPEN.sensitivity > RoomProfile.Preset.SMALL.sensitivity)
        org.junit.Assert.assertEquals(0.53, RoomProfile.Preset.LIVING.sensitivity, 0.02)
        org.junit.Assert.assertTrue(RoomProfile.Preset.HALL.restoreDelaySec > RoomProfile.Preset.SMALL.restoreDelaySec)
    }
}
