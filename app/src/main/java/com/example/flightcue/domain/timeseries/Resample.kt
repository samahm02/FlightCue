package com.example.flightcue.domain.timeseries

import kotlin.math.max

// Irregular → fixed-rate resampling with linear interp and capped gap-filling (parity with script).
object Resample {

    fun accel(src: AccelSlice, hz: Double, gapFactor: Double): AccelResampled {
        if (src.t.isEmpty()) return AccelResampled(DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0))
        val (grid, limit) = gridAndLimit(src.t, hz, gapFactor)
        return AccelResampled(
            grid,
            interp(src.t, src.ax, grid, limit),
            interp(src.t, src.ay, grid, limit),
            interp(src.t, src.az, grid, limit)
        )
    }
    fun baro(src: BaroSlice, hz: Double, gapFactor: Double): BaroResampled {
        if (src.t.isEmpty()) return BaroResampled(DoubleArray(0), DoubleArray(0))
        val (grid, limit) = gridAndLimit(src.t, hz, gapFactor)
        return BaroResampled(grid, interp(src.t, src.p, grid, limit))
    }

    private fun gridAndLimit(t: DoubleArray, hz: Double, gapFactor: Double): Pair<DoubleArray,Int> {
        val step = 1.0 / hz
        val n = max(1, (((t.last()-t.first())/step).toInt()+1))
        val grid = DoubleArray(n){ i -> t.first()+i*step }
        val med = medianPosDiff(t)
        val big = gapFactor * med
        val limit = max(1, (big/step).toInt())
        return grid to limit
    }
    private fun medianPosDiff(t: DoubleArray): Double {
        if (t.size<2) return 1.0
        val d = buildList { for (i in 1 until t.size) { val v=t[i]-t[i-1]; if (v>0) add(v) } }.sorted()
        if (d.isEmpty()) return 1.0
        val m=d.size; return if (m%2==1) d[m/2] else 0.5*(d[m/2-1]+d[m/2])
    }
    private fun interp(tx: DoubleArray, x: DoubleArray, grid: DoubleArray, limit: Int): DoubleArray {
        val y = DoubleArray(grid.size) { Double.NaN }
        var j=0
        for (i in grid.indices) {
            val tg=grid[i]
            while (j+1<tx.size && tx[j+1] <= tg) j++
            val j0=j; val j1=(j+1).coerceAtMost(tx.lastIndex)
            if (tx[j0] <= tg && tg <= tx[j1]) {
                val w = if (tx[j1]==tx[j0]) 0.0 else (tg-tx[j0])/(tx[j1]-tx[j0])
                y[i] = (1-w)*x[j0] + w*x[j1]
            }
        }
        // fill small gaps up to 'limit'
        var last = -1_000_000
        for (i in y.indices) if (!y[i].isNaN()) { last=i; break }
        for (i in y.indices) {
            if (y[i].isNaN()) continue
            if (last+1 < i) {
                val gap = i-last-1
                if (gap <= limit) {
                    val prev=y[last]; val cur=y[i]
                    for (k in 1..gap) {
                        val w = k.toDouble()/(gap+1).toDouble()
                        y[last+k] = (1-w)*prev + w*cur
                    }
                }
            }
            last=i
        }
        return y
    }
}

data class AccelResampled(val t: DoubleArray, val ax: DoubleArray, val ay: DoubleArray, val az: DoubleArray)
data class BaroResampled (val t: DoubleArray, val p: DoubleArray)
