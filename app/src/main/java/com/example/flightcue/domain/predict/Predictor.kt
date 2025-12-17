package com.example.flightcue.domain.predict

/** Small seam so the core stays model/runtime-agnostic. */
interface Predictor {
    fun scoreTakeoff(features: DoubleArray): Double
    fun scoreLanding(features: DoubleArray): Double
}
