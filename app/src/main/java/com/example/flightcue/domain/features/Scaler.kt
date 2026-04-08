package com.example.flightcue.domain.features

/**
 * StandardScaler parity helper.
 *
 * IMPORTANT:
 * - mean/scale are per-FEATURE (length = nFeatures = 154)
 * - scaling must happen at WINDOW level (154) BEFORE building the sequence
 * - do NOT scale flattened sequences (3850) with mean/scale (154)
 */
object Scaler {

    /**
     * Scale one window vector (length = nFeatures):
     * z[i] = (x[i] - mean[i]) / scale[i]
     *
     * NaN/Inf handling:
     * - if x[i] is NaN/Inf => treat as "missing" and impute mean[i] so z[i] becomes 0
     * - if scale[i] is ~0 => z[i] = 0
     */

    //This must match training StandardScaler exported to scaler.npz
    fun scaleWindow(
        raw: DoubleArray,
        mean: DoubleArray,
        scale: DoubleArray
    ): DoubleArray {
        require(raw.size == mean.size && raw.size == scale.size) {
            "scaleWindow: size mismatch raw=${raw.size}, mean=${mean.size}, scale=${scale.size}"
        }

        val out = DoubleArray(raw.size)
        for (i in raw.indices) {
            val x = raw[i].takeIf { it.isFinite() } ?: mean[i]   // mean-impute missing => z=0
            val s = scale[i]

            val z = if (!s.isFinite() || s < 1e-8) {
                0.0
            } else {
                (x - mean[i]) / s
            }

            out[i] = if (z.isFinite()) z else 0.0
        }
        return out
    }

    /** Convenience for an already-scaled "neutral" window. */
    fun zeros(nFeatures: Int): DoubleArray = DoubleArray(nFeatures) { 0.0 }
}
