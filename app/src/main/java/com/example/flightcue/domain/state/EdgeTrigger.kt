package com.example.flightcue.domain.state

import kotlin.math.abs

/**
 * Edge detector with optional EMA smoothing.
 *
 * Supports BOTH:
 *  - Hysteresis mode: tOn > tOff (classic)
 *  - No-hysteresis mode: tOn == tOff (GRU-only recommended)
 *
 * In no-hysteresis mode we re-arm only when p < tOff (strict),
 * so p == threshold is treated as "ON" not "OFF".
 */
class EdgeTrigger(
    private val tOn: Double,
    private val tOff: Double,
    minRunWins: Int,
    private val cooldownSec: Double,
    startArmed: Boolean = true,
    private val emaAlpha: Double? = null,
) {
    init { require(tOn >= tOff) { "EdgeTrigger: require tOn >= tOff (got tOn=$tOn tOff=$tOff)" } }

    private val minRunWinsClamped = if (minRunWins < 1) 1 else minRunWins

    // True when thresholds are equal — disables the hysteresis dead-band.
    private val noHyst = abs(tOn - tOff) <= 1e-12

    private var armed = startArmed // whether the trigger can fire
    private var run = 0  // consecutive ON-windows since arming
    private var lastFire = Double.NEGATIVE_INFINITY   // elapsed seconds of last fire
    private var s: Double? = null   // EMA state (null until first sample)

    /**
     * Feed one probability score at time [t].
     * Returns true when the trigger fires (run >= minRun and cooldown elapsed).
     */
    fun update(pRaw: Double, t: Double): Boolean {
        val p = emaAlpha?.let { a ->
            s = s?.let { it + a * (pRaw - it) } ?: pRaw
            s!!
        } ?: pRaw

        // Re-arm logic
        // - Hysteresis: re-arm when p <= tOff
        // - No-hysteresis: re-arm when p <  tOff (strict), so p==tOn doesn't get classified as OFF.
        val rearm = if (noHyst) (p < tOff) else (p <= tOff)
        if (rearm) {
            armed = true
            run = 0
            return false
        }

        // ON logic
        if (p >= tOn) {
            if (!armed) return false
            if (t - lastFire <= cooldownSec) return false

            run += 1
            if (run >= minRunWinsClamped) {
                lastFire = t
                armed = false
                run = 0
                return true
            }
            return false
        }

        // Between thresholds (only meaningful when hysteresis is enabled)
        run = 0
        return false
    }

    /** Resets trigger state, optionally restoring armed status and last-fire time. */
    fun reset(startArmed: Boolean = true, lastFireSec: Double = Double.NEGATIVE_INFINITY) {
        armed = startArmed
        run = 0
        lastFire = lastFireSec
        s = null
    }
}
