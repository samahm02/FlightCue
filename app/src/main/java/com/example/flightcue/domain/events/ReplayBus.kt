package com.example.flightcue.domain.events

/** Dedicated bus for local replay UI (does not write logs unless tee is enabled). */
object ReplayBus : FlowPublisher()

/** Publisher that always forwards to primary, and optionally mirrors to secondary (AppBus). */
class TeePublisher(
    private val primary: FlightEventPublisher,
    private val secondary: FlightEventPublisher
) : FlightEventPublisher {
    @Volatile var shareToHistory: Boolean = false

    override fun publish(event: FlightDomainEvent) {
        primary.publish(event)
        if (shareToHistory) secondary.publish(event)
    }

    override fun setState(state: FlightState) {
        primary.setState(state)
        if (shareToHistory) secondary.setState(state)
    }
}
