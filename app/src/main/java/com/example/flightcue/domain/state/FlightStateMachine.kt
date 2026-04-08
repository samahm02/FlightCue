package com.example.flightcue.domain.state

import com.example.flightcue.domain.events.FlightDomainEvent
import com.example.flightcue.domain.events.FlightEventPublisher
import com.example.flightcue.domain.events.FlightState

/**
 * NotFlying → watches TAKEOFF trigger → FlightStarted → Flying
 * Flying    → watches LANDING trigger → FlightEnded  → NotFlying
 */
class FlightStateMachine(
    private val toTrigger: EdgeTrigger,
    private val ldTrigger: EdgeTrigger,
    private val minSepSec: Double,
    private val publisher: FlightEventPublisher
) {
    var state: FlightState = FlightState.NotFlying
        private set

    private var lastEventSec: Double = Double.NEGATIVE_INFINITY

    fun onTakeoffScore(p: Double, edgeSec: Double): FlightDomainEvent? {
        if (state != FlightState.NotFlying) return null
        if ((edgeSec - lastEventSec) < minSepSec) return null

        if (toTrigger.update(p, edgeSec)) {
            state = FlightState.Flying
            lastEventSec = edgeSec
            ldTrigger.reset()

            val ev = FlightDomainEvent.FlightStarted(edgeSec, p)
            publisher.setState(state)
            publisher.publish(ev)
            return ev
        }
        return null
    }

    fun onLandingScore(p: Double, edgeSec: Double): FlightDomainEvent? {
        if (state != FlightState.Flying) return null
        if ((edgeSec - lastEventSec) < minSepSec) return null

        if (ldTrigger.update(p, edgeSec)) {
            state = FlightState.NotFlying
            lastEventSec = edgeSec
            toTrigger.reset()

            val ev = FlightDomainEvent.FlightEnded(edgeSec, p)
            publisher.setState(state)
            publisher.publish(ev)
            return ev
        }
        return null
    }

    fun resetToNotFlying(nowSec: Double = Double.NEGATIVE_INFINITY) {
        state = FlightState.NotFlying
        lastEventSec = nowSec
        publisher.setState(state)
        toTrigger.reset()
        ldTrigger.reset()
    }
}
