package com.example.flightcue.domain.features

/**
 * StandardScaler helper matching the Python scaler exported to scaler.npz.
 *
 * Mean and scale arrays are per-feature (length = nFeatures).
 * Scaling happens at window level before building the sequence —
 * never on the flattened sequence array.
 */
object Scaler {

    /**
     * Scales one window vector using z = (x - mean) / scale.
     *
     * NaN/Inf handling:
     * - Non-finite input values are mean-imputed so the scaled output is 0.
     * - If scale is ~0 or non-finite the output is 0.
     * - Any remaining non-finite output is clamped to 0.
     *
     * Must match the training StandardScaler exported to scaler.npz.
     */
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
            val x = raw[i].takeIf { it.isFinite() } ?: mean[i]
            val s = scale[i]
            val z = if (!s.isFinite() || s < 1e-8) 0.0 else (x - mean[i]) / s
            out[i] = if (z.isFinite()) z else 0.0
        }
        return out
    }

    /** Returns an all-zero vector of length [nFeatures], used as a fallback for low-coverage windows. */
    fun zeros(nFeatures: Int): DoubleArray = DoubleArray(nFeatures)
}