package com.example.flightcue.domain.detector

import com.example.flightcue.data.modelspec.FeatureSchema
import com.example.flightcue.data.modelspec.ModelProfile
import com.example.flightcue.domain.timeseries.AccelBuffer
import com.example.flightcue.domain.timeseries.BaroBuffer
import com.example.flightcue.domain.state.EdgeTrigger
import com.example.flightcue.domain.util.FeatureMath
import com.example.flightcue.domain.events.FlightDomainEvent
import com.example.flightcue.domain.events.FlightEventPublisher
import com.example.flightcue.domain.events.FlightState
import com.example.flightcue.domain.state.FlightStateMachine
import com.example.flightcue.domain.events.FlowPublisher
import com.example.flightcue.domain.util.Params
import com.example.flightcue.domain.timeseries.Resample
import com.example.flightcue.domain.features.Features
import com.example.flightcue.domain.features.RobustCenters
import com.example.flightcue.domain.predict.Predictor
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * FlightDetector (core):
 * buffers → resample → derive → slice → features → Predictor → FSM
 * - No Android Context
 * - No asset I/O
 * - No ONNX: uses Predictor interface
 */
class FlightDetector(
    private val toSchema: FeatureSchema,
    private val ldSchema: FeatureSchema,
    private val toMedians: DoubleArray,
    private val ldMedians: DoubleArray,
    private val toProfile: ModelProfile,
    private val ldProfile: ModelProfile,
    private val predictor: Predictor,
    debug: Boolean = true,
    eventPublisher: FlightEventPublisher = FlowPublisher(),
    private val overrides: Overrides? = null
) {
    private companion object {
        private const val TAG = "FlightDetectorCore"

        // Parity with training
        private const val COVERAGE_THR = 0.90
        private const val TO_WIN_S = 20.0
        private const val LD_WIN_S = 24.0
        private const val TO_HOP_S = 10.0
        private const val LD_HOP_S = 12.0

        // Match edges to hop grid (can be relaxed via overrides for replay)
        private const val MOD_EPS_S = 0.10 // ±100ms

        private const val DEFAULT_MIN_RUN = 1 // windows
    }

    enum class EdgeMode { EMA, TWO_OF_THREE }

    /** Runtime overrides from settings / callers (optional). */
    data class Overrides(
        val edgeMode: EdgeMode? = null, // (future) when EdgeTrigger exposes mode selection
        val emaAlpha: Double? = null,   // overrides EMA alpha if provided
        val minSepSec: Int? = null,     // overrides FSM min separation if provided
        val strictHopParity: Boolean? = null // if false, disable exact-hop gating (useful for fast replay)
    )

    data class Decision(val event: String, val p: Double, val on: Double, val off: Double)

    // Pure ring buffers live in domain
    private val accel = AccelBuffer()
    private val baro  = BaroBuffer()

    /** Expose flows (for service/UI) if FlowPublisher is used. */
    private val flowPub: FlowPublisher? = eventPublisher as? FlowPublisher
    val stateFlow: StateFlow<FlightState>? get() = flowPub?.state
    val events: SharedFlow<FlightDomainEvent>? get() = flowPub?.events

    // modulo anchors & last used edges
    private var anchorTo: Double? = null
    private var anchorLd: Double? = null
    private var lastToEdge: Double = Double.NEGATIVE_INFINITY
    private var lastLdEdge: Double = Double.NEGATIVE_INFINITY

    // whether we enforce strict hop parity for edge times (can be relaxed via Overrides)
    private val strictHopParity: Boolean = overrides?.strictHopParity ?: true

    // Build triggers and FSM from profile
    private val fsm: FlightStateMachine

    init {
        // Thresholds
        val (toOn, toOff) = toProfile.thresholds()
        val (ldOn, ldOff) = ldProfile.thresholds()

        // minRun (clamped to >=1 by EdgeTrigger internally, but keep old default)
        val toMinRun = if (toProfile.minRun <= 0) DEFAULT_MIN_RUN else toProfile.minRun
        val ldMinRun = if (ldProfile.minRun <= 0) DEFAULT_MIN_RUN else ldProfile.minRun

        // Cooldowns
        val toCooldownSec = (toProfile.cooldownMs.toDouble()) / 1000.0
        val ldCooldownSec = (ldProfile.cooldownMs.toDouble()) / 1000.0

        // Base min-sep from profiles; allow override
        var minSepSec = (maxOf(toProfile.minSepMs, ldProfile.minSepMs).toDouble()) / 1000.0
        overrides?.minSepSec?.let { if (it >= 0) minSepSec = it.toDouble() }

        // EMA alpha: if profile smoothing > 0, convert to alpha; allow override
        val profToAlpha = toProfile.smoothing.takeIf { it > 0 }?.let { 2.0 / (it + 1.0) }
        val profLdAlpha = ldProfile.smoothing.takeIf { it > 0 }?.let { 2.0 / (it + 1.0) }
        val toAlpha = overrides?.emaAlpha ?: profToAlpha
        val ldAlpha = overrides?.emaAlpha ?: profLdAlpha

        val toTrigger =
            EdgeTrigger(toOn, toOff, toMinRun, toCooldownSec, startArmed = true, emaAlpha = toAlpha)
        val ldTrigger =
            EdgeTrigger(ldOn, ldOff, ldMinRun, ldCooldownSec, startArmed = true, emaAlpha = ldAlpha)
        fsm = FlightStateMachine(toTrigger, ldTrigger, minSepSec, eventPublisher)
    }

    fun onAccel(tsSec: Double, x: Double, y: Double, z: Double) = accel.push(tsSec, x, y, z)
    fun onBaro(tsSec: Double, pHpa: Double) = baro.push(tsSec, pHpa)

    /** Call on a timer (e.g., 1 Hz live, coarser in replay). Returns a Decision when an event fires. */
    fun tick(nowSec: Double): Decision? {
        val aR = Resample.accel(accel.snapshot(), Params.ACCEL_HZ, Params.BIG_GAP_FACTOR)
        val bR = Resample.baro (baro.snapshot(),  Params.BARO_HZ,  Params.BIG_GAP_FACTOR)

        val aD = if (aR.t.isNotEmpty()) Features.deriveAccel(aR, Params.ACCEL_HZ) else null
        val bD = if (bR.t.isNotEmpty()) Features.deriveBaro (bR, Params.BARO_HZ ) else null
        val centers: RobustCenters? = if (Params.ROBUST_PER_FLIGHT) buildCenters(aD, bD) else null

        // TAKEOFF edge
        alignedEdge(nowSec, isTO = true)?.let { edge ->
            val weA = round(edge * Params.ACCEL_HZ) / Params.ACCEL_HZ
            val weB = round(edge * Params.BARO_HZ) / Params.BARO_HZ
            val wsA = weA - TO_WIN_S
            val wsB = weB - TO_WIN_S

            val aW = sliceAccel(aD, wsA, weA)
            val bW = sliceBaro (bD, wsB, weB)

            if (passesCoverage(aW, bW)) {
                val fMap = Features.windowFeatures(aW, bW, Params.ACCEL_HZ, Params.BARO_HZ, centers, Params.DO_PSD)
                val x = align(fMap, toSchema.names, toMedians)
                val p = predictor.scoreTakeoff(x)
                val (on, off) = toProfile.thresholds()
                lastToEdge = edge
                fsm.onTakeoffScore(p, edge)?.let { return Decision("TAKEOFF", p, on, off) }
            }
        }

        // LANDING edge
        alignedEdge(nowSec, isTO = false)?.let { edge ->
            val weA = round(edge * Params.ACCEL_HZ) / Params.ACCEL_HZ
            val weB = round(edge * Params.BARO_HZ) / Params.BARO_HZ
            val wsA = weA - LD_WIN_S
            val wsB = weB - LD_WIN_S

            val aW = sliceAccel(aD, wsA, weA)
            val bW = sliceBaro (bD, wsB, weB)

            if (passesCoverage(aW, bW)) {
                val fMap = Features.windowFeatures(aW, bW, Params.ACCEL_HZ, Params.BARO_HZ, centers, Params.DO_PSD)
                val x = align(fMap, ldSchema.names, ldMedians)
                val p = predictor.scoreLanding(x)
                val (on, off) = ldProfile.thresholds()
                lastLdEdge = edge
                fsm.onLandingScore(p, edge)?.let { return Decision("LANDING", p, on, off) }
            }
        }

        return null
    }

    // ---- exact-hop edge gate (can be relaxed via overrides) ----
    private fun alignedEdge(now: Double, isTO: Boolean): Double? {
        if (!strictHopParity) return now
        val hop  = if (isTO) TO_HOP_S else LD_HOP_S
        val last = if (isTO) lastToEdge else lastLdEdge
        if (isTO) {
            if (anchorTo == null) anchorTo = now
        } else {
            if (anchorLd == null) anchorLd = now
        }
        val a = if (isTO) anchorTo!! else anchorLd!!
        var k = round((now - a) / hop).toLong()
        var cand = a + k * hop
        if (cand <= last + 1e-9) {
            k += 1
            cand = a + k * hop
        }
        return if (abs(now - cand) <= MOD_EPS_S) cand else null
    }

    // ---- coverage parity ----
    private fun passesCoverage(a: Features.AccelDerived?, b: Features.BaroDerived?): Boolean {
        var ok = true
        if (a != null) {
            if (a.ax.isNotEmpty()) ok = ok && coverage(a.ax) >= COVERAGE_THR
            if (a.ay.isNotEmpty()) ok = ok && coverage(a.ay) >= COVERAGE_THR
            if (a.az.isNotEmpty()) ok = ok && coverage(a.az) >= COVERAGE_THR
        }
        if (b != null) {
            if (b.p.isNotEmpty()) ok = ok && coverage(b.p) >= COVERAGE_THR
        }
        return ok
    }

    private fun coverage(arr: DoubleArray): Double {
        if (arr.isEmpty()) return 0.0
        var n = 0
        for (v in arr) if (!v.isNaN()) n++
        return n.toDouble() / arr.size.toDouble()
    }

    // ---- slicing & utils ----
    private fun sliceAccel(ad: Features.AccelDerived?, ws: Double, we: Double): Features.AccelDerived? {
        if (ad == null) return null
        val i0 = ad.t.indexOfFirst { it >= ws }.let { if (it == -1) 0 else it }
        val i1 = ad.t.indexOfLast  { it <  we }.let { if (it == -1) ad.t.lastIndex else it }
        if (i0 >= i1) return null
        fun cut(x: DoubleArray) = x.sliceArray(i0..i1)
        return Features.AccelDerived(
            cut(ad.t), cut(ad.ax), cut(ad.ay), cut(ad.az),
            cut(ad.amag), cut(ad.amagMA10), cut(ad.amagVar10),
            cut(ad.avert), cut(ad.ahoriz)
        )
    }

    private fun sliceBaro(bd: Features.BaroDerived?, ws: Double, we: Double): Features.BaroDerived? {
        if (bd == null) return null
        val i0 = bd.t.indexOfFirst { it >= ws }.let { if (it == -1) 0 else it }
        val i1 = bd.t.indexOfLast  { it <  we }.let { if (it == -1) bd.t.lastIndex else it }
        if (i0 >= i1) return null
        fun cut(x: DoubleArray) = x.sliceArray(i0..i1)
        return Features.BaroDerived(
            cut(bd.t), cut(bd.p), cut(bd.h), cut(bd.dhdt),
            cut(bd.pMA30), cut(bd.pMA30dt1)
        )
    }

    private fun align(feats: Map<String, Double>, names: List<String>, med: DoubleArray): DoubleArray =
        DoubleArray(names.size) { i -> feats[names[i]]?.takeIf { it.isFinite() } ?: med[i] }

    private fun buildCenters(a: Features.AccelDerived?, b: Features.BaroDerived?): RobustCenters? {
        if (a == null && b == null) return null
        val medAx = a?.ax?.let { FeatureMath.median(it) } ?: 0.0
        val medAy = a?.ay?.let { FeatureMath.median(it) } ?: 0.0
        val medAz = a?.az?.let { FeatureMath.median(it) } ?: 0.0
        val iqrAx = a?.ax?.let { FeatureMath.iqr(it) } ?: 1.0
        val iqrAy = a?.ay?.let { FeatureMath.iqr(it) } ?: 1.0
        val iqrAz = a?.az?.let { FeatureMath.iqr(it) } ?: 1.0
        val medP  = b?.p ?.let { FeatureMath.median(it) } ?: 0.0
        val iqrP  = b?.p ?.let { FeatureMath.iqr(it) } ?: 1.0
        return RobustCenters(medAx, iqrAx, medAy, iqrAy, medAz, iqrAz, medP, iqrP)
    }

    @Suppress("unused")
    private fun fmt4(x: Double) = String.Companion.format(Locale.US, "%.4f", x)
}
