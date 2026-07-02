package com.sonex.core

/**
 * Phase 7: per-output-device behaviour. Every output target (phone speaker,
 * TV, Bluetooth, Cast) shares one command interface; this policy decides which
 * command each target gets for a room-state change, honouring the per-device
 * rule the user picked in Settings.
 */
enum class TargetRule { DUCK, MUTE, PAUSE, BOOST }

object RulePolicy {
    /**
     * Command for one target on a state change, or null for "leave it alone".
     * @param rule what the user chose for this device when talking starts.
     */
    fun commandFor(state: RoomState, rule: TargetRule, duckPercent: Int, boostPercent: Int): Command? =
        when (state) {
            RoomState.TALKING -> when (rule) {
                TargetRule.DUCK -> Command(Action.DUCK, duckPercent, "speech")
                TargetRule.MUTE -> Command(Action.MUTE, reason = "speech")
                TargetRule.PAUSE -> Command(Action.PAUSE, reason = "speech")
                // "Boost" devices are for hard-of-hearing setups: they duck less, not mute.
                TargetRule.BOOST -> Command(Action.DUCK, (duckPercent * 2).coerceAtMost(100), "speech")
            }
            RoomState.BOOST -> when (rule) {
                TargetRule.PAUSE -> null // paused devices don't join the loudness war
                else -> Command(Action.BOOST, boostPercent, "ambient")
            }
            RoomState.QUIET -> when (rule) {
                TargetRule.PAUSE -> Command(Action.RESUME, reason = "quiet")
                else -> Command(Action.RESTORE, reason = "quiet")
            }
            // Whispering means "already being considerate" — change nothing.
            RoomState.WHISPER -> null
        }
}
