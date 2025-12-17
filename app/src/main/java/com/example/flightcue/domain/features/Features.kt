package com.example.flightcue.domain.features

import com.example.flightcue.domain.timeseries.AccelResampled
import com.example.flightcue.domain.timeseries.BaroResampled
import com.example.flightcue.domain.util.FeatureMath
import com.example.flightcue.domain.util.Params
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Runtime feature extraction that mirrors the Python preprocessor.
 * - deriveAccel/deriveBaro: build continuous streams
 * - windowFeatures: compute per-window features (same keys as training)
 */
object Features {

    // Arrays don’t have value-equality — use plain classes (not data classes).
    class AccelDerived(
        val t: DoubleArray,
        val ax: DoubleArray, val ay: DoubleArray, val az: DoubleArray,
        val amag: DoubleArray,
        val amagMA10: DoubleArray, val amagVar10: DoubleArray,
        val avert: DoubleArray, val ahoriz: DoubleArray
    )
    class BaroDerived(
        val t: DoubleArray,
        val p: DoubleArray, val h: DoubleArray, val dhdt: DoubleArray,
        val pMA30: DoubleArray, val pMA30dt1: DoubleArray
    )

    // ---- derive continuous streams (parity with script) ----
    fun deriveAccel(r: AccelResampled, hz: Double): AccelDerived {
        val ax = r.ax; val ay = r.ay; val az = r.az
        val amag = DoubleArray(ax.size) { i -> sqrt(ax[i]*ax[i] + ay[i]*ay[i] + az[i]*az[i]) }

        val k = maxOf(1, (10.0 * hz).toInt())
        val amagMA10 = rollMean(amag, k)
        val amagVar10 = rollVar(amag, k)

        val a = 2.0 / (1.0 + Params.GRAV_TAU_S * hz)
        val gx = FeatureMath.ema(ax, a)
        val gy = FeatureMath.ema(ay, a)
        val gz = FeatureMath.ema(az, a)

        val norm = DoubleArray(gx.size) { i ->
            val g = sqrt(gx[i]*gx[i] + gy[i]*gy[i] + gz[i]*gz[i])
            if (g == 0.0) 1e-8 else g
        }
        val ux = DoubleArray(gx.size) { i -> gx[i] / norm[i] }
        val uy = DoubleArray(gy.size) { i -> gy[i] / norm[i] }
        val uz = DoubleArray(gz.size) { i -> gz[i] / norm[i] }

        val dx = DoubleArray(ax.size) { i -> ax[i] - gx[i] }
        val dy = DoubleArray(ay.size) { i -> ay[i] - gy[i] }
        val dz = DoubleArray(az.size) { i -> az[i] - gz[i] }

        val dot = DoubleArray(ax.size) { i -> dx[i]*ux[i] + dy[i]*uy[i] + dz[i]*uz[i] }
        val avert = dot
        val ahoriz = DoubleArray(ax.size) { i ->
            val dyn2 = dx[i]*dx[i] + dy[i]*dy[i] + dz[i]*dz[i]
            val vert2 = dot[i]*dot[i]
            sqrt(max(0.0, dyn2 - vert2))
        }

        return AccelDerived(r.t, ax, ay, az, amag, amagMA10, amagVar10, avert, ahoriz)
    }

    fun deriveBaro(r: BaroResampled, hz: Double): BaroDerived {
        val p = r.p
        val p0 = if (p.isEmpty()) Double.NaN else run {
            val t0 = r.t.first(); val end = t0 + Params.P0_HORIZON_S
            val idx = r.t.indexOfLast { it <= end }.coerceAtLeast(0)
            val win = if (idx >= 4) p.sliceArray(0..idx) else p
            FeatureMath.median(win)
        }

        val h = DoubleArray(p.size) { i ->
            if (p0.isNaN() || p0 <= 0) Double.NaN
            else {
                val ratio = (p[i] / p0).coerceIn(1e-6, 100.0)
                44330.0 * (1.0 - Math.pow(ratio, 1.0 / 5.255))
            }
        }

        val dt = if (hz > 0) 1.0 / hz else 1.0
        val dh = DoubleArray(h.size) { i -> if (i == 0) 0.0 else (h[i] - h[i-1]) / dt }
        val a = 2.0 / (1.0 + Params.DHDT_TAU_S * hz)
        val dhdt = FeatureMath.ema(dh, a)

        val k = maxOf(1, (30.0 * hz).toInt())
        val pMA30 = rollMean(p, k)
        val pMA30dt1 = DoubleArray(pMA30.size) { i -> if (i == 0) 0.0 else (pMA30[i] - pMA30[i-1]) / dt }

        return BaroDerived(r.t, p, h, dhdt, pMA30, pMA30dt1)
    }

    private fun rollMean(x: DoubleArray, k: Int): DoubleArray {
        if (x.isEmpty() || k <= 1) return x.copyOf()
        val out = DoubleArray(x.size) { Double.NaN }
        val half = k / 2
        for (i in x.indices) {
            val i0 = (i - half).coerceAtLeast(0)
            val i1 = (i + half).coerceAtMost(x.lastIndex)
            var s = 0.0; var c = 0
            for (j in i0..i1) { s += x[j]; c++ }
            out[i] = s / c
        }
        return out
    }

    private fun rollVar(x: DoubleArray, k: Int): DoubleArray {
        if (x.isEmpty() || k <= 1) return DoubleArray(x.size) { 0.0 }
        val out = DoubleArray(x.size)
        val half = k / 2
        for (i in x.indices) {
            val i0 = (i - half).coerceAtLeast(0)
            val i1 = (i + half).coerceAtMost(x.lastIndex)
            val slice = x.sliceArray(i0..i1)
            val m = FeatureMath.mean(slice)
            var s = 0.0
            for (v in slice) s += (v - m) * (v - m)
            out[i] = s / slice.size
        }
        return out
    }

    // ---- per-window features (same formulas and keys as the script) ----
    fun windowFeatures(
        a: AccelDerived?, b: BaroDerived?,
        accelHz: Double, baroHz: Double,
        centers: RobustCenters?, doPsd: Boolean
    ): Map<String, Double> {
        val out = LinkedHashMap<String, Double>(160)

        val ax = a?.ax ?: DoubleArray(0)
        val ay = a?.ay ?: DoubleArray(0)
        val az = a?.az ?: DoubleArray(0)
        val amag = a?.amag ?: DoubleArray(0)
        val avert = a?.avert ?: DoubleArray(0)
        val ahoriz = a?.ahoriz ?: DoubleArray(0)
        val amagMA10 = a?.amagMA10 ?: DoubleArray(0)
        val amagVar10 = a?.amagVar10 ?: DoubleArray(0)

        for ((name, arr) in listOf("ax" to ax, "ay" to ay, "az" to az, "amag" to amag)) {
            out["${name}_mean"] = FeatureMath.mean(arr);  out["${name}_std"] = FeatureMath.std(arr)
            out["${name}_min"]  = FeatureMath.min(arr);   out["${name}_max"] = FeatureMath.max(arr)
            out["${name}_median"] = FeatureMath.median(arr); out["${name}_iqr"] = FeatureMath.iqr(arr)
            out["${name}_rms"] = FeatureMath.rms(arr)
            out["${name}_range"] = FeatureMath.max(arr) - FeatureMath.min(arr)
            out["${name}_skew"] = FeatureMath.skew(arr); out["${name}_kurt"] = FeatureMath.kurtEx(arr)
        }
        out["amag_halfdiff"] = FeatureMath.halfDiff(amag)

        if (amag.size >= 2) {
            val dt = 1.0 / accelHz
            val jerk = DoubleArray(amag.size) { i -> if (i == 0) (amag[1] - amag[0]) / dt else (amag[i] - amag[i-1]) / dt }
            out["jerk_mean"] = FeatureMath.mean(jerk)
            out["jerk_std"]  = FeatureMath.std(jerk)
            out["jerk_rms"]  = FeatureMath.rms(jerk)
        } else {
            out["jerk_mean"] = Double.NaN; out["jerk_std"] = Double.NaN; out["jerk_rms"] = Double.NaN
        }

        val aDyn = 2.0 / (1.0 + Params.DYN_TAU_S * accelHz)
        val gHat = FeatureMath.ema(amag, aDyn)
        val dyn = DoubleArray(amag.size) { i -> amag[i] - gHat[i] }
        out["dyn_mean"] = FeatureMath.mean(dyn); out["dyn_std"] = FeatureMath.std(dyn); out["dyn_rms"] = FeatureMath.rms(dyn)
        out["dyn_max"] = FeatureMath.max(dyn);  out["dyn_range"] = FeatureMath.max(dyn) - FeatureMath.min(dyn)
        out["dyn_halfdiff"] = FeatureMath.halfDiff(dyn); out["dyn_skew"] = FeatureMath.skew(dyn); out["dyn_kurt"] = FeatureMath.kurtEx(dyn)
        out["amag_peakcount"] = FeatureMath.peakCount(amag, 2.0)
        out["dyn_peakcount"]  = FeatureMath.peakCount(dyn, 2.0)

        if (doPsd && amag.size >= 8) {
            FeatureMath.relBandPowers(amag, accelHz, arrayOf(0.0 to 0.5, 0.5 to 3.0, 3.0 to 10.0))
                .forEach { (k, v) -> out[k] = v }
            FeatureMath.relBandPowers(dyn, accelHz, arrayOf(0.0 to 0.3, 0.3 to 1.0, 1.0 to 3.0, 3.0 to 8.0, 8.0 to 15.0))
                .forEach { (k, v) -> out["dyn_$k"] = v }
        } else {
            listOf("0.0_0.5", "0.5_3.0", "3.0_10.0").forEach { out["pow_$it"] = Double.NaN }
            listOf("0.0_0.3", "0.3_1.0", "1.0_3.0", "3.0_8.0", "8.0_15.0").forEach { out["dyn_pow_$it"] = Double.NaN }
        }

        if (avert.isNotEmpty()) {
            out["avert_mean"] = FeatureMath.mean(avert); out["avert_std"] = FeatureMath.std(avert); out["avert_rms"] = FeatureMath.rms(avert)
            out["avert_peakcount"] = FeatureMath.peakCount(avert, 2.0)
        } else { out["avert_mean"] = Double.NaN; out["avert_std"] = Double.NaN; out["avert_rms"] = Double.NaN; out["avert_peakcount"] = Double.NaN }

        if (ahoriz.isNotEmpty()) {
            out["ahoriz_mean"] = FeatureMath.mean(ahoriz); out["ahoriz_std"] = FeatureMath.std(ahoriz); out["ahoriz_rms"] = FeatureMath.rms(ahoriz)
            out["ahoriz_peakcount"] = FeatureMath.peakCount(ahoriz, 2.0)
        } else { out["ahoriz_mean"] = Double.NaN; out["ahoriz_std"] = Double.NaN; out["ahoriz_rms"] = Double.NaN; out["ahoriz_peakcount"] = Double.NaN }

        val ar = out["avert_rms"]; val hr = out["ahoriz_rms"]
        out["avert_to_ahoriz_ratio"] =
            if (ar != null && hr != null && ar.isFinite() && hr.isFinite() && hr != 0.0) ar / hr else Double.NaN

        // --- BARO ---
        val bT: DoubleArray = b?.t ?: DoubleArray(0)  // safe time base for slope()
        val p = b?.p ?: DoubleArray(0)
        if (p.isNotEmpty()) {
            out["p_mean"] = FeatureMath.mean(p); out["p_std"] = FeatureMath.std(p)
            out["p_min"] = FeatureMath.min(p);   out["p_max"] = FeatureMath.max(p)
            out["p_median"] = FeatureMath.median(p); out["p_iqr"] = FeatureMath.iqr(p)
            out["p_range"] = FeatureMath.max(p) - FeatureMath.min(p)
            out["p_skew"] = FeatureMath.skew(p); out["p_kurt"] = FeatureMath.kurtEx(p)
            val dt = 1.0 / baroHz
            val dpdt = DoubleArray(p.size) { i -> if (i == 0) 0.0 else (p[i] - p[i-1]) / dt }
            out["dpdt_mean"] = FeatureMath.mean(dpdt); out["dpdt_std"] = FeatureMath.std(dpdt)
            out["p_slope"]   = FeatureMath.slope(bT, p)
        } else {
            listOf("p_mean","p_std","p_min","p_max","p_median","p_iqr","p_range","p_skew","p_kurt","dpdt_mean","dpdt_std","p_slope")
                .forEach { out[it] = Double.NaN }
        }

        val h = b?.h ?: DoubleArray(0)
        if (h.isNotEmpty()) {
            out["h_mean"] = FeatureMath.mean(h); out["h_std"] = FeatureMath.std(h)
            out["h_min"]  = FeatureMath.min(h);  out["h_max"]  = FeatureMath.max(h)
            out["h_range"] = FeatureMath.max(h) - FeatureMath.min(h)
            out["h_slope"] = FeatureMath.slope(bT, h)
        } else {
            listOf("h_mean","h_std","h_min","h_max","h_range","h_slope").forEach { out[it] = Double.NaN }
        }

        val v = b?.dhdt ?: DoubleArray(0)
        if (v.isNotEmpty()) {
            out["dhdt_mean"] = FeatureMath.mean(v); out["dhdt_std"] = FeatureMath.std(v)
            out["dhdt_maxabs"] = v.maxOfOrNull { abs(it) } ?: Double.NaN
            out["plateau_frac"] =
                if (v.isNotEmpty()) v.count { abs(it) < Params.DHDT_PLATEAU_THR }.toDouble() / v.size else Double.NaN
            out["dhdt_zcr_ps"] = FeatureMath.zcrPerSec(v, baroHz, Params.ZCR_THR)
            out["runlen_climb_s"]   = FeatureMath.runLenSec(BooleanArray(v.size) { i -> v[i] >  Params.RUNLEN_CLIMB_THR }, baroHz)
            out["runlen_descent_s"] = FeatureMath.runLenSec(BooleanArray(v.size) { i -> v[i] <  Params.RUNLEN_DESC_THR }, baroHz)
        } else {
            listOf("dhdt_mean","dhdt_std","dhdt_maxabs","plateau_frac","dhdt_zcr_ps","runlen_climb_s","runlen_descent_s")
                .forEach { out[it] = Double.NaN }
        }

        // smoothed streams
        out["amag_thirddiff"] = FeatureMath.thirdDiff(amag)
        if (amagMA10.isNotEmpty()) {
            out["amag_ma10_mean"] = FeatureMath.mean(amagMA10); out["amag_ma10_std"] = FeatureMath.std(amagMA10)
            out["amag_ma10_range"] = FeatureMath.max(amagMA10) - FeatureMath.min(amagMA10)
            out["amag_ma10_thirddiff"] = FeatureMath.thirdDiff(amagMA10)
        } else {
            listOf("mean","std","range","thirddiff").forEach { out["amag_ma10_$it"] = Double.NaN }
        }

        if (amagVar10.isNotEmpty()) {
            out["amag_var10_mean"] = FeatureMath.mean(amagVar10)
            out["amag_var10_iqr"]  = FeatureMath.iqr(amagVar10)
        } else {
            out["amag_var10_mean"] = Double.NaN; out["amag_var10_iqr"] = Double.NaN
        }

        val pMA30 = b?.pMA30 ?: DoubleArray(0)
        if (pMA30.isNotEmpty()) {
            out["p_ma30_mean"] = FeatureMath.mean(pMA30); out["p_ma30_std"] = FeatureMath.std(pMA30)
            out["p_ma30_slope"] = FeatureMath.slope(bT, pMA30)
        } else {
            out["p_ma30_mean"] = Double.NaN; out["p_ma30_std"] = Double.NaN; out["p_ma30_slope"] = Double.NaN
        }

        val pMA30dt1 = b?.pMA30dt1 ?: DoubleArray(0)
        if (pMA30dt1.isNotEmpty()) {
            out["p_ma30_dt1_mean"] = FeatureMath.mean(pMA30dt1); out["p_ma30_dt1_std"] = FeatureMath.std(pMA30dt1)
            out["p_ma30_dt1_posfrac"] = pMA30dt1.count { it > 0.0 }.toDouble() / pMA30dt1.size
        } else {
            out["p_ma30_dt1_mean"] = Double.NaN; out["p_ma30_dt1_std"] = Double.NaN; out["p_ma30_dt1_posfrac"] = Double.NaN
        }

        // energy ratios & third-diffs
        fun sd(a: Double?, b: Double?) =
            if (a == null || b == null || !a.isFinite() || !b.isFinite() || b == 0.0) Double.NaN else a / b
        out["amag_ratio_0.5_3__0_0.5"] = sd(out["pow_0.5_3.0"], out["pow_0.0_0.5"])
        out["dyn_ratio_1_3__0.3_1"]    = sd(out["dyn_pow_1.0_3.0"], out["dyn_pow_0.3_1.0"])
        out["dyn_thirddiff"] = FeatureMath.thirdDiff(dyn)
        out["h_thirddiff"]   = FeatureMath.thirdDiff(h)

        // robust (median/IQR) variants
        if (centers != null) {
            fun rz(x: DoubleArray, med: Double, iqr: Double): DoubleArray {
                val s = if (iqr > 1e-8 && iqr.isFinite()) iqr else 1.0
                return DoubleArray(x.size) { i -> (x[i] - med) / s }
            }
            val axr = rz(ax, centers.medAx, centers.iqrAx)
            val ayr = rz(ay, centers.medAy, centers.iqrAy)
            val azr = rz(az, centers.medAz, centers.iqrAz)
            val amr = DoubleArray(axr.size) { i -> sqrt(axr[i]*axr[i] + ayr[i]*ayr[i] + azr[i]*azr[i]) }
            for ((name, arr) in listOf("ax_r" to axr, "ay_r" to ayr, "az_r" to azr, "amag_r" to amr)) {
                out["${name}_mean"] = FeatureMath.mean(arr); out["${name}_std"] = FeatureMath.std(arr); out["${name}_iqr"] = FeatureMath.iqr(arr)
                out["${name}_rms"] = FeatureMath.rms(arr); out["${name}_range"] = FeatureMath.max(arr) - FeatureMath.min(arr)
            }
            val pAll = b?.p ?: DoubleArray(0)
            if (pAll.isNotEmpty()) {
                val pr = rz(pAll, centers.medP, centers.iqrP)
                out["p_r_mean"] = FeatureMath.mean(pr); out["p_r_std"] = FeatureMath.std(pr); out["p_r_iqr"] = FeatureMath.iqr(pr)
            } else {
                out["p_r_mean"] = Double.NaN; out["p_r_std"] = Double.NaN; out["p_r_iqr"] = Double.NaN
            }
        } else {
            listOf("ax_r","ay_r","az_r","amag_r").forEach { bname ->
                listOf("mean","std","iqr","rms","range").forEach { s -> out["${bname}_$s"] = Double.NaN }
            }
            listOf("p_r_mean","p_r_std","p_r_iqr").forEach { out[it] = Double.NaN }
        }

        return out
    }
}

data class RobustCenters(
    val medAx: Double, val iqrAx: Double,
    val medAy: Double, val iqrAy: Double,
    val medAz: Double, val iqrAz: Double,
    val medP:  Double, val iqrP:  Double
)
