package com.example.flightcue.data.detection

import android.content.Context
import android.util.Log
import com.example.flightcue.domain.detector.FlightDetector
import com.example.flightcue.domain.events.FlightEventPublisher
import com.example.flightcue.domain.events.FlowPublisher
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

        // Dev-only sanity logs
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

        require(toProfile.hopSec.isFinite() && toProfile.hopSec > 0.0) { "TAKEOFF hopSec invalid: ${toProfile.hopSec}" }
        require(toProfile.winLenSec.isFinite() && toProfile.winLenSec > 0.0) { "TAKEOFF winLenSec invalid: ${toProfile.winLenSec}" }
        require(ldProfile.hopSec.isFinite() && ldProfile.hopSec > 0.0) { "LANDING hopSec invalid: ${ldProfile.hopSec}" }
        require(ldProfile.winLenSec.isFinite() && ldProfile.winLenSec > 0.0) { "LANDING winLenSec invalid: ${ldProfile.winLenSec}" }

        require(toSchema.names.size == toProfile.nFeatures) {
            "TAKEOFF schema(${toSchema.names.size}) != profile.nFeatures(${toProfile.nFeatures})"
        }
        require(ldSchema.names.size == ldProfile.nFeatures) {
            "LANDING schema(${ldSchema.names.size}) != profile.nFeatures(${ldProfile.nFeatures})"
        }

        // ✅ Dev-only enforcement: release builds cannot enable window logs even if caller tries.
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

    fun onAccel(tsSec: Double, x: Double, y: Double, z: Double) = core.onAccel(tsSec, x, y, z)
    fun onBaro(tsSec: Double, pHpa: Double) = core.onBaro(tsSec, pHpa)
    fun tick(nowSec: Double) = core.tick(nowSec)

    fun reset(nowSec: Double) = core.reset(nowSec)

    companion object {
        private const val TAG = "LiveDetection"
    }
}
