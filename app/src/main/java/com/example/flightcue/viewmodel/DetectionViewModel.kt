// file: app/src/main/java/com/example/flightcue/ui/DetectionViewModel.kt
package com.example.flightcue.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.flightcue.domain.events.AppBus
import com.example.flightcue.domain.events.FlightDomainEvent
import com.example.flightcue.domain.events.FlightState
import com.example.flightcue.service.FlightDetectionService
import kotlinx.coroutines.flow.*

/** Exposes flight state and last event from AppBus, and forwards manual override actions to the service. */
class DetectionViewModel(app: Application) : AndroidViewModel(app) {
    fun startService() {
        FlightDetectionService.start(getApplication())
    }

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


    fun forceToggle() {
        when (flightState.value) {
            FlightState.NotFlying -> {
                FlightDetectionService.forceTakeoff(getApplication())
            }
            FlightState.Flying -> {
                FlightDetectionService.forceLanding(getApplication())
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
