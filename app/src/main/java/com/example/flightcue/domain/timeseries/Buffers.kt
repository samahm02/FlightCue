package com.example.flightcue.domain.timeseries

/**
 * Thread-safe circular buffer for accelerometer samples.
 * Capacity covers ~100s at the maximum expected device rate (200 Hz).
 * snapshotSince() returns a time-sorted slice for safe use on the detector thread.
 */
class AccelBuffer(private val cap: Int = 20_000) {   // 100s × 200Hz max device rate
    private val lock = Any()
    private val t  = DoubleArray(cap)
    private val ax = DoubleArray(cap)
    private val ay = DoubleArray(cap)
    private val az = DoubleArray(cap)
    private var n = 0; private var head = 0

    fun push(ts: Double, x: Double, y: Double, z: Double) = synchronized(lock) {
        t[head] = ts; ax[head] = x; ay[head] = y; az[head] = z
        head = (head + 1) % cap
        n = minOf(cap, n + 1)
    }

    fun snapshotSince(minTs: Double): AccelSlice = synchronized(lock) {
        if (n == 0) return@synchronized AccelSlice(
            DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0)
        )
        val start = (head - n + cap) % cap

        var i0 = 0
        while (i0 < n) {
            if (t[(start + i0) % cap] >= minTs) break
            i0++
        }
        val m = n - i0
        if (m <= 0) return@synchronized AccelSlice(
            DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0)
        )

        val outT = DoubleArray(m)
        val outX = DoubleArray(m); val outY = DoubleArray(m); val outZ = DoubleArray(m)
        for (j in 0 until m) {
            val idx = (start + i0 + j) % cap
            outT[j] = t[idx]; outX[j] = ax[idx]; outY[j] = ay[idx]; outZ[j] = az[idx]
        }

        sortByTimestamp(outT, outX, outY, outZ, m)

        AccelSlice(outT, outX, outY, outZ)
    }

    private fun sortByTimestamp(t: DoubleArray, x: DoubleArray, y: DoubleArray, z: DoubleArray, n: Int) {
        // Insertion sort: O(n) when nearly sorted (out-of-order is rare per Reinholdt)
        for (i in 1 until n) {
            val ti = t[i]; val xi = x[i]; val yi = y[i]; val zi = z[i]
            var j = i - 1
            while (j >= 0 && t[j] > ti) {
                t[j + 1] = t[j]; x[j + 1] = x[j]; y[j + 1] = y[j]; z[j + 1] = z[j]
                j--
            }
            t[j + 1] = ti; x[j + 1] = xi; y[j + 1] = yi; z[j + 1] = zi
        }
    }
}

/**
 * Thread-safe circular buffer for barometer samples.
 * Capacity covers ~160s at the maximum expected device rate (25 Hz).
 */
class BaroBuffer(private val cap: Int = 4_000) {     // 160s × 25Hz max device rate
    private val lock = Any()
    private val t = DoubleArray(cap); private val p = DoubleArray(cap)
    private var n = 0; private var head = 0

    fun push(ts: Double, pressureHpa: Double) = synchronized(lock) {
        t[head] = ts; p[head] = pressureHpa
        head = (head + 1) % cap
        n = minOf(cap, n + 1)
    }

    fun snapshotSince(minTs: Double): BaroSlice = synchronized(lock) {
        if (n == 0) return@synchronized BaroSlice(DoubleArray(0), DoubleArray(0))
        val start = (head - n + cap) % cap

        var i0 = 0
        while (i0 < n) {
            if (t[(start + i0) % cap] >= minTs) break
            i0++
        }
        val m = n - i0
        if (m <= 0) return@synchronized BaroSlice(DoubleArray(0), DoubleArray(0))

        val outT = DoubleArray(m); val outP = DoubleArray(m)
        for (j in 0 until m) {
            val idx = (start + i0 + j) % cap
            outT[j] = t[idx]; outP[j] = p[idx]
        }

        sortByTimestamp(outT, outP, m)

        BaroSlice(outT, outP)
    }

    // Insertion sort: O(n) on nearly-sorted data; out-of-order timestamps are rare in practice.
    private fun sortByTimestamp(t: DoubleArray, p: DoubleArray, n: Int) {
        for (i in 1 until n) {
            val ti = t[i]; val pi = p[i]
            var j = i - 1
            while (j >= 0 && t[j] > ti) {
                t[j + 1] = t[j]; p[j + 1] = p[j]; j--
            }
            t[j + 1] = ti; p[j + 1] = pi
        }
    }
}

class AccelSlice(val t: DoubleArray, val ax: DoubleArray, val ay: DoubleArray, val az: DoubleArray)
class BaroSlice (val t: DoubleArray, val p: DoubleArray)
