package com.example.flightcue.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.flightcue.data.replay.ReplayRepository
import com.example.flightcue.data.replay.ReplayRunner
import com.example.flightcue.data.replay.ReplaySummary
import com.example.flightcue.service.FlightDetectionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * ViewModel for the replay screen.
 * Manages file selection, runs ReplayRunner on a background thread, and exposes
 * progress and results to the UI via StateFlow.
 */
class ReplayViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ReplayRepository()
    private val runner = ReplayRunner(app) { msg -> postMsg(msg) }

    private val _fileUri = MutableStateFlow<Uri?>(null)
    val fileUri: StateFlow<Uri?> = _fileUri.asStateFlow()

    private val _fileName = MutableStateFlow<String?>(null)
    val fileName: StateFlow<String?> = _fileName.asStateFlow()

    private val _messages = MutableStateFlow<List<String>>(emptyList())
    val messages: StateFlow<List<String>> = _messages.asStateFlow()

    private val _summary = MutableStateFlow<ReplaySummary?>(null)
    val summary: StateFlow<ReplaySummary?> = _summary.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    // 0..1, updated by ReplayRunner
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    // Simple status line under the bar
    private val _progressText = MutableStateFlow("")
    val progressText: StateFlow<String> = _progressText.asStateFlow()

    private var lastRunJob: Job? = null

    fun clearMessages() { _messages.value = emptyList() }
    fun clearSummary() { _summary.value = null }

    fun open(uri: Uri) {
        // Stop live detection before running replay
        FlightDetectionService.stop(getApplication())

        _fileUri.value = uri
        _fileName.value = null
        clearMessages()
        clearSummary()
        _progress.value = 0f
        _progressText.value = ""

        viewModelScope.launch {
            val name = resolveDisplayName(uri)
            _fileName.value = name
            postMsg("Selected: " + (name ?: uri.toString()))
        }
    }

    fun run() {
        // Cancel any previous run
        lastRunJob?.cancel()
        lastRunJob = viewModelScope.launch {
            val uri = _fileUri.value ?: run {
                postMsg("No file selected")
                return@launch
            }

            _running.value = true
            _progress.value = 0f
            _progressText.value = "Loading recording (this can take a bit for large files)…"

            try {
                // 1) Parse / load recording (I/O)
                val src = withContext(Dispatchers.IO) {
                    repo.openRecording(getApplication(), uri)
                }

                // After we have the recording, we shift to ML stage
                _progress.value = 0.1f
                _progressText.value =
                    "Analyzing ${_fileName.value ?: "recording"} with ML model…"

                // 2) Heavy ML work (CPU) + real progress from ReplayRunner
                val sum = withContext(Dispatchers.Default) {
                    runner.runFast(src) { frac ->
                        val mapped = 0.1f + 0.9f * frac.toFloat()
                        _progress.value = mapped.coerceIn(0f, 1f)
                    }
                }

                _summary.value = sum
                _progress.value = 1f
                _progressText.value = "Analysis complete"
                postMsg("Replay done.")
            } catch (ce: CancellationException) {
                _progressText.value = "Cancelled"
                postMsg("Cancelled")
                throw ce
            } catch (e: Exception) {
                _progressText.value = "Replay failed"
                postMsg("Replay failed: ${e.message}")
            } finally {
                _running.value = false
            }
        }
    }

    fun cancelRun() {
        lastRunJob?.cancel()
    }

    private fun postMsg(m: String) {
        _messages.value = _messages.value + m
    }

    private suspend fun resolveDisplayName(uri: Uri): String? =
        withContext(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            try {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) {
                        cursor.getString(idx)
                    } else null
                }
            } catch (_: Exception) {
                null
            }
        }
}
