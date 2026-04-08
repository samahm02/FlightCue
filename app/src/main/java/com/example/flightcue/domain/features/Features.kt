package com.example.flightcue.domain.features

import com.example.flightcue.domain.timeseries.AccelResampled
import com.example.flightcue.domain.timeseries.BaroResampled
import com.example.flightcue.domain.util.FeatureMath
import com.example.flightcue.domain.util.Params
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Runtime feature extraction matching `preprocess_all_data_causal_fixed_v5_final.py` (best-effort parity).
 *
 * What this file assumes is already handled elsewhere (your Resample.kt / windowing):
 * - Causal bin-mean resampling + bounded forward-hold (gap limit)
 * - Window slicing already produces per-window AccelResampled/BaroResampled
 *
 * Key parity points with the Python you pasted:
 * - Pressure stays pressure everywhere (`p_*`, `dpdt_*`, `p_ema30_*`). Altitude is only an internal intermediate for `dhdt`.
 * - Gravity alignment uses EMA that *skips NaNs and outputs NaN on NaN* (no unlimited forward fill).
 * - `dyn(t) = amag(t) - EMA_skipnan(amag(t), tau=2s)`; outputs NaN when amag is NaN.
 * - `amag_ema10` is EMA-mean (tau=10s), `amag_emaVar10` is EMA-variance (tau=10s) as in Python (NOT rolling windows).
 * - `p_ema30` is EMA-mean (tau=30s); `p_ema30_dt1` is its discrete derivative, padded like Python.
 * - `dhdt` is computed from pressure->altitude conversion only to get a derivative; smoothed with EMA_skipnan (tau=2s)
 *   and preserves NaNs (no missing→0 conversion).
 * - Window stats:
 *   - accel stats use m3 gating (ax/ay/az must be finite together)
 *   - most *_mean/std/etc use finite-only arrays (like Python’s `arr[np.isfinite(arr)]`)
 */
object Features {

    enum class AccelUnits { M_S2, G, AUTO }
    private const val G0 = 9.80665

    // Arrays don't have value-equality — use plain classes (not data classes).
    class AccelDerived(
        val t: DoubleArray,
        val ax: DoubleArray, val ay: DoubleArray, val az: DoubleArray,
        val amag: DoubleArray,
        val amagEma10: DoubleArray,
        val amagEmaVar10: DoubleArray,
        val avert: DoubleArray,
        val ahoriz: DoubleArray,
        val dyn: DoubleArray
    )

    class BaroDerived(
        val t: DoubleArray,
        val p: DoubleArray,            // ✅ PRESSURE (Pa or hPa consistently with training)
        val dhdt: DoubleArray,         // climb rate proxy from pressure-derived altitude (m/s)
        val pEma30: DoubleArray,        // EMA mean of pressure (tau=30s)
        val pEma30dt1: DoubleArray      // discrete derivative of pEma30, padded like Python
    )

    // -------------------------------------------------------------------------
    // Continuous streams (parity with Python)
    // -------------------------------------------------------------------------

    fun deriveAccel(
        r: AccelResampled,
        hz: Double,
        units: AccelUnits = AccelUnits.M_S2
    ): AccelDerived {
        val (axS, ayS, azS) = normalizeAccelUnits(r.ax, r.ay, r.az, units)

        // amag: requires all 3 axes finite (m3 gating), else NaN
        val amag = DoubleArray(axS.size) { i ->
            val x = axS[i]; val y = ayS[i]; val z = azS[i]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) Double.NaN
            else sqrt(x * x + y * y + z * z)
        }

        // Python: amag_ema10 = ema_mean(skipnan) with tau=10s
        val amagEma10 = emaMeanSkipNanOutNan(amag, hz, tauS = 10.0)

        // Python: amag_emaVar10 = ema_var with tau=10s (EMA mean + EMA variance), skipnan, output NaN on NaN
        val amagEmaVar10 = emaVarSkipNanOutNan(amag, hz, tauS = 10.0)

        // Gravity estimate: Python uses ema_series_skipnan on each axis (tau ~ 0.6s)
        val aGrav = alphaFromTau(hz, Params.GRAV_TAU_S)
        val gx = emaSkipNanOutNan(axS, aGrav)
        val gy = emaSkipNanOutNan(ayS, aGrav)
        val gz = emaSkipNanOutNan(azS, aGrav)

        // Unit gravity vector only where norm is finite and non-degenerate (match Python valid_g gating)
        val ux = DoubleArray(axS.size) { Double.NaN }
        val uy = DoubleArray(axS.size) { Double.NaN }
        val uz = DoubleArray(axS.size) { Double.NaN }

        for (i in gx.indices) {
            val x = gx[i]; val y = gy[i]; val z = gz[i]
            if (x.isFinite() && y.isFinite() && z.isFinite()) {
                val n = sqrt(x * x + y * y + z * z)
                if (n.isFinite() && n > 1e-8) {
                    ux[i] = x / n
                    uy[i] = y / n
                    uz[i] = z / n
                }
            }
        }

        // Dynamic component (raw - gravity) only where both are finite (NaNs propagate naturally)
        val dx = DoubleArray(axS.size) { i ->
            val a = axS[i]; val g = gx[i]
            if (!a.isFinite() || !g.isFinite()) Double.NaN else (a - g)
        }
        val dy = DoubleArray(ayS.size) { i ->
            val a = ayS[i]; val g = gy[i]
            if (!a.isFinite() || !g.isFinite()) Double.NaN else (a - g)
        }
        val dz = DoubleArray(azS.size) { i ->
            val a = azS[i]; val g = gz[i]
            if (!a.isFinite() || !g.isFinite()) Double.NaN else (a - g)
        }

        val dot = DoubleArray(axS.size) { i ->
            val dxi = dx[i]; val dyi = dy[i]; val dzi = dz[i]
            val uxi = ux[i]; val uyi = uy[i]; val uzi = uz[i]
            if (!dxi.isFinite() || !dyi.isFinite() || !dzi.isFinite() ||
                !uxi.isFinite() || !uyi.isFinite() || !uzi.isFinite()
            ) Double.NaN
            else dxi * uxi + dyi * uyi + dzi * uzi
        }

        val avert = dot

        val ahoriz = DoubleArray(axS.size) { i ->
            val dxi = dx[i]; val dyi = dy[i]; val dzi = dz[i]
            val di = dot[i]
            if (!dxi.isFinite() || !dyi.isFinite() || !dzi.isFinite() || !di.isFinite()) Double.NaN
            else {
                val dyn2 = dxi * dxi + dyi * dyi + dzi * dzi
                val vert2 = di * di
                sqrt(max(0.0, dyn2 - vert2))
            }
        }

        // Python: dyn = amag - ema_series_skipnan(amag, tau=2s)
        val aDyn = alphaFromTau(hz, Params.DYN_TAU_S)
        val gHat = emaSkipNanOutNan(amag, aDyn)
        val dyn = DoubleArray(amag.size) { i ->
            val m = amag[i]; val g = gHat[i]
            if (!m.isFinite() || !g.isFinite()) Double.NaN else (m - g)
        }

        return AccelDerived(
            t = r.t,
            ax = axS, ay = ayS, az = azS,
            amag = amag,
            amagEma10 = amagEma10,
            amagEmaVar10 = amagEmaVar10,
            avert = avert,
            ahoriz = ahoriz,
            dyn = dyn
        )
    }

    fun deriveBaro(
        r: BaroResampled,
        hz: Double,
    ): BaroDerived {
        val p = r.p

        // Python: p_ema30 = ema_mean(skipnan) tau=30s
        val pEma30 = emaMeanSkipNanOutNan(p, hz, tauS = 30.0)

        // Python: p_ema30_dt1 from p_ema30, with padding: [dp0, dp0, dp1, ...]
        val dt = if (hz > 0.0) 1.0 / hz else 1.0
        val pEma30dt1 = DoubleArray(pEma30.size) { Double.NaN }
        if (pEma30.size >= 2 && pEma30.count { it.isFinite() } >= 2) {
            for (i in 1 until pEma30.size) {
                val a = pEma30[i]
                val b = pEma30[i - 1]
                pEma30dt1[i] = if (a.isFinite() && b.isFinite()) (a - b) / dt else Double.NaN
            }
            // pad first sample with dp0 (Python behavior)
            pEma30dt1[0] = pEma30dt1[1]
        }

        // Python: dhdt derived from pressure->altitude using first finite p as p0_temp
        val p0Temp = firstFinite(p)
        val dhdt = if (p0Temp == null || !p0Temp.isFinite() || p0Temp <= 0.0) {
            DoubleArray(p.size) { Double.NaN }
        } else {
            // h_temp from p and p0_temp
            val hTemp = DoubleArray(p.size) { i ->
                val pi = p[i]
                if (!pi.isFinite()) Double.NaN
                else {
                    val ratio = (pi / p0Temp).coerceIn(1e-6, 100.0)
                    44330.0 * (1.0 - ratio.pow(1.0 / 5.255))
                }
            }

            // dh: backward diff only where both adjacent are finite; dh[0] stays NaN (Python)
            val dh = DoubleArray(hTemp.size) { Double.NaN }
            for (i in 1 until hTemp.size) {
                val a = hTemp[i]
                val b = hTemp[i - 1]
                if (a.isFinite() && b.isFinite()) {
                    dh[i] = (a - b) / dt
                }
            }

            // dhdt: EMA_skipnan on dh (tau=2s), output NaN on NaN
            val aDh = alphaFromTau(hz, Params.DHDT_TAU_S)
            emaSkipNanOutNan(dh, aDh)
        }

        // ✅ RETURN PRESSURE (not altitude)
        return BaroDerived(
            t = r.t,
            p = p,
            dhdt = dhdt,
            pEma30 = pEma30,
            pEma30dt1 = pEma30dt1
        )
    }

    // -------------------------------------------------------------------------
    // Per-window features (match Python naming + finite-only behavior)
    // -------------------------------------------------------------------------

    fun windowFeatures(
        a: AccelDerived?,
        b: BaroDerived?,
        accelHz: Double,
        baroHz: Double,
        doPsd: Boolean
    ): Map<String, Double> {

        val out = LinkedHashMap<String, Double>(200)

        // -------------------- ACCEL --------------------
        val ax = a?.ax ?: DoubleArray(0)
        val ay = a?.ay ?: DoubleArray(0)
        val az = a?.az ?: DoubleArray(0)

        // m3 gating: only indices where all 3 axes are finite
        val idx = IntArray(ax.size)
        var m = 0
        for (i in ax.indices) {
            if (ax[i].isFinite() && ay[i].isFinite() && az[i].isFinite()) idx[m++] = i
        }

        fun gather(x: DoubleArray): DoubleArray {
            val outArr = DoubleArray(m)
            for (k in 0 until m) outArr[k] = x[idx[k]]
            return outArr
        }

        val axU = if (m > 0) gather(ax) else DoubleArray(0)
        val ayU = if (m > 0) gather(ay) else DoubleArray(0)
        val azU = if (m > 0) gather(az) else DoubleArray(0)

        val amagU = DoubleArray(axU.size) { i ->
            val x = axU[i]; val y = ayU[i]; val z = azU[i]
            sqrt(x * x + y * y + z * z)
        }

        fun putStats(name: String, arr: DoubleArray) {
            // arr is already finite-only in our calls for accel (m3 gating), matching Python
            out["${name}_mean"] = FeatureMath.mean(arr)
            out["${name}_std"] = FeatureMath.std(arr)
            out["${name}_min"] = FeatureMath.min(arr)
            out["${name}_max"] = FeatureMath.max(arr)
            out["${name}_median"] = FeatureMath.median(arr)
            out["${name}_iqr"] = FeatureMath.iqr(arr)
            out["${name}_rms"] = FeatureMath.rms(arr)
            out["${name}_range"] = FeatureMath.max(arr) - FeatureMath.min(arr)
            out["${name}_skew"] = FeatureMath.skew(arr)
            out["${name}_kurt"] = FeatureMath.kurtEx(arr)
        }

        if (amagU.isNotEmpty()) {
            putStats("ax", axU)
            putStats("ay", ayU)
            putStats("az", azU)
            putStats("amag", amagU)

            out["amag_halfdiff"] = FeatureMath.halfDiff(amagU)
            out["amag_thirddiff"] = FeatureMath.thirdDiff(amagU)

            // recency features (amag)
            out["amag_recent_diff_25pct"] = FeatureMath.recentVsEarlierDiff(amagU, 0.25)
            out["amag_recent_slope_2s"] = FeatureMath.recentSlope(amagU, accelHz, 2.0)
            out["amag_recent_slope_3s"] = FeatureMath.recentSlope(amagU, accelHz, 3.0)
            out["amag_recent_std_2s"] = FeatureMath.recentStd(amagU, accelHz, 2.0)
            out["amag_recent_mean_2s"] = FeatureMath.recentMean(amagU, accelHz, 2.0)
            out["amag_recent_max_abs_2s"] = FeatureMath.recentMaxAbs(amagU, accelHz, 2.0)
            out["amag_ratio_recent_std"] = FeatureMath.ratioRecentToWindow(amagU, accelHz, 2.0, "std")

            // jerk from amagU: jerk_mean = mean(abs(jerk))
            if (amagU.size >= 2 && accelHz > 0.0) {
                val dt = 1.0 / accelHz
                val jerk = DoubleArray(amagU.size - 1) { i -> (amagU[i + 1] - amagU[i]) / dt }
                val jerkAbs = DoubleArray(jerk.size) { i -> abs(jerk[i]) }
                out["jerk_mean"] = FeatureMath.mean(jerkAbs)
                out["jerk_std"] = FeatureMath.std(jerk)
                out["jerk_rms"] = FeatureMath.rms(jerk)
                out["jerk_recent_std_2s"] = FeatureMath.recentStd(jerk, accelHz, 2.0)
                out["jerk_recent_max_abs_2s"] = FeatureMath.recentMaxAbs(jerk, accelHz, 2.0)
            } else {
                out["jerk_mean"] = Double.NaN
                out["jerk_std"] = Double.NaN
                out["jerk_rms"] = Double.NaN
                out["jerk_recent_std_2s"] = Double.NaN
                out["jerk_recent_max_abs_2s"] = Double.NaN
            }

            // dyn sliced from stream (already EMA_skipnan; NaN when amag NaN)
            val dynFull = a?.dyn ?: DoubleArray(0)
            val dynU = if (dynFull.size == ax.size && m > 0) gather(dynFull) else DoubleArray(0)
            val dynF = dynU.filter { it.isFinite() }.toDoubleArray()

            if (dynF.isNotEmpty()) {
                out["dyn_mean"] = FeatureMath.mean(dynF)
                out["dyn_std"] = FeatureMath.std(dynF)
                out["dyn_rms"] = FeatureMath.rms(dynF)
                out["dyn_max"] = FeatureMath.max(dynF)
                out["dyn_range"] = FeatureMath.max(dynF) - FeatureMath.min(dynF)
                out["dyn_skew"] = FeatureMath.skew(dynF)
                out["dyn_kurt"] = FeatureMath.kurtEx(dynF)
                out["dyn_peakcount"] = FeatureMath.peakCount(dynF, 2.0)

                out["dyn_halfdiff"] = FeatureMath.halfDiff(dynF)
                out["dyn_thirddiff"] = FeatureMath.thirdDiff(dynU) // Python filters finite inside third_diff

                out["dyn_recent_diff_25pct"] = FeatureMath.recentVsEarlierDiff(dynF, 0.25)
                out["dyn_recent_slope_2s"] = FeatureMath.recentSlope(dynF, accelHz, 2.0)
                out["dyn_recent_std_2s"] = FeatureMath.recentStd(dynF, accelHz, 2.0)
                out["dyn_recent_max_abs_2s"] = FeatureMath.recentMaxAbs(dynF, accelHz, 2.0)
                out["dyn_ratio_recent_std"] = FeatureMath.ratioRecentToWindow(dynF, accelHz, 2.0, "std")
            } else {
                for (s in listOf(
                    "mean","std","rms","max","range","skew","kurt","peakcount",
                    "halfdiff","thirddiff",
                    "recent_diff_25pct","recent_slope_2s","recent_std_2s","recent_max_abs_2s","ratio_recent_std"
                )) {
                    out["dyn_$s"] = Double.NaN
                }
            }

            out["amag_peakcount"] = FeatureMath.peakCount(amagU, 2.0)

            // PSD + spectral features (match Python bands + naming)
            if (doPsd && amagU.size >= 16) {
                FeatureMath.relBandPowers(
                    amagU, accelHz,
                    arrayOf(0.0 to 0.3, 0.3 to 1.0, 1.0 to 3.0, 3.0 to 8.0, 8.0 to 15.0)
                ).forEach { (k, v) -> out[k] = v }

                out["amag_dominant_freq"] = FeatureMath.dominantFrequency(amagU, accelHz)
                out["amag_spectral_centroid"] = FeatureMath.spectralCentroid(amagU, accelHz)
                out["amag_spectral_bandwidth"] = FeatureMath.spectralBandwidth(amagU, accelHz)

                if (dynF.size >= 16) {
                    FeatureMath.relBandPowers(
                        dynF, accelHz,
                        arrayOf(0.0 to 0.5, 0.5 to 2.0, 2.0 to 5.0, 5.0 to 10.0, 10.0 to 20.0)
                    ).forEach { (k, v) -> out["dyn_$k"] = v }

                    out["dyn_dominant_freq"] = FeatureMath.dominantFrequency(dynF, accelHz)
                    out["dyn_spectral_centroid"] = FeatureMath.spectralCentroid(dynF, accelHz)
                } else {
                    listOf("0.0_0.5","0.5_2.0","2.0_5.0","5.0_10.0","10.0_20.0")
                        .forEach { out["dyn_pow_$it"] = Double.NaN }
                    out["dyn_dominant_freq"] = Double.NaN
                    out["dyn_spectral_centroid"] = Double.NaN
                }
            } else {
                listOf("0.0_0.3","0.3_1.0","1.0_3.0","3.0_8.0","8.0_15.0")
                    .forEach { out["pow_$it"] = Double.NaN }
                out["amag_dominant_freq"] = Double.NaN
                out["amag_spectral_centroid"] = Double.NaN
                out["amag_spectral_bandwidth"] = Double.NaN

                listOf("0.0_0.5","0.5_2.0","2.0_5.0","5.0_10.0","10.0_20.0")
                    .forEach { out["dyn_pow_$it"] = Double.NaN }
                out["dyn_dominant_freq"] = Double.NaN
                out["dyn_spectral_centroid"] = Double.NaN
            }

            // avert/ahoriz stats on finite-only (Python uses a = arr[np.isfinite])
            val avertFull = a?.avert ?: DoubleArray(0)
            val avertU = if (avertFull.size == ax.size && m > 0) gather(avertFull) else DoubleArray(0)
            val avertF = avertU.filter { it.isFinite() }.toDoubleArray()

            if (avertF.isNotEmpty()) {
                out["avert_mean"] = FeatureMath.mean(avertF)
                out["avert_std"] = FeatureMath.std(avertF)
                out["avert_rms"] = FeatureMath.rms(avertF)
                out["avert_peakcount"] = FeatureMath.peakCount(avertF, 2.0)
                out["avert_recent_slope_2s"] = FeatureMath.recentSlope(avertF, accelHz, 2.0)
                out["avert_recent_std_2s"] = FeatureMath.recentStd(avertF, accelHz, 2.0)
            } else {
                out["avert_mean"] = Double.NaN
                out["avert_std"] = Double.NaN
                out["avert_rms"] = Double.NaN
                out["avert_peakcount"] = Double.NaN
                out["avert_recent_slope_2s"] = Double.NaN
                out["avert_recent_std_2s"] = Double.NaN
            }

            val ahorizFull = a?.ahoriz ?: DoubleArray(0)
            val ahorizU = if (ahorizFull.size == ax.size && m > 0) gather(ahorizFull) else DoubleArray(0)
            val ahorizF = ahorizU.filter { it.isFinite() }.toDoubleArray()

            if (ahorizF.isNotEmpty()) {
                out["ahoriz_mean"] = FeatureMath.mean(ahorizF)
                out["ahoriz_std"] = FeatureMath.std(ahorizF)
                out["ahoriz_rms"] = FeatureMath.rms(ahorizF)
                out["ahoriz_peakcount"] = FeatureMath.peakCount(ahorizF, 2.0)
                out["ahoriz_recent_slope_2s"] = FeatureMath.recentSlope(ahorizF, accelHz, 2.0)
                out["ahoriz_recent_std_2s"] = FeatureMath.recentStd(ahorizF, accelHz, 2.0)
            } else {
                out["ahoriz_mean"] = Double.NaN
                out["ahoriz_std"] = Double.NaN
                out["ahoriz_rms"] = Double.NaN
                out["ahoriz_peakcount"] = Double.NaN
                out["ahoriz_recent_slope_2s"] = Double.NaN
                out["ahoriz_recent_std_2s"] = Double.NaN
            }

            val ar = out["avert_rms"]
            val hr = out["ahoriz_rms"]
            out["avert_to_ahoriz_ratio"] =
                if (ar != null && hr != null && ar.isFinite() && hr.isFinite() && hr > 1e-6) (ar / hr).coerceIn(0.0, 100.0)
                else Double.NaN

            // amag_ema10 / amag_emaVar10 features (finite-only like Python)
            val amagEma10Full = a?.amagEma10 ?: DoubleArray(0)
            val amagEma10U = if (amagEma10Full.size == ax.size && m > 0) gather(amagEma10Full) else DoubleArray(0)
            val amagEma10F = amagEma10U.filter { it.isFinite() }.toDoubleArray()
            if (amagEma10F.isNotEmpty()) {
                out["amag_ema10_mean"] = FeatureMath.mean(amagEma10F)
                out["amag_ema10_std"] = FeatureMath.std(amagEma10F)
                out["amag_ema10_range"] = FeatureMath.max(amagEma10F) - FeatureMath.min(amagEma10F)
                out["amag_ema10_thirddiff"] = FeatureMath.thirdDiff(amagEma10F)
            } else {
                listOf("mean","std","range","thirddiff").forEach { out["amag_ema10_$it"] = Double.NaN }
            }

            val amagEmaVar10Full = a?.amagEmaVar10 ?: DoubleArray(0)
            val amagEmaVar10U = if (amagEmaVar10Full.size == ax.size && m > 0) gather(amagEmaVar10Full) else DoubleArray(0)
            val amagEmaVar10F = amagEmaVar10U.filter { it.isFinite() }.toDoubleArray()
            if (amagEmaVar10F.isNotEmpty()) {
                out["amag_emaVar10_mean"] = FeatureMath.mean(amagEmaVar10F)
                out["amag_emaVar10_iqr"] = FeatureMath.iqr(amagEmaVar10F)
            } else {
                out["amag_emaVar10_mean"] = Double.NaN
                out["amag_emaVar10_iqr"] = Double.NaN
            }

        } else {
            // No accel window
            for (k in listOf("ax","ay","az","amag")) {
                for (s in listOf("mean","std","min","max","median","iqr","rms","range","skew","kurt")) {
                    out["${k}_$s"] = Double.NaN
                }
            }
            out["amag_halfdiff"] = Double.NaN
            out["amag_thirddiff"] = Double.NaN
            out["amag_recent_diff_25pct"] = Double.NaN
            out["amag_recent_slope_2s"] = Double.NaN
            out["amag_recent_slope_3s"] = Double.NaN
            out["amag_recent_std_2s"] = Double.NaN
            out["amag_recent_mean_2s"] = Double.NaN
            out["amag_recent_max_abs_2s"] = Double.NaN
            out["amag_ratio_recent_std"] = Double.NaN

            out["jerk_mean"] = Double.NaN
            out["jerk_std"] = Double.NaN
            out["jerk_rms"] = Double.NaN
            out["jerk_recent_std_2s"] = Double.NaN
            out["jerk_recent_max_abs_2s"] = Double.NaN

            for (s in listOf(
                "mean","std","rms","max","range","skew","kurt","peakcount",
                "halfdiff","thirddiff",
                "recent_diff_25pct","recent_slope_2s","recent_std_2s","recent_max_abs_2s","ratio_recent_std"
            )) {
                out["dyn_$s"] = Double.NaN
            }

            out["amag_peakcount"] = Double.NaN

            listOf("0.0_0.3","0.3_1.0","1.0_3.0","3.0_8.0","8.0_15.0").forEach { out["pow_$it"] = Double.NaN }
            out["amag_dominant_freq"] = Double.NaN
            out["amag_spectral_centroid"] = Double.NaN
            out["amag_spectral_bandwidth"] = Double.NaN

            listOf("0.0_0.5","0.5_2.0","2.0_5.0","5.0_10.0","10.0_20.0").forEach { out["dyn_pow_$it"] = Double.NaN }
            out["dyn_dominant_freq"] = Double.NaN
            out["dyn_spectral_centroid"] = Double.NaN

            listOf(
                "avert_mean","avert_std","avert_rms","avert_peakcount","avert_recent_slope_2s","avert_recent_std_2s",
                "ahoriz_mean","ahoriz_std","ahoriz_rms","ahoriz_peakcount","ahoriz_recent_slope_2s","ahoriz_recent_std_2s",
                "avert_to_ahoriz_ratio"
            ).forEach { out[it] = Double.NaN }

            listOf(
                "amag_ema10_mean","amag_ema10_std","amag_ema10_range","amag_ema10_thirddiff",
                "amag_emaVar10_mean","amag_emaVar10_iqr"
            ).forEach { out[it] = Double.NaN }
        }

        // Ratio features (must come after PSD)
        fun safeDiv(a: Double?, b: Double?, maxVal: Double = 100.0): Double =
            if (a == null || b == null || !a.isFinite() || !b.isFinite() || b < 1e-9) Double.NaN
            else (a / b).coerceIn(0.0, maxVal)

        out["amag_ratio_1_3__0.3_1"] = safeDiv(out["pow_1.0_3.0"], out["pow_0.3_1.0"])
        out["dyn_ratio_2_5__0.5_2"] = safeDiv(out["dyn_pow_2.0_5.0"], out["dyn_pow_0.5_2.0"])

        // FIX: ?: 0.0 doesn't handle NaN (NaN is not null), use takeIf { isFinite() } ?: 0.0
        fun getOrZero(key: String) = out[key]?.takeIf { it.isFinite() } ?: 0.0

        val pGround = getOrZero("pow_8.0_15.0") + getOrZero("pow_3.0_8.0")
        val pAir    = getOrZero("pow_0.0_0.3")  + getOrZero("pow_0.3_1.0")

        val pVibration = getOrZero("pow_3.0_8.0")
        val pStatic    = getOrZero("pow_0.0_0.3")


        out["ground_vs_air_ratio"] =
            if ((pGround + pAir) > 1e-9) pGround / (pAir + 1e-9)
            else Double.NaN

        out["vibration_vs_static_ratio"] =
            if ((pVibration + pStatic) > 1e-9) pVibration / (pStatic + 1e-9)
            else Double.NaN

        // -------------------- BARO --------------------
        val bt = b?.t ?: DoubleArray(0)
        val pRaw = b?.p ?: DoubleArray(0)
        val pF = pRaw.filter { it.isFinite() }.toDoubleArray()

        if (pF.isNotEmpty()) {
            out["p_mean"] = FeatureMath.mean(pF)
            out["p_std"] = FeatureMath.std(pF)
            out["p_min"] = FeatureMath.min(pF)
            out["p_max"] = FeatureMath.max(pF)
            out["p_median"] = FeatureMath.median(pF)
            out["p_iqr"] = FeatureMath.iqr(pF)
            out["p_range"] = FeatureMath.max(pF) - FeatureMath.min(pF)
            out["p_skew"] = FeatureMath.skew(pF)
            out["p_kurt"] = FeatureMath.kurtEx(pF)

            // Python: dpdt on finite-only p_f, padded with dp[0]
            val dt = if (baroHz > 0.0) 1.0 / baroHz else 1.0
            if (pF.size >= 2) {
                val dp = DoubleArray(pF.size) { Double.NaN }
                for (i in 1 until pF.size) dp[i] = (pF[i] - pF[i - 1]) / dt
                dp[0] = dp[1]
                out["dpdt_mean"] = FeatureMath.mean(dp)
                out["dpdt_std"] = FeatureMath.std(dp)
            } else {
                out["dpdt_mean"] = Double.NaN
                out["dpdt_std"] = Double.NaN
            }

            // Python slope uses t_b with filtering inside
            out["p_slope"] = FeatureMath.slope(bt, pRaw)

            out["p_recent_diff_25pct"] = FeatureMath.recentVsEarlierDiff(pF, 0.25)
            out["p_recent_slope_3s"] = FeatureMath.recentSlope(pF, baroHz, 3.0)
            out["p_recent_slope_5s"] = FeatureMath.recentSlope(pF, baroHz, 5.0)
            out["p_recent_std_3s"] = FeatureMath.recentStd(pF, baroHz, 3.0)
        } else {
            listOf(
                "p_mean","p_std","p_min","p_max","p_median","p_iqr","p_range","p_skew","p_kurt",
                "dpdt_mean","dpdt_std","p_slope",
                "p_recent_diff_25pct","p_recent_slope_3s","p_recent_slope_5s","p_recent_std_3s"
            ).forEach { out[it] = Double.NaN }
        }

        val vRaw = b?.dhdt ?: DoubleArray(0)
        val vF = vRaw.filter { it.isFinite() }.toDoubleArray()

        if (vF.isNotEmpty()) {
            out["dhdt_mean"] = FeatureMath.mean(vF)
            out["dhdt_std"] = FeatureMath.std(vF)

            var maxAbs = Double.NaN
            for (x in vF) {
                val aabs = abs(x)
                if (!maxAbs.isFinite() || aabs > maxAbs) maxAbs = aabs
            }
            out["dhdt_maxabs"] = maxAbs

            // plateau_frac = mean(|v| < 0.3) over finite only (Python)
            var plate = 0
            for (x in vF) if (abs(x) < Params.DHDT_PLATEAU_THR) plate++
            out["plateau_frac"] = plate.toDouble() / vF.size.toDouble()

            out["dhdt_zcr_ps"] = FeatureMath.zcrPerSec(vF, baroHz, Params.ZCR_THR)

            // Run lengths: treat missing as breaks (Python: finite & threshold mask)
            out["runlen_climb_s"] = FeatureMath.runLenSec(
                BooleanArray(vRaw.size) { i -> vRaw[i].isFinite() && vRaw[i] > Params.RUNLEN_CLIMB_THR },
                baroHz
            )
            out["runlen_descent_s"] = FeatureMath.runLenSec(
                BooleanArray(vRaw.size) { i -> vRaw[i].isFinite() && vRaw[i] < Params.RUNLEN_DESC_THR },
                baroHz
            )

            out["dhdt_recent_mean_2s"] = FeatureMath.recentMean(vF, baroHz, 2.0)
            out["dhdt_recent_mean_3s"] = FeatureMath.recentMean(vF, baroHz, 3.0)
            out["dhdt_recent_slope_3s"] = FeatureMath.recentSlope(vF, baroHz, 3.0)
            out["dhdt_recent_std_3s"] = FeatureMath.recentStd(vF, baroHz, 3.0)
            out["dhdt_recent_max_abs_2s"] = FeatureMath.recentMaxAbs(vF, baroHz, 2.0)
        } else {
            listOf(
                "dhdt_mean","dhdt_std","dhdt_maxabs","plateau_frac","dhdt_zcr_ps",
                "runlen_climb_s","runlen_descent_s",
                "dhdt_recent_mean_2s","dhdt_recent_mean_3s","dhdt_recent_slope_3s","dhdt_recent_std_3s","dhdt_recent_max_abs_2s"
            ).forEach { out[it] = Double.NaN }
        }

        // p_ema30 features (finite-only like Python)
        val pEma30Raw = b?.pEma30 ?: DoubleArray(0)
        val pEma30F = pEma30Raw.filter { it.isFinite() }.toDoubleArray()
        if (pEma30F.isNotEmpty()) {
            out["p_ema30_mean"] = FeatureMath.mean(pEma30F)
            out["p_ema30_std"] = FeatureMath.std(pEma30F)
            out["p_ema30_slope"] = FeatureMath.slope(bt, pEma30Raw)
        } else {
            out["p_ema30_mean"] = Double.NaN
            out["p_ema30_std"] = Double.NaN
            out["p_ema30_slope"] = Double.NaN
        }

        // p_ema30_dt1 features (finite-only like Python)
        val pEma30dt1Raw = b?.pEma30dt1 ?: DoubleArray(0)
        val pEma30dt1F = pEma30dt1Raw.filter { it.isFinite() }.toDoubleArray()
        if (pEma30dt1F.isNotEmpty()) {
            out["p_ema30_dt1_mean"] = FeatureMath.mean(pEma30dt1F)
            out["p_ema30_dt1_std"] = FeatureMath.std(pEma30dt1F)

            var pos = 0
            for (x in pEma30dt1F) if (x > 0.0) pos++
            out["p_ema30_dt1_posfrac"] = pos.toDouble() / pEma30dt1F.size.toDouble()
        } else {
            out["p_ema30_dt1_mean"] = Double.NaN
            out["p_ema30_dt1_std"] = Double.NaN
            out["p_ema30_dt1_posfrac"] = Double.NaN
        }

        return out
    }

    // -------------------------------------------------------------------------
    // Helpers (EMA + units)
    // -------------------------------------------------------------------------

    private fun alphaFromTau(hz: Double, tauS: Double): Double {
        if (hz <= 0.0) return 1.0
        // Python: alpha = 2 / (1 + tau_s * hz)
        return (2.0 / (1.0 + tauS * hz)).coerceIn(0.0, 1.0)
    }

    /**
     * EMA mean that:
     * - updates only on finite samples
     * - outputs NaN where input is NaN
     * (matches Python ema_series_skipnan + ema_mean wrapper)
     */
    private fun emaMeanSkipNanOutNan(x: DoubleArray, hz: Double, tauS: Double): DoubleArray {
        val a = alphaFromTau(hz, tauS)
        return emaSkipNanOutNan(x, a)
    }

    /**
     * EMA variance (tau) matching Python `ema_var()`:
     * - EMA mean `m[i]` updates only when x[i] finite; else m[i] stays NaN
     * - EMA variance state updates only when x[i] and m[i] finite; else output NaN
     */
    private fun emaVarSkipNanOutNan(xIn: DoubleArray, hz: Double, tauS: Double): DoubleArray {
        if (xIn.isEmpty()) return DoubleArray(0)
        val a = alphaFromTau(hz, tauS)
        val x = xIn

        // EMA mean stream (NaN where x is NaN)
        val m = DoubleArray(x.size) { Double.NaN }
        var mu = Double.NaN
        for (i in x.indices) {
            val xi = x[i]
            if (xi.isFinite()) {
                mu = if (!mu.isFinite()) xi else (a * xi + (1.0 - a) * mu)
                m[i] = mu
            }
        }

        // EMA variance (NaN where x is NaN)
        val v = DoubleArray(x.size) { Double.NaN }
        var var0 = 0.0
        for (i in x.indices) {
            val xi = x[i]
            val mi = m[i]
            if (xi.isFinite() && mi.isFinite()) {
                val err = xi - mi
                var0 = a * (err * err) + (1.0 - a) * var0
                v[i] = var0
            }
        }
        return v
    }

    /**
     * EMA that:
     * - updates ONLY on finite samples
     * - outputs NaN when input is NaN
     * - keeps state across NaN gaps (but does NOT invent values into the gap)
     *
     * This matches Python `ema_series_skipnan`.
     */
    private fun emaSkipNanOutNan(x: DoubleArray, alpha: Double): DoubleArray {
        if (x.isEmpty()) return DoubleArray(0)
        val a = alpha.coerceIn(0.0, 1.0)
        val out = DoubleArray(x.size) { Double.NaN }

        var have = false
        var last = 0.0

        for (i in x.indices) {
            val v = x[i]
            if (!v.isFinite()) {
                out[i] = Double.NaN
                continue
            }
            if (!have) {
                last = v
                have = true
            } else {
                last = a * v + (1.0 - a) * last
            }
            out[i] = last
        }
        return out
    }

    private fun firstFinite(x: DoubleArray): Double? {
        for (v in x) if (v.isFinite()) return v
        return null
    }

    private fun normalizeAccelUnits(
        ax: DoubleArray, ay: DoubleArray, az: DoubleArray,
        units: AccelUnits
    ): Triple<DoubleArray, DoubleArray, DoubleArray> {

        if (ax.isEmpty()) return Triple(ax.copyOf(), ay.copyOf(), az.copyOf())

        val scale = when (units) {
            AccelUnits.M_S2 -> 1.0
            AccelUnits.G -> G0
            AccelUnits.AUTO -> {
                // Heuristic: magnitude near 1 => likely g-units; near 9.8 => m/s^2.
                val amag = DoubleArray(ax.size) { i ->
                    val x = ax[i]; val y = ay[i]; val z = az[i]
                    if (!x.isFinite() || !y.isFinite() || !z.isFinite()) Double.NaN
                    else sqrt(x * x + y * y + z * z)
                }
                val med = FeatureMath.median(amag)
                if (med in 0.6..1.4) G0 else 1.0
            }
        }

        if (scale == 1.0) return Triple(ax.copyOf(), ay.copyOf(), az.copyOf())

        val axS = DoubleArray(ax.size) { i -> ax[i] * scale }
        val ayS = DoubleArray(ay.size) { i -> ay[i] * scale }
        val azS = DoubleArray(az.size) { i -> az[i] * scale }
        return Triple(axS, ayS, azS)
    }
}
