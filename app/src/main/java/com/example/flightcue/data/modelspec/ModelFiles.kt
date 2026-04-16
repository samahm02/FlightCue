package com.example.flightcue.data.modelspec

import android.content.Context
import android.util.Log

/** Thin helpers for reading model asset files and logging their inventory on startup. */
object ModelFiles {
    private const val TAG = "ModelFiles"

    fun listTakeoff(context: Context): List<String> =
        context.assets.list(AppPaths.TAKEOFF_DIR)?.toList().orEmpty()

    fun listLanding(context: Context): List<String> =
        context.assets.list(AppPaths.LANDING_DIR)?.toList().orEmpty()

    /** Logs the asset inventory for both model directories and throws if either is empty. */
    fun logInventory(context: Context) {
        val to = listTakeoff(context)
        val ld = listLanding(context)
        Log.i(TAG, "TAKEOFF assets @${AppPaths.TAKEOFF_DIR}: $to")
        Log.i(TAG, "LANDING assets @${AppPaths.LANDING_DIR}: $ld")
        require(to.isNotEmpty()) { "No files under ${AppPaths.TAKEOFF_DIR}" }
        require(ld.isNotEmpty()) { "No files under ${AppPaths.LANDING_DIR}" }
    }
}