package com.example.flightcue.data.modelspec

import android.content.Context
import com.example.flightcue.data.modelspec.FeatureSchema
import com.example.flightcue.data.modelspec.ModelConfigs
import com.example.flightcue.data.modelspec.ModelProfile

data class ModelArtifacts(
    val toSchema: FeatureSchema,
    val ldSchema: FeatureSchema,
    val toMedians: DoubleArray,
    val ldMedians: DoubleArray,
    val toProfile: ModelProfile,
    val ldProfile: ModelProfile
)

/** Loads schema + medians + profile for TAKEOFF and LANDING from assets. */
object ModelArtifactsLoader {
    fun load(context: Context): ModelArtifacts {
        val toSchema = ModelConfigs.parseFeatures(context, true)
        val ldSchema = ModelConfigs.parseFeatures(context, false)
        val toMeds   = ModelConfigs.parseMedians(context, toSchema, true)
        val ldMeds   = ModelConfigs.parseMedians(context, ldSchema, false)
        val toProf   = ModelConfigs.parseProfile(context, true)
        val ldProf   = ModelConfigs.parseProfile(context, false)
        return ModelArtifacts(toSchema, ldSchema, toMeds, ldMeds, toProf, ldProf)
    }
}
