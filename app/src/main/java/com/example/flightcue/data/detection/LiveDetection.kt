package com.example.flightcue.data.detection

import android.content.Context
import android.util.Log
import com.example.flightcue.domain.detector.FlightDetector
import com.example.flightcue.domain.events.EventMode
import com.example.flightcue.domain.events.FlightDomainEvent
import com.example.flightcue.domain.events.FlightEventPublisher
import com.example.flightcue.domain.events.FlowPublisher
import com.example.flightcue.domain.events.FlightState
import com.example.flightcue.domain.predict.Predictor
import com.example.flightcue.ml.OrtSessionManager

class LiveDetection(
    context: Context,
    predictor: Predictor,
    eventPublisher: FlightEventPublisher = FlowPublisher(),
    debug: Boolean = true,
    overrides: FlightDetector.Overrides? = null
) {
    private val core: FlightDetector

    init {
        val toSchema = OrtSessionManager.schemaTakeoff()
        val ldSchema = OrtSessionManager.schemaLanding()

        val (toMean, toScale) = OrtSessionManager.scalerTakeoff()
        val (ldMean, ldScale) = OrtSessionManager.scalerLanding()

        val toProfile = OrtSessionManager.profileTakeoff()
        val ldProfile = OrtSessionManager.profileLanding()

        if (debug) {
            Log.i(
                TAG,
                "TAKEOFF profile: seq=${toProfile.seqLen} labelIdx=${toProfile.labelIdx} future=${toProfile.futureWindows} " +
                        "hop=${toProfile.hopSec} win=${toProfile.winLenSec} nFeat=${toProfile.nFeatures} thr=${toProfile.threshold}"
            )
            Log.i(
                TAG,
                "LANDING profile: seq=${ldProfile.seqLen} labelIdx=${ldProfile.labelIdx} future=${ldProfile.futureWindows} " +
                        "hop=${ldProfile.hopSec} win=${ldProfile.winLenSec} nFeat=${ldProfile.nFeatures} thr=${ldProfile.threshold}"
            )
        }

        require(toProfile.hopSec.isFinite() && toProfile.hopSec > 0.0) {
            "TAKEOFF hopSec invalid: ${toProfile.hopSec}"
        }
        require(toProfile.winLenSec.isFinite() && toProfile.winLenSec > 0.0) {
            "TAKEOFF winLenSec invalid: ${toProfile.winLenSec}"
        }
        require(ldProfile.hopSec.isFinite() && ldProfile.hopSec > 0.0) {
            "LANDING hopSec invalid: ${ldProfile.hopSec}"
        }
        require(ldProfile.winLenSec.isFinite() && ldProfile.winLenSec > 0.0) {
            "LANDING winLenSec invalid: ${ldProfile.winLenSec}"
        }

        require(toSchema.names.size == toProfile.nFeatures) {
            "TAKEOFF schema(${toSchema.names.size}) != profile.nFeatures(${toProfile.nFeatures})"
        }
        require(ldSchema.names.size == ldProfile.nFeatures) {
            "LANDING schema(${ldSchema.names.size}) != profile.nFeatures(${ldProfile.nFeatures})"
        }

        val effOverrides = (overrides ?: FlightDetector.Overrides()).let { o ->
            if (debug) o else o.copy(windowLogsEnabled = false)
        }

        core = FlightDetector(
            toSchema = toSchema,
            ldSchema = ldSchema,
            toScalerMean = toMean,
            toScalerScale = toScale,
            ldScalerMean = ldMean,
            ldScalerScale = ldScale,
            toProfile = toProfile,
            ldProfile = ldProfile,
            predictor = predictor,
            debug = debug,
            eventPublisher = eventPublisher,
            overrides = effOverrides
        )
    }

    fun onAccel(tsSec: Double, x: Double, y: Double, z: Double) =
        core.onAccel(tsSec, x, y, z)

    fun onBaro(tsSec: Double, pHpa: Double) =
        core.onBaro(tsSec, pHpa)

    fun tick(nowSec: Double) =
        core.tick(nowSec)

    fun reset(nowSec: Double) =
        core.reset(nowSec)

    /**
     * Manual/forced takeoff path.
     * This updates the authoritative detector FSM and re-arms landing.
     */
    fun forceFlightStarted(
        atSec: Double,
        confidence: Double = 1.0,
        mode: EventMode = EventMode.FORCED,
        publishEvent: Boolean = true
    ): FlightDomainEvent? =
        core.forceFlightStarted(
            atSec = atSec,
            confidence = confidence,
            mode = mode,
            publishEvent = publishEvent
        )

    /**
     * Manual/forced landing path.
     * This updates the authoritative detector FSM and re-arms takeoff.
     */
    fun forceFlightEnded(
        atSec: Double,
        confidence: Double = 1.0,
        mode: EventMode = EventMode.FORCED,
        publishEvent: Boolean = true
    ): FlightDomainEvent? =
        core.forceFlightEnded(
            atSec = atSec,
            confidence = confidence,
            mode = mode,
            publishEvent = publishEvent
        )

    /**
     * Restore detector FSM state without publishing a new event.
     */
    fun restoreState(state: FlightState, nowSec: Double) =
        core.restoreState(state, nowSec)

    companion object {
        private const val TAG = "LiveDetection"
    }
}