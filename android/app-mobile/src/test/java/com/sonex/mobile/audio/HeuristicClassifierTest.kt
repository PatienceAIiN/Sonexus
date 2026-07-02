package com.sonex.mobile.audio

import com.sonex.core.Dsp
import com.sonex.core.FrameKind
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Canned-PCM fixtures through the fallback classifier (the Phase-1 seam impl). */
class HeuristicClassifierTest {

    // Calibration with known anchors: trigger = -30dB, boostTrigger = -27dB.
    private val cal = Calibration(mediaBaselineDb = -35.0, mediaPlusTalkDb = -25.0, sensitivity = 0.5)
    private val classifier = HeuristicClassifier(cal)

    private fun sine(freqHz: Double, amplitude: Double, samples: Int = 480) =
        ShortArray(samples) { i ->
            (amplitude * 32767.0 * sin(2 * PI * freqHz * i / 16_000)).toInt().toShort()
        }

    private fun classify(buf: ShortArray) = classifier.classify(buf, buf.size, Dsp.rmsDb(buf))

    @Test fun loud_speech_band_tone_is_speech() {
        // 1kHz (speech-band ZCR) at high amplitude => well above trigger.
        assertEquals(FrameKind.SPEECH, classify(sine(1000.0, 0.5)))
    }

    @Test fun quiet_speech_band_tone_is_quiet() {
        // Same shape but ~-52dB, below the -30dB trigger.
        assertEquals(FrameKind.QUIET, classify(sine(1000.0, 0.0025)))
    }

    @Test fun loud_low_hum_is_noise() {
        // 60Hz hum: not speech-shaped, above boost trigger => BOOST candidate.
        assertEquals(FrameKind.NOISE, classify(sine(60.0, 0.5)))
    }

    @Test fun silence_is_quiet() {
        assertEquals(FrameKind.QUIET, classify(ShortArray(480)))
    }
}
