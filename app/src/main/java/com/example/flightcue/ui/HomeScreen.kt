// file: app/src/main/java/com/example/flightcue/ui/HomeScreen.kt
package com.example.flightcue.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.flightcue.domain.events.FlightDomainEvent
import com.example.flightcue.domain.events.FlightState

@Composable
fun HomeScreen(vm: DetectionViewModel = viewModel(factory = DetectionViewModel.Factory)) {
    val state   by vm.flightState.collectAsState()
    val last    by vm.lastEvent.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("FlightCue", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        // State card (no detection switch here anymore)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Flight state")
                    AssistChip(onClick = {}, enabled = false, label = { Text(state.name) })
                }
            }
        }

        // Force button: flips state (records as "forced" in history)
        val forceLabel = if (state == FlightState.NotFlying) "Mark Takeoff (forced)" else "Mark Landing (forced)"
        Button(
            onClick = { vm.forceToggle() },
            modifier = Modifier.fillMaxWidth()
        ) { Text(forceLabel) }

        // Last event (lightweight)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Last event", fontWeight = FontWeight.Medium)
                when (val ev = last) {
                    null -> Text("—")
                    is FlightDomainEvent.FlightStarted ->
                        Text("FlightStarted • conf=${"%.3f".format(ev.confidence)} • t≈${"%.1f".format(ev.atSec)}s")
                    is FlightDomainEvent.FlightEnded ->
                        Text("FlightEnded • conf=${"%.3f".format(ev.confidence)} • t≈${"%.1f".format(ev.atSec)}s")
                }
            }
        }
    }
}
