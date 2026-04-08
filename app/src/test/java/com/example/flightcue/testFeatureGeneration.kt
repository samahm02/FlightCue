package com.example.flightcue

import com.example.flightcue.domain.features.Features
import com.example.flightcue.domain.timeseries.AccelResampled
import com.example.flightcue.domain.timeseries.BaroResampled
import com.example.flightcue.domain.util.Params
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.cos

class FeatureGenerationTest {

    // -------------------------------------------------------------------------
    // Required feature list — must match features.json exactly
    // -------------------------------------------------------------------------
    private val required = listOf(
        "accel_coverage", "ahoriz_mean", "ahoriz_peakcount", "ahoriz_recent_slope_2s",
        "ahoriz_recent_std_2s", "ahoriz_rms", "ahoriz_std", "amag_dominant_freq",
        "amag_ema10_mean", "amag_ema10_range", "amag_ema10_std", "amag_ema10_thirddiff",
        "amag_emaVar10_iqr", "amag_emaVar10_mean", "amag_halfdiff", "amag_iqr",
        "amag_kurt", "amag_max", "amag_mean", "amag_median", "amag_min", "amag_peakcount",
        "amag_range", "amag_ratio_1_3__0.3_1", "amag_ratio_recent_std", "amag_recent_diff_25pct",
        "amag_recent_max_abs_2s", "amag_recent_mean_2s", "amag_recent_slope_2s",
        "amag_recent_slope_3s", "amag_recent_std_2s", "amag_rms", "amag_skew",
        "amag_spectral_bandwidth", "amag_spectral_centroid", "amag_std", "amag_thirddiff",
        "avert_mean", "avert_peakcount", "avert_recent_slope_2s", "avert_recent_std_2s",
        "avert_rms", "avert_std", "avert_to_ahoriz_ratio",
        "ax_iqr", "ax_kurt", "ax_max", "ax_mean", "ax_median", "ax_min",
        "ax_range", "ax_rms", "ax_skew", "ax_std",
        "ay_iqr", "ay_kurt", "ay_max", "ay_mean", "ay_median", "ay_min",
        "ay_range", "ay_rms", "ay_skew", "ay_std",
        "az_iqr", "az_kurt", "az_max", "az_mean", "az_median", "az_min",
        "az_range", "az_rms", "az_skew", "az_std",
        "baro_coverage", "dhdt_maxabs", "dhdt_mean", "dhdt_recent_max_abs_2s",
        "dhdt_recent_mean_2s", "dhdt_recent_mean_3s", "dhdt_recent_slope_3s",
        "dhdt_recent_std_3s", "dhdt_std", "dhdt_zcr_ps", "dpdt_mean", "dpdt_std",
        "dt_prev_end_s", "dyn_dominant_freq", "dyn_halfdiff", "dyn_kurt", "dyn_max",
        "dyn_mean", "dyn_peakcount", "dyn_pow_0.0_0.5", "dyn_pow_0.5_2.0",
        "dyn_pow_10.0_20.0", "dyn_pow_2.0_5.0", "dyn_pow_5.0_10.0", "dyn_range",
        "dyn_ratio_2_5__0.5_2", "dyn_ratio_recent_std", "dyn_recent_diff_25pct",
        "dyn_recent_max_abs_2s", "dyn_recent_slope_2s", "dyn_recent_std_2s", "dyn_rms",
        "dyn_skew", "dyn_spectral_centroid", "dyn_std", "dyn_thirddiff", "grid_id",
        "ground_vs_air_ratio", "has_accel", "has_baro", "has_dyn", "has_spectral", "hop_s",
        "jerk_mean", "jerk_recent_max_abs_2s", "jerk_recent_std_2s", "jerk_rms", "jerk_std",
        "log_dt_prev_end_s", "log_win_s",
        "p_ema30_dt1_mean", "p_ema30_dt1_posfrac", "p_ema30_dt1_std",
        "p_ema30_mean", "p_ema30_slope", "p_ema30_std",
        "p_iqr", "p_kurt", "p_max", "p_mean", "p_median", "p_min", "p_range",
        "p_recent_diff_25pct", "p_recent_slope_3s", "p_recent_slope_5s", "p_recent_std_3s",
        "p_skew", "p_slope", "p_std", "plateau_frac",
        "pow_0.0_0.3", "pow_0.3_1.0", "pow_1.0_3.0", "pow_3.0_8.0", "pow_8.0_15.0",
        "runlen_climb_s", "runlen_descent_s", "vibration_vs_static_ratio", "win_s"
    )

    // -------------------------------------------------------------------------
    // Helpers — MUST mirror FlightDetector.kt exactly
    // -------------------------------------------------------------------------

    /** Mirrors FlightDetector.finiteCoverage */
    private fun finiteCoverage(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        var fin = 0
        for (v in x) if (v.isFinite()) fin++
        return fin.toDouble() / x.size.toDouble()
    }

    /** Mirrors FlightDetector.tripleFiniteCoverage */
    private fun tripleFiniteCoverage(ax: DoubleArray, ay: DoubleArray, az: DoubleArray): Double {
        val n = minOf(ax.size, ay.size, az.size)
        if (n == 0) return 0.0
        var fin = 0
        for (i in 0 until n) if (ax[i].isFinite() && ay[i].isFinite() && az[i].isFinite()) fin++
        return fin.toDouble() / n.toDouble()
    }

    /** Mirrors FlightDetector.gridStringToId */
    private fun gridStringToId(gridId: String): Double = when (gridId) {
        "BASE"     -> 0.0
        "TO_EVENT" -> 1.0
        "LD_EVENT" -> 2.0
        else       -> 0.0
    }

    /**
     * Mirrors FlightDetector.windowVector metadata injection exactly.
     * If FlightDetector.windowVector changes, update this too.
     */
    private fun buildMerged(
        aD: Features.AccelDerived,
        bD: Features.BaroDerived,
        accelHz: Double,
        baroHz: Double,
        doPsd: Boolean,
        winSec: Double,
        hopSec: Double,
        gridIdStr: String,
        dtPrevEndSec: Double
    ): MutableMap<String, Double> {
        val fMap = Features.windowFeatures(aD, bD, accelHz, baroHz, doPsd).toMutableMap()

        fMap["accel_coverage"]    = tripleFiniteCoverage(aD.ax, aD.ay, aD.az)
        fMap["baro_coverage"]     = finiteCoverage(bD.p)
        fMap["has_accel"]         = if (aD.ax.isNotEmpty()) 1.0 else 0.0
        fMap["has_baro"]          = if (bD.p.isNotEmpty()) 1.0 else 0.0
        fMap["has_dyn"]           = if (aD.dyn.any { it.isFinite() }) 1.0 else 0.0
        fMap["has_spectral"]      = if (doPsd) 1.0 else 0.0
        fMap["grid_id"]           = gridStringToId(gridIdStr)
        fMap["win_s"]             = winSec
        fMap["hop_s"]             = hopSec
        fMap["dt_prev_end_s"]     = dtPrevEndSec
        // Must use ln(1 + x) — NOT kotlin.math.ln1p — to match FlightDetector.kt
        fMap["log_win_s"]         = ln(1.0 + winSec.coerceAtLeast(0.0))
        fMap["log_dt_prev_end_s"] = ln(1.0 + dtPrevEndSec.coerceAtLeast(0.0))

        return fMap
    }

    // -------------------------------------------------------------------------
    // Test data builders
    // -------------------------------------------------------------------------

    /** Constant accel: ax=0.1, ay=0.2, az=9.8 */
    private fun constantAccel(nSamples: Int, hz: Double) = AccelResampled(
        t  = DoubleArray(nSamples) { it * (1.0 / hz) },
        ax = DoubleArray(nSamples) { 0.1 },
        ay = DoubleArray(nSamples) { 0.2 },
        az = DoubleArray(nSamples) { 9.8 }
    )

    /** Slightly varying accel — avoids degenerate std=0 edge cases in PSD/ratio features */
    private fun varyingAccel(nSamples: Int, hz: Double) = AccelResampled(
        t  = DoubleArray(nSamples) { it * (1.0 / hz) },
        ax = DoubleArray(nSamples) { i -> 0.1 + 0.05 * sin(i * 0.3) },
        ay = DoubleArray(nSamples) { i -> 0.2 + 0.05 * cos(i * 0.3) },
        az = DoubleArray(nSamples) { i -> 9.8 + 0.05 * sin(i * 0.1) }
    )

    /** Constant pressure — no climb/descent */
    private fun constantBaro(nSamples: Int, hz: Double) = BaroResampled(
        t = DoubleArray(nSamples) { it * (1.0 / hz) },
        p = DoubleArray(nSamples) { 1013.25 }
    )

    /** Slightly trending pressure — produces finite dhdt */
    private fun trendingBaro(nSamples: Int, hz: Double) = BaroResampled(
        t = DoubleArray(nSamples) { it * (1.0 / hz) },
        p = DoubleArray(nSamples) { i -> 1013.25 - 0.05 * i }
    )

    // -------------------------------------------------------------------------
    // TEST 1: Feature contract — presence and exact count
    // -------------------------------------------------------------------------

    @Test
    fun testFeatureContractPresenceAndCount() {
        println("=== TEST 1: Feature contract (presence + count) ===")

        val dupes = required.groupBy { it }.filterValues { it.size > 1 }.keys.toList()
        assertTrue("Duplicate feature names in required list: $dupes", dupes.isEmpty())

        val hz  = Params.ACCEL_HZ
        val bHz = Params.BARO_HZ
        val nA  = (hz  * 25).toInt()
        val nB  = (bHz * 25).toInt()

        val aD = Features.deriveAccel(varyingAccel(nA, hz), hz)
        val bD = Features.deriveBaro(trendingBaro(nB, bHz), bHz)

        val merged = buildMerged(aD, bD, hz, bHz, Params.DO_PSD,
            winSec = 25.0, hopSec = 25.0, gridIdStr = "BASE", dtPrevEndSec = 0.0)

        val reqSet  = required.toSet()
        val missing = required.filter { it !in merged.keys }
        val extra   = merged.keys.filter { it !in reqSet }

        println("Required : ${required.size}")
        println("Generated: ${merged.size}")
        if (missing.isNotEmpty()) println("MISSING  : $missing")
        if (extra.isNotEmpty())   println("EXTRA    : $extra")

        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            fail("Feature contract mismatch.\nmissing(${missing.size}): ${missing.joinToString()}\nextra(${extra.size}): ${extra.joinToString()}")
        }
        assertEquals("Exact feature count", required.size, merged.size)
        println("PASS")
    }

    // -------------------------------------------------------------------------
    // TEST 2: Metadata values injected correctly
    // -------------------------------------------------------------------------

    @Test
    fun testMetadataValuesCorrect() {
        println("=== TEST 2: Metadata values ===")

        val hz  = Params.ACCEL_HZ
        val bHz = Params.BARO_HZ
        val nA  = (hz  * 25).toInt()
        val nB  = (bHz * 25).toInt()

        val aD = Features.deriveAccel(varyingAccel(nA, hz), hz)
        val bD = Features.deriveBaro(constantBaro(nB, bHz), bHz)

        val winSec       = 20.0
        val hopSec       = 10.0
        val dtPrevEndSec = 30.0
        val gridIdStr    = "TO_EVENT"

        val m = buildMerged(aD, bD, hz, bHz, Params.DO_PSD,
            winSec, hopSec, gridIdStr, dtPrevEndSec)

        // All samples finite → coverage = 1.0
        assertEquals("accel_coverage", 1.0, m["accel_coverage"]!!, 1e-9)
        assertEquals("baro_coverage",  1.0, m["baro_coverage"]!!,  1e-9)

        // Flag features
        assertEquals("has_accel",    1.0, m["has_accel"]!!, 1e-9)
        assertEquals("has_baro",     1.0, m["has_baro"]!!,  1e-9)
        assertEquals("has_dyn",      1.0, m["has_dyn"]!!,   1e-9)
        assertEquals("has_spectral", if (Params.DO_PSD) 1.0 else 0.0, m["has_spectral"]!!, 1e-9)

        // Grid / timing pass-through
        assertEquals("grid_id",       1.0,          m["grid_id"]!!,       1e-9)
        assertEquals("win_s",         winSec,       m["win_s"]!!,         1e-9)
        assertEquals("hop_s",         hopSec,       m["hop_s"]!!,         1e-9)
        assertEquals("dt_prev_end_s", dtPrevEndSec, m["dt_prev_end_s"]!!, 1e-9)

        // Log features must match Python np.log1p = ln(1 + x)
        assertEquals("log_win_s",         ln(1.0 + winSec),       m["log_win_s"]!!,         1e-12)
        assertEquals("log_dt_prev_end_s", ln(1.0 + dtPrevEndSec), m["log_dt_prev_end_s"]!!, 1e-12)

        println("PASS")
    }

    // -------------------------------------------------------------------------
    // TEST 3: Grid ID mapping covers all values
    // -------------------------------------------------------------------------

    @Test
    fun testGridIdMapping() {
        println("=== TEST 3: grid_id mapping ===")
        assertEquals("BASE",     0.0, gridStringToId("BASE"),     1e-9)
        assertEquals("TO_EVENT", 1.0, gridStringToId("TO_EVENT"), 1e-9)
        assertEquals("LD_EVENT", 2.0, gridStringToId("LD_EVENT"), 1e-9)
        assertEquals("unknown",  0.0, gridStringToId("???"),      1e-9)
        println("PASS")
    }

    // -------------------------------------------------------------------------
    // TEST 4: log_win_s and log_dt_prev_end_s match Python np.log1p for multiple values
    // -------------------------------------------------------------------------

    @Test
    fun testLogFeaturesMatchNpLog1p() {
        println("=== TEST 4: log features == Python np.log1p ===")

        val hz  = Params.ACCEL_HZ
        val bHz = Params.BARO_HZ
        val nA  = (hz  * 25).toInt()
        val nB  = (bHz * 25).toInt()
        val aD  = Features.deriveAccel(constantAccel(nA, hz), hz)
        val bD  = Features.deriveBaro(constantBaro(nB, bHz), bHz)

        data class Case(val win: Double, val hop: Double, val dt: Double)
        listOf(
            Case(25.0, 25.0,   0.0),
            Case(20.0, 10.0,  10.0),
            Case(24.0, 12.0,  12.0),
            Case(25.0, 25.0, 300.0)
        ).forEach { c ->
            val m = buildMerged(aD, bD, hz, bHz, false, c.win, c.hop, "BASE", c.dt)
            assertEquals("log_win_s win=${c.win}",  ln(1.0 + c.win), m["log_win_s"]!!,         1e-12)
            assertEquals("log_dt dt=${c.dt}",       ln(1.0 + c.dt),  m["log_dt_prev_end_s"]!!, 1e-12)
        }
        println("PASS")
    }

    // -------------------------------------------------------------------------
    // TEST 5: Sensor feature sanity — constant input produces known values
    // -------------------------------------------------------------------------

    @Test
    fun testSensorFeatureSanityConstantSignal() {
        println("=== TEST 5: Sensor feature sanity (constant signal) ===")

        val hz  = Params.ACCEL_HZ
        val bHz = Params.BARO_HZ
        val nA  = (hz  * 25).toInt()
        val nB  = (bHz * 25).toInt()

        // ax=0, ay=0, az=9.8 → amag=9.8 everywhere
        val aR = AccelResampled(
            t  = DoubleArray(nA) { it * (1.0 / hz) },
            ax = DoubleArray(nA) { 0.0 },
            ay = DoubleArray(nA) { 0.0 },
            az = DoubleArray(nA) { 9.8 }
        )
        val aD = Features.deriveAccel(aR, hz)
        val bD = Features.deriveBaro(constantBaro(nB, bHz), bHz)

        val m = buildMerged(aD, bD, hz, bHz, Params.DO_PSD,
            winSec = 25.0, hopSec = 25.0, gridIdStr = "BASE", dtPrevEndSec = 0.0)

        // amag = 9.8 everywhere
        assertTrue("amag_mean in [9.7,9.9]", m["amag_mean"]!! in 9.7..9.9)
        assertTrue("amag_std ≈ 0",           m["amag_std"]!! < 1e-6)
        assertTrue("amag_range ≈ 0",         m["amag_range"]!! < 1e-6)

        // Constant pressure
        assertTrue("p_mean ≈ 1013.25",       m["p_mean"]!! in 1012.0..1015.0)
        assertTrue("p_std ≈ 0",              m["p_std"]!! < 1e-6)

        // No altitude change
        val dhdtMean = m["dhdt_mean"]
        if (dhdtMean != null && dhdtMean.isFinite()) {
            assertTrue("dhdt_mean ≈ 0", abs(dhdtMean) < 0.1)
        }

        // No jerk from constant signal
        assertTrue("jerk_mean ≈ 0", m["jerk_mean"]!! < 1e-6)

        // Plateau everywhere (no dhdt movement)
        val pf = m["plateau_frac"]
        if (pf != null && pf.isFinite()) {
            assertTrue("plateau_frac = 1.0", pf > 0.99)
        }

        println("PASS")
    }

    // -------------------------------------------------------------------------
    // TEST 6: No unexpected NaN for a full-coverage varying window
    // -------------------------------------------------------------------------

    @Test
    fun testNoUnexpectedNanForFullCoverageWindow() {
        println("=== TEST 6: No unexpected NaN for full-coverage window ===")

        val hz  = Params.ACCEL_HZ
        val bHz = Params.BARO_HZ
        val nA  = (hz  * 25).toInt()
        val nB  = (bHz * 25).toInt()

        val aD = Features.deriveAccel(varyingAccel(nA, hz), hz)
        val bD = Features.deriveBaro(trendingBaro(nB, bHz), bHz)

        val m = buildMerged(aD, bD, hz, bHz, Params.DO_PSD,
            winSec = 25.0, hopSec = 25.0, gridIdStr = "BASE", dtPrevEndSec = 0.0)

        // These can legitimately be NaN when denominator ≈ 0
        val nanAllowed = setOf(
            "amag_ratio_recent_std",
            "dyn_ratio_recent_std",
            "avert_to_ahoriz_ratio"
        )

        val badNans = required.filter { name ->
            name !in nanAllowed &&
                    m.containsKey(name) &&
                    (m[name] == null || m[name]!!.isNaN())
        }

        if (badNans.isNotEmpty()) {
            println("---- Unexpected NaNs ----")
            badNans.forEach { println("  $it = ${m[it]}") }
            fail("${badNans.size} features unexpectedly NaN: $badNans")
        }
        println("PASS")
    }

    // -------------------------------------------------------------------------
    // TEST 7: dt_prev_end_s is 0 on first window, correct on subsequent
    // -------------------------------------------------------------------------

    @Test
    fun testDtPrevEndSecValues() {
        println("=== TEST 7: dt_prev_end_s pass-through ===")

        val hz  = Params.ACCEL_HZ
        val bHz = Params.BARO_HZ
        val nA  = (hz  * 25).toInt()
        val nB  = (bHz * 25).toInt()
        val aD  = Features.deriveAccel(constantAccel(nA, hz), hz)
        val bD  = Features.deriveBaro(constantBaro(nB, bHz), bHz)

        // First window on a fresh grid → dt = 0
        val m0 = buildMerged(aD, bD, hz, bHz, false, 25.0, 25.0, "BASE", 0.0)
        assertEquals("first dt_prev_end_s",     0.0,       m0["dt_prev_end_s"]!!,     1e-9)
        assertEquals("first log_dt = ln(1)",    ln(1.0),   m0["log_dt_prev_end_s"]!!, 1e-9)

        // Second window on same grid → dt = hop = 25 s
        val m1 = buildMerged(aD, bD, hz, bHz, false, 25.0, 25.0, "BASE", 25.0)
        assertEquals("second dt_prev_end_s",    25.0,      m1["dt_prev_end_s"]!!,     1e-9)
        assertEquals("second log_dt = ln(26)",  ln(26.0),  m1["log_dt_prev_end_s"]!!, 1e-9)

        println("PASS")
    }
}