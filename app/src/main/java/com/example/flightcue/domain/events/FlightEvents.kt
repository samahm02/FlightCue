package com.example.flightcue.domain.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether an event came from the model (AUTO) or user override (FORCED). */
enum class EventMode { AUTO, FORCED }

sealed class FlightDomainEvent {
    abstract val atSec: Double
    abstract val confidence: Double
    abstract val mode: EventMode

    data class FlightStarted(
        override val atSec: Double,
        override val confidence: Double,
        override val mode: EventMode = EventMode.AUTO
    ) : FlightDomainEvent()

    data class FlightEnded(
        override val atSec: Double,
        override val confidence: Double,
        override val mode: EventMode = EventMode.AUTO
    ) : FlightDomainEvent()
}

enum class FlightState { NotFlying, Flying }

interface FlightEventPublisher {
    fun publish(event: FlightDomainEvent)
    fun setState(state: FlightState) { /* optional no-op */ }
}

open class FlowPublisher : FlightEventPublisher {
    private val _state = MutableStateFlow(FlightState.NotFlying)
    val state: StateFlow<FlightState> = _state

    private val _events =
        MutableSharedFlow<FlightDomainEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<FlightDomainEvent> = _events

    override fun publish(event: FlightDomainEvent) { _events.tryEmit(event) }
    override fun setState(state: FlightState) { _state.value = state }
}

/** One shared bus for the whole process. */
object AppBus : FlowPublisher()
