package com.sonex.mobile.output

import com.sonex.core.Action
import com.sonex.core.Command
import com.sonex.core.RoomState
import com.sonex.core.TargetRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputRouterTest {

    private class FakeTarget(
        override val id: String,
        override val isActive: Boolean = true
    ) : OutputTarget {
        override val name = id
        val received = mutableListOf<Command>()
        override suspend fun send(cmd: Command) { received += cmd }
    }

    private fun router(rules: Map<String, TargetRule>) = OutputRouter(
        ruleFor = { rules[it] ?: TargetRule.DUCK },
        duckPercent = { 30 }
    )

    @Test fun fan_out_sends_rule_specific_commands() = runBlocking {
        val tv = FakeTarget("tv"); val bt = FakeTarget("bt"); val cast = FakeTarget("cast")
        val r = router(mapOf("tv" to TargetRule.DUCK, "bt" to TargetRule.MUTE, "cast" to TargetRule.PAUSE))
        listOf(tv, bt, cast).forEach(r::register)

        r.onState(RoomState.TALKING)

        assertEquals(Action.DUCK, tv.received.single().action)
        assertEquals(30, tv.received.single().level)
        assertEquals(Action.MUTE, bt.received.single().action)
        assertEquals(Action.PAUSE, cast.received.single().action)
    }

    @Test fun inactive_targets_are_skipped() = runBlocking {
        val active = FakeTarget("tv"); val inactive = FakeTarget("bt", isActive = false)
        val r = router(emptyMap())
        r.register(active); r.register(inactive)

        r.onState(RoomState.TALKING)

        assertEquals(1, active.received.size)
        assertTrue("inactive target must receive nothing", inactive.received.isEmpty())
    }

    @Test fun quiet_resumes_paused_and_restores_ducked() = runBlocking {
        val ducked = FakeTarget("tv"); val paused = FakeTarget("cast")
        val r = router(mapOf("tv" to TargetRule.DUCK, "cast" to TargetRule.PAUSE))
        r.register(ducked); r.register(paused)

        r.onState(RoomState.QUIET)

        assertEquals(Action.RESTORE, ducked.received.single().action)
        assertEquals(Action.RESUME, paused.received.single().action)
    }

    @Test fun duplicate_ids_register_once_and_unregister_works() = runBlocking {
        val a = FakeTarget("tv"); val dup = FakeTarget("tv")
        val r = router(emptyMap())
        r.register(a); r.register(dup)
        assertEquals(1, r.activeTargets().size)
        r.unregister("tv")
        assertTrue(r.activeTargets().isEmpty())
    }

    @Test fun broadcast_reaches_all_active_targets_verbatim() = runBlocking {
        val tv = FakeTarget("tv"); val bt = FakeTarget("bt")
        val voice = Command(Action.MUTE, reason = "voice")
        val r = router(mapOf("tv" to TargetRule.PAUSE)) // rule must NOT rewrite broadcasts
        r.register(tv); r.register(bt)

        r.broadcast(voice)

        assertEquals(listOf(voice), tv.received)
        assertEquals(listOf(voice), bt.received)
    }
}
