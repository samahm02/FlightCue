package com.example.flightcue.domain.state

/**
 * Hysteretic edge detector with optional EMA smoothing on the input probability.
 * - Use EMA (emaAlpha != null) to reduce noise without forcing multi-hop streaks.
 *   alpha ~ 2/(N+1) if you think of N as a "window" in hops.
 */
class EdgeTrigger(
    private val tOn: Double,
    private val tOff: Double,
    minRunWins: Int,
    private val cooldownSec: Double,
    startArmed: Boolean = true,
    private val emaAlpha: Double? = null,   // <- enable EMA when not null
) {
    init { require(tOn > tOff) }

    private val minRunWinsClamped = if (minRunWins < 1) 1 else minRunWins

    private var armed = startArmed
    private var run = 0
    private var lastFire = Double.NEGATIVE_INFINITY
    private var s: Double? = null           // EMA state

    fun update(pRaw: Double, t: Double): Boolean {
        // Smooth or pass-through
        val p = emaAlpha?.let { a ->
            s = s?.let { it + a * (pRaw - it) } ?: pRaw
            s!!
        } ?: pRaw

        // Re-arm when we fall to/below tOff
        if (p <= tOff) {
            armed = true
            run = 0
            return false
        }

        // Above the "on" threshold
        if (p >= tOn) {
            if (!armed) return false
            if (t - lastFire <= cooldownSec) return false

            run += 1
            if (run >= minRunWinsClamped) {
                lastFire = t
                armed = false            // require dip below tOff to re-arm
                run = 0
                return true
            }
            return false
        }

        // Between tOff and tOn: neutral; reset consecutive-on run
        run = 0
        return false
    }

    fun reset(startArmed: Boolean = true, lastFireSec: Double = Double.NEGATIVE_INFINITY) {
        armed = startArmed
        run = 0
        lastFire = lastFireSec
        s = null                           // clear EMA state
    }
}