package com.sonex.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinearVadTest {
    // Hand-built 5-feature model [rms, zcr, swing, voiced_band, steady].
    // SPEECH = voiced + not steady; NOISE = loud + steady.
    private val vad = LinearVad(
        version = "t",
        classes = listOf("QUIET", "SPEECH", "NOISE", "WHISPER"),
        weights = listOf(
            listOf(0.0, 0.0, 0.0, 0.0, 0.0),    // QUIET
            listOf(0.0, 0.0, 0.0, 3.0, -3.0),   // SPEECH: voiced, not steady
            listOf(0.2, 0.0, 0.0, -2.0, 3.0),   // NOISE: loud + steady
            listOf(0.0, 5.0, 0.0, 0.0, 0.0)     // WHISPER: high zcr
        ),
        bias = listOf(0.0, 0.0, 0.0, 0.0)
    )

    @Test fun usable_only_with_matching_shapes() {
        assertTrue(vad.isUsable)
        assertFalse("empty model is not usable", LinearVad().isUsable)
    }

    @Test fun loud_and_modulated_is_speech_over_a_steady_machine() {
        // 20 dB over floor, voice-band zcr, high swing => a talking person.
        assertEquals("SPEECH", vad.predict(20.0, 0.15, 12.0).first)
        // Same loudness but STEADY (low swing) => machine => NOISE (boost).
        assertEquals("NOISE", vad.predict(20.0, 0.15, 1.0).first)
    }

    @Test fun probabilities_sum_to_one() {
        val p = vad.probabilities(20.0, 0.15, 12.0)
        assertEquals(1.0, p.sum(), 1e-9)
    }

    @Test fun labels_map_to_frame_kinds() {
        assertEquals(FrameKind.SPEECH, LinearVad.labelToKind("SPEECH"))
        assertEquals(FrameKind.NOISE, LinearVad.labelToKind("NOISE"))
        assertEquals(FrameKind.WHISPER, LinearVad.labelToKind("WHISPER"))
        assertEquals(FrameKind.QUIET, LinearVad.labelToKind("anything else"))
    }

    @Test fun standardisation_is_applied_when_present() {
        val z = vad.copy(mean = listOf(10.0, 0.2, 6.0, 0.5, 0.5), std = listOf(5.0, 0.1, 4.0, 0.5, 0.5))
        // Should still produce a valid distribution and a decisive class.
        val p = z.probabilities(20.0, 0.15, 12.0)
        assertEquals(1.0, p.sum(), 1e-9)
    }
}
