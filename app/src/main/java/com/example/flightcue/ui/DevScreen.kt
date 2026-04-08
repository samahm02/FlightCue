package com.example.flightcue.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.flightcue.data.replay.ReplaySummary
import com.example.flightcue.viewmodel.ReplayViewModel
import java.util.Locale
import kotlin.math.abs

@Composable
fun DevScreen(vm: ReplayViewModel = viewModel()) {
    val uri          by vm.fileUri.collectAsState()
    val fileName     by vm.fileName.collectAsState()
    val msgs         by vm.messages.collectAsState()
    val summary      by vm.summary.collectAsState()
    val running      by vm.running.collectAsState()
    val progress     by vm.progress.collectAsState()
    val progressText by vm.progressText.collectAsState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { picked: Uri? ->
        picked?.let {
            vm.open(it)
            vm.run()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Replay",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            // File picker row
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        picker.launch(
                            arrayOf(
                                "text/*",
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream"
                            )
                        )
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        if (uri == null) "Pick recording" else "Pick another file",
                        fontWeight = FontWeight.Medium
                    )
                }

                if (running) {
                    OutlinedButton(
                        onClick = { vm.cancelRun() },
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Cancel") }
                } else if (uri != null) {
                    OutlinedButton(
                        onClick = { vm.run() },
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Run again") }
                }
            }

            if (fileName != null) {
                Text(
                    fileName!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Progress
            if (running) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    if (progressText.isNotBlank()) {
                        Text(
                            progressText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Message log
            Text(
                "Messages",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                tonalElevation = 2.dp,
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                if (msgs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Pick a recording file to begin.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        itemsIndexed(msgs) { _, m ->
                            Text(
                                "• $m",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        if (summary != null) {
            FlightStatsDialog(
                summary = summary!!,
                onDismiss = { vm.clearSummary() }
            )
        }
    }
}

@Composable
private fun FlightStatsDialog(summary: ReplaySummary, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = {
            Text("Replay results", fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ResultSection(
                    title   = "Takeoff",
                    detected = summary.takeoffDataSec,
                    marker   = summary.takeoffMarkerSec,
                    delta    = summary.takeoffDeltaSec
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ResultSection(
                    title   = "Landing",
                    detected = summary.landingDataSec,
                    marker   = summary.landingMarkerSec,
                    delta    = summary.landingDeltaSec
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Complete flights detected: ${summary.flightsDetected}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun ResultSection(
    title: String,
    detected: Double?,
    marker: Double?,
    delta: Double?
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        ResultRow("Detected", fmtClockOrDash(detected, "not detected"))
        ResultRow("Marker",   fmtClockOrDash(marker,   "no marker"))
        ResultRow("Δ",        fmtDelta(delta))
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun fmtClockOrDash(t: Double?, fallback: String): String {
    if (t == null) return fallback
    val s = t.toInt()
    return "%02d:%02d:%02d".format(Locale.US, s / 3600, (s % 3600) / 60, s % 60)
}

private fun fmtDelta(d: Double?): String {
    if (d == null) return "n/a"
    return "${if (d >= 0) "+" else "-"}${"%.1f".format(Locale.US, abs(d))} s"
}