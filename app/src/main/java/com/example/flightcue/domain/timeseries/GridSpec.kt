package com.example.flightcue.domain.timeseries

/** Defines a single windowing grid by its window length and hop size. */
data class GridSpec(
    val id: String,
    val winSec: Double,
    val hopSec: Double
) {
    init {
        require(winSec.isFinite() && winSec > 0.0) { "GridSpec($id): winSec invalid=$winSec" }
        require(hopSec.isFinite() && hopSec > 0.0) { "GridSpec($id): hopSec invalid=$hopSec" }
    }
}

/** A scheduled window ready for feature extraction. */
data class WindowRequest(
    val gridId: String,
    val endSec: Double,
    val winSec: Double,
    val hopSec: Double
)
