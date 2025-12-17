package com.example.flightcue.data.replay

import android.content.Context
import com.example.flightcue.data.detection.LiveDetection
import com.example.flightcue.domain.detector.FlightDetector
import com.example.flightcue.ml.OnnxPredictor
import com.example.flightcue.ml.OrtSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max

data class ReplaySummary(
    val takeoffDataSec: Double?,     // from ML model
    val landingDataSec: Double?,     // from ML model
    val flightsDetected: Int
)

class ReplayRunner(
    private val context: Context,
    private val onDevMsg: (String) -> Unit = {}
) {

    /**
     * Fast ML-only replay:
     *
     *  - Feeds ALL accel + baro samples into a LiveDetection instance
     *  - Chooses a stride automatically so that we never do more than
     *    ~targetTicks ML evaluations, no matter how long the recording is
     *  - Disables strict hop parity inside FlightDetector so large strides
     *    still work
     *  - Tracks TAKEOFF / LANDING events from FlightDetector.Decision
     *  - Returns first takeoff and last landing + number of flights
     *
     * If the model does NOT detect a complete flight (takeoff + landing),
     * this will throw an IllegalStateException so the UI shows an error.
     *
     * onProgress: gets values in [0.0, 1.0] for actual replay progress.
     */
    suspend fun runFast(
        source: ReplaySource,
        tickStrideSec: Double = 5.0,   // "base" stride; will be auto-adjusted
        onProgress: ((Double) -> Unit)? = null
    ): ReplaySummary = withContext(Dispatchers.Default) {
        val r = source.rec

        // Total duration of this recording
        val endT = max(
            r.accelTs.lastOrNull() ?: 0.0,
            r.baroTs.lastOrNull() ?: 0.0
        )

        // ---- Adaptive stride logic ----
        // We want to cap the number of ML ticks for long recordings.
        val targetTicks = 200.0          // hard cap on ~how many ticks we allow
        val minStride  = 5.0             // don't go below this (live parity-ish)
        val maxStride  = 300.0           // also don't go totally crazy

        var stride = tickStrideSec.coerceAtLeast(minStride)
        if (endT > 0.0) {
            val approxTicks = endT / stride
            if (approxTicks > targetTicks) {
                // Increase stride so we end up around targetTicks
                stride = (endT / targetTicks).coerceIn(minStride, maxStride)
            }
        }

        onDevMsg(
            "Replay (ML): accel=${r.ax.size} samples, " +
                    "baro=${r.pHpa.size} samples, duration≈${"%.1f".format(endT)}s, " +
                    "stride≈${"%.2f".format(stride)}s (adaptive)"
        )

        // Init ONNX (idempotent)
        OrtSessionManager.init(context)
        val predictor = OnnxPredictor(OrtSessionManager)

        // IMPORTANT:
        // - No AppBus here → this LiveDetection is fully local to replay
        // - We pass an override that disables strict hop parity so we can use
        //   a larger tick stride without having to match exact 10s/12s hops.
        val live = LiveDetection(
            context = context,
            predictor = predictor,
            // eventPublisher default = FlowPublisher(), i.e. local bus
            debug = false,
            overrides = FlightDetector.Overrides(
                strictHopParity = false
            )
        )

        var ia = 0
        var ib = 0

        var firstTakeoff: Double? = null
        var lastLanding: Double? = null
        var inFlight = false
        var flightsDetected = 0

        // ---- Progress setup ----
        val mainTicks = if (endT > 0.0 && stride > 0.0) {
            ceil(endT / stride).toInt()
        } else 0
        val flushTicks = 3
        val totalTicks = mainTicks + flushTicks

        if (totalTicks > 0) {
            onProgress?.invoke(0.0)
        }

        var tickIndex = 0

        // Main replay loop: feed samples and tick the model on our adaptive grid
        for (i in 0 until mainTicks) {
            coroutineContext.ensureActive()

            val tMax = ((i + 1) * stride).coerceAtMost(endT)

            // Feed accel samples up to tMax
            while (ia < r.accelTs.size && r.accelTs[ia] <= tMax) {
                live.onAccel(r.accelTs[ia], r.ax[ia], r.ay[ia], r.az[ia])
                ia++
            }

            // Feed baro samples up to tMax
            while (ib < r.baroTs.size && r.baroTs[ib] <= tMax) {
                live.onBaro(r.baroTs[ib], r.pHpa[ib])
                ib++
            }

            // Tick the ML detector once for this (possibly large) stride
            val decision = live.tick(tMax)
            when (decision?.event) {
                "TAKEOFF" -> {
                    if (!inFlight) {
                        inFlight = true
                        if (firstTakeoff == null) {
                            firstTakeoff = tMax
                        }
                    }
                }
                "LANDING" -> {
                    if (inFlight) {
                        inFlight = false
                        lastLanding = tMax
                        flightsDetected++
                    }
                }
            }

            tickIndex++
            if (totalTicks > 0) {
                val frac = tickIndex.toDouble() / totalTicks.toDouble()
                onProgress?.invoke(frac.coerceIn(0.0, 1.0))
            }
        }

        // Extra ticks to flush any delayed edges at the tail
        for (k in 0 until flushTicks) {
            coroutineContext.ensureActive()

            val tFlush = endT + (k + 1) * stride
            val decision = live.tick(tFlush)
            when (decision?.event) {
                "TAKEOFF" -> {
                    if (!inFlight) {
                        inFlight = true
                        if (firstTakeoff == null) {
                            firstTakeoff = tFlush
                        }
                    }
                }
                "LANDING" -> {
                    if (inFlight) {
                        inFlight = false
                        lastLanding = tFlush
                        flightsDetected++
                    }
                }
            }

            tickIndex++
            if (totalTicks > 0) {
                val frac = tickIndex.toDouble() / totalTicks.toDouble()
                onProgress?.invoke(frac.coerceIn(0.0, 1.0))
            }
        }

        // If model didn't find a clean flight, treat it as an error
        if (firstTakeoff == null || lastLanding == null || lastLanding!! <= firstTakeoff!!) {
            throw IllegalStateException("Model did not detect a complete flight in this recording")
        }

        // Defensive: if for some reason flightsDetected stayed 0 but we have TO/LDG, set to 1
        if (flightsDetected == 0) {
            flightsDetected = 1
        }

        onDevMsg(
            "Replay (ML) done: takeoff≈${fmtTime(firstTakeoff)} " +
                    "landing≈${fmtTime(lastLanding)} flights=$flightsDetected"
        )

        // Make sure we end on 100% for the caller
        onProgress?.invoke(1.0)

        ReplaySummary(
            takeoffDataSec = firstTakeoff,
            landingDataSec = lastLanding,
            flightsDetected = flightsDetected
        )
    }

    private fun fmtTime(t: Double?): String =
        if (t == null) "n/a" else {
            val s = t.toInt()
            val hh = s / 3600
            val mm = (s % 3600) / 60
            val ss = s % 60
            "%02d:%02d:%02d".format(hh, mm, ss)
        }
}
//17