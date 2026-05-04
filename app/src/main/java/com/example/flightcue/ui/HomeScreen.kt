package com.example.flightcue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.flightcue.R
import com.example.flightcue.domain.events.FlightDomainEvent
import com.example.flightcue.domain.events.FlightState
import com.example.flightcue.viewmodel.DetectionViewModel

/** Main screen showing live flight state, last detected event, and the manual override button. */
@Composable
fun HomeScreen(vm: DetectionViewModel = viewModel(factory = DetectionViewModel.Factory)) {
    val state by vm.flightState.collectAsState()
    val last  by vm.lastEvent.collectAsState()

    LaunchedEffect(Unit) { vm.startService() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        // Flight state card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.home_flight_state_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (state == FlightState.Flying)
                            stringResource(R.string.home_state_airborne)
                        else
                            stringResource(R.string.home_state_on_ground),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Status indicator pill
                val pillColor = if (state == FlightState.Flying)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.outline

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(pillColor.copy(alpha = 0.15f))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        state.name,
                        color = if (state == FlightState.Flying)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Last event card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.home_last_event_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when (val ev = last) {
                    null -> Text(
                        stringResource(R.string.home_no_events),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    is FlightDomainEvent.FlightStarted -> EventRow(
                        label = stringResource(R.string.home_event_takeoff),
                        confidence = ev.confidence,
                        timeSec = ev.atSec
                    )
                    is FlightDomainEvent.FlightEnded -> EventRow(
                        label = stringResource(R.string.home_event_landing),
                        confidence = ev.confidence,
                        timeSec = ev.atSec
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Force toggle
        Button(
            onClick = { vm.forceToggle() },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                if (state == FlightState.NotFlying)
                    stringResource(R.string.home_button_mark_takeoff)
                else
                    stringResource(R.string.home_button_mark_landing),
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun EventRow(label: String, confidence: Double, timeSec: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            stringResource(R.string.home_event_meta, confidence, timeSec),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}