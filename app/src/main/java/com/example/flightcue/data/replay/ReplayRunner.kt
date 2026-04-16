package com.example.flightcue.data.replay

import android.content.Context
import com.example.flightcue.data.detection.LiveDetection
import com.example.flightcue.domain.detector.FlightDetector
import com.example.flightcue.ml.OnnxPredictor
import com.example.flightcue.ml.OrtSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import java.util.Locale
import kotlin.math.abs

/** Detected and marker-based event times from a single replay run. */
data class ReplaySummary(
    val takeoffDataSec: Double?,   // detected by model
    val landingDataSec: Double?,   // detected by model
    val takeoffMarkerSec: Double?, // ground-truth marker (if present in file)
    val landingMarkerSec: Double?, // ground-truth marker (if present in file)
    val takeoffDeltaSec: Double?,  // detected − marker (if both exist)
    val landingDeltaSec: Double?,  // detected − marker (if both exist)
    val flightsDetected: Int       // completed TO→LD pairs
)

/**
 * Runs fast offline replay by feeding a [Recording] through the full live detection pipeline.
 * Uses the same [FlightDetector], ONNX model, buffers, and feature extraction as live mode.
 * Returns a [ReplaySummary] with detected event times and optional delta against file markers.
 */
class ReplayRunner(
    private val context: Context,
    private val onDevMsg: (String) -> Unit = {}
) {

    suspend fun runFast(
        recording: Recording,
        tickStrideSec: Double = 1.0,
        onProgress: ((Double) -> Unit)? = null
    ): ReplaySummary = withContext(Dispatchers.Default) {
        val r = recording

        val endT = max(
            r.accelTs.lastOrNull() ?: 0.0,
            r.baroTs.lastOrNull() ?: 0.0
        )

        val stride = tickStrideSec.coerceAtLeast(1.0)

        onDevMsg(
            "Replay (ML): accel=${r.ax.size} samples, " +
                    "baro=${r.pHpa.size} samples, duration≈${"%.1f".format(Locale.US, endT)}s, " +
                    "stride≈${"%.2f".format(Locale.US, stride)}s (fixed)"
        )

        OrtSessionManager.init(context)
        val predictor = OnnxPredictor(OrtSessionManager)

        val live = LiveDetection(
            predictor = predictor,
            debug = false,
            overrides = FlightDetector.Overrides(
                strictHopParity = false,
                inferenceSkipFactor = 1
            )
        )

        var ia = 0
        var ib = 0

        var firstTakeoff: Double? = null
        var lastLanding: Double? = null
        var inFlight = false
        var flightsDetected = 0

        val mainTicks  = if (endT > 0.0 && stride > 0.0) ceil(endT / stride).toInt() else 0
        val flushTicks = 3  // extra ticks after end to drain any buffered detections
        val totalTicks = mainTicks + flushTicks
        if (totalTicks > 0) onProgress?.invoke(0.0)

        var tickIndex = 0

        // Main replay loop: feed sensor data up to each tick boundary, then call tick().
        for (i in 0 until mainTicks) {
            coroutineContext.ensureActive()

            val tMax = ((i + 1) * stride).coerceAtMost(endT)

            while (ia < r.accelTs.size && r.accelTs[ia] <= tMax) {
                live.onAccel(r.accelTs[ia], r.ax[ia], r.ay[ia], r.az[ia])
                ia++
            }
            while (ib < r.baroTs.size && r.baroTs[ib] <= tMax) {
                live.onBaro(r.baroTs[ib], r.pHpa[ib])
                ib++
            }

            val decision = live.tick(tMax)
            when (decision?.event) {
                "TAKEOFF" -> {
                    if (!inFlight) {
                        inFlight = true
                        if (firstTakeoff == null) firstTakeoff = decision.atSec
                    }
                }
                "LANDING" -> {
                    if (inFlight) {
                        inFlight = false
                        lastLanding = decision.atSec
                        flightsDetected++
                    }
                }
            }

            tickIndex++
            if (totalTicks > 0) onProgress?.invoke((tickIndex.toDouble() / totalTicks).coerceIn(0.0, 1.0))
        }

        // Tail flush: tick past end of recording to drain any remaining buffered detections.
        for (k in 0 until flushTicks) {
            coroutineContext.ensureActive()

            val tFlush = endT + (k + 1) * stride
            val decision = live.tick(tFlush)
            when (decision?.event) {
                "TAKEOFF" -> {
                    if (!inFlight) {
                        inFlight = true
                        if (firstTakeoff == null) firstTakeoff = decision.atSec
                    }
                }
                "LANDING" -> {
                    if (inFlight) {
                        inFlight = false
                        lastLanding = decision.atSec
                        flightsDetected++
                    }
                }
            }

            tickIndex++
            if (totalTicks > 0) onProgress?.invoke((tickIndex.toDouble() / totalTicks).coerceIn(0.0, 1.0))
        }

        val toMarker = r.markerSec("TAKEOFF")
        val ldMarker = r.markerSec("LANDING")
        val toDelta  = if (firstTakeoff != null && toMarker != null) firstTakeoff - toMarker else null
        val ldDelta  = if (lastLanding  != null && ldMarker != null) lastLanding  - ldMarker else null

        onDevMsg(
            "Replay (ML) result: " +
                    "TO=${fmtTime(firstTakeoff)} (marker=${fmtTime(toMarker)}, Δ=${fmtDelta(toDelta)}), " +
                    "LD=${fmtTime(lastLanding)} (marker=${fmtTime(ldMarker)}, Δ=${fmtDelta(ldDelta)}), " +
                    "completedFlights=$flightsDetected"
        )

        onProgress?.invoke(1.0)

        ReplaySummary(
            takeoffDataSec   = firstTakeoff,
            landingDataSec   = lastLanding,
            takeoffMarkerSec = toMarker,
            landingMarkerSec = ldMarker,
            takeoffDeltaSec  = toDelta,
            landingDeltaSec  = ldDelta,
            flightsDetected  = flightsDetected
        )
    }

    private fun fmtTime(t: Double?): String {
        if (t == null) return "n/a"
        val s  = t.toInt()
        val hh = s / 3600
        val mm = (s % 3600) / 60
        val ss = s % 60
        return "%02d:%02d:%02d".format(Locale.US, hh, mm, ss)
    }

    private fun fmtDelta(d: Double?): String {
        if (d == null) return "n/a"
        val sign = if (d >= 0) "+" else "-"
        return "$sign${"%.1f".format(Locale.US, abs(d))}s"
    }
}