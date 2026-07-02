package com.sonex.mobile.output

import com.sonex.core.Command
import com.sonex.core.RoomState
import com.sonex.core.RulePolicy
import com.sonex.core.TargetRule
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Phase 7: every audio output SoNex can steer — phone speaker, paired TV,
 * Bluetooth, Cast — sits behind this one interface. The ListeningService fans
 * out through OutputRouter; per-device rules come from Settings.
 */
interface OutputTarget {
    val id: String
    val name: String
    /** Only active targets receive commands (e.g. BT only when routed to BT). */
    val isActive: Boolean get() = true
    suspend fun send(cmd: Command)
}

/**
 * Fans a room-state change out to all active targets, translating it per
 * device through RulePolicy. Framework-free — unit-tested with fake targets.
 */
class OutputRouter(
    private val ruleFor: (targetId: String) -> TargetRule,
    private val duckPercent: () -> Int,
    private val boostPercent: () -> Int = { 100 }
) {
    private val targets = mutableListOf<OutputTarget>()

    fun register(target: OutputTarget) {
        if (targets.none { it.id == target.id }) targets += target
    }

    fun unregister(id: String) { targets.removeAll { it.id == id } }

    fun activeTargets(): List<OutputTarget> = targets.filter { it.isActive }

    /** Room state changed: every active target reacts AT THE SAME TIME —
     *  a fading phone ramp must never delay the TV command. */
    suspend fun onState(state: RoomState) = coroutineScope {
        for (t in activeTargets()) {
            val cmd = RulePolicy.commandFor(state, ruleFor(t.id), duckPercent(), boostPercent())
                ?: continue
            launch { runCatching { t.send(cmd) } }
        }
    }

    /** Voice commands apply verbatim to every active target, concurrently. */
    suspend fun broadcast(cmd: Command) = coroutineScope {
        for (t in activeTargets()) launch { runCatching { t.send(cmd) } }
    }
}
