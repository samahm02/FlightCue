package com.example.flightcue.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.flightcue.data.replay.ReplaySummary
import com.example.flightcue.viewmodel.ReplayViewModel

@Composable
fun DevScreen(vm: ReplayViewModel = viewModel()) {
    val uri by vm.fileUri.collectAsState()
    val fileName by vm.fileName.collectAsState()
    val msgs by vm.messages.collectAsState()
    val summary by vm.summary.collectAsState()
    val running by vm.running.collectAsState()
    val progress by vm.progress.collectAsState()
    val progressText by vm.progressText.collectAsState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { picked: Uri? ->
        picked?.let {
            vm.open(it)
            vm.run()   // start replay immediately after picking
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                "Dev / Replay",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        picker.launch(
                            arrayOf(
                                "text/*",
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream",
                            )
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        if (uri == null) "Pick recording (.txt/.csv/.tsv/.zip)"
                        else "Pick another file"
                    )
                }

                when {
                    running -> {
                        OutlinedButton(
                            onClick = { vm.cancelRun() },
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text("Cancel")
                        }
                    }

                    uri != null -> {
                        OutlinedButton(
                            onClick = { vm.run() },
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text("Run again")
                        }
                    }
                }
            }

            if (uri != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Selected file: ${fileName ?: "Unknown"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current.copy(alpha = 0.8f)
                )
            }

            ReplayProgress(
                isRunning = running,
                progress = progress,
                progressText = progressText,
                modifier = Modifier.padding(top = 12.dp)
            )

            ReplayMessages(
                messages = msgs,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .weight(1f, fill = true)
            )
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
private fun ReplayProgress(
    isRunning: Boolean,
    progress: Float,
    progressText: String,
    modifier: Modifier = Modifier
) {
    if (!isRunning) return

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = "Analyzing flight (ML-only)…",
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
        )
        if (progressText.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                progressText,
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun ReplayMessages(
    messages: List<String>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Text("Messages", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 260.dp),
            tonalElevation = 2.dp,
            shape = RoundedCornerShape(16.dp)
        ) {
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No replay run yet. Pick a file to start.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    // index-based keys -> no duplicates crash
                    itemsIndexed(messages) { _, m ->
                        Text(
                            text = "• $m",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FlightStatsDialog(
    summary: ReplaySummary,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("CLOSE") }
        },
        title = { Text("Flight stats (ML)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Model takeoff time: ${fmtClock(summary.takeoffDataSec)}")
                Text("Model landing time: ${fmtClock(summary.landingDataSec)}")
                Spacer(Modifier.height(8.dp))
                Text("Number of flights detected: ${summary.flightsDetected}")
            }
        }
    )
}

private fun fmtClock(t: Double?): String {
    if (t == null) return "n/a"
    val s = t.toInt()
    val hh = s / 3600
    val mm = (s % 3600) / 60
    val ss = s % 60
    return "%02d:%02d:%02d".format(hh, mm, ss)
}
//17