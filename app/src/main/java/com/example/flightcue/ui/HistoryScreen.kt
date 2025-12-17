package com.example.flightcue.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.flightcue.viewmodel.FlightSummaryRow
import com.example.flightcue.viewmodel.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    vm: HistoryViewModel = viewModel()
) {
    val rows by vm.rows.collectAsState()

    // Ensure we pull the latest once when the screen first appears
    LaunchedEffect(Unit) { vm.refresh() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Flights history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No flights yet.")
            }
        } else {
            FlightsList(rows)
        }
    }
}

@Composable
private fun FlightsList(rows: List<FlightSummaryRow>) {
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 88.dp)
    ) {
        items(rows, key = { it.flightId + it.endWall }) { r ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Flight ${r.flightId}", fontWeight = FontWeight.Medium)
                        Text(durationPretty(r.durationSec), style = MaterialTheme.typography.labelMedium)
                    }
                    Text("Start: ${sdf.format(Date(r.startWall))}")
                    Text("End:   ${sdf.format(Date(r.endWall))}")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(label = { Text("start: ${r.startMode}") }, onClick = {}, enabled = false)
                        AssistChip(label = { Text("end: ${r.endMode}") }, onClick = {}, enabled = false)
                    }
                }
            }
        }
    }
}

private fun durationPretty(sec: Double): String {
    if (sec.isNaN() || sec < 0) return "—"
    val total = sec.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%dh %02dm %02ds".format(h, m, s) else "%dm %02ds".format(m, s)
}
