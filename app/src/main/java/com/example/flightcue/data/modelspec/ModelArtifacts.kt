package com.example.flightcue.data.modelspec

/**
 * Holds all parsed model artifacts for both the takeoff and landing detectors.
 * Scaler arrays (mean/scale) are used for StandardScaler normalisation: (x - mean) / scale.
 * Loading is done by [com.example.flightcue.ml.OrtSessionManager].
 */
data class ModelArtifacts(
    val toSchema: FeatureSchema,
    val ldSchema: FeatureSchema,

    val toScalerMean: DoubleArray,
    val ldScalerMean: DoubleArray,

    val toScalerScale: DoubleArray,
    val ldScalerScale: DoubleArray,

    val toProfile: ModelProfile,
    val ldProfile: ModelProfile
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ModelArtifacts

        return toSchema == other.toSchema &&
                ldSchema == other.ldSchema &&
                toScalerMean.contentEquals(other.toScalerMean) &&
                ldScalerMean.contentEquals(other.ldScalerMean) &&
                toScalerScale.contentEquals(other.toScalerScale) &&
                ldScalerScale.contentEquals(other.ldScalerScale) &&
                toProfile == other.toProfile &&
                ldProfile == other.ldProfile
    }

    override fun hashCode(): Int {
        var result = toSchema.hashCode()
        result = 31 * result + ldSchema.hashCode()
        result = 31 * result + toScalerMean.contentHashCode()
        result = 31 * result + ldScalerMean.contentHashCode()
        result = 31 * result + toScalerScale.contentHashCode()
        result = 31 * result + ldScalerScale.contentHashCode()
        result = 31 * result + toProfile.hashCode()
        result = 31 * result + ldProfile.hashCode()
        return result
    }
}