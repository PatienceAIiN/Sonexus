package com.sonex.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MlDecisionTest {
    private val trigger = -30.0
    private val boost = -27.0

    private fun decide(prob: Double?, isSpeech: Boolean?, db: Double) =
        MlDecision.decide(prob, isSpeech, db, trigger, boost)

    @Test fun confident_speech_above_trigger_ducks() {
        assertEquals(FrameKind.SPEECH, decide(0.9, true, -20.0))
    }

    @Test fun speech_below_trigger_stays_quiet() {
        // A whisper across the room is speech but doesn't compete with the TV.
        assertEquals(FrameKind.QUIET, decide(0.9, true, -45.0))
    }

    @Test fun low_probability_is_not_speech() {
        assertEquals(FrameKind.QUIET, decide(0.2, null, -20.0))
    }

    @Test fun threshold_boundary_counts_as_speech() {
        assertEquals(FrameKind.SPEECH, decide(0.5, null, -20.0))
    }

    @Test fun loud_confident_nonspeech_boosts() {
        // VAD says no speech, YAMNet says non-speech class, loud => BOOST.
        assertEquals(FrameKind.NOISE, decide(0.1, false, -20.0))
    }

    @Test fun loud_nonspeech_without_yamnet_does_not_boost() {
        // No sound-class model => can't distinguish appliance from music; be conservative.
        assertEquals(FrameKind.QUIET, decide(0.1, null, -20.0))
    }

    @Test fun yamnet_alone_can_drive_ducking() {
        // VAD missing: YAMNet speech verdict still ducks when loud enough.
        assertEquals(FrameKind.SPEECH, decide(null, true, -20.0))
        assertEquals(FrameKind.QUIET, decide(null, true, -45.0))
    }

    @Test fun no_models_means_quiet_so_caller_falls_back() {
        assertEquals(FrameKind.QUIET, decide(null, null, -10.0))
    }

    @Test fun custom_speech_threshold_is_honoured() {
        assertEquals(FrameKind.QUIET, MlDecision.decide(0.6, null, -20.0, trigger, boost, speechThreshold = 0.8))
        assertEquals(FrameKind.SPEECH, MlDecision.decide(0.85, null, -20.0, trigger, boost, speechThreshold = 0.8))
    }
}
