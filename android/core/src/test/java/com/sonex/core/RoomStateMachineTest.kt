package com.sonex.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun interruption_slows_but_does_not_reset_the_streak() {
        // Real speech has gaps between syllables — a single quiet frame must
        // drain the score a little, not wipe it (else ducking never fires).
        val m = machine(talkOn = 5)
        repeat(3) { m.step(FrameKind.SPEECH) }   // score 6 of 10
        m.step(FrameKind.QUIET)                   // score 5 — dips, not resets
        m.step(FrameKind.SPEECH)                  // 7
        assertNull(m.step(FrameKind.SPEECH))      // 9 — not yet
        assertEquals(RoomState.TALKING, m.step(FrameKind.SPEECH)) // 10+
    }

    @Test fun sparse_blips_never_accumulate_to_talking() {
        // One speech frame every 4th frame drains faster than it fills.
        val m = machine(talkOn = 5, quietOff = 1000)
        repeat(200) { i ->
            m.step(if (i % 4 == 0) FrameKind.SPEECH else FrameKind.QUIET)
        }
        assertEquals(RoomState.QUIET, m.state)
    }

    @Test fun bursty_real_speech_still_triggers_talking() {
        // ~70% voiced frames with syllable gaps — the realistic pattern.
        val m = machine(talkOn = 17, quietOff = 100)
        var flipped = false
        repeat(60) { i ->
            val kind = if (i % 10 < 7) FrameKind.SPEECH else FrameKind.QUIET
            if (m.step(kind) == RoomState.TALKING) flipped = true
        }
        assertTrue("realistic speech cadence must duck within ~2s", flipped)
    }

    @Test fun restore_survives_occasional_blips_during_quiet() {
        val m = machine(talkOn = 2, quietOff = 30)
        m.step(FrameKind.SPEECH)
        assertEquals(RoomState.TALKING, m.step(FrameKind.SPEECH))
        // Quiet with a stray blip every 10th frame — restore must still land.
        var restored = false
        repeat(80) { i ->
            val kind = if (i % 10 == 9) FrameKind.SPEECH else FrameKind.QUIET
            if (m.step(kind) == RoomState.QUIET) restored = true
        }
        assertTrue("volume must recover after conversation ends", restored)
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

    @Test fun classify_near_floor_speech_as_quiet_but_soft_speech_as_whisper() {
        // Just above the floor: hiss, not a person => QUIET.
        assertEquals(FrameKind.QUIET,
            RoomStateMachine.classify(-52.0, true, -30.0, -27.0, noiseFloorDb = -55.0))
        // Clearly audible but below the duck trigger => WHISPER (hold volume).
        assertEquals(FrameKind.WHISPER,
            RoomStateMachine.classify(-40.0, true, -30.0, -27.0, noiseFloorDb = -55.0))
    }

    @Test fun classify_soft_nonspeech_as_quiet() {
        assertEquals(
            FrameKind.QUIET,
            RoomStateMachine.classify(-28.0, speechShaped = false, trigger = -30.0, boostTrigger = -27.0)
        )
    }
}
