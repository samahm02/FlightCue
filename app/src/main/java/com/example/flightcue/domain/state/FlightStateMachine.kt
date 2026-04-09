package com.example.flightcue.domain.state

import com.example.flightcue.domain.events.EventMode
import com.example.flightcue.domain.events.FlightDomainEvent
import com.example.flightcue.domain.events.FlightEventPublisher
import com.example.flightcue.domain.events.FlightState

/**
 * NotFlying → watches TAKEOFF trigger → FlightStarted → Flying
 * Flying    → watches LANDING trigger → FlightEnded  → NotFlying
 *
 * Authoritative state lives here.
 * The publisher mirrors state/events outward to AppBus/UI.
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

    /**
     * Force transition into Flying.
     * Used for manual takeoff / override.
     */
    fun forceFlightStarted(
        atSec: Double,
        confidence: Double = 1.0,
        mode: EventMode = EventMode.FORCED,
        publishEvent: Boolean = true
    ): FlightDomainEvent? {
        if (state == FlightState.Flying) return null

        state = FlightState.Flying
        lastEventSec = atSec

        toTrigger.reset()
        ldTrigger.reset()

        val ev = FlightDomainEvent.FlightStarted(
            atSec = atSec,
            confidence = confidence,
            mode = mode
        )

        publisher.setState(state)
        if (publishEvent) {
            publisher.publish(ev)
        }
        return ev
    }

    /**
     * Force transition into NotFlying.
     * Used for manual landing / override.
     */
    fun forceFlightEnded(
        atSec: Double,
        confidence: Double = 1.0,
        mode: EventMode = EventMode.FORCED,
        publishEvent: Boolean = true
    ): FlightDomainEvent? {
        if (state == FlightState.NotFlying) return null

        state = FlightState.NotFlying
        lastEventSec = atSec

        toTrigger.reset()
        ldTrigger.reset()

        val ev = FlightDomainEvent.FlightEnded(
            atSec = atSec,
            confidence = confidence,
            mode = mode
        )

        publisher.setState(state)
        if (publishEvent) {
            publisher.publish(ev)
        }
        return ev
    }

    /**
     * Restore authoritative state, e.g. after service/process recreation.
     * Does not emit a new event.
     */
    fun restoreState(restored: FlightState, nowSec: Double) {
        state = restored
        lastEventSec = nowSec
        toTrigger.reset()
        ldTrigger.reset()
        publisher.setState(state)
    }

    fun resetToNotFlying(nowSec: Double = Double.NEGATIVE_INFINITY) {
        state = FlightState.NotFlying
        lastEventSec = nowSec
        publisher.setState(state)
        toTrigger.reset()
        ldTrigger.reset()
    }
}