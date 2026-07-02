package com.sonex.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdsTest {
    @Test fun trigger_between_baseline_and_talk() {
        val t = Thresholds.trigger(-35.0, -25.0, sensitivity = 0.5)
        assertTrue("trigger should sit between the two anchors", t in -35.0..-25.0)
    }

    @Test fun higher_sensitivity_lowers_trigger() {
        val low = Thresholds.trigger(-35.0, -25.0, 0.2)
        val high = Thresholds.trigger(-35.0, -25.0, 0.8)
        assertTrue("more sensitive => triggers at lower level", high < low)
    }

    @Test fun corner_placement_preserves_gap() {
        // Corner phone reads everything ~10dB lower; the gap logic still holds.
        val centre = Thresholds.trigger(-35.0, -25.0, 0.5)
        val corner = Thresholds.trigger(-45.0, -35.0, 0.5)
        assertEquals(centre - corner, 10.0, 0.001)
    }

    @Test fun boost_is_above_baseline() {
        assertTrue(Thresholds.boostTrigger(-35.0) > -35.0)
    }
}
