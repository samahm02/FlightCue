// file: app/src/main/java/com/example/flightcue/ui/DetectionViewModel.kt
package com.example.flightcue.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.flightcue.data.SettingsStore
import com.example.flightcue.domain.events.AppBus
import com.example.flightcue.domain.events.EventMode
import com.example.flightcue.domain.events.FlightDomainEvent
import com.example.flightcue.domain.events.FlightState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DetectionViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)

    val detectionEnabled: StateFlow<Boolean> =
        settings.detectionEnabled.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = true
        )

    val flightState: StateFlow<FlightState> =
        AppBus.state.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            FlightState.NotFlying
        )

    val lastEvent: StateFlow<FlightDomainEvent?> =
        AppBus.events
            .runningFold<FlightDomainEvent, FlightDomainEvent?>(null) { _, e -> e }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setEnabled(v: Boolean) = viewModelScope.launch { settings.setDetectionEnabled(v) }

    /** Force toggle the current state and emit a FORCED event for history/logging. */
    fun forceToggle() = viewModelScope.launch {
        val nowSec = SystemClock.elapsedRealtime() / 1000.0
        when (flightState.value) {
            FlightState.NotFlying -> {
                // User says: we already took off.
                AppBus.publish(FlightDomainEvent.FlightStarted(nowSec, confidence = 1.0, mode = EventMode.FORCED))
                AppBus.setState(FlightState.Flying)
            }
            FlightState.Flying -> {
                // User says: we have landed.
                AppBus.publish(FlightDomainEvent.FlightEnded(nowSec, confidence = 1.0, mode = EventMode.FORCED))
                AppBus.setState(FlightState.NotFlying)
            }
        }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                return DetectionViewModel(app) as T
            }
        }
    }
}
