package com.sonex.core

/**
 * Pure duck/boost/restore volume arithmetic shared by the TV server and the
 * phone's own media ducking. Remembers the pre-duck volume so RESTORE always
 * returns to exactly where the user had it, even across repeated ducks.
 */
class VolumePolicy(private val maxVolume: Int) {
    /** Volume saved before the first duck/boost; -1 when nothing is saved. */
    var savedVolume: Int = -1
        private set

    /**
     * Apply a command given the current stream volume.
     * Returns the new volume to set, or null when nothing should change.
     */
    fun apply(cmd: Command, currentVolume: Int): Int? = when (cmd.action) {
        Action.DUCK, Action.BOOST -> {
            if (savedVolume < 0) savedVolume = currentVolume
            val pct = cmd.level.coerceIn(0, 100)
            maxVolume * pct / 100
        }
        Action.MUTE -> {
            if (savedVolume < 0) savedVolume = currentVolume
            0
        }
        Action.RESTORE, Action.RESUME -> {
            if (savedVolume >= 0) {
                val v = savedVolume
                savedVolume = -1
                v
            } else null
        }
        Action.PAUSE -> null // hook to the playing app's media session later
    }

    /** Boosted level: +30% over the saved (pre-boost) volume, capped at max. */
    fun boostedFrom(baseVolume: Int): Int =
        minOf(maxVolume, (baseVolume.coerceAtLeast(1) * 13) / 10)
}
