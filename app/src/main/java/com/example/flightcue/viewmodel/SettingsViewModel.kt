// file: app/src/main/java/com/example/flightcue/viewmodel/SettingsViewModel.kt
package com.example.flightcue.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.flightcue.data.SettingsStore
import com.example.flightcue.data.logging.LogExporter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)

    val detectionEnabled: StateFlow<Boolean> =
        settings.detectionEnabled.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true
        )


    fun setDetectionEnabled(on: Boolean) = viewModelScope.launch {
        settings.setDetectionEnabled(on)
        // ✅ Do NOT start/stop service here (MainActivity already owns that)
    }

    fun exportLogs(context: Context) {
        LogExporter.shareLogsZip(context)
    }

    fun clearLogs(context: Context, onDone: (() -> Unit)? = null) = viewModelScope.launch {
        LogExporter.clearAllLogs(context)
        onDone?.invoke()
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                return SettingsViewModel(app) as T
            }
        }
    }
}
