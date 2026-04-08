package com.example.flightcue

import com.example.flightcue.domain.features.Features
import com.example.flightcue.domain.timeseries.AccelSlice
import com.example.flightcue.domain.timeseries.BaroSlice
import com.example.flightcue.domain.timeseries.GridSpec
import com.example.flightcue.domain.timeseries.Resample
import com.example.flightcue.domain.timeseries.UnionGridScheduler
import com.example.flightcue.domain.util.Params
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.round

/**
 * Full end-to-end pipeline parity test — pure JVM, no Android device required.
 *
 * Run with:
 *   ./gradlew :app:testDebugUnitTest --tests "com.example.flightcue.FeatureParityTest"
 *
 * Required files in app/src/test/resources/:
 *   parity_golden.npz
 *   parity_golden_feature_names.json
 *
 * Note: scaler.npz is NOT used here. Z-score standardisation is embedded inside
 * the ONNX model graph and is verified separately by ParityRunner at startup.
 * This test covers raw feature parity only.
 */
class FeatureParityTest {

    private val RAW_TOL   = 1e-5
    private val SCHED_TOL = 1e-4

    @Test
    fun testFullEndToEndPipeline() {
        val golden    = loadGolden()
        val featNames = loadFeatureNames()

        println("=".repeat(70))
        println("FeatureParityTest: ${golden.nWindows} golden windows × ${golden.nFeatures} features")

        assertEquals("Feature count mismatch", golden.nFeatures, featNames.size)

        // -----------------------------------------------------------------------
        // Step 1: Resample
        // -----------------------------------------------------------------------
        val aSlice = AccelSlice(golden.rawAccelT, golden.rawAccelAx, golden.rawAccelAy, golden.rawAccelAz)
        val bSlice = BaroSlice(golden.rawBaroT, golden.rawBaroP)

        val aR = Resample.accel(aSlice, Params.ACCEL_HZ, Params.BIG_GAP_FACTOR)
        val bR = Resample.baro(bSlice,  Params.BARO_HZ,  Params.BIG_GAP_FACTOR)

        println("Resampled: accel=${aR.t.size}, baro=${bR.t.size}")
        check(aR.t.isNotEmpty() || bR.t.isNotEmpty()) { "Resampling empty" }

        // -----------------------------------------------------------------------
        // Step 2: Compute obs_mask for accel (to match Python's accel_coverage)
        //
        // Python uses causal_resample_with_mask which tracks which grid points
        // were covered by real raw observations vs filled from a gap.
        // A grid point at t_grid[k] is "observed" if any raw sample falls within
        // the bin: (t_grid[k-1], t_grid[k]].
        // -----------------------------------------------------------------------
        val obsAccelMask = if (golden.rawAccelT.isNotEmpty() && aR.t.isNotEmpty())
            computeObsMask(golden.rawAccelT, aR.t, Params.ACCEL_HZ)
        else
            BooleanArray(0)

        val obsBaroMask = if (golden.rawBaroT.isNotEmpty() && bR.t.isNotEmpty())
            computeObsMask(golden.rawBaroT, bR.t, Params.BARO_HZ)
        else
            BooleanArray(0)

        // -----------------------------------------------------------------------
        // Step 3: Derive signals
        // -----------------------------------------------------------------------
        val aD = if (aR.t.isNotEmpty()) Features.deriveAccel(aR, Params.ACCEL_HZ) else null
        val bD = if (bR.t.isNotEmpty()) Features.deriveBaro(bR,  Params.BARO_HZ)  else null

        // -----------------------------------------------------------------------
        // Step 4: Run UnionGridScheduler with Python's origin
        // -----------------------------------------------------------------------
        val origin   = golden.schedulerOriginSec
        val baseWinS = golden.baseWinS
        val baseHopS = golden.baseHopS

        val grids = listOf(
            GridSpec("BASE",     winSec = baseWinS, hopSec = baseHopS),
            GridSpec("TO_EVENT", winSec = 20.0,     hopSec = 10.0),
            GridSpec("LD_EVENT", winSec = 24.0,     hopSec = 12.0),
        )
        val sched = UnionGridScheduler(grids)
        sched.setOrigin(origin)

        val tMax = golden.windowTEnd.max()!! + 1.0

        data class KotlinWindow(val gridId: Int, val tEnd: Double, val winS: Double, val hopS: Double)
        val kotWindows = mutableListOf<KotlinWindow>()

        var nowSec = origin + grids.minOf { it.winSec }
        while (nowSec <= tMax) {
            var req = sched.pollNext(nowSec)
            while (req != null) {
                kotWindows.add(KotlinWindow(gridStringToId(req.gridId), req.endSec, req.winSec, req.hopSec))
                req = sched.pollNext(nowSec)
            }
            nowSec += 1.0
        }
        println("Kotlin scheduler produced ${kotWindows.size} windows")

        // -----------------------------------------------------------------------
        // Step 5: Verify scheduling
        // -----------------------------------------------------------------------
        val schedMismatches = mutableListOf<String>()
        val matchedKotIdx   = mutableListOf<Int>()

        for (w in 0 until golden.nWindows) {
            val kIdx = kotWindows.indexOfFirst { k ->
                k.gridId == golden.windowGridId[w].toInt() &&
                        abs(k.tEnd - golden.windowTEnd[w]) <= SCHED_TOL
            }
            if (kIdx < 0) {
                schedMismatches += "Window $w: t_end=${fmt(golden.windowTEnd[w])} grid=${golden.windowGridId[w].toInt()} NOT FOUND"
            }
            matchedKotIdx.add(kIdx)
        }

        val matchedSet = matchedKotIdx.filter { it >= 0 }.toSet()
        val extraKotlin = kotWindows.indices.filter { it !in matchedSet }.take(10)
            .map { "  Kotlin extra: t_end=${fmt(kotWindows[it].tEnd)} grid=${kotWindows[it].gridId}" }

        if (schedMismatches.isNotEmpty() || extraKotlin.isNotEmpty()) {
            println("\n=== SCHEDULING MISMATCHES ===")
            schedMismatches.take(20).forEach { println("  MISSING: $it") }
            if (extraKotlin.isNotEmpty()) {
                println("Extra Kotlin windows (not in golden):")
                extraKotlin.forEach { println(it) }
            }
        }

        assertEquals(
            "Scheduling mismatch: ${schedMismatches.size} windows not found.\n${schedMismatches.take(5).joinToString("\n")}",
            0, schedMismatches.size
        )
        println("✅ Scheduling: all ${golden.nWindows} windows match")

        // -----------------------------------------------------------------------
        // Step 6: Raw feature comparison
        // -----------------------------------------------------------------------
        var totalChecks  = 0
        var failedChecks = 0

        val worstRawErr = mutableMapOf<String, Double>()
        val failingWindowDetails = StringBuilder()

        for (w in 0 until golden.nWindows) {
            val tEnd   = golden.windowTEnd[w]
            val winS   = golden.windowWinS[w]
            val hopS   = golden.windowHopS[w]
            val gridId = golden.windowGridId[w].toInt()
            val dtPrev = golden.windowDtPrev[w]

            val weA = round(tEnd * Params.ACCEL_HZ) / Params.ACCEL_HZ;  val wsA = weA - winS
            val weB = round(tEnd * Params.BARO_HZ)  / Params.BARO_HZ;   val wsB = weB - winS

            val aW = sliceAccel(aD, wsA, weA)
            val bW = sliceBaro(bD,  wsB, weB)

            val obsA_w = sliceObsMask(obsAccelMask, aR.t, wsA, weA)
            val obsB_w = sliceObsMask(obsBaroMask,  bR.t, wsB, weB)

            val accelCov = if (obsA_w.isNotEmpty()) obsA_w.count { it }.toDouble() / obsA_w.size else 0.0
            val baroCov  = if (obsB_w.isNotEmpty()) obsB_w.count { it }.toDouble() / obsB_w.size else 0.0

            val fMap = Features.windowFeatures(aW, bW, Params.ACCEL_HZ, Params.BARO_HZ, Params.DO_PSD)
                .toMutableMap()

            fMap["accel_coverage"]    = accelCov
            fMap["baro_coverage"]     = baroCov
            fMap["has_accel"]         = if (aW != null && aW.ax.isNotEmpty()) 1.0 else 0.0
            fMap["has_baro"]          = if (bW != null && bW.p.isNotEmpty()) 1.0 else 0.0
            fMap["has_dyn"]           = if (aW?.dyn?.any { it.isFinite() } == true) 1.0 else 0.0
            fMap["has_spectral"]      = if (Params.DO_PSD && aW != null && aW.ax.size >= 16) 1.0 else 0.0
            fMap["grid_id"]           = gridId.toDouble()
            fMap["win_s"]             = winS
            fMap["hop_s"]             = hopS
            fMap["dt_prev_end_s"]     = dtPrev
            fMap["log_win_s"]         = ln(1.0 + winS.coerceAtLeast(0.0))
            fMap["log_dt_prev_end_s"] = ln(1.0 + dtPrev.coerceAtLeast(0.0))

            val rawVec = DoubleArray(featNames.size) { i -> fMap[featNames[i]] ?: Double.NaN }

            var windowHadFailure = false
            val windowFailures   = StringBuilder()

            for (f in featNames.indices) {
                val name     = featNames[f]
                val expected = golden.featuresRaw[w][f]
                val actual   = rawVec[f]
                totalChecks++

                val err = featureError(expected, actual)
                if (err > RAW_TOL) {
                    failedChecks++
                    if ((worstRawErr[name] ?: 0.0) < err) worstRawErr[name] = err
                    windowHadFailure = true
                    windowFailures.append("    RAW  $name: got=${fmt(actual)} expected=${fmt(expected)} err=${fmtE(err)}\n")
                }
            }

            if (windowHadFailure) {
                val dynN      = aW?.dyn?.count { it.isFinite() } ?: 0
                val dynNTotal = aW?.dyn?.size ?: 0
                val pyDynN    = golden.windowDynN[w].toInt()
                val dynNMatch = if (pyDynN < 0) "py_dyn_n=N/A" else "py_dyn_n=$pyDynN kotlin_dyn_n=$dynN ${if (pyDynN == dynN) "✓" else "MISMATCH (diff=${dynN - pyDynN})"}"
                failingWindowDetails.append(
                    "\n  WINDOW $w  t_end=${fmt(tEnd)}  grid=$gridId  win_s=${fmt(winS)}\n" +
                            "    accel_cov=${fmt(accelCov)}  baro_cov=${fmt(baroCov)}  dyn=$dynN/$dynNTotal  $dynNMatch\n"
                )
                failingWindowDetails.append(windowFailures)
            }
        }

        if (failedChecks > 0) {
            println("\n=== WORST FEATURE ERRORS (top 20) ===")
            worstRawErr.entries.sortedByDescending { it.value }.take(20)
                .forEach { (name, err) -> println("  $name: ${fmtE(err)}") }

            println("\n=== PER-WINDOW DETAILS ===")
            println(failingWindowDetails.toString())
        }

        println(
            "\nFeature parity: $failedChecks / $totalChecks checks exceeded tolerance (RAW=$RAW_TOL)"
        )

        assertEquals(
            "Feature parity FAILED: $failedChecks/$totalChecks checks exceeded tolerance.\n" +
                    "Top offenders: ${
                        worstRawErr.entries.sortedByDescending { it.value }.take(5)
                            .joinToString { "${it.key}=${fmtE(it.value)}" }
                    }",
            0, failedChecks
        )

        println("✅ All features match within tolerance")
    }

    // ---------------------------------------------------------------------------
    // obs_mask computation — matches Python causal_resample_with_mask
    // ---------------------------------------------------------------------------

    private fun computeObsMask(rawT: DoubleArray, gridT: DoubleArray, hz: Double): BooleanArray {
        val result = BooleanArray(gridT.size)
        if (rawT.isEmpty()) return result

        var rawIdx = 0
        for (k in gridT.indices) {
            val lo = if (k == 0) Double.NEGATIVE_INFINITY else gridT[k - 1]
            val hi = gridT[k]

            while (rawIdx < rawT.size - 1 && rawT[rawIdx] <= lo) rawIdx++

            var j = rawIdx
            while (j < rawT.size && rawT[j] <= hi) {
                if (rawT[j] > lo) { result[k] = true; break }
                j++
            }
        }
        return result
    }

    private fun sliceObsMask(mask: BooleanArray, gridT: DoubleArray, ws: Double, we: Double): BooleanArray {
        if (mask.isEmpty() || gridT.isEmpty()) return BooleanArray(0)
        val eps = 1e-9
        var i0 = -1; var i1 = -1
        for (i in gridT.indices) {
            if (i0 < 0 && gridT[i] >= ws - eps) i0 = i
            if (gridT[i] <= we + eps) i1 = i
        }
        if (i0 < 0 || i1 < 0 || i0 > i1) return BooleanArray(0)
        return mask.sliceArray(i0..i1)
    }

    // ---------------------------------------------------------------------------
    // Window slicing — mirrors FlightDetector.sliceAccel / sliceBaro
    // ---------------------------------------------------------------------------

    private fun sliceAccel(aD: Features.AccelDerived?, ws: Double, we: Double): Features.AccelDerived? {
        if (aD == null || aD.t.isEmpty()) return null
        val eps = 1e-9
        var i0 = -1; var i1 = -1
        for (i in aD.t.indices) {
            if (i0 < 0 && aD.t[i] >= ws - eps) i0 = i
            if (aD.t[i] <= we + eps) i1 = i
        }
        if (i0 < 0 || i1 < 0 || i0 > i1) return null
        fun cut(x: DoubleArray) = x.sliceArray(i0..i1)
        return Features.AccelDerived(
            cut(aD.t),
            cut(aD.ax), cut(aD.ay), cut(aD.az),
            cut(aD.amag),
            cut(aD.amagEma10), cut(aD.amagEmaVar10),
            cut(aD.avert), cut(aD.ahoriz),
            cut(aD.dyn)
        )
    }

    private fun sliceBaro(bD: Features.BaroDerived?, ws: Double, we: Double): Features.BaroDerived? {
        if (bD == null || bD.t.isEmpty()) return null
        val eps = 1e-9
        var i0 = -1; var i1 = -1
        for (i in bD.t.indices) {
            if (i0 < 0 && bD.t[i] >= ws - eps) i0 = i
            if (bD.t[i] <= we + eps) i1 = i
        }
        if (i0 < 0 || i1 < 0 || i0 > i1) return null
        fun cut(x: DoubleArray) = x.sliceArray(i0..i1)
        return Features.BaroDerived(cut(bD.t), cut(bD.p), cut(bD.dhdt), cut(bD.pEma30), cut(bD.pEma30dt1))
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun featureError(expected: Double, actual: Double): Double = when {
        expected.isNaN() && actual.isNaN() -> 0.0
        expected.isNaN() != actual.isNaN() -> Double.MAX_VALUE
        else                               -> abs(actual - expected)
    }

    private fun gridStringToId(id: String): Int = when (id) {
        "BASE"     -> 0; "TO_EVENT" -> 1; "LD_EVENT" -> 2; else -> 0
    }

    private fun fmt(x: Double)  = String.format(java.util.Locale.US, "%.6f", x)
    private fun fmtE(x: Double) = String.format(java.util.Locale.US, "%.6e", x)
    private fun DoubleArray.max(): Double? = if (isEmpty()) null else maxOrNull()

    // ---------------------------------------------------------------------------
    // Golden data model
    // ---------------------------------------------------------------------------

    private data class GoldenData(
        val nWindows:           Int,
        val nFeatures:          Int,
        val schedulerOriginSec: Double,
        val baseWinS:           Double,
        val baseHopS:           Double,
        val rawAccelT:          DoubleArray,
        val rawAccelAx:         DoubleArray,
        val rawAccelAy:         DoubleArray,
        val rawAccelAz:         DoubleArray,
        val rawBaroT:           DoubleArray,
        val rawBaroP:           DoubleArray,
        val windowTEnd:         DoubleArray,
        val windowWinS:         DoubleArray,
        val windowHopS:         DoubleArray,
        val windowGridId:       DoubleArray,
        val windowDtPrev:       DoubleArray,
        val windowDynN:         DoubleArray,
        val featuresRaw:        Array<DoubleArray>,
    )

    private fun loadGolden(): GoldenData {
        val arr = loadNpz("parity_golden.npz")
        val nW  = arr["n_windows"]!![0].toInt()
        val nF  = arr["n_features"]!![0].toInt()
        val raw = arr["features_raw"]!!
        return GoldenData(
            nWindows           = nW,
            nFeatures          = nF,
            schedulerOriginSec = arr["scheduler_origin_sec"]!![0],
            baseWinS           = arr["base_win_s"]!![0],
            baseHopS           = arr["base_hop_s"]!![0],
            rawAccelT          = arr["raw_accel_t"]!!,
            rawAccelAx         = arr["raw_accel_ax"]!!,
            rawAccelAy         = arr["raw_accel_ay"]!!,
            rawAccelAz         = arr["raw_accel_az"]!!,
            rawBaroT           = arr["raw_baro_t"]!!,
            rawBaroP           = arr["raw_baro_p"]!!,
            windowTEnd         = arr["window_t_end"]!!,
            windowWinS         = arr["window_win_s"]!!,
            windowHopS         = arr["window_hop_s"]!!,
            windowGridId       = arr["window_grid_id"]!!,
            windowDtPrev       = arr["window_dt_prev_end_s"]!!,
            windowDynN         = arr["window_dyn_n"] ?: DoubleArray(nW) { -1.0 },
            featuresRaw        = Array(nW) { w -> DoubleArray(nF) { f -> raw[w * nF + f] } },
        )
    }

    private fun loadFeatureNames(): List<String> {
        val stream = javaClass.classLoader?.getResourceAsStream("parity_golden_feature_names.json")
            ?: error("parity_golden_feature_names.json not found in test resources")
        return stream.use { s ->
            s.bufferedReader().readText()
                .trim().removePrefix("[").removeSuffix("]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotEmpty() }
        }
    }

    // ---------------------------------------------------------------------------
    // NPZ / NPY readers
    // ---------------------------------------------------------------------------

    private fun loadNpz(resourceName: String): Map<String, DoubleArray> {
        val stream = javaClass.classLoader?.getResourceAsStream(resourceName)
            ?: error("$resourceName not found in app/src/test/resources/")
        val result = mutableMapOf<String, DoubleArray>()
        stream.use { s ->
            ZipInputStream(s).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    result[entry.name.removeSuffix(".npy")] = readNpy(zip)
                    entry = zip.nextEntry
                }
            }
        }
        return result
    }

    private fun readNpy(stream: InputStream): DoubleArray {
        val magic = ByteArray(6); stream.read(magic)
        val major = stream.read(); stream.read()
        val hLenBytes = ByteArray(if (major == 1) 2 else 4); stream.read(hLenBytes)
        val hLen = ByteBuffer.wrap(hLenBytes).order(ByteOrder.LITTLE_ENDIAN).let {
            if (major == 1) it.short.toInt() and 0xFFFF else it.int
        }
        val hBytes = ByteArray(hLen); stream.read(hBytes)
        val header = String(hBytes)

        val isF32 = header.contains("float32") || header.contains("<f4")
        val isF64 = header.contains("float64") || header.contains("<f8")
        val isI32 = header.contains("int32")   || header.contains("<i4")
        check(isF32 || isF64 || isI32) { "Unsupported dtype: $header" }

        val total = Regex("""'shape':\s*\(([^)]*)\)""").find(header)
            ?.groupValues?.get(1)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.map { it.toInt() }?.fold(1, Int::times) ?: 1

        val bpe = if (isF32 || isI32) 4 else 8
        val raw = ByteArray(total * bpe)
        var off = 0
        while (off < raw.size) {
            val n = stream.read(raw, off, raw.size - off)
            if (n < 0) break; off += n
        }
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return DoubleArray(total) { when { isF64 -> buf.double; isF32 -> buf.float.toDouble(); else -> buf.int.toDouble() } }
    }
}