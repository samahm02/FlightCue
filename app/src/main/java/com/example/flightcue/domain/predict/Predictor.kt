package com.example.flightcue.domain.predict

/**
 * Abstraction over the underlying ML runtime (ONNX).
 * Keeps the domain layer model-agnostic and makes the detector testable
 * without a real ORT session.
 */
interface Predictor {
    /** Returns a takeoff probability in [0, 1] for the given feature vector. */
    fun scoreTakeoff(features: DoubleArray): Double

    /** Returns a landing probability in [0, 1] for the given feature vector. */
    fun scoreLanding(features: DoubleArray): Double
}