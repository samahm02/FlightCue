package com.example.flightcue.domain.timeseries


/**
 * Resamples irregular sensor data onto a fixed-rate causal grid.
 *
 * Each grid point holds the mean of all raw samples that fall within
 * its bin. If a bin has no samples, the last known value is carried
 * forward — but only up to gapFactor × median sample interval. Beyond
 * that gap the output is NaN. No interpolation is used.
 *
 * Matches the Python causal resampling pipeline.
 */
object Resample {

    fun accel(src: AccelSlice, hz: Double, gapFactor: Double): AccelResampled {
        if (src.t.isEmpty()) return AccelResampled(DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0))
        val (grid, ax) = causalResample(src.t, src.ax, hz, gapFactor)
        val (_,   ay) = causalResample(src.t, src.ay, hz, gapFactor, grid)
        val (_,   az) = causalResample(src.t, src.az, hz, gapFactor, grid)
        return AccelResampled(grid, ax, ay, az)
    }

    fun baro(src: BaroSlice, hz: Double, gapFactor: Double): BaroResampled {
        if (src.t.isEmpty()) return BaroResampled(DoubleArray(0), DoubleArray(0))
        val (grid, p) = causalResample(src.t, src.p, hz, gapFactor)
        return BaroResampled(grid, p)
    }

    private fun medianPosDiff(t: DoubleArray): Double? {
        if (t.size < 2) return null
        val diffs = ArrayList<Double>(t.size)
        for (i in 1 until t.size) {
            val d = t[i] - t[i - 1]
            if (d > 0.0 && d.isFinite()) diffs.add(d)
        }
        if (diffs.isEmpty()) return null
        diffs.sort()
        val m = diffs.size
        return if (m % 2 == 1) diffs[m / 2] else 0.5 * (diffs[m / 2 - 1] + diffs[m / 2])
    }


    private fun buildGrid(t: DoubleArray, hz: Double): DoubleArray {
        val step = 1.0 / hz

        var tMin = Double.POSITIVE_INFINITY
        var tMax = Double.NEGATIVE_INFINITY
        for (ti in t) {
            if (!ti.isFinite()) continue
            if (ti < tMin) tMin = ti
            if (ti > tMax) tMax = ti
        }
        if (!tMin.isFinite() || !tMax.isFinite()) return DoubleArray(0)

        // Grid starts at the first raw sample (matches Python's t0 = t_raw[0]).
        val t0 = tMin
        val n = maxOf(1, kotlin.math.floor((tMax - t0) / step + 0.5).toInt() + 1)
        return DoubleArray(n) { i -> t0 + i * step }
    }



    /**
     * Causal bin resample of one channel.
     * If `gridOverride` is provided, the output aligns to that grid (used for ay/az to share grid).
     */
    private fun causalResample(
        tRaw: DoubleArray,
        xRaw: DoubleArray,
        hz: Double,
        gapFactor: Double,
        gridOverride: DoubleArray? = null
    ): Pair<DoubleArray, DoubleArray> {

        val grid = gridOverride ?: buildGrid(tRaw, hz)
        val step = 1.0 / hz
        val t0 = grid.first()
        val tLast = grid.last()

        // Bounded ffill horizon (Python: big_gap_factor * median_dt).
        // If median_dt is unavailable, falling back to step avoids "ffill disabled" behavior.
        val md = medianPosDiff(tRaw)
        val mdEff = if (md != null && md > 0.0) md else step
        val maxHoldS = gapFactor * mdEff

        val sum = DoubleArray(grid.size)
        val cnt = IntArray(grid.size)
        val lastTimeInBin = DoubleArray(grid.size) { Double.NaN }

        // Left-closed bins [tg, tg+step) matching Python's floor((t - t0) / step).
        fun binIndex(t: Double): Int {
            val idx = kotlin.math.floor((t - t0) / step).toInt()
            return idx.coerceIn(0, grid.lastIndex)
        }

        val n = minOf(tRaw.size, xRaw.size)
        for (i in 0 until n) {
            val ti = tRaw[i]
            val xi = xRaw[i]
            if (!ti.isFinite()) continue

            // Safety: ignore samples way outside the grid.
            // (Shouldn't happen if grid is built from the same tRaw, but helps if callers pass overrides.)
            if (ti <= (t0 - step) || ti > tLast) continue

            val bi = binIndex(ti)
            if (xi.isFinite()) {
                sum[bi] += xi
                cnt[bi] += 1
                // Critical: protect against out-of-order timestamps (batching can reorder slightly).
                val prev = lastTimeInBin[bi]
                lastTimeInBin[bi] = if (!prev.isFinite() || ti > prev) ti else prev
            }
        }

        val y = DoubleArray(grid.size) { Double.NaN }
        var lastVal = Double.NaN
        var lastObsT = Double.NEGATIVE_INFINITY

        for (i in grid.indices) {
            if (cnt[i] > 0) {
                val v = sum[i] / cnt[i].toDouble()
                y[i] = v
                lastVal = v

                val lt = lastTimeInBin[i]
                if (lt.isFinite()) lastObsT = lt
            } else {
                // bounded forward-fill only
                if (lastVal.isFinite() && maxHoldS > 0.0) {
                    val tg = grid[i]
                    if ((tg - lastObsT) <= maxHoldS) y[i] = lastVal
                }
            }
        }

        return grid to y
    }
}

class AccelResampled(val t: DoubleArray, val ax: DoubleArray, val ay: DoubleArray, val az: DoubleArray)
class BaroResampled(val t: DoubleArray, val p: DoubleArray)
