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
        assertEquals("not yet speaking => soft speech is a solo whisper, never a duck",
            FrameKind.WHISPER,
            RoomStateMachine.classify(-46.0, true, -30.0, -27.0, inSpeechState = false, noiseFloorDb = -55.0))
    }

    @Test fun below_the_exit_band_speech_becomes_whisper_not_talking() {
        assertEquals(FrameKind.WHISPER,
            RoomStateMachine.classify(-46.0, true, -30.0, -27.0, inSpeechState = true, noiseFloorDb = -55.0))
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

class WhisperTest {
    @org.junit.Test fun soft_speech_classifies_as_whisper_not_quiet() {
        // Speech-shaped, just above floor+6 but below floor+12 => solo whisper.
        org.junit.Assert.assertEquals(FrameKind.WHISPER,
            RoomStateMachine.classify(-46.0, true, -30.0, -27.0, noiseFloorDb = -55.0))
        // Non-speech at the same level stays quiet.
        org.junit.Assert.assertEquals(FrameKind.QUIET,
            RoomStateMachine.classify(-46.0, false, -30.0, -27.0, noiseFloorDb = -55.0))
        // Near-silence is quiet even if speech-shaped (mic hiss).
        org.junit.Assert.assertEquals(FrameKind.QUIET,
            RoomStateMachine.classify(-52.0, true, -30.0, -27.0, noiseFloorDb = -55.0))
    }

    @org.junit.Test fun sustained_whisper_enters_whisper_state_and_holds_volume() {
        val m = RoomStateMachine(talkOnFrames = 3, quietOffFrames = 10)
        repeat(2) { m.step(FrameKind.WHISPER) }
        org.junit.Assert.assertEquals(RoomState.WHISPER, m.step(FrameKind.WHISPER))
        // Whisper never produces a command for any device rule.
        TargetRule.entries.forEach {
            org.junit.Assert.assertNull(RulePolicy.commandFor(RoomState.WHISPER, it, 30, 100))
        }
    }

    @org.junit.Test fun whisper_then_quiet_still_restores() {
        val m = RoomStateMachine(talkOnFrames = 2, quietOffFrames = 5)
        m.step(FrameKind.SPEECH); m.step(FrameKind.SPEECH)   // TALKING (ducked)
        repeat(3) { m.step(FrameKind.WHISPER) }               // WHISPER (hold)
        var restored = false
        repeat(15) { if (m.step(FrameKind.QUIET) == RoomState.QUIET) restored = true }
        org.junit.Assert.assertTrue("quiet after whispering must restore", restored)
    }

    @org.junit.Test fun ramp_steps_are_gradual_and_end_on_target() {
        org.junit.Assert.assertEquals(listOf(13, 11, 9, 7, 5), VolumeRamp.steps(15, 5, maxSteps = 5))
        org.junit.Assert.assertEquals(emptyList<Int>(), VolumeRamp.steps(7, 7))
        org.junit.Assert.assertEquals(listOf(5, 6), VolumeRamp.steps(4, 6))
        org.junit.Assert.assertEquals(6, VolumeRamp.steps(4, 6).last())
    }
}

class PercentileTest {
    @org.junit.Test fun percentile_bands_and_iqr() {
        val v = (0..100).map { it.toDouble() }
        org.junit.Assert.assertEquals(20.0, Stats.percentile(v, 20.0), 0.01)
        org.junit.Assert.assertEquals(50.0, Stats.median(v), 0.01)
        org.junit.Assert.assertEquals(75.0, Stats.percentile(v, 75.0), 0.01)
        org.junit.Assert.assertEquals(50.0, Stats.iqr(v), 0.01)
        org.junit.Assert.assertEquals(0.0, Stats.percentile(emptyList(), 50.0), 0.0)
    }

    @org.junit.Test fun talk_step_p75_survives_tv_only_gaps() {
        // Step 3 reality: 60% frames are TV-only (-35dB), 40% talk bursts (-24dB).
        val frames = List(60) { -35.0 } + List(40) { -24.0 }
        val median = Stats.median(frames)          // lands on TV-only level
        val p75 = Stats.percentile(frames, 75.0)   // captures the talking
        org.junit.Assert.assertEquals(-35.0, median, 0.5)
        org.junit.Assert.assertEquals(-24.0, p75, 0.5)
    }

    @org.junit.Test fun unsteady_measurements_are_flagged() {
        org.junit.Assert.assertTrue(CalibrationQuality.isUnsteady(15.0))
        org.junit.Assert.assertFalse(CalibrationQuality.isUnsteady(6.0))
    }
}

class MachineNoiseTest {
    @org.junit.Test fun steady_machine_noise_boosts_instead_of_ducking() {
        val m = ModulationTracker()
        // Cooler: loud, speech-band ZCR, but dead-steady level.
        var sw = 99.0
        repeat(40) { sw = m.update(-22.0 + (it % 2) * 0.4 ) }
        org.junit.Assert.assertTrue(sw < ModulationTracker.STEADY_SWING_DB)
        org.junit.Assert.assertEquals("steady 'speech-shaped' loudness is interference => BOOST",
            FrameKind.NOISE,
            RoomStateMachine.classify(-22.0, true, -30.0, -27.0, noiseFloorDb = -55.0, dbSwingDb = sw))
    }

    @org.junit.Test fun real_speech_still_ducks_because_it_pulses() {
        val m = ModulationTracker()
        var sw = 99.0
        // Syllables: level swings between -22 and -38 dB.
        repeat(40) { sw = m.update(if (it % 6 < 3) -22.0 else -38.0) }
        org.junit.Assert.assertTrue(sw >= ModulationTracker.STEADY_SWING_DB)
        org.junit.Assert.assertEquals(FrameKind.SPEECH,
            RoomStateMachine.classify(-22.0, true, -30.0, -27.0, noiseFloorDb = -55.0, dbSwingDb = sw))
    }

    @org.junit.Test fun steady_soft_hum_is_quiet_not_whisper() {
        org.junit.Assert.assertEquals("a soft steady hum must not hold the volume hostage",
            FrameKind.QUIET,
            RoomStateMachine.classify(-40.0, true, -30.0, -27.0, noiseFloorDb = -55.0, dbSwingDb = 1.5))
    }

    @org.junit.Test fun whispering_still_holds_never_boosts() {
        // Solo whisper: soft AND modulated, low in the band => WHISPER (hold).
        val k = RoomStateMachine.classify(-46.0, true, -30.0, -27.0, noiseFloorDb = -55.0, dbSwingDb = 9.0)
        org.junit.Assert.assertEquals(FrameKind.WHISPER, k)
        TargetRule.entries.forEach {
            org.junit.Assert.assertNull(RulePolicy.commandFor(RoomState.WHISPER, it, 30, 100))
        }
    }
}

class GroupWhisperTest {
    // floor -55: solo band floor+6..+12 = -49..-43; group band +12..trigger = -43..-30.
    @org.junit.Test fun louder_whisper_is_group_whisper() {
        org.junit.Assert.assertEquals("one soft breath => solo whisper", FrameKind.WHISPER,
            RoomStateMachine.classify(-46.0, true, -30.0, -27.0, noiseFloorDb = -55.0, dbSwingDb = 6.0))
        org.junit.Assert.assertEquals("several hushed voices => group whisper", FrameKind.WHISPER_GROUP,
            RoomStateMachine.classify(-38.0, true, -30.0, -27.0, noiseFloorDb = -55.0, dbSwingDb = 6.0))
    }

    @org.junit.Test fun group_whisper_eases_volume_solo_holds() {
        // Solo whisper never sends a command; group whisper sends a small duck.
        TargetRule.entries.forEach {
            org.junit.Assert.assertNull(RulePolicy.commandFor(RoomState.WHISPER, it, 30, 100))
        }
        val cmd = RulePolicy.commandFor(RoomState.WHISPER_GROUP, TargetRule.DUCK, 40, 100)!!
        org.junit.Assert.assertEquals(Action.DUCK, cmd.action)
        org.junit.Assert.assertEquals(20, cmd.level)           // half of 40, clamped 10..30
        org.junit.Assert.assertEquals("whisper", cmd.reason)
        // Small duck is bounded even for a big duck setting.
        org.junit.Assert.assertEquals(30, RulePolicy.commandFor(RoomState.WHISPER_GROUP, TargetRule.MUTE, 80, 100)!!.level)
        // Paused devices stay paused-silent, not nudged.
        org.junit.Assert.assertNull(RulePolicy.commandFor(RoomState.WHISPER_GROUP, TargetRule.PAUSE, 40, 100))
    }

    @org.junit.Test fun sustained_group_whisper_enters_group_state() {
        val m = RoomStateMachine(talkOnFrames = 3, quietOffFrames = 10)
        repeat(2) { m.step(FrameKind.WHISPER_GROUP) }
        org.junit.Assert.assertEquals(RoomState.WHISPER_GROUP, m.step(FrameKind.WHISPER_GROUP))
    }

    @org.junit.Test fun unvoiced_whisper_is_caught_by_the_wider_band() {
        // A breathy whisper has HIGHER zcr than voiced speech: speechShaped=false
        // but whisperShaped=true must still register as a whisper, not silence.
        org.junit.Assert.assertEquals(FrameKind.WHISPER,
            RoomStateMachine.classify(-46.0, speechShaped = false, trigger = -30.0, boostTrigger = -27.0,
                noiseFloorDb = -55.0, dbSwingDb = 6.0, whisperShaped = true))
    }
}
