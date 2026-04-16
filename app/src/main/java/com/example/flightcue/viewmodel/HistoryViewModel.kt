package com.example.flightcue.viewmodel

import android.app.Application
import android.os.Build
import android.os.FileObserver
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.flightcue.data.modelspec.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class FlightSummaryRow(
    val flightId: String,
    val startWall: Long,
    val endWall: Long,
    val durationSec: Double,
    val startMode: String,
    val endMode: String
)

/**
 * Loads and exposes flight summaries from the local JSONL log files.
 * Watches the log directory with a FileObserver and refreshes automatically
 * when files are created, modified, or rotated.
 */
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val _rows = MutableStateFlow<List<FlightSummaryRow>>(emptyList())
    val rows: StateFlow<List<FlightSummaryRow>> = _rows

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val logDir = File(getApplication<Application>().filesDir, AppPaths.LOG_DIR_NAME).apply { mkdirs() }

    // Debounce so we don’t parse the file on every single line append
    @Volatile private var refreshScheduled = false

    private val observer: FileObserver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        object : FileObserver(logDir, CREATE or MOVED_TO or MOVED_FROM or DELETE or CLOSE_WRITE or MODIFY) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && path.startsWith("flightlog")) scheduleRefresh()
            }
        }
    } else {
        @Suppress("DEPRECATION")
        object : FileObserver(logDir.absolutePath, CREATE or MOVED_TO or MOVED_FROM or DELETE or CLOSE_WRITE or MODIFY) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && path.startsWith("flightlog")) scheduleRefresh()
            }
        }
    }

    init {
        refresh()          // initial content
        observer.startWatching()
    }

    override fun onCleared() {
        observer.stopWatching()
        super.onCleared()
    }

    private fun scheduleRefresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        viewModelScope.launch(Dispatchers.IO) {
            delay(250)      // small debounce window
            try { refresh() } finally { refreshScheduled = false }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val candidates = buildList {
                val main = File(logDir, AppPaths.LOG_FILE_BASE)
                if (main.exists()) add(main)
                for (i in 1..AppPaths.LOG_KEEP) {
                    val f = File(logDir, "flightlog.$i.jsonl")
                    if (f.exists()) add(f)
                }
            }
            val out = ArrayList<FlightSummaryRow>(64)
            for (file in candidates) {
                file.forEachLine { line ->
                    val t = line.trim()
                    if (t.isEmpty() || !t.startsWith("{")) return@forEachLine
                    val obj = runCatching { JSONObject(t) }.getOrNull() ?: return@forEachLine
                    if (obj.optString("type") == "flight_summary") {
                        val startMs = iso.parse(obj.optString("start_wall"))?.time ?: 0L
                        val endMs   = iso.parse(obj.optString("end_wall"))?.time ?: 0L
                        out += FlightSummaryRow(
                            flightId = obj.optString("flight_id"),
                            startWall = startMs,
                            endWall = endMs,
                            durationSec = obj.optDouble("duration_sec", Double.NaN),
                            startMode = obj.optString("start_mode", "auto"),
                            endMode = obj.optString("end_mode", "auto")
                        )
                    }
                }
            }
            _rows.value = out.sortedByDescending { it.endWall }.take(200)
        }
    }
}
