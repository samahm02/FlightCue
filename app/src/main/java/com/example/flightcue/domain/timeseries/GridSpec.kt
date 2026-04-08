package com.example.flightcue.domain.timeseries

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

data class WindowRequest(
    val gridId: String,
    val endSec: Double,
    val winSec: Double,
    val hopSec: Double
)
