package com.example.flightcue.domain.detector

import android.util.Log
import com.example.flightcue.data.modelspec.FeatureSchema
import com.example.flightcue.data.modelspec.ModelProfile
import com.example.flightcue.domain.events.FlightDomainEvent
import com.example.flightcue.domain.events.FlightEventPublisher
import com.example.flightcue.domain.events.FlightState
import com.example.flightcue.domain.events.FlowPublisher
import com.example.flightcue.domain.features.Features
import com.example.flightcue.domain.features.Scaler
import com.example.flightcue.domain.predict.Predictor
import com.example.flightcue.domain.state.EdgeTrigger
import com.example.flightcue.domain.state.FlightStateMachine
import com.example.flightcue.domain.timeseries.AccelBuffer
import com.example.flightcue.domain.timeseries.AccelResampled
import com.example.flightcue.domain.timeseries.BaroBuffer
import com.example.flightcue.domain.timeseries.BaroResampled
import com.example.flightcue.domain.timeseries.GridSpec
import com.example.flightcue.domain.timeseries.Resample
import com.example.flightcue.domain.timeseries.UnionGridScheduler
import com.example.flightcue.domain.timeseries.WindowRequest
import com.example.flightcue.domain.util.FeatureMath
import com.example.flightcue.domain.util.Params
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.ln
import kotlin.math.round

/**
 * FlightDetector is the domain-layer core for on-device flight event detection.
 *
 * Pipeline:
 *   sensors → buffers → resample → derived streams → UNION-GRID window scheduling
 *   → TimedSeqRing(seqLen) → Predictor(ONNX) → FlightStateMachine(two-phase gating)
 *
 * Phase gating:
 *   - NotFlying: run TAKEOFF inference only
 *   - Flying:    run LANDING inference only
 *   - After an event: clear + re-anchor the next phase so it must build a full seqLen again
 *
 * Time semantics (IMPORTANT):
 *   - Windows are right-anchored: t_anchor = t_end (parity with Python)
 *   - labelTimeSec = ring.tAnchorAt(labelIdx)
 *       Used for parity/debug and for monotonic "new label" gating.
 *   - decisionTimeSec = nowSec
 *       Used for FSM timestamps, notifications, cooldown/minSep, and phase re-arming.
 *
 * The key change vs earlier versions:
 *   Re-arming (setOrigin + ring clear) uses decisionTimeSec (nowSec),
 *   NOT labelTimeSec, to avoid backfilling the next phase from a past origin.
 *
 * Performance notes (replay):
 *   - sliceAccel / sliceBaro use binary search (O(log n)) instead of linear scan (O(n)).
 *   - inferenceSkipFactor is tracked via class-level counters (toInferenceCount /
 *     ldInferenceCount) so skipping is global across tick() calls, not per-tick.
 */
class FlightDetector(
    private val toSchema: FeatureSchema,
    private val ldSchema: FeatureSchema,
    private val toScalerMean: DoubleArray,
    private val toScalerScale: DoubleArray,
    private val ldScalerMean: DoubleArray,
    private val ldScalerScale: DoubleArray,
    private val toProfile: ModelProfile,
    private val ldProfile: ModelProfile,
    private val predictor: Predictor,
    debug: Boolean = true,
    eventPublisher: FlightEventPublisher = FlowPublisher(),
    private val overrides: Overrides? = null
) {
    private companion object {
        private const val TAG = "FlightDetectorCore"

        private const val COVERAGE_THR = 0.80
        private const val DEFAULT_MIN_RUN = 1
        private const val DEFAULT_MAX_HOPS_PER_TICK = 200
        private const val WIN_LOG_EVERY = 50
        private const val P0_MIN_SAMPLES = 5

        // Shared event grids (SAME for both models)
        private const val WIN_TO_EVENT_S = 20.0
        private const val HOP_TO_EVENT_S = 10.0
        private const val WIN_LD_EVENT_S = 24.0
        private const val HOP_LD_EVENT_S = 12.0

        /** Extra history needed by rolling/EMA-based features to stabilize (cheap + safe). */
        private fun extraLookbackSec(): Double {
            val rollNeed = 30.0
            val emaNeed = maxOf(
                5.0 * Params.GRAV_TAU_S,
                5.0 * Params.DYN_TAU_S,
                5.0 * Params.DHDT_TAU_S
            )
            return maxOf(rollNeed, emaNeed) + 2.0
        }

        /** Format helpers used in logs. */
        private fun fmt2(x: Double): String = String.format(Locale.US, "%.2f", x)
        private fun fmt1(x: Double): String = String.format(Locale.US, "%.1f", x)
    }

    enum class EdgeMode { EMA, TWO_OF_THREE }

    data class Overrides(
        val edgeMode: EdgeMode? = null,
        val emaAlpha: Double? = null,
        val minSepSec: Int? = null,
        val strictHopParity: Boolean? = null,
        val maxHopsPerTick: Int? = null,
        val windowLogsEnabled: Boolean = false,
        val windowLogsFirstN: Int = 30,
        val windowLogsEveryN: Int = 50,
        /** Only run ONNX inference every N full-ring windows. 1 = every window (default, live mode).
         *  Higher values speed up replay at the cost of up to (N * hopSec) timestamp imprecision. */
        val inferenceSkipFactor: Int = 1
    )

    /**
     * Returned when an event fires.
     *
     * atSec      = decision time (nowSec) i.e., when user gets the event
     * labelAtSec = model-aligned label time (ring.tAnchorAt(labelIdx)) for parity/debug
     */
    data class Decision(
        val event: String,
        val p: Double,
        val atSec: Double,
        val labelAtSec: Double,
        val on: Double,
        val off: Double
    )

    private val debugEnabled = debug

    /** Raw sample buffers. */
    private val accel = AccelBuffer()
    private val baro = BaroBuffer()

    // Debug counters
    private var toWinTotal = 0
    private var toWinFallback = 0
    private var ldWinTotal = 0
    private var ldWinFallback = 0
    private var toSeqFullLogged = false
    private var ldSeqFullLogged = false

    // Windowing trace counters (for log throttling + ring fill)
    private var toWinTraceCount = 0
    private var ldWinTraceCount = 0
    private var toRingFill = 0
    private var ldRingFill = 0

    /**
     * Class-level inference counters for inferenceSkipFactor.
     * Must be class-level (not local to runStream) so skipping is global across tick() calls.
     * Reset in armTakeoffFrom / armLandingFrom / reset().
     */
    private var toInferenceCount = 0
    private var ldInferenceCount = 0

    /**
     * Per-grid last-end tracking for dt_prev_end_s (matches Python groupby file_id+grid → diff(t_end)).
     * Separate maps per phase so arming one phase doesn't pollute the other.
     */
    private val toLastEndByGrid = mutableMapOf<String, Double>()
    private val ldLastEndByGrid = mutableMapOf<String, Double>()

    /** Optional flow publisher for UI / dev screens. */
    private val flowPub: FlowPublisher? = eventPublisher as? FlowPublisher
    val stateFlow: StateFlow<FlightState>? get() = flowPub?.state
    val events: SharedFlow<FlightDomainEvent>? get() = flowPub?.events

    private val fsm: FlightStateMachine

    /** Sliding sequence rings (features + timing columns). */
    private val toRing = TimedSeqRing(toProfile.seqLen, toSchema.names.size)
    private val ldRing = TimedSeqRing(ldProfile.seqLen, ldSchema.names.size)

    /** Shared event grids. */
    private val gridToEvent = GridSpec(id = "TO_EVENT", winSec = WIN_TO_EVENT_S, hopSec = HOP_TO_EVENT_S)
    private val gridLdEvent = GridSpec(id = "LD_EVENT", winSec = WIN_LD_EVENT_S, hopSec = HOP_LD_EVENT_S)

    /**
     * Union schedulers per model:
     * - BASE grid differs per model (profile hop/win)
     * - EVENT grids are shared/fixed
     */
    private val toSched = UnionGridScheduler(
        listOf(
            GridSpec(id = "BASE", winSec = toProfile.winLenSec, hopSec = toProfile.hopSec),
            gridToEvent,
            gridLdEvent
        )
    )
    private val ldSched = UnionGridScheduler(
        listOf(
            GridSpec(id = "BASE", winSec = ldProfile.winLenSec, hopSec = ldProfile.hopSec),
            gridToEvent,
            gridLdEvent
        )
    )

    /** Last label time processed (prevents duplicates when multiple windows are polled per tick). */
    private var lastToLabelTime: Double = Double.NEGATIVE_INFINITY
    private var lastLdLabelTime: Double = Double.NEGATIVE_INFINITY

    /** Safety cap on how many scheduled windows we process per tick(). */
    private val maxHopsPerTick: Int =
        (overrides?.maxHopsPerTick ?: DEFAULT_MAX_HOPS_PER_TICK).coerceAtLeast(1)

    /** Stream origin is initialized lazily to the first tick() time after reset. */
    private var streamOriginSec: Double? = null

    // Baro p0 baseline
    private var baroP0StartT: Double? = null
    private var baroP0Frozen: Double? = null
    private val baroP0Samples = ArrayList<Double>(256)

    init {
        if (toProfile.nFeatures > 0 && toProfile.nFeatures != toSchema.names.size) {
            throw IllegalStateException("TAKEOFF: profile.nFeatures=${toProfile.nFeatures} != schema.names.size=${toSchema.names.size}")
        }
        if (ldProfile.nFeatures > 0 && ldProfile.nFeatures != ldSchema.names.size) {
            throw IllegalStateException("LANDING: profile.nFeatures=${ldProfile.nFeatures} != schema.names.size=${ldSchema.names.size}")
        }

        require(toScalerMean.size == toSchema.names.size && toScalerScale.size == toSchema.names.size) {
            "TAKEOFF: scaler size mismatch mean=${toScalerMean.size} scale=${toScalerScale.size} names=${toSchema.names.size}"
        }
        require(ldScalerMean.size == ldSchema.names.size && ldScalerScale.size == ldSchema.names.size) {
            "LANDING: scaler size mismatch mean=${ldScalerMean.size} scale=${ldScalerScale.size} names=${ldSchema.names.size}"
        }

        require(toProfile.hopSec.isFinite() && toProfile.hopSec > 0.0) { "TAKEOFF hopSec invalid: ${toProfile.hopSec}" }
        require(toProfile.winLenSec.isFinite() && toProfile.winLenSec > 0.0) { "TAKEOFF winLenSec invalid: ${toProfile.winLenSec}" }
        require(ldProfile.hopSec.isFinite() && ldProfile.hopSec > 0.0) { "LANDING hopSec invalid: ${ldProfile.hopSec}" }
        require(ldProfile.winLenSec.isFinite() && ldProfile.winLenSec > 0.0) { "LANDING winLenSec invalid: ${ldProfile.winLenSec}" }

        val (toOn, toOff) = toProfile.thresholds()
        val (ldOn, ldOff) = ldProfile.thresholds()

        val toMinRun = maxOf(toProfile.triggerK, if (toProfile.minRun > 0) toProfile.minRun else DEFAULT_MIN_RUN)
        val ldMinRun = maxOf(ldProfile.triggerK, if (ldProfile.minRun > 0) ldProfile.minRun else DEFAULT_MIN_RUN)

        val toCooldownSec = Params.COOLDOWN_SEC
        val ldCooldownSec = Params.COOLDOWN_SEC

        var minSepSec = Params.MIN_SEP_SEC
        overrides?.minSepSec?.let { if (it >= 0) minSepSec = it.toDouble() }

        val profToAlpha = toProfile.smoothing.takeIf { it > 0 }?.let { 2.0 / (it + 1.0) }
        val profLdAlpha = ldProfile.smoothing.takeIf { it > 0 }?.let { 2.0 / (it + 1.0) }
        val alpha = overrides?.emaAlpha

        val toTrigger = EdgeTrigger(toOn, toOff, toMinRun, toCooldownSec, startArmed = true, emaAlpha = alpha ?: profToAlpha)
        val ldTrigger = EdgeTrigger(ldOn, ldOff, ldMinRun, ldCooldownSec, startArmed = true, emaAlpha = alpha ?: profLdAlpha)

        fsm = FlightStateMachine(toTrigger, ldTrigger, minSepSec, eventPublisher)

        if (debugEnabled) {
            Log.i(
                TAG,
                "Profiles: TAKEOFF(seq=${toProfile.seqLen}, labelIdx=${toProfile.labelIdx}, BASE hop=${toProfile.hopSec}, BASE win=${toProfile.winLenSec}); " +
                        "LANDING(seq=${ldProfile.seqLen}, labelIdx=${ldProfile.labelIdx}, BASE hop=${ldProfile.hopSec}, BASE win=${ldProfile.winLenSec})"
            )
            Log.i(TAG, "Event grids: TO_EVENT(win=$WIN_TO_EVENT_S hop=$HOP_TO_EVENT_S), LD_EVENT(win=$WIN_LD_EVENT_S hop=$HOP_LD_EVENT_S)")
        }
    }

    /** Ingest one accelerometer sample (seconds since boot). */
    fun onAccel(tsSec: Double, x: Double, y: Double, z: Double) = accel.push(tsSec, x, y, z)

    /**
     * Ingest one barometer sample (hPa) and build a stable p0 baseline early in the session.
     * p0 is frozen once we have enough samples beyond a time horizon.
     */
    fun onBaro(tsSec: Double, pHpa: Double) {
        baro.push(tsSec, pHpa)

        if (!pHpa.isFinite()) return
        if (baroP0Frozen != null) return

        val t0 = baroP0StartT
        if (t0 == null) {
            baroP0StartT = tsSec
            baroP0Samples.add(pHpa)
            return
        }

        if (tsSec <= t0 + Params.P0_HORIZON_S) {
            baroP0Samples.add(pHpa)
            return
        }

        baroP0Frozen = if (baroP0Samples.size >= P0_MIN_SAMPLES) {
            val arr = DoubleArray(baroP0Samples.size) { i -> baroP0Samples[i] }
            FeatureMath.median(arr)
        } else {
            pHpa
        }
        baroP0Samples.clear()
    }

    /** Compute how much history this (profile + union scheduler) could need to produce a full sequence. */
    private fun requiredLookbackFor(profile: ModelProfile, sched: UnionGridScheduler): Double {
        val seqSpanUpper = (profile.seqLen - 1).coerceAtLeast(0) * sched.minHopSec
        return sched.maxWinSec + seqSpanUpper
    }

    /** Compute a safe lookback that works for both phases + feature stabilization. */
    private fun requiredLookbackAll(): Double {
        val needTo = requiredLookbackFor(toProfile, toSched)
        val needLd = requiredLookbackFor(ldProfile, ldSched)
        return maxOf(needTo, needLd) + extraLookbackSec()
    }

    /** Return a usable p0 baseline if frozen or sufficiently sampled, else null. */
    private fun currentFixedP0OrNull(): Double? {
        baroP0Frozen?.let { return it }
        if (baroP0Samples.size >= P0_MIN_SAMPLES) {
            val arr = DoubleArray(baroP0Samples.size) { i -> baroP0Samples[i] }
            return FeatureMath.median(arr)
        }
        return null
    }

    /** Whether window scheduling logs are enabled (dev-only). */
    private fun windowLogsEnabled(): Boolean =
        debugEnabled && (overrides?.windowLogsEnabled ?: false)

    /** Throttle window logs: log first N and then every N. */
    private fun shouldWindowLog(eventUpper: String): Boolean {
        if (!windowLogsEnabled()) return false

        val firstN = (overrides?.windowLogsFirstN ?: 0).coerceAtLeast(0)
        val every = (overrides?.windowLogsEveryN ?: 1).coerceAtLeast(1)

        val c = if (eventUpper == "TAKEOFF") ++toWinTraceCount else ++ldWinTraceCount
        return (c <= firstN) || (c % every == 0)
    }

    /**
     * Initialize origins once after reset.
     * We intentionally delay origin init until we get a real nowSec from tick().
     */
    private fun ensureOrigins(nowSec: Double) {
        if (streamOriginSec != null) return
        streamOriginSec = nowSec
        toSched.setOrigin(nowSec)
        ldSched.setOrigin(nowSec)
        if (debugEnabled) Log.i(TAG, "Window origins initialized at originSec=${fmt2(nowSec)}")
    }

    /**
     * Arm LANDING from decision time:
     * - clears landing ring so fill restarts at 0/seqLen
     * - sets landing scheduler origin to decisionTimeSec so it starts "from now", not backfilled
     * - clears per-grid dt tracking so first landing window gets dtPrevEndSec = 0
     * - resets landing inference counter so skip factor counts from 0
     */
    private fun armLandingFrom(decisionTimeSec: Double) {
        ldRing.clear()
        ldRingFill = 0
        ldSeqFullLogged = false
        lastLdLabelTime = Double.NEGATIVE_INFINITY
        ldWinTraceCount = 0
        ldWinTotal = 0
        ldWinFallback = 0
        ldLastEndByGrid.clear()
        ldInferenceCount = 0
        ldSched.setOrigin(decisionTimeSec)
        if (debugEnabled) Log.i(TAG, "Armed LANDING pipeline from originSec=${fmt2(decisionTimeSec)}")
    }

    /**
     * Arm TAKEOFF from decision time:
     * - clears takeoff ring so fill restarts at 0/seqLen
     * - sets takeoff scheduler origin to decisionTimeSec so it starts "from now", not backfilled
     * - clears per-grid dt tracking so first takeoff window gets dtPrevEndSec = 0
     * - resets takeoff inference counter so skip factor counts from 0
     */
    private fun armTakeoffFrom(decisionTimeSec: Double) {
        toRing.clear()
        toRingFill = 0
        toSeqFullLogged = false
        lastToLabelTime = Double.NEGATIVE_INFINITY
        toWinTraceCount = 0
        toWinTotal = 0
        toWinFallback = 0
        toLastEndByGrid.clear()
        toInferenceCount = 0
        toSched.setOrigin(decisionTimeSec)
        if (debugEnabled) Log.i(TAG, "Armed TAKEOFF pipeline from originSec=${fmt2(decisionTimeSec)}")
    }

    /**
     * Main driver:
     * - builds derived streams using a safe lookback
     * - runs exactly ONE phase depending on FSM state
     * - returns Decision when an event is emitted
     */
    fun tick(nowSec: Double): Decision? {
        ensureOrigins(nowSec)

        val lookbackSec = requiredLookbackAll()
        val guard = maxOf(1.0 / Params.ACCEL_HZ, 1.0 / Params.BARO_HZ)
        val minTs = nowSec - lookbackSec - guard

        val aSlice = accel.snapshotSince(minTs)
        val bSlice = baro.snapshotSince(minTs)

        val aR: AccelResampled = Resample.accel(aSlice, Params.ACCEL_HZ, Params.BIG_GAP_FACTOR)
        val bR: BaroResampled = Resample.baro(bSlice, Params.BARO_HZ, Params.BIG_GAP_FACTOR)

        val aD = if (aR.t.isNotEmpty()) Features.deriveAccel(aR, Params.ACCEL_HZ) else null
        val bD = if (bR.t.isNotEmpty()) Features.deriveBaro(bR, Params.BARO_HZ) else null

        return when (fsm.state) {
            FlightState.NotFlying -> {
                runStream(
                    nowSec = nowSec,
                    profile = toProfile,
                    schemaNames = toSchema.names,
                    ring = toRing,
                    sched = toSched,
                    lastEndByGrid = toLastEndByGrid,
                    getRingFill = { toRingFill },
                    setRingFill = { toRingFill = it },
                    lastLabelTimeRef = { lastToLabelTime },
                    setLastLabelTime = { lastToLabelTime = it },
                    getInferenceCount = { toInferenceCount },
                    setInferenceCount = { toInferenceCount = it },
                    score = { seq -> predictor.scoreTakeoff(seq) },
                    onFire = { p, labelTimeSec, decisionTimeSec ->
                        val (on, off) = toProfile.thresholds()
                        val ev = fsm.onTakeoffScore(p, decisionTimeSec)
                        if (ev != null) {
                            armLandingFrom(decisionTimeSec)
                            Decision("TAKEOFF", p, decisionTimeSec, labelTimeSec, on, off)
                        } else null
                    },
                    aD = aD,
                    bD = bD
                )
            }

            FlightState.Flying -> {
                runStream(
                    nowSec = nowSec,
                    profile = ldProfile,
                    schemaNames = ldSchema.names,
                    ring = ldRing,
                    sched = ldSched,
                    lastEndByGrid = ldLastEndByGrid,
                    getRingFill = { ldRingFill },
                    setRingFill = { ldRingFill = it },
                    lastLabelTimeRef = { lastLdLabelTime },
                    setLastLabelTime = { lastLdLabelTime = it },
                    getInferenceCount = { ldInferenceCount },
                    setInferenceCount = { ldInferenceCount = it },
                    score = { seq -> predictor.scoreLanding(seq) },
                    onFire = { p, labelTimeSec, decisionTimeSec ->
                        val (on, off) = ldProfile.thresholds()
                        val ev = fsm.onLandingScore(p, decisionTimeSec)
                        if (ev != null) {
                            armTakeoffFrom(decisionTimeSec)
                            Decision("LANDING", p, decisionTimeSec, labelTimeSec, on, off)
                        } else null
                    },
                    aD = aD,
                    bD = bD
                )
            }
        }
    }

    /**
     * Run a single phase stream (TAKEOFF or LANDING):
     * - poll union scheduler windows up to maxHopsPerTick
     * - compute feature vectors
     * - push into ring
     * - when ring is full, run model every inferenceSkipFactor windows and evaluate trigger
     */
    private fun runStream(
        nowSec: Double,
        profile: ModelProfile,
        schemaNames: List<String>,
        ring: TimedSeqRing,
        sched: UnionGridScheduler,
        lastEndByGrid: MutableMap<String, Double>,
        getRingFill: () -> Int,
        setRingFill: (Int) -> Unit,
        lastLabelTimeRef: () -> Double,
        setLastLabelTime: (Double) -> Unit,
        getInferenceCount: () -> Int,
        setInferenceCount: (Int) -> Unit,
        score: (DoubleArray) -> Double,
        onFire: (p: Double, labelTimeSec: Double, decisionTimeSec: Double) -> Decision?,
        aD: Features.AccelDerived?,
        bD: Features.BaroDerived?
    ): Decision? {
        val ev = profile.event.trim().uppercase(Locale.US)

        val maxLagSec = requiredLookbackFor(profile, sched) + extraLookbackSec()
        val didFF = sched.fastForwardIfLagging(nowSec, maxLagSec)
        if (didFF) {
            if (debugEnabled) {
                Log.w(TAG, "WIN[$ev] fast-forwarded union scheduler (lag > ${fmt2(maxLagSec)}s); clearing ring.")
            }
            ring.clear()
            setRingFill(0)
            setLastLabelTime(Double.NEGATIVE_INFINITY)
            //setInferenceCount(0)
        }

        val skipFactor = (overrides?.inferenceSkipFactor ?: 1).coerceAtLeast(1)

        var processed = 0
        while (processed < maxHopsPerTick) {
            val req: WindowRequest = sched.pollNext(nowSec) ?: break

            val endT   = req.endSec
            val winSec = req.winSec
            val hopSec = req.hopSec

            // Numeric grid_id: BASE=0, TO_EVENT=1, LD_EVENT=2 (matches Python grid_id values)
            val gridId = gridStringToId(req.gridId)

            // dt_prev_end_s: time since last window end on this grid (matches Python groupby file_id+grid → diff(t_end))
            // First window on a grid gets 0.0 (matches Python's fillna(0.0) on the first row)
            val prevEnd = lastEndByGrid[req.gridId]
            val dtPrevEndSec = if (prevEnd != null) (endT - prevEnd).coerceAtLeast(0.0) else 0.0
            lastEndByGrid[req.gridId] = endT

            val xWin = windowVector(
                event = ev,
                endSec = endT,
                winSec = winSec,
                hopSec = hopSec,
                gridId = gridId,
                dtPrevEndSec = dtPrevEndSec,
                aD = aD,
                bD = bD,
                names = schemaNames,
            )

            // Right-anchored parity: anchor == end
            ring.push(xWin, tEndSec = endT, tAnchorSec = endT)
            setRingFill(minOf(profile.seqLen, getRingFill() + 1))

            if (windowLogsEnabled() && shouldWindowLog(ev)) {
                val fill = getRingFill()
                val labelT = if (ring.isFull()) ring.tAnchorAt(profile.labelIdx) else Double.NaN
                Log.i(
                    TAG,
                    "WIN[$ev] grid=${req.gridId} end=${fmt2(endT)} win=${fmt1(winSec)} hop=${fmt1(hopSec)} " +
                            "anchor=${fmt2(endT)} fill=$fill/${profile.seqLen} full=${ring.isFull()} " +
                            "labelIdx=${profile.labelIdx} labelT=${if (labelT.isFinite()) fmt2(labelT) else "n/a"}"
                )
            }

            if (ring.isFull()) {
                if (ev == "TAKEOFF" && !toSeqFullLogged) {
                    toSeqFullLogged = true
                    if (debugEnabled) Log.i(TAG, "TAKEOFF sequence FULL (seqLen=${profile.seqLen})")
                }
                if (ev == "LANDING" && !ldSeqFullLogged) {
                    ldSeqFullLogged = true
                    if (debugEnabled) Log.i(TAG, "LANDING sequence FULL (seqLen=${profile.seqLen})")
                }

                // Increment class-level counter and gate inference on skipFactor.
                // Counter is class-level so skipping is consistent across tick() calls.
                val count = getInferenceCount() + 1
                setInferenceCount(count)

                if (count % skipFactor == 0) {
                    val seq = ring.flattenReusable()
                    val expected = profile.seqLen * schemaNames.size
                    require(seq.size == expected) { "$ev: seq size ${seq.size} != ${profile.seqLen}*${schemaNames.size}" }

                    val p = score(seq)
                    val labelTimeSec = ring.tAnchorAt(profile.labelIdx)

                    if (labelTimeSec > lastLabelTimeRef() + 1e-9) {
                        setLastLabelTime(labelTimeSec)
                        val decisionTimeSec = nowSec
                        onFire(p, labelTimeSec, decisionTimeSec)?.let { return it }
                    }
                }
            }

            processed++
        }

        return null
    }

    /**
     * Build one window feature vector at a specific (endSec, winSec).
     * Injects all metadata columns that Python adds post-extraction:
     *   accel_coverage, baro_coverage, has_accel, has_baro, has_dyn, has_spectral,
     *   grid_id, win_s, hop_s, dt_prev_end_s, log_win_s, log_dt_prev_end_s
     * If coverage is too low, returns an all-zero vector (fallback).
     */
    private fun windowVector(
        event: String,
        endSec: Double,
        winSec: Double,
        hopSec: Double,
        gridId: Double,
        dtPrevEndSec: Double,
        aD: Features.AccelDerived?,
        bD: Features.BaroDerived?,
        names: List<String>,
    ): DoubleArray {
        // Snap to sensor grids to match offline preprocessing discretization.
        val weA = round(endSec * Params.ACCEL_HZ) / Params.ACCEL_HZ
        val weB = round(endSec * Params.BARO_HZ) / Params.BARO_HZ
        val wsA = weA - winSec
        val wsB = weB - winSec

        val aW = sliceAccel(aD, wsA, weA)
        val bW = sliceBaro(bD, wsB, weB)

        val ok = passesCoverage(aW, bW)
        bumpWindowCounters(event, usedFallback = !ok)
        if (!ok) return Scaler.zeros(names.size)

        // toMutableMap() required — windowFeatures returns an immutable Map
        val fMap = Features.windowFeatures(aW, bW, Params.ACCEL_HZ, Params.BARO_HZ, Params.DO_PSD)
            .toMutableMap()

        // Metadata injection (matches Python main() post-extraction columns)
        val accelCov = if (aW != null) tripleFiniteCoverage(aW.ax, aW.ay, aW.az) else 0.0
        val baroCov  = if (bW != null) finiteCoverage(bW.p) else 0.0

        fMap["accel_coverage"]    = accelCov
        fMap["baro_coverage"]     = baroCov
        fMap["has_accel"]         = if (aW != null && aW.ax.isNotEmpty()) 1.0 else 0.0
        fMap["has_baro"]          = if (bW != null && bW.p.isNotEmpty()) 1.0 else 0.0
        fMap["has_dyn"]           = if (aW?.dyn?.any { it.isFinite() } == true) 1.0 else 0.0
        fMap["has_spectral"]      = if (Params.DO_PSD && aW != null && aW.ax.size >= 16) 1.0 else 0.0
        fMap["grid_id"]           = gridId
        fMap["win_s"]             = winSec
        fMap["hop_s"]             = hopSec
        fMap["dt_prev_end_s"]     = dtPrevEndSec
        // Python: np.log1p(x) = ln(1 + x); coerceAtLeast(0) guards against floating point negatives
        fMap["log_win_s"]         = ln(1.0 + winSec.coerceAtLeast(0.0))
        fMap["log_dt_prev_end_s"] = ln(1.0 + dtPrevEndSec.coerceAtLeast(0.0))

        return alignRaw(fMap, names)
    }

    // -------------------------------------------------------------------------
    // Coverage helpers
    // -------------------------------------------------------------------------

    /**
     * Fraction of positions where ALL THREE axes are finite.
     * Matches Python's obs_mask_accel = (obs_mask_a["ax"] & obs_mask_a["ay"] & obs_mask_a["az"]).
     */
    private fun tripleFiniteCoverage(a: DoubleArray, b: DoubleArray, c: DoubleArray): Double {
        val n = minOf(a.size, b.size, c.size)
        if (n == 0) return 0.0
        var count = 0
        for (i in 0 until n) {
            if (a[i].isFinite() && b[i].isFinite() && c[i].isFinite()) count++
        }
        return count.toDouble() / n.toDouble()
    }

    /**
     * Fraction of finite samples in a single array.
     * Matches Python's coverage_from_obs_mask for single-channel sensors (baro).
     */
    private fun finiteCoverage(arr: DoubleArray): Double {
        if (arr.isEmpty()) return 0.0
        var n = 0
        for (v in arr) if (v.isFinite()) n++
        return n.toDouble() / arr.size.toDouble()
    }

    /**
     * Map grid string ID → numeric ID matching Python's grid_id column:
     *   base=0, to=1 (TO_EVENT), ld=2 (LD_EVENT)
     */
    private fun gridStringToId(gridId: String): Double = when (gridId) {
        "BASE"     -> 0.0
        "TO_EVENT" -> 1.0
        "LD_EVENT" -> 2.0
        else       -> 0.0
    }

    // -------------------------------------------------------------------------
    // Window helpers
    // -------------------------------------------------------------------------

    /** Update fallback counters and emit a throttled fallback summary log. */
    private fun bumpWindowCounters(event: String, usedFallback: Boolean) {
        if (!debugEnabled) return
        when (event) {
            "TAKEOFF" -> {
                toWinTotal++
                if (usedFallback) toWinFallback++
                if (toWinTotal % WIN_LOG_EVERY == 0) {
                    val pct = 100.0 * toWinFallback.toDouble() / toWinTotal.toDouble()
                    Log.i(TAG, "TAKEOFF windows: total=$toWinTotal fallback=$toWinFallback (${String.format(Locale.US, "%.1f", pct)}%)")
                }
            }
            "LANDING" -> {
                ldWinTotal++
                if (usedFallback) ldWinFallback++
                if (ldWinTotal % WIN_LOG_EVERY == 0) {
                    val pct = 100.0 * ldWinFallback.toDouble() / ldWinTotal.toDouble()
                    Log.i(TAG, "LANDING windows: total=$ldWinTotal fallback=$ldWinFallback (${String.format(Locale.US, "%.1f", pct)}%)")
                }
            }
        }
    }

    /**
     * Coverage gate: matches Python's combined obs_mask_accel check.
     * Accel: fraction of bins where ALL THREE axes are simultaneously finite.
     * Baro:  fraction of finite pressure samples.
     * Both must reach COVERAGE_THR independently.
     */
    private fun passesCoverage(a: Features.AccelDerived?, b: Features.BaroDerived?): Boolean {
        var ok = true
        if (a != null && a.ax.isNotEmpty()) {
            ok = ok && tripleFiniteCoverage(a.ax, a.ay, a.az) >= COVERAGE_THR
        }
        if (b != null && b.p.isNotEmpty()) {
            ok = ok && finiteCoverage(b.p) >= COVERAGE_THR
        }
        return ok
    }

    /**
     * Slice accel-derived arrays for [ws, we] with inclusive right endpoint (<= we).
     * Uses binary search (O(log n)) instead of linear scan (O(n)) for replay performance.
     * Returns null if the slice is empty.
     */
    private fun sliceAccel(ad: Features.AccelDerived?, ws: Double, we: Double): Features.AccelDerived? {
        if (ad == null || ad.t.isEmpty()) return null

        val eps = 1e-9

        // Find first index where t >= ws - eps
        var i0 = java.util.Arrays.binarySearch(ad.t, ws - eps).let {
            if (it >= 0) it else -(it + 1)
        }
        // Find last index where t <= we + eps
        var i1 = java.util.Arrays.binarySearch(ad.t, we + eps).let {
            if (it >= 0) it else -(it + 1) - 1
        }

        if (i0 > i1 || i0 >= ad.t.size || i1 < 0) return null
        i1 = i1.coerceAtMost(ad.t.size - 1)

        fun cut(x: DoubleArray) = x.sliceArray(i0..i1)

        return Features.AccelDerived(
            cut(ad.t),
            cut(ad.ax), cut(ad.ay), cut(ad.az),
            cut(ad.amag),
            cut(ad.amagEma10), cut(ad.amagEmaVar10),
            cut(ad.avert), cut(ad.ahoriz),
            cut(ad.dyn)
        )
    }

    /**
     * Slice baro-derived arrays for [ws, we] with inclusive right endpoint (<= we).
     * Uses binary search (O(log n)) instead of linear scan (O(n)) for replay performance.
     * Returns null if the slice is empty.
     */
    private fun sliceBaro(bd: Features.BaroDerived?, ws: Double, we: Double): Features.BaroDerived? {
        if (bd == null || bd.t.isEmpty()) return null

        val eps = 1e-9

        // Find first index where t >= ws - eps
        var i0 = java.util.Arrays.binarySearch(bd.t, ws - eps).let {
            if (it >= 0) it else -(it + 1)
        }
        // Find last index where t <= we + eps
        var i1 = java.util.Arrays.binarySearch(bd.t, we + eps).let {
            if (it >= 0) it else -(it + 1) - 1
        }

        if (i0 > i1 || i0 >= bd.t.size || i1 < 0) return null
        i1 = i1.coerceAtMost(bd.t.size - 1)

        fun cut(x: DoubleArray) = x.sliceArray(i0..i1)

        return Features.BaroDerived(
            cut(bd.t),
            cut(bd.p),
            cut(bd.dhdt),
            cut(bd.pEma30), cut(bd.pEma30dt1)
        )
    }

    /** Align a Map<String, Double> into a dense feature vector ordered by schema names. */
    private fun alignRaw(feats: Map<String, Double>, names: List<String>): DoubleArray =
        DoubleArray(names.size) { i -> feats[names[i]]?.takeIf { it.isFinite() } ?: Double.NaN }

    /**
     * Reset detector to a clean NotFlying state:
     * - clears rings and schedulers
     * - resets timing/counters
     * - re-initializes origins on next tick()
     */
    fun reset(nowSec: Double) {
        fsm.resetToNotFlying(nowSec)

        toRing.clear()
        ldRing.clear()

        streamOriginSec = null
        toSched.reset()
        ldSched.reset()

        lastToLabelTime = Double.NEGATIVE_INFINITY
        lastLdLabelTime = Double.NEGATIVE_INFINITY

        toWinTotal = 0; toWinFallback = 0
        ldWinTotal = 0; ldWinFallback = 0
        toSeqFullLogged = false
        ldSeqFullLogged = false

        toWinTraceCount = 0
        ldWinTraceCount = 0
        toRingFill = 0
        ldRingFill = 0

        toInferenceCount = 0
        ldInferenceCount = 0

        toLastEndByGrid.clear()
        ldLastEndByGrid.clear()

        baroP0StartT = null
        baroP0Frozen = null
        baroP0Samples.clear()

        if (debugEnabled) Log.i(TAG, "reset(nowSec=$nowSec) done")
    }
}