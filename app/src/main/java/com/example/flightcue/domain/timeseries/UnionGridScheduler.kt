package com.example.flightcue.domain.timeseries

import kotlin.math.floor
import kotlin.math.round

/**
 * Emits window end-times (t_end) in chronological order for a union of grids.
 *
 * Python ground truth per grid:
 *   ws = segStart + k*hop
 *   we = ws + win = segStart + win + k*hop
 *
 * This scheduler tracks next "we" per grid.
 */
class UnionGridScheduler(
    private val grids: List<GridSpec>
) {
    init {
        require(grids.isNotEmpty()) { "UnionGridScheduler: empty grids" }
    }

    private val nextEndById = HashMap<String, Double>(grids.size)
    private var initialized = false
    private var originSec: Double = Double.NaN

    val minHopSec: Double = grids.minOf { it.hopSec }
    val maxWinSec: Double = grids.maxOf { it.winSec }

    private fun round6(x: Double): Double = round(x * 1e6) / 1e6

    /** Sets the stream origin and initialises each grid's first window end-time. */
    fun setOrigin(originSec: Double) {
        require(originSec.isFinite()) { "UnionGridScheduler.setOrigin: originSec invalid=$originSec" }
        this.originSec = originSec

        nextEndById.clear()
        for (g in grids) {
            nextEndById[g.id] = round6(originSec + g.winSec)  // FIRST end from origin
        }
        initialized = true
    }

    /** Resets scheduler state; requires setOrigin() before next use. */
    fun reset() {
        nextEndById.clear()
        initialized = false
    }

    private fun endAtOrBefore(nowSec: Double, g: GridSpec): Double {
        val firstEnd = originSec + g.winSec
        if (nowSec < firstEnd) return round6(firstEnd)

        val k = floor((nowSec - firstEnd) / g.hopSec)
        return round6(firstEnd + k * g.hopSec)
    }

    fun ensureInit(nowSec: Double) {
        if (initialized) return
        require(originSec.isFinite()) {
            "UnionGridScheduler.ensureInit: originSec not set. Call setOrigin(segStartSec) first."
        }
        for (g in grids) {
            nextEndById[g.id] = endAtOrBefore(nowSec, g)
        }
        initialized = true
    }

    /**
     * If the scheduler has fallen more than [maxLagSec] behind [nowSec],
     * fast-forwards all grids to the most recent end-time at or before now.
     * Returns true if a fast-forward occurred.
     */
    fun fastForwardIfLagging(nowSec: Double, maxLagSec: Double): Boolean {
        ensureInit(nowSec)
        val minNext = nextEndById.values.minOrNull() ?: nowSec
        if (minNext < nowSec - maxLagSec) {
            for (g in grids) {
                nextEndById[g.id] = endAtOrBefore(nowSec, g)
            }
            return true
        }
        return false
    }

    /**
     * Returns the next due WindowRequest across all grids, or null if none are ready.
     * Advances the returned grid's next end-time by one hop.
     */
    fun pollNext(nowSec: Double, eps: Double = 1e-6): WindowRequest? {
        ensureInit(nowSec)

        var bestGrid: GridSpec? = null
        var bestEnd = Double.POSITIVE_INFINITY

        for (g in grids) {
            val e = nextEndById[g.id] ?: continue
            if (e < bestEnd) {
                bestEnd = e
                bestGrid = g
            }
        }

        val g = bestGrid ?: return null
        if (bestEnd > nowSec + eps) return null

        nextEndById[g.id] = round6(bestEnd + g.hopSec)
        return WindowRequest(g.id, bestEnd, g.winSec, g.hopSec)
    }
}
