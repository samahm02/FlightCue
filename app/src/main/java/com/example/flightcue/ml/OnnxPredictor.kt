package com.example.flightcue.ml

import com.example.flightcue.domain.predict.Predictor

/**
 * Thin adapter between [Predictor] and [OrtSessionManager].
 * Assumes [OrtSessionManager.init] has been called before any score calls.
 */
class OnnxPredictor(
    private val ort: OrtSessionManager
) : Predictor {
    override fun scoreTakeoff(features: DoubleArray): Double = ort.scoreTakeoff(features)
    override fun scoreLanding(features: DoubleArray): Double = ort.scoreLanding(features)
}