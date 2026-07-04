package com.sonex.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class DspTest {

    private fun sine(freqHz: Double, samples: Int, amplitude: Double, sampleRate: Int = 16_000) =
        ShortArray(samples) { i ->
            (amplitude * 32767.0 * sin(2 * PI * freqHz * i / sampleRate)).toInt().toShort()
        }

    @Test fun silence_reports_floor() {
        assertEquals(Dsp.SILENCE_DB, Dsp.rmsDb(ShortArray(480)), 0.001)
    }

    @Test fun empty_frame_reports_floor() {
        assertEquals(Dsp.SILENCE_DB, Dsp.rmsDb(ShortArray(0)), 0.001)
    }

    @Test fun full_scale_sine_is_about_minus_3dbfs() {
        // RMS of a full-scale sine is 1/sqrt(2) => ~ -3.01 dBFS.
        val db = Dsp.rmsDb(sine(1000.0, 480, amplitude = 1.0))
        assertEquals(-3.01, db, 0.1)
    }

    @Test fun quieter_signal_reads_lower() {
        val loud = Dsp.rmsDb(sine(1000.0, 480, 0.5))
        val soft = Dsp.rmsDb(sine(1000.0, 480, 0.05))
        assertTrue("10x quieter should read ~20dB lower", loud - soft in 19.0..21.0)
    }

    @Test fun rmsDb_honours_sample_count() {
        val buf = ShortArray(480)
        buf[479] = 20_000 // outside the first n samples
        assertEquals(Dsp.SILENCE_DB, Dsp.rmsDb(buf, 100), 0.001)
    }

    @Test fun zcr_of_dc_signal_is_zero() {
        val buf = ShortArray(480) { 1000 }
        assertEquals(0.0, Dsp.zeroCrossingRate(buf), 0.0)
    }

    @Test fun zcr_of_alternating_signal_is_near_one() {
        val buf = ShortArray(480) { if (it % 2 == 0) 1000 else -1000 }
        assertTrue(Dsp.zeroCrossingRate(buf) > 0.9)
    }

    @Test fun speech_band_tone_is_speech_shaped() {
        // 1kHz at 16kHz sampling => 2 crossings/ms => ZCR = 0.125, inside 0.05..0.35.
        assertTrue(Dsp.isSpeechShaped(sine(1000.0, 480, 0.5)))
    }

    @Test fun low_hum_is_not_speech_shaped() {
        // 60Hz mains hum => ZCR ~ 0.0075, below the speech band.
        assertFalse(Dsp.isSpeechShaped(sine(60.0, 480, 0.5)))
    }

    @Test fun hiss_is_not_speech_shaped() {
        // 6kHz "hiss" => ZCR = 0.75, above the speech band.
        assertFalse(Dsp.isSpeechShaped(sine(6000.0, 480, 0.5)))
    }

    // ---- harmonicity: the cue a fan/cooler physically cannot fake ----

    @Test fun voiced_pitch_is_harmonic() {
        // 150Hz glottal-range tone => strong self-correlation at its period lag.
        assertTrue("a voiced pitch must score harmonic", Dsp.harmonicity(sine(150.0, 480, 0.5)) > 0.3)
    }

    @Test fun white_noise_is_not_harmonic() {
        // Broadband airflow/hiss is aperiodic => near-zero autocorrelation peak.
        val rnd = java.util.Random(42)
        val noise = ShortArray(480) { (rnd.nextGaussian() * 8000).toInt().toShort() }
        assertTrue("broadband noise must NOT read as harmonic", Dsp.harmonicity(noise) < 0.3)
    }

    @Test fun silence_has_no_harmonicity() {
        assertEquals(0.0, Dsp.harmonicity(ShortArray(480)), 0.0001)
    }

    @Test fun voiced_tone_beats_noise_on_harmonicity() {
        val rnd = java.util.Random(7)
        val noise = ShortArray(480) { (rnd.nextGaussian() * 8000).toInt().toShort() }
        assertTrue(Dsp.harmonicity(sine(150.0, 480, 0.5)) > Dsp.harmonicity(noise))
    }
}
