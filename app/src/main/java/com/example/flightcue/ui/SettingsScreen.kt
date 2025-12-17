package com.example.flightcue.ui

// file: app/src/main/java/com/example/flightcue/ui/settings/SettingsScreen.kt

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.flightcue.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val detectionEnabled by vm.detectionEnabled.collectAsState()

    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        // Detection
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Flight detection")
                    Switch(
                        checked = detectionEnabled,
                        onCheckedChange = { on -> scope.launch { vm.setDetectionEnabled(on) } }
                    )
                }
                Text(
                    "When enabled, FlightCue monitors sensors in the background (requires accelerometer and barometer).",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Export
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Data & export", fontWeight = FontWeight.Medium)
                Button(
                    onClick = { vm.exportLogs(ctx) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Export flight logs (ZIP)") }

                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors()
                ) { Text("Clear flight logs") }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear flight logs?") },
            text = { Text("This deletes all local flightlog*.jsonl files. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearLogs(ctx) {
                        Toast.makeText(ctx, "Logs cleared", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}
