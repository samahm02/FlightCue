package com.example.flightcue.domain.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain-level mode for how a flight event was produced:
 * - AUTO: emitted by model / detector logic
 * - FORCED: user override (manual mark)
 */
enum class EventMode { AUTO, FORCED }

/**
 * Domain events emitted by the detector/state machine.
 *
 * Time base:
 * - atSec is "seconds since boot" (elapsed realtime), consistent with the detector clock.
 * - confidence is model confidence for AUTO, or a UI-chosen value for FORCED.
 */
sealed class FlightDomainEvent {
    abstract val atSec: Double
    abstract val confidence: Double
    abstract val mode: EventMode

    /** FSM transition NotFlying -> Flying. */
    data class FlightStarted(
        override val atSec: Double,
        override val confidence: Double,
        override val mode: EventMode = EventMode.AUTO
    ) : FlightDomainEvent()

    /** FSM transition Flying -> NotFlying. */
    data class FlightEnded(
        override val atSec: Double,
        override val confidence: Double,
        override val mode: EventMode = EventMode.AUTO
    ) : FlightDomainEvent()
}

/**
 * High-level flight phase derived from model events.
 *
 * Note: this is the domain's notion of state; the service/UI can map it to UI state.
 */
enum class FlightState { NotFlying, Flying }

/**
 * Minimal abstraction for publishing flight events from domain code.
 *
 * Implementations are allowed to be no-ops for setState().
 */
interface FlightEventPublisher {
    /** Publish a domain event. */
    fun publish(event: FlightDomainEvent)

    /** Update the current high-level flight state (optional). */
    fun setState(state: FlightState) { /* optional no-op */ }
}

/**
 * Flow-backed publisher used by the app process.
 *
 * - state is held in a StateFlow
 * - events is a SharedFlow (buffered)
 *
 * Emission uses tryEmit() to avoid blocking the detector thread.
 * If the buffer is full, events may be dropped.
 */
open class FlowPublisher(
    eventBufferCapacity: Int = 32
) : FlightEventPublisher {

    private val _state = MutableStateFlow(FlightState.NotFlying)
    val state: StateFlow<FlightState> = _state

    private val _events = MutableSharedFlow<FlightDomainEvent>(
        extraBufferCapacity = eventBufferCapacity
    )
    val events: SharedFlow<FlightDomainEvent> = _events

    override fun publish(event: FlightDomainEvent) {
        _events.tryEmit(event)
    }

    override fun setState(state: FlightState) {
        _state.value = state
    }
}

/**
 * Process-wide event bus for the "live" detector.
 *
 * Used by the service + UI to observe flight state and events.
 */
object AppBus : FlowPublisher()
