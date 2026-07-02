package com.sonex.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomStateMachineTest {

    private fun machine(talkOn: Int = 17, quietOff: Int = 100) =
        RoomStateMachine(talkOnFrames = talkOn, quietOffFrames = quietOff)

    @Test fun starts_quiet() {
        assertEquals(RoomState.QUIET, machine().state)
    }

    @Test fun sustained_speech_switches_to_talking() {
        val m = machine(talkOn = 5)
        repeat(4) { assertNull("no flip before the streak completes", m.step(FrameKind.SPEECH)) }
        assertEquals(RoomState.TALKING, m.step(FrameKind.SPEECH))
    }

    @Test fun single_burst_does_not_flip() {
        val m = machine(talkOn = 5, quietOff = 100)
        m.step(FrameKind.SPEECH) // a cough
        assertNull(m.step(FrameKind.QUIET))
        assertEquals(RoomState.QUIET, m.state)
    }

    @Test fun interruption_resets_the_streak() {
        val m = machine(talkOn = 3)
        m.step(FrameKind.SPEECH); m.step(FrameKind.SPEECH)
        m.step(FrameKind.QUIET) // streak broken
        m.step(FrameKind.SPEECH); m.step(FrameKind.SPEECH)
        assertNull("streak must restart after interruption", m.step(FrameKind.QUIET))
        assertEquals(RoomState.QUIET, m.state)
    }

    @Test fun sustained_quiet_restores_after_talking() {
        val m = machine(talkOn = 2, quietOff = 3)
        m.step(FrameKind.SPEECH)
        assertEquals(RoomState.TALKING, m.step(FrameKind.SPEECH))
        m.step(FrameKind.QUIET); m.step(FrameKind.QUIET)
        assertEquals(RoomState.QUIET, m.step(FrameKind.QUIET))
    }

    @Test fun sustained_noise_switches_to_boost() {
        val m = machine(talkOn = 3)
        m.step(FrameKind.NOISE); m.step(FrameKind.NOISE)
        assertEquals(RoomState.BOOST, m.step(FrameKind.NOISE))
    }

    @Test fun speech_wins_over_earlier_noise() {
        val m = machine(talkOn = 3)
        m.step(FrameKind.NOISE); m.step(FrameKind.NOISE) // almost boosting
        m.step(FrameKind.SPEECH); m.step(FrameKind.SPEECH)
        assertEquals(RoomState.TALKING, m.step(FrameKind.SPEECH))
    }

    @Test fun no_change_notification_while_state_holds() {
        val m = machine(talkOn = 2, quietOff = 100)
        m.step(FrameKind.SPEECH)
        assertEquals(RoomState.TALKING, m.step(FrameKind.SPEECH))
        assertNull("already TALKING — no repeat notification", m.step(FrameKind.SPEECH))
    }

    // ---- classify ----

    @Test fun classify_speech_above_trigger() {
        assertEquals(
            FrameKind.SPEECH,
            RoomStateMachine.classify(-20.0, speechShaped = true, trigger = -30.0, boostTrigger = -27.0)
        )
    }

    @Test fun classify_loud_nonspeech_as_noise() {
        assertEquals(
            FrameKind.NOISE,
            RoomStateMachine.classify(-20.0, speechShaped = false, trigger = -30.0, boostTrigger = -27.0)
        )
    }

    @Test fun classify_below_trigger_as_quiet() {
        assertEquals(
            FrameKind.QUIET,
            RoomStateMachine.classify(-40.0, speechShaped = true, trigger = -30.0, boostTrigger = -27.0)
        )
    }

    @Test fun classify_soft_nonspeech_as_quiet() {
        assertEquals(
            FrameKind.QUIET,
            RoomStateMachine.classify(-28.0, speechShaped = false, trigger = -30.0, boostTrigger = -27.0)
        )
    }
}
