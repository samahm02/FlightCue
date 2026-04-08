package com.example.flightcue.debug

import android.util.Log
import java.util.Locale
import kotlin.math.sqrt

object TakeoffDebug {
    private const val TAG = "TAKEOFF_DBG"

    /** Flip this on/off from Params / BuildConfig.DEBUG as you prefer */
    @Volatile var enabled: Boolean = true

    /** Set >0 to rate-limit logs (e.g., 2500ms = once per hop for hop=2.5s) */
    @Volatile var minIntervalMs: Long = 0L

    private var lastLogMs: Long = 0L

    /**
     * Call this once per TAKEOFF inference hop.
     *
     * ax/ay/az = the accel samples used to build the CURRENT window features
     * accelHz  = accel sampling rate (Hz)
     * baroP    = optional pressure samples used to build the CURRENT window features
     * baroHz   = baro sampling rate (Hz)
     */
    fun logHop(
        atSec: Double,
        p: Float,
        ax: FloatArray,
        ay: FloatArray,
        az: FloatArray,
        accelHz: Double,
        baroP: FloatArray? = null,
        baroHz: Double = 0.0
    ) {
        if (!enabled) return

        val now = android.os.SystemClock.elapsedRealtime()
        if (minIntervalMs > 0 && (now - lastLogMs) < minIntervalMs) return
        lastLogMs = now

        val (magMean, magStd, jerkRms) = accelMagStatsAndJerk(ax, ay, az, accelHz)
        val baroSlope = if (baroP != null && baroP.size >= 2 && baroHz > 0.0) {
            linearSlopePerSec(baroP, baroHz) // Pa/s (negative typically means climb)
        } else null

        val msg = if (baroSlope != null) {
            String.format(
                Locale.US,
                "t=%.1fs p=%.3f |a|_mean=%.4f |a|_std=%.4f jerk_rms=%.4f m/s^3 baro_slope=%.3f Pa/s",
                atSec, p, magMean, magStd, jerkRms, baroSlope
            )
        } else {
            String.format(
                Locale.US,
                "t=%.1fs p=%.3f |a|_mean=%.4f |a|_std=%.4f jerk_rms=%.4f m/s^3 baro_slope=n/a",
                atSec, p, magMean, magStd, jerkRms
            )
        }

        Log.i(TAG, msg)
    }

    private data class AccelStats(val mean: Double, val std: Double, val jerkRms: Double)

    /**
     * Computes:
     *  - |a| mean/std over the window
     *  - jerk proxy = RMS of d|a|/dt (units ~ m/s^3 if ax/ay/az are m/s^2)
     */
    private fun accelMagStatsAndJerk(
        ax: FloatArray,
        ay: FloatArray,
        az: FloatArray,
        accelHz: Double
    ): AccelStats {
        val n = minOf(ax.size, ay.size, az.size)
        if (n <= 1) return AccelStats(0.0, 0.0, 0.0)

        // First pass: mean(|a|)
        var sum = 0.0
        var prevMag = 0.0
        var sumJerk2 = 0.0

        // We compute mag and jerk in one pass; we also need mean for std, so we’ll store mags.
        val mags = DoubleArray(n)

        for (i in 0 until n) {
            val x = ax[i].toDouble()
            val y = ay[i].toDouble()
            val z = az[i].toDouble()
            val mag = sqrt(x * x + y * y + z * z)
            mags[i] = mag
            sum += mag

            if (i > 0) {
                val dMag = (mag - prevMag) * accelHz // derivative per second
                sumJerk2 += dMag * dMag
            }
            prevMag = mag
        }

        val mean = sum / n

        // Second pass: std(|a|)
        var varSum = 0.0
        for (i in 0 until n) {
            val d = mags[i] - mean
            varSum += d * d
        }
        val std = sqrt(varSum / n)

        val jerkRms = sqrt(sumJerk2 / (n - 1))
        return AccelStats(mean, std, jerkRms)
    }

    /**
     * Linear regression slope of pressure over time for the window.
     * Returns slope in Pa/s.
     */
    private fun linearSlopePerSec(p: FloatArray, hz: Double): Double {
        val n = p.size
        if (n < 2) return 0.0

        // t_i = i / hz
        var sumT = 0.0
        var sumP = 0.0
        var sumTT = 0.0
        var sumTP = 0.0

        for (i in 0 until n) {
            val t = i.toDouble() / hz
            val pi = p[i].toDouble()
            sumT += t
            sumP += pi
            sumTT += t * t
            sumTP += t * pi
        }

        val denom = n * sumTT - sumT * sumT
        if (denom == 0.0) return 0.0

        // slope = (n*sumTP - sumT*sumP) / (n*sumTT - sumT^2)
        return (n * sumTP - sumT * sumP) / denom
    }
}
