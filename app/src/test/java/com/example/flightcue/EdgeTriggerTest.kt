package com.example.flightcue

import com.example.flightcue.domain.state.EdgeTrigger
import org.junit.Assert.*
import org.junit.Test

/** Edge gate for model scores: starts ARMED, fires when p>=tOn for `minRunWins`
 * and not in cooldown; then DISARMS, re-arming only after p<=tOff.
 * Neutral band (tOff<p<tOn) clears the run count but keeps the arm state. */

class EdgeTriggerTest {
    @Test fun fires_after_minrun_and_respects_hysteresis_and_cooldown() {
        val trig = EdgeTrigger(tOn = 0.5, tOff = 0.3, minRunWins = 2, cooldownSec = 5.0)

        // 1st above-on (not enough for minRun)
        assertFalse(trig.update(0.6, 10.0))
        // 2nd above-on -> fire
        assertTrue(trig.update(0.7, 12.0))
        // immediately after, still above-on but in cooldown -> no fire
        assertFalse(trig.update(0.8, 14.0))
        // drop below off to re-arm
        assertFalse(trig.update(0.2, 16.0))
        // still within cooldown window -> no fire
        assertFalse(trig.update(0.7, 17.0))
        // after cooldown
        assertFalse(trig.update(0.6, 17.9))
        assertTrue(trig.update(0.6, 18.1)) // fires again
    }
}
