package com.sonex.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumePolicyTest {

    @Test fun duck_scales_to_max_volume() {
        val p = VolumePolicy(maxVolume = 20)
        assertEquals(6, p.apply(Command(Action.DUCK, level = 30), currentVolume = 15))
    }

    @Test fun restore_returns_pre_duck_volume() {
        val p = VolumePolicy(maxVolume = 20)
        p.apply(Command(Action.DUCK, level = 30), currentVolume = 15)
        assertEquals(15, p.apply(Command(Action.RESTORE), currentVolume = 6))
    }

    @Test fun repeated_ducks_keep_the_original_volume() {
        val p = VolumePolicy(maxVolume = 20)
        p.apply(Command(Action.DUCK, level = 30), currentVolume = 15)
        p.apply(Command(Action.DUCK, level = 20), currentVolume = 6) // already ducked
        assertEquals("restore must go back to 15, not 6", 15,
            p.apply(Command(Action.RESTORE), currentVolume = 4))
    }

    @Test fun restore_without_duck_is_a_noop() {
        val p = VolumePolicy(maxVolume = 20)
        assertNull(p.apply(Command(Action.RESTORE), currentVolume = 15))
    }

    @Test fun double_restore_is_a_noop() {
        val p = VolumePolicy(maxVolume = 20)
        p.apply(Command(Action.MUTE), currentVolume = 15)
        assertEquals(15, p.apply(Command(Action.RESTORE), currentVolume = 0))
        assertNull(p.apply(Command(Action.RESTORE), currentVolume = 15))
    }

    @Test fun mute_goes_to_zero_and_remembers() {
        val p = VolumePolicy(maxVolume = 20)
        assertEquals(0, p.apply(Command(Action.MUTE), currentVolume = 12))
        assertEquals(12, p.apply(Command(Action.RESUME), currentVolume = 0))
    }

    @Test fun default_level_never_goes_negative() {
        // Command's default level is -1; a malformed DUCK must clamp, not underflow.
        val p = VolumePolicy(maxVolume = 20)
        assertEquals(0, p.apply(Command(Action.DUCK), currentVolume = 12))
    }

    @Test fun oversized_level_clamps_to_max() {
        val p = VolumePolicy(maxVolume = 20)
        assertEquals(20, p.apply(Command(Action.BOOST, level = 250), currentVolume = 12))
    }

    @Test fun pause_changes_nothing() {
        val p = VolumePolicy(maxVolume = 20)
        assertNull(p.apply(Command(Action.PAUSE), currentVolume = 12))
        assertEquals(-1, p.savedVolume)
    }

    @Test fun boost_adds_thirty_percent_capped_at_max() {
        val p = VolumePolicy(maxVolume = 20)
        assertEquals(13, p.boostedFrom(10))
        assertEquals(20, p.boostedFrom(19)) // capped
        assertEquals(1, p.boostedFrom(0))   // floor of 1 so boost is audible
    }
}
