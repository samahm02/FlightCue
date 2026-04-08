// file: app/src/main/java/com/example/flightcue/data/SettingsStore.kt
package com.example.flightcue.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val DETECTION_ENABLED = booleanPreferencesKey("detection_enabled")
    }

    /** Master on/off for flight detection (default: true). */
    val detectionEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.DETECTION_ENABLED] ?: true }

    suspend fun setDetectionEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.DETECTION_ENABLED] = value }
    }

}
