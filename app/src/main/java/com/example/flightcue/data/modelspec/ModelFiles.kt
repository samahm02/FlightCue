package com.example.flightcue.data.modelspec

import android.content.Context
import android.util.Log

object ModelFiles {
    private const val TAG = "ModelFiles"

    fun listTakeoff(context: Context): List<String> =
        context.assets.list(AppPaths.TAKEOFF_DIR)?.toList().orEmpty()

    fun listLanding(context: Context): List<String> =
        context.assets.list(AppPaths.LANDING_DIR)?.toList().orEmpty()

    fun readAssetText(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    fun readAssetBytes(context: Context, path: String): ByteArray =
        context.assets.open(path).use { it.readBytes() }

    fun logInventory(context: Context) {
        val to = listTakeoff(context)
        val ld = listLanding(context)
        Log.i(TAG, "TAKEOFF assets @${AppPaths.TAKEOFF_DIR}: $to")
        Log.i(TAG, "LANDING assets @${AppPaths.LANDING_DIR}: $ld")
        require(to.isNotEmpty()) { "No files under ${AppPaths.TAKEOFF_DIR}" }
        require(ld.isNotEmpty()) { "No files under ${AppPaths.LANDING_DIR}" }
    }
}
