package com.sonex.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentParserTest {

    @Test fun english_phrases_map_to_intents() {
        assertEquals(VoiceIntent.LOWER_VOLUME, IntentParser.parse("please lower the volume"))
        assertEquals(VoiceIntent.LOWER_VOLUME, IntentParser.parse("VOLUME DOWN"))
        assertEquals(VoiceIntent.RAISE_VOLUME, IntentParser.parse("turn it up a bit"))
        assertEquals(VoiceIntent.RAISE_VOLUME, IntentParser.parse("louder"))
        assertEquals(VoiceIntent.STOP, IntentParser.parse("stop the music"))
        assertEquals(VoiceIntent.PLAY, IntentParser.parse("resume playback"))
        assertEquals(VoiceIntent.MUTE, IntentParser.parse("mute the tv"))
        assertEquals(VoiceIntent.RESTORE, IntentParser.parse("back to normal volume"))
    }

    @Test fun hindi_devanagari_phrases_map_to_intents() {
        assertEquals(VoiceIntent.LOWER_VOLUME, IntentParser.parse("आवाज़ कम करो"))
        assertEquals(VoiceIntent.RAISE_VOLUME, IntentParser.parse("आवाज़ बढ़ाओ"))
        assertEquals(VoiceIntent.STOP, IntentParser.parse("रुको"))
        assertEquals(VoiceIntent.PLAY, IntentParser.parse("चलाओ"))
        assertEquals(VoiceIntent.MUTE, IntentParser.parse("आवाज़ बंद करो"))
    }

    @Test fun romanised_hindi_phrases_map_to_intents() {
        assertEquals(VoiceIntent.LOWER_VOLUME, IntentParser.parse("sonex awaaz kam karo"))
        assertEquals(VoiceIntent.LOWER_VOLUME, IntentParser.parse("volume kam kar do"))
        assertEquals(VoiceIntent.RAISE_VOLUME, IntentParser.parse("volume badhao"))
        assertEquals(VoiceIntent.RAISE_VOLUME, IntentParser.parse("tez karo"))
        assertEquals(VoiceIntent.STOP, IntentParser.parse("band karo"))
        assertEquals(VoiceIntent.PLAY, IntentParser.parse("chalu karo"))
    }

    @Test fun mute_beats_stop_inside_awaaz_band_karo() {
        // "आवाज़ बंद करो" contains "बंद करो" (STOP) but means mute — table order wins.
        assertEquals(VoiceIntent.MUTE, IntentParser.parse("awaaz band karo"))
    }

    @Test fun unrelated_speech_maps_to_nothing() {
        assertNull(IntentParser.parse("what a lovely evening"))
        assertNull(IntentParser.parse("क्या हाल है"))
        assertNull(IntentParser.parse(""))
        assertNull(IntentParser.parse("   "))
    }

    @Test fun intents_translate_to_wire_commands() {
        assertEquals(Command(Action.DUCK, 30, "voice"), IntentParser.toCommand(VoiceIntent.LOWER_VOLUME, 30, 100))
        assertEquals(Command(Action.BOOST, 100, "voice"), IntentParser.toCommand(VoiceIntent.RAISE_VOLUME, 30, 100))
        assertEquals(Action.PAUSE, IntentParser.toCommand(VoiceIntent.STOP, 30, 100).action)
        assertEquals(Action.RESUME, IntentParser.toCommand(VoiceIntent.PLAY, 30, 100).action)
        assertEquals(Action.MUTE, IntentParser.toCommand(VoiceIntent.MUTE, 30, 100).action)
        assertEquals(Action.RESTORE, IntentParser.toCommand(VoiceIntent.RESTORE, 30, 100).action)
    }
}

class WakeWordGateTest {

    @Test fun command_without_wake_word_is_blocked() {
        val gate = WakeWordGate(windowMs = 8000)
        org.junit.Assert.assertFalse(gate.tryAccept(nowMs = 1000))
    }

    @Test fun command_inside_window_is_accepted_once() {
        val gate = WakeWordGate(windowMs = 8000)
        gate.arm(nowMs = 1000)
        org.junit.Assert.assertTrue(gate.tryAccept(nowMs = 5000))
        org.junit.Assert.assertFalse("one command per wake", gate.tryAccept(nowMs = 5500))
    }

    @Test fun command_after_window_is_blocked() {
        val gate = WakeWordGate(windowMs = 8000)
        gate.arm(nowMs = 1000)
        org.junit.Assert.assertFalse(gate.tryAccept(nowMs = 9001 + 1000))
    }

    @Test fun rearming_extends_the_window() {
        val gate = WakeWordGate(windowMs = 8000)
        gate.arm(nowMs = 1000)
        gate.arm(nowMs = 10_000)
        org.junit.Assert.assertTrue(gate.tryAccept(nowMs = 12_000))
    }

    @Test fun wake_word_is_spotted_in_transcripts() {
        org.junit.Assert.assertTrue(WakeWordGate.containsWakeWord("hey SoNex lower volume"))
        org.junit.Assert.assertTrue(WakeWordGate.containsWakeWord("so nex ruko"))
        org.junit.Assert.assertTrue(WakeWordGate.containsWakeWord("सोनेक्स आवाज़ कम करो"))
        org.junit.Assert.assertFalse(WakeWordGate.containsWakeWord("sonic the hedgehog"))
    }
}

class AmountParsingTest {
    @org.junit.Test fun numeric_amounts_are_extracted() {
        val p = IntentParser.parseWithAmount("sonex increase volume by 20")!!
        org.junit.Assert.assertEquals(VoiceIntent.RAISE_VOLUME, p.intent)
        org.junit.Assert.assertEquals(20, p.amount)
        org.junit.Assert.assertEquals(15,
            IntentParser.parseWithAmount("volume kam karo 15")!!.amount)
        org.junit.Assert.assertNull(IntentParser.parseWithAmount("lower the volume")!!.amount)
    }

    @org.junit.Test fun word_numbers_and_clamping() {
        org.junit.Assert.assertEquals(20, IntentParser.parseWithAmount("volume up by twenty")!!.amount)
        org.junit.Assert.assertEquals(100, IntentParser.parseWithAmount("raise volume by 250")!!.amount)
        org.junit.Assert.assertNull(IntentParser.parseWithAmount("what a lovely evening"))
    }
}
