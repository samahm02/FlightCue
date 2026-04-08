package com.example.flightcue.data.modelspec

import android.content.Context

/**
 * FIXED: Renamed fields to clearly indicate these are SCALER parameters, not feature medians.
 *
 * WHY THE FIX:
 * - toScalerMean/ldScalerMean: Used for StandardScaler normalization: (x - mean) / scale
 * - toScalerScale/ldScalerScale: Standard deviation for each feature
 * - These should ONLY be used in the scaling pipeline, NOT as standalone features!
 */
data class ModelArtifacts(
    val toSchema: FeatureSchema,
    val ldSchema: FeatureSchema,

    // ✅ RENAMED: "Medians" → "ScalerMean" (clearer purpose)
    val toScalerMean: DoubleArray,
    val ldScalerMean: DoubleArray,

    // ✅ NEW: Added scale arrays (required for StandardScaler!)
    val toScalerScale: DoubleArray,
    val ldScalerScale: DoubleArray,

    val toProfile: ModelProfile,
    val ldProfile: ModelProfile
) {
    /**
     * Verify that all arrays have the expected length (should match n_features).
     */
    fun validate() {
        val toExpected = toProfile.nFeatures
        val ldExpected = ldProfile.nFeatures

        require(toSchema.names.size == toExpected) {
            "Takeoff schema mismatch: ${toSchema.names.size} ≠ $toExpected"
        }
        require(ldSchema.names.size == ldExpected) {
            "Landing schema mismatch: ${ldSchema.names.size} ≠ $ldExpected"
        }
        require(toScalerMean.size == toExpected) {
            "Takeoff scaler mean mismatch: ${toScalerMean.size} ≠ $toExpected"
        }
        require(toScalerScale.size == toExpected) {
            "Takeoff scaler scale mismatch: ${toScalerScale.size} ≠ $toExpected"
        }
        require(ldScalerMean.size == ldExpected) {
            "Landing scaler mean mismatch: ${ldScalerMean.size} ≠ $ldExpected"
        }
        require(ldScalerScale.size == ldExpected) {
            "Landing scaler scale mismatch: ${ldScalerScale.size} ≠ $ldExpected"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ModelArtifacts

        if (toSchema != other.toSchema) return false
        if (ldSchema != other.ldSchema) return false
        if (!toScalerMean.contentEquals(other.toScalerMean)) return false
        if (!ldScalerMean.contentEquals(other.ldScalerMean)) return false
        if (!toScalerScale.contentEquals(other.toScalerScale)) return false
        if (!ldScalerScale.contentEquals(other.ldScalerScale)) return false
        if (toProfile != other.toProfile) return false
        if (ldProfile != other.ldProfile) return false

        return true
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

/** Loads schema + scaler (mean/scale) + profile for TAKEOFF and LANDING from assets. */
object ModelArtifactsLoader {
    fun load(context: Context): ModelArtifacts {
        // Load schemas (feature names)
        val toSchema = ModelConfigs.parseFeatures(context, true)
        val ldSchema = ModelConfigs.parseFeatures(context, false)

        // ✅ FIXED: Load BOTH mean and scale from scaler.npz
        val (toMean, toScale) = ModelConfigs.parseScaler(context, toSchema, true)
        val (ldMean, ldScale) = ModelConfigs.parseScaler(context, ldSchema, false)

        // Load profiles (model configuration)
        val toProf = ModelConfigs.parseProfile(context, true)
        val ldProf = ModelConfigs.parseProfile(context, false)

        val artifacts = ModelArtifacts(
            toSchema, ldSchema,
            toMean, ldMean,
            toScale, ldScale,
            toProf, ldProf
        )

        // Validate all arrays are correctly sized
        artifacts.validate()

        return artifacts
    }
}