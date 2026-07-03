package com.sonex.mobile.voice

import com.sonex.core.VoiceIntent
import com.sonex.core.WakeWordGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceControllerTest {

    private fun controller(clock: () -> Long, sink: MutableList<VoiceIntent>) =
        VoiceController(WakeWordGate(windowMs = 8000), now = clock, onIntent = { i, _ -> sink += i })

    @Test fun command_without_wake_word_is_ignored() {
        val fired = mutableListOf<VoiceIntent>()
        val c = controller({ 1000L }, fired)
        assertNull(c.onTranscript("lower the volume"))
        assertTrue(fired.isEmpty())
    }

    @Test fun wake_word_and_command_in_one_utterance() {
        val fired = mutableListOf<VoiceIntent>()
        val c = controller({ 1000L }, fired)
        assertEquals(VoiceIntent.LOWER_VOLUME, c.onTranscript("sonex lower volume"))
        assertEquals(listOf(VoiceIntent.LOWER_VOLUME), fired)
    }

    @Test fun wake_word_then_command_within_window() {
        var now = 1000L
        val fired = mutableListOf<VoiceIntent>()
        val c = controller({ now }, fired)
        c.onTranscript("sonex")
        now = 5000L
        assertEquals(VoiceIntent.RAISE_VOLUME, c.onTranscript("volume up"))
    }

    @Test fun command_after_window_expires_is_ignored() {
        var now = 1000L
        val fired = mutableListOf<VoiceIntent>()
        val c = controller({ now }, fired)
        c.onTranscript("sonex")
        now = 20_000L
        assertNull(c.onTranscript("volume up"))
        assertTrue(fired.isEmpty())
    }

    @Test fun hindi_command_flows_through_the_gate() {
        val fired = mutableListOf<VoiceIntent>()
        val c = controller({ 1000L }, fired)
        assertEquals(VoiceIntent.LOWER_VOLUME, c.onTranscript("सोनेक्स आवाज़ कम करो"))
    }

    @Test fun blank_transcripts_are_noise() {
        val fired = mutableListOf<VoiceIntent>()
        val c = controller({ 1000L }, fired)
        assertNull(c.onTranscript(""))
        assertNull(c.onTranscript("   "))
    }
}
