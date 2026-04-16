// file: app/src/main/java/com/example/flightcue/data/logging/EventLogWriter.kt
package com.example.flightcue.data.logging

import android.content.Context
import android.os.Build
import com.example.flightcue.data.modelspec.AppPaths
import com.example.flightcue.domain.events.EventMode
import com.example.flightcue.domain.events.FlightDomainEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

/**
 * Append-only JSONL flight log with simple rotation.
 * Writes three kinds of lines:
 *  - flight_started
 *  - flight_ended
 *  - flight_summary
 *
 * Storage per flight: ~0.4–0.7 KB. Rotation at ~5 MB by default.
 */
class EventLogWriter(
    appContext: Context,
    private val events: Flow<FlightDomainEvent>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectJob: Job? = null

    private val dir = File(appContext.filesDir, AppPaths.LOG_DIR_NAME)
    private val mutex = Mutex()
    private var writer: BufferedWriter? = null

    // Track a single in-progress flight to compute summary at landing
    private data class InFlight(
        val id: String,
        val startWallMs: Long,
        val startSec: Double,
        val startMode: String,
        val startConfidence: Double
    )
    private var inFlight: InFlight? = null
    private val seq = AtomicInteger(1)

    fun start() {
        if (!dir.exists()) dir.mkdirs()
        openWriterIfNeeded()

        collectJob?.cancel()
        collectJob = scope.launch {
            events.collectLatest { ev ->
                when (ev) {
                    is FlightDomainEvent.FlightStarted -> onStarted(ev.atSec, ev.confidence, ev.mode)
                    is FlightDomainEvent.FlightEnded   -> onEnded(ev.atSec, ev.confidence, ev.mode)
                }
            }
        }
    }

    fun close() {
        collectJob?.cancel()
        collectJob = null
        scope.launch {
            mutex.withLock {
                writer?.runCatching { flush(); close() }
                writer = null
            }
        }
    }

    // ---- Handlers ----
    private suspend fun onStarted(tSec: Double, conf: Double, mode: EventMode) {
        val now = System.currentTimeMillis()
        val id = makeFlightId(now)
        val start = InFlight(
            id = id,
            startWallMs = now,
            startSec = tSec,
            startMode = mode.toWire(),
            startConfidence = conf
        )
        inFlight = start

        writeJsonLine(
            JSONObject()
                .put("type", "flight_started")
                .put("flight_id", id)
                .put("t_wall", isoUtc(now))
                .put("t_sec", tSec)
                .put("mode", mode.toWire())
                .put("event", "TAKEOFF")
                .put("confidence", conf)
                .put("device", JSONObject()
                    .put("brand", Build.BRAND)
                    .put("model", Build.MODEL)
                    .put("sdk", Build.VERSION.SDK_INT)
                )
        )
    }

    private suspend fun onEnded(tSec: Double, conf: Double, mode: EventMode) {
        val now = System.currentTimeMillis()
        val current = inFlight

        // Always write a flight_ended line
        writeJsonLine(
            JSONObject()
                .put("type", "flight_ended")
                .put("flight_id", current?.id ?: makeFlightId(now))
                .put("t_wall", isoUtc(now))
                .put("t_sec", tSec)
                .put("mode", mode.toWire())
                .put("event", "LANDING")
                .put("confidence", conf)
        )

        // If we had a start, also write a summary
        if (current != null) {
            val durSec = (now - current.startWallMs) / 1000.0
            writeJsonLine(
                JSONObject()
                    .put("type", "flight_summary")
                    .put("flight_id", current.id)
                    .put("start_wall", isoUtc(current.startWallMs))
                    .put("end_wall", isoUtc(now))
                    .put("duration_sec", durSec)
                    .put("start_mode", current.startMode)
                    .put("end_mode", mode.toWire())
            )
            inFlight = null
        }
    }

    // ---- I/O ----
    private suspend fun writeJsonLine(obj: JSONObject) {
        mutex.withLock {
            // Ensure dir exists (in case it was wiped)
            if (!dir.exists()) dir.mkdirs()

            // If the main path was deleted (e.g., user cleared logs), drop the stale handle.
            val main = File(dir, AppPaths.LOG_FILE_BASE)
            if (!main.exists()) {
                writer?.runCatching { flush(); close() }
                writer = null
            }

            rotateIfNeededLocked()
            if (writer == null) openWriterIfNeededLocked()

            writer!!.apply {
                append(obj.toString())
                append('\n')
                flush()
            }
        }
    }

    private fun openWriterIfNeeded() = runBlocking { mutex.withLock { openWriterIfNeededLocked() } }
    private fun openWriterIfNeededLocked() {
        if (!dir.exists()) dir.mkdirs()
        if (writer != null) return
        val file = File(dir, AppPaths.LOG_FILE_BASE)
        writer = BufferedWriter(FileWriter(file, /*append=*/true))
    }

    private fun rotateIfNeededLocked() {
        val file = File(dir, AppPaths.LOG_FILE_BASE)
        if (!file.exists()) {
            // If file was removed externally, make sure we reopen on next write.
            writer?.runCatching { flush(); close() }
            writer = null
            return
        }
        if (file.length() <= AppPaths.LOG_MAX_BYTES) return

        // Close current (important on Android/Linux so the inode isn’t “hidden open”)
        writer?.runCatching { flush(); close() }
        writer = null

        // shift: .(KEEP) <- .(KEEP-1) <- ... <- .1 <- main
        val maxIdx = AppPaths.LOG_KEEP
        val last = File(dir, "flightlog.$maxIdx.jsonl")
        if (last.exists()) last.delete()

        for (i in maxIdx - 1 downTo 1) {
            val from = File(dir, "flightlog.$i.jsonl")
            val to = File(dir, "flightlog.${i + 1}.jsonl")
            if (from.exists()) from.renameTo(to)
        }
        val main = File(dir, AppPaths.LOG_FILE_BASE)
        if (main.exists()) main.renameTo(File(dir, "flightlog.1.jsonl"))

        // reopen new main
        openWriterIfNeededLocked()
    }

    // ---- Utils ----
    private fun makeFlightId(startWallMs: Long): String {
        // e.g., 20251103-152835-001
        val d = Date(startWallMs)
        val base = String.format(Locale.US, "%tY%<tm%<td-%<tH%<tM%<tS", d)
        val n = seq.getAndIncrement() % 1000
        return "%s-%03d".format(Locale.US, base, n)
    }

    private fun isoUtc(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(ms))
    }

    private fun EventMode.toWire(): String = if (this == EventMode.FORCED) "forced" else "auto"

}
