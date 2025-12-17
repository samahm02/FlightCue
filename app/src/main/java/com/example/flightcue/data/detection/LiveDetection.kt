package com.example.flightcue.data.detection

import android.content.Context
import com.example.flightcue.data.modelspec.ModelArtifactsLoader
import com.example.flightcue.domain.detector.FlightDetector
import com.example.flightcue.domain.events.FlightDomainEvent
import com.example.flightcue.domain.events.FlightEventPublisher
import com.example.flightcue.domain.events.FlightState
import com.example.flightcue.domain.events.FlowPublisher
import com.example.flightcue.domain.predict.Predictor
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * LiveDetection: small wrapper that:
 *  - loads artifacts from assets
 *  - injects a Predictor (e.g., ONNX)
 *  - exposes the same onAccel/onBaro/tick API as the old Detector
 */
class LiveDetection(
    context: Context,
    predictor: Predictor,
    eventPublisher: FlightEventPublisher = FlowPublisher(),
    debug: Boolean = true,
    overrides: FlightDetector.Overrides? = null
) {
    private val core: FlightDetector

    init {
        val art = ModelArtifactsLoader.load(context)
        core = FlightDetector(
            toSchema = art.toSchema,
            ldSchema = art.ldSchema,
            toMedians = art.toMedians,
            ldMedians = art.ldMedians,
            toProfile = art.toProfile,
            ldProfile = art.ldProfile,
            predictor = predictor,
            debug = debug,
            eventPublisher = eventPublisher,
            overrides = overrides
        )
    }

    // Mirror the core API
    fun onAccel(tsSec: Double, x: Double, y: Double, z: Double) = core.onAccel(tsSec, x, y, z)
    fun onBaro (tsSec: Double, pHpa: Double)                 = core.onBaro(tsSec, pHpa)
    fun tick(nowSec: Double)                                 = core.tick(nowSec)

    val stateFlow: StateFlow<FlightState>? get() = core.stateFlow
    val events: SharedFlow<FlightDomainEvent>?   get() = core.events
}
