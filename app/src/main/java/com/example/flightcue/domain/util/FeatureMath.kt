package com.example.flightcue.domain.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Python-parity helpers: all stats ignore non-finite values.
// Notes:
// - ddof=0 everywhere (matches NumPy std(..., ddof=0))
// - slope filters finite pairs
object FeatureMath {

    // ---------- finite-only utilities ----------

    private fun finiteCopy(x: DoubleArray): DoubleArray {
        var c = 0
        for (v in x) if (v.isFinite()) c++
        if (c == 0) return DoubleArray(0)
        val out = DoubleArray(c)
        var j = 0
        for (v in x) if (v.isFinite()) out[j++] = v
        return out
    }

    fun mean(x: DoubleArray): Double {
        var s = 0.0
        var c = 0
        for (v in x) if (v.isFinite()) { s += v; c++ }
        return if (c > 0) s / c else Double.NaN
    }

    fun std(x: DoubleArray): Double {
        var s = 0.0
        var c = 0
        for (v in x) if (v.isFinite()) { s += v; c++ }
        if (c == 0) return Double.NaN
        val m = s / c
        var s2 = 0.0
        for (v in x) if (v.isFinite()) {
            val d = v - m
            s2 += d * d
        }
        return sqrt(s2 / c) // ddof=0
    }

    fun min(x: DoubleArray): Double {
        var best = Double.POSITIVE_INFINITY
        var ok = false
        for (v in x) if (v.isFinite()) {
            ok = true
            if (v < best) best = v
        }
        return if (ok) best else Double.NaN
    }

    fun max(x: DoubleArray): Double {
        var best = Double.NEGATIVE_INFINITY
        var ok = false
        for (v in x) if (v.isFinite()) {
            ok = true
            if (v > best) best = v
        }
        return if (ok) best else Double.NaN
    }

    fun median(x: DoubleArray): Double {
        val a = finiteCopy(x)
        if (a.isEmpty()) return Double.NaN
        a.sort()
        val n = a.size
        return if (n % 2 == 1) a[n / 2] else 0.5 * (a[n / 2 - 1] + a[n / 2])
    }

    private fun pctSorted(sorted: DoubleArray, p: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val pos = (p / 100.0) * (sorted.size - 1)
        val lo = floor(pos).toInt()
        val hi = ceil(pos).toInt()
        if (lo == hi) return sorted[lo]
        val w = pos - lo
        return (1 - w) * sorted[lo] + w * sorted[hi]
    }

    fun iqr(x: DoubleArray): Double {
        val a = finiteCopy(x)
        if (a.isEmpty()) return Double.NaN
        a.sort()
        return pctSorted(a, 75.0) - pctSorted(a, 25.0)
    }

    fun rms(x: DoubleArray): Double {
        var s2 = 0.0
        var c = 0
        for (v in x) if (v.isFinite()) {
            s2 += v * v
            c++
        }
        return if (c > 0) sqrt(s2 / c) else Double.NaN
    }

    fun skew(x: DoubleArray): Double {
        val a = finiteCopy(x)
        val n = a.size
        if (n < 3) return Double.NaN
        val m = mean(a)
        val sd = std(a)
        if (!sd.isFinite() || sd == 0.0) return Double.NaN
        var m3 = 0.0
        for (v in a) m3 += (v - m).pow(3.0)
        m3 /= n.toDouble()
        return m3 / sd.pow(3.0)
    }

    fun kurtEx(x: DoubleArray): Double {
        val a = finiteCopy(x)
        val n = a.size
        if (n < 4) return Double.NaN
        val m = mean(a)
        val sd = std(a)
        if (!sd.isFinite() || sd == 0.0) return Double.NaN
        var m4 = 0.0
        for (v in a) m4 += (v - m).pow(4.0)
        m4 /= n.toDouble()
        return (m4 / sd.pow(4.0)) - 3.0
    }

    fun halfDiff(x: DoubleArray): Double {
        val a = finiteCopy(x)
        val n = a.size
        if (n < 4) return Double.NaN
        val h = n / 2
        val first = a.copyOfRange(0, h)
        val second = a.copyOfRange(h, n)
        return mean(second) - mean(first)
    }

    fun thirdDiff(x: DoubleArray): Double {
        val a = finiteCopy(x)
        val n = a.size
        if (n < 6) return Double.NaN
        val a1 = n / 3
        val b1 = 2 * n / 3
        val first = a.copyOfRange(0, a1)
        val last = a.copyOfRange(b1, n)
        return mean(last) - mean(first)
    }

    fun peakCount(x: DoubleArray, k: Double = 2.0): Double {
        val a = finiteCopy(x)
        if (a.isEmpty()) return 0.0
        val sd = std(a)
        if (!sd.isFinite() || sd == 0.0) return 0.0
        val m = mean(a)
        val thr = k * sd
        var c = 0
        for (v in a) if (abs(v - m) > thr) c++
        return c.toDouble()
    }

    // Python parity: NaNs treated as 0, zero-state does not count as sign.
    fun zcrPerSec(xIn: DoubleArray, hz: Double, thr: Double): Double {
        if (xIn.size < 2) return 0.0
        val x = DoubleArray(xIn.size) { i -> if (xIn[i].isFinite()) xIn[i] else 0.0 }

        fun sig(v: Double): Int = when {
            abs(v) <= thr -> 0
            v > 0.0 -> 1
            else -> -1
        }

        var zc = 0
        var prev = sig(x[0])
        for (i in 1 until x.size) {
            val cur = sig(x[i])
            if (cur == 0 || prev == 0) {
                prev = cur
                continue
            }
            if (cur != prev) zc++
            prev = cur
        }

        val dur = x.size / hz
        return if (dur > 0) zc.toDouble() / dur else 0.0
    }

    fun runLenSec(mask: BooleanArray, hz: Double): Double {
        var best = 0
        var cur = 0
        for (v in mask) {
            if (v) {
                cur++
                if (cur > best) best = cur
            } else {
                cur = 0
            }
        }
        return best.toDouble() / hz
    }

    fun slope(t: DoubleArray, y: DoubleArray): Double {
        val n = min(t.size, y.size)
        if (n < 2) return Double.NaN

        var c = 0
        var ts = 0.0
        var ys = 0.0
        for (i in 0 until n) {
            val ti = t[i]
            val yi = y[i]
            if (ti.isFinite() && yi.isFinite()) {
                ts += ti
                ys += yi
                c++
            }
        }
        if (c < 2) return Double.NaN
        val tm = ts / c
        val ym = ys / c

        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val ti = t[i]
            val yi = y[i]
            if (ti.isFinite() && yi.isFinite()) {
                val dt = ti - tm
                num += dt * (yi - ym)
                den += dt * dt
            }
        }
        return if (den == 0.0) Double.NaN else num / den
    }

    // ---------- FFT / band powers ----------
    // We use radix-2 FFT with zero padding to next pow2 (fast).
    // Hann window is applied over the true length n (Python does Hann over n).
    // This is a close approximation to NumPy rfft for relative band power.



    fun rfftPower2(xIn: DoubleArray, fs: Double): Pair<DoubleArray, DoubleArray> {
        val x = finiteCopy(xIn)
        val n = x.size
        if (n < 8) return DoubleArray(0) to DoubleArray(0)

        val mu = mean(x)
        // Hann window + mean removal — matches Python np.hanning(n) and x - mean(x)
        val w = DoubleArray(n) { i ->
            val v = x[i] - mu
            val hann = 0.5 * (1.0 - cos(2.0 * Math.PI * i.toDouble() / (n - 1).toDouble()))
            v * hann
        }

        // Direct DFT — exact n-point, no zero-padding, matches Python np.fft.rfft
        val outN = n / 2 + 1
        val f = DoubleArray(outN) { k -> k.toDouble() * fs / n.toDouble() }
        val p = DoubleArray(outN) { k ->
            var re = 0.0
            var im = 0.0
            val step = -2.0 * Math.PI * k / n
            for (j in 0 until n) {
                val angle = step * j
                re += w[j] * cos(angle)
                im += w[j] * sin(angle)
            }
            re * re + im * im
        }
        return f to p
    }

    fun rfftPower(xIn: DoubleArray, fs: Double): Pair<DoubleArray, DoubleArray> {
        val x = finiteCopy(xIn)
        val n = x.size
        if (n < 8) return DoubleArray(0) to DoubleArray(0)

        val mu = mean(x)
        val w = DoubleArray(n) { i ->
            val v = x[i] - mu
            val hann = 0.5 * (1.0 - cos(2.0 * Math.PI * i.toDouble() / (n - 1).toDouble()))
            v * hann
        }

        val outN = n / 2 + 1
        // Match numpy rfftfreq(n, d=1.0/fs) exactly:
        val d = 1.0 / fs
        val freqVal = 1.0 / (n.toDouble() * d)
        val f = DoubleArray(outN) { k -> k.toDouble() * freqVal }
        val p = DoubleArray(outN) { k ->
            var re = 0.0
            var im = 0.0
            val step = -2.0 * Math.PI * k / n  // this "step" is untouched
            for (j in 0 until n) {
                val angle = step * j
                re += w[j] * cos(angle)
                im += w[j] * sin(angle)
            }
            re * re + im * im
        }
        return f to p
    }

    fun relBandPowers(x: DoubleArray, fs: Double, bands: Array<Pair<Double, Double>>): Map<String, Double> {
        val (f, p) = rfftPower(x, fs)
        val out = LinkedHashMap<String, Double>(bands.size)

        if (f.isEmpty()) {
            for ((lo, hi) in bands) out[key(lo, hi)] = Double.NaN
            return out
        }

        val tot = p.sum()
        if (!tot.isFinite() || tot <= 0.0) {
            for ((lo, hi) in bands) out[key(lo, hi)] = Double.NaN
            return out
        }

        for ((lo, hi) in bands) {
            var s = 0.0
            for (i in f.indices) {
                if (f[i] >= lo && f[i] < hi) s += p[i]
            }
            out[key(lo, hi)] = s / tot
        }
        return out
    }
    private fun key(lo: Double, hi: Double): String =
        "pow_${String.format(Locale.US, "%.1f", lo)}_${String.format(Locale.US, "%.1f", hi)}"


    // ---------- RECENCY FEATURES ----------

    /**
     * Compute slope over the last `recentS` seconds of data.
     * Python: recent_slope(arr, hz, recent_s)
     */
    fun recentSlope(arr: DoubleArray, hz: Double, recentS: Double = 2.0): Double {
        if (arr.isEmpty()) return Double.NaN
        val recentSamples = min(arr.size, kotlin.math.max(3, (recentS * hz).toInt()))
        val recent = arr.copyOfRange(kotlin.math.max(0, arr.size - recentSamples), arr.size)

        val t = DoubleArray(recent.size) { i -> i.toDouble() / hz }
        return slope(t, recent)
    }

    /**
     * Compute std over the last `recentS` seconds of data.
     * Python: recent_std(arr, hz, recent_s)
     */
    fun recentStd(arr: DoubleArray, hz: Double, recentS: Double = 2.0): Double {
        if (arr.isEmpty()) return Double.NaN
        val recentSamples = min(arr.size, kotlin.math.max(3, (recentS * hz).toInt()))
        val recent = arr.copyOfRange(kotlin.math.max(0, arr.size - recentSamples), arr.size)
        return std(recent)
    }

    /**
     * Compute mean over the last `recentS` seconds of data.
     * Python: recent_mean(arr, hz, recent_s)
     */
    fun recentMean(arr: DoubleArray, hz: Double, recentS: Double = 2.0): Double {
        if (arr.isEmpty()) return Double.NaN
        val recentSamples = min(arr.size, kotlin.math.max(1, (recentS * hz).toInt()))
        val recent = arr.copyOfRange(kotlin.math.max(0, arr.size - recentSamples), arr.size)
        return mean(recent)
    }

    /**
     * Compute max(abs(x)) over the last `recentS` seconds of data.
     * Python: recent_max_abs(arr, hz, recent_s)
     */
    fun recentMaxAbs(arr: DoubleArray, hz: Double, recentS: Double = 2.0): Double {
        if (arr.isEmpty()) return Double.NaN
        val recentSamples = min(arr.size, kotlin.math.max(1, (recentS * hz).toInt()))
        val recent = arr.copyOfRange(kotlin.math.max(0, arr.size - recentSamples), arr.size)

        var maxAbs = Double.NEGATIVE_INFINITY
        var found = false
        for (v in recent) {
            if (v.isFinite()) {
                found = true
                val a = abs(v)
                if (a > maxAbs) maxAbs = a
            }
        }
        return if (found) maxAbs else Double.NaN
    }

    /**
     * Compare mean of recent fraction vs earlier portion.
     * Python: recent_vs_earlier_diff(arr, recent_frac)
     */
    fun recentVsEarlierDiff(arr: DoubleArray, recentFrac: Double = 0.25): Double {
        val a = finiteCopy(arr)
        val n = a.size
        if (n < 4) return Double.NaN

        val recentStart = ((n.toDouble() * (1.0 - recentFrac)).toInt()).coerceAtLeast(1)
        if (recentStart >= n) return Double.NaN

        val earlier = a.copyOfRange(0, recentStart)
        val recent = a.copyOfRange(recentStart, n)

        return mean(recent) - mean(earlier)
    }

    /**
     * Ratio of recent stat to window stat.
     * Python: ratio_recent_to_window(arr, hz, recent_s, stat)
     */
    fun ratioRecentToWindow(arr: DoubleArray, hz: Double, recentS: Double = 2.0, stat: String = "std", eps: Double = 1e-6): Double {
        val a = finiteCopy(arr)
        if (a.size < 4) return Double.NaN

        val recentSamples = min(arr.size, kotlin.math.max(3, (recentS * hz).toInt()))
        val recent = arr.copyOfRange(kotlin.math.max(0, arr.size - recentSamples), arr.size)
        val recentFinite = finiteCopy(recent)

        if (recentFinite.size < 2) return Double.NaN

        val recentVal = when (stat) {
            "std" -> std(recentFinite)
            "mean" -> {
                var s = 0.0
                for (v in recentFinite) s += abs(v)
                s / recentFinite.size
            }
            "rms" -> rms(recentFinite)
            else -> return Double.NaN
        }

        val windowVal = when (stat) {
            "std" -> std(a)
            "mean" -> {
                var s = 0.0
                for (v in a) s += abs(v)
                s / a.size
            }
            "rms" -> rms(a)
            else -> return Double.NaN
        }

        if (!windowVal.isFinite() || windowVal < eps) return Double.NaN
        return (recentVal / windowVal).coerceIn(0.0, 100.0)
    }

    // ---------- SPECTRAL ANALYSIS ----------

    /**
     * Find dominant frequency (frequency with max power).
     * Python: dominant_frequency(x, fs, min_freq)
     */
    fun dominantFrequency(x: DoubleArray, fs: Double, minFreq: Double = 0.1): Double {
        val (f, p) = rfftPower(x, fs)
        if (f.isEmpty()) return Double.NaN

        var maxPower = Double.NEGATIVE_INFINITY
        var maxFreq = Double.NaN

        for (i in f.indices) {
            if (f[i] >= minFreq && p[i] > maxPower) {
                maxPower = p[i]
                maxFreq = f[i]
            }
        }

        return if (maxPower > 0.0) maxFreq else Double.NaN
    }

    /**
     * Compute spectral centroid (weighted mean frequency).
     * Python: spectral_centroid(x, fs, min_freq)
     */
    fun spectralCentroid(x: DoubleArray, fs: Double, minFreq: Double = 0.1): Double {
        val (f, p) = rfftPower(x, fs)
        if (f.isEmpty()) return Double.NaN

        var sumPower = 0.0
        var sumWeighted = 0.0

        for (i in f.indices) {
            if (f[i] >= minFreq) {
                sumPower += p[i]
                sumWeighted += p[i] * f[i]
            }
        }

        return if (sumPower > 0.0) sumWeighted / sumPower else Double.NaN
    }

    /**
     * Compute spectral bandwidth (spread around centroid).
     * Python: spectral_bandwidth(x, fs, min_freq)
     */
    fun spectralBandwidth(x: DoubleArray, fs: Double, minFreq: Double = 0.1): Double {
        val (f, p) = rfftPower(x, fs)
        if (f.isEmpty()) return Double.NaN

        var sumPower = 0.0
        var sumWeighted = 0.0
        for (i in f.indices) {
            if (f[i] >= minFreq) {
                sumPower += p[i]
                sumWeighted += p[i] * f[i]
            }
        }
        if (sumPower <= 0.0) return Double.NaN
        val centroid = sumWeighted / sumPower

        var sumVariance = 0.0
        for (i in f.indices) {
            if (f[i] >= minFreq) {
                val diff = f[i] - centroid
                sumVariance += p[i] * diff * diff
            }
        }
        return sqrt(sumVariance / sumPower)
    }


}
