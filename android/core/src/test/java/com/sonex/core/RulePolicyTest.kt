package com.sonex.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RulePolicyTest {

    private fun cmd(state: RoomState, rule: TargetRule) =
        RulePolicy.commandFor(state, rule, duckPercent = 30, boostPercent = 100)

    @Test fun talking_applies_each_device_rule() {
        assertEquals(Command(Action.DUCK, 30, "speech"), cmd(RoomState.TALKING, TargetRule.DUCK))
        assertEquals(Action.MUTE, cmd(RoomState.TALKING, TargetRule.MUTE)!!.action)
        assertEquals(Action.PAUSE, cmd(RoomState.TALKING, TargetRule.PAUSE)!!.action)
    }

    @Test fun boost_rule_ducks_gently_when_talking() {
        val c = cmd(RoomState.TALKING, TargetRule.BOOST)!!
        assertEquals(Action.DUCK, c.action)
        assertEquals("hard-of-hearing devices duck to 2x the level", 60, c.level)
    }

    @Test fun quiet_restores_or_resumes_per_rule() {
        assertEquals(Action.RESTORE, cmd(RoomState.QUIET, TargetRule.DUCK)!!.action)
        assertEquals(Action.RESTORE, cmd(RoomState.QUIET, TargetRule.MUTE)!!.action)
        assertEquals(Action.RESUME, cmd(RoomState.QUIET, TargetRule.PAUSE)!!.action)
    }

    @Test fun ambient_boost_skips_paused_devices() {
        assertEquals(Action.BOOST, cmd(RoomState.BOOST, TargetRule.DUCK)!!.action)
        assertNull("paused devices don't join the loudness war", cmd(RoomState.BOOST, TargetRule.PAUSE))
    }

    @Test fun boost_duck_level_caps_at_100() {
        val c = RulePolicy.commandFor(RoomState.TALKING, TargetRule.BOOST, duckPercent = 70, boostPercent = 100)!!
        assertEquals(100, c.level)
    }
}
