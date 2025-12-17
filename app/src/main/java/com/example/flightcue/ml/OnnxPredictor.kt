package com.example.flightcue.ml

import com.example.flightcue.domain.predict.Predictor

/**
 * Thin adapter so the core doesn't know about OrtSessionManager.
 * Assumes OrtSessionManager.init(context) has been called elsewhere.
 */
class OnnxPredictor(
    private val ort: OrtSessionManager
) : Predictor {
    override fun scoreTakeoff(features: DoubleArray): Double = ort.scoreTakeoff(features)
    override fun scoreLanding(features: DoubleArray): Double = ort.scoreLanding(features)
}
