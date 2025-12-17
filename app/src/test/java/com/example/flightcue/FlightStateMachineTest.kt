package com.example.flightcue

import com.example.flightcue.domain.state.EdgeTrigger
import com.example.flightcue.domain.events.FlightDomainEvent
import com.example.flightcue.domain.events.FlightEventPublisher
import com.example.flightcue.domain.events.FlightState
import com.example.flightcue.domain.state.FlightStateMachine
import org.junit.Assert.*
import org.junit.Test

/** Verifies 2-state loop:
 * NotFlying --(TAKEOFF fire)--> Flying with min-separation blocking re-triggers,
 * Flying --(LANDING fire)--> NotFlying; exactly one start/end event is published. */


private class Collector : FlightEventPublisher {
    val events = mutableListOf<FlightDomainEvent>()
    var lastState: FlightState = FlightState.NotFlying
    override fun publish(event: FlightDomainEvent) { events += event }
    override fun setState(state: FlightState) { lastState = state }
}


class FlightStateMachineTest {
    @Test fun notflying_to_flying_then_back_with_minsep() {
        val to = EdgeTrigger(0.5, 0.3, minRunWins = 1, cooldownSec = 0.0)
        val ld = EdgeTrigger(0.6, 0.4, minRunWins = 1, cooldownSec = 0.0)
        val col = Collector()
        val fsm = FlightStateMachine(to, ld, minSepSec = 5.0, publisher = col)

        // TAKEOFF at t=10 -> start
        assertNotNull(fsm.onTakeoffScore(0.8, 10.0))
        assertEquals(FlightState.Flying, fsm.state)

        // Another TAKEOFF within minSep -> ignored
        assertNull(fsm.onTakeoffScore(0.9, 12.0))

        // LANDING at t=16 (>=5s later) -> end
        assertNotNull(fsm.onLandingScore(0.9, 16.0))
        assertEquals(FlightState.NotFlying, fsm.state)

        // Events captured
        assertEquals(2, col.events.size)
        assertTrue(col.events[0] is FlightDomainEvent.FlightStarted)
        assertTrue(col.events[1] is FlightDomainEvent.FlightEnded)
    }

    @Test fun landing_ignored_before_takeoff() {
        val fsm = FlightStateMachine(EdgeTrigger(0.5,0.3,1,0.0), EdgeTrigger(0.6,0.4,1,0.0), 5.0, Collector())
        assertNull(fsm.onLandingScore(0.99, 5.0))               // ignored in NotFlying
        assertEquals(FlightState.NotFlying, fsm.state)
    }

    @Test fun stop_monitoring_resets_state() {
        val col = Collector()
        val fsm = FlightStateMachine(EdgeTrigger(0.5,0.3,1,0.0), EdgeTrigger(0.6,0.4,1,0.0), 5.0, col)
        assertNotNull(fsm.onTakeoffScore(0.9, 10.0))            // go Flying
        fsm.resetToNotFlying()
        assertEquals(FlightState.NotFlying, fsm.state)
    }

}
