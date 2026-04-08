// app/src/main/java/com/example/flightcue/data/replay/RecordingParser.kt
package com.example.flightcue.data.replay

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.math.max

// RecordingParser.kt
// Parses recorded sensor data files (CSV, TSV, TXT, ZIP) into a Recording object.
// Supports SensorRecord format, combined tables, and separate accel/baro files.
// Automatically infers the time base and normalises all timestamps to elapsed seconds.

data class Marker(
    val name: String,   // e.g. "TAKEOFF", "LANDING"
    val tSec: Double
)

class Recording(
    val accelTs: DoubleArray,
    val ax: DoubleArray, val ay: DoubleArray, val az: DoubleArray,
    val baroTs: DoubleArray,
    val pHpa: DoubleArray,
    val markers: List<Marker> = emptyList()
) {
    val durationSec: Double = listOf(
        accelTs.lastOrNull() ?: 0.0,
        baroTs.lastOrNull() ?: 0.0
    ).maxOrNull() ?: 0.0

    fun markerSec(name: String): Double? =
        markers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.tSec
}

object RecordingParser {

    fun parse(context: Context, uri: Uri): Recording {
        val r = context.contentResolver
        return if (isZip(r, uri)) parseZip(r, uri) else parseTextStreaming(r, uri)
    }

    // ---------- ZIP ----------

    private fun isZip(resolver: ContentResolver, uri: Uri): Boolean {
        resolver.openInputStream(uri)?.use { raw ->
            val bis = BufferedInputStream(raw)
            val sig = ByteArray(4)
            val n = bis.read(sig)
            return (n >= 2 && sig[0] == 0x50.toByte() && sig[1] == 0x4B.toByte())
        }
        return false
    }

    private fun parseZip(resolver: ContentResolver, uri: Uri): Recording {
        resolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zis ->
                var accelParsed: AccelParsed? = null
                var baroParsed: BaroParsed? = null
                var combinedParsed: Recording? = null

                var e: ZipEntry? = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        val name = e.name.lowercase(Locale.US)
                        val br = BufferedReader(InputStreamReader(zis))
                        when {
                            name.contains("combined") ||
                                    (name.endsWith(".csv") || name.endsWith(".tsv") || name.endsWith(".txt")) &&
                                    !name.contains("accel") && !name.contains("baro") &&
                                    !name.contains("barometer") && !name.contains("pressure") -> {
                                combinedParsed = parseCombinedStreaming(br)
                            }

                            (name.contains("accel")) && (name.endsWith(".csv") || name.endsWith(".tsv") || name.endsWith(".txt")) -> {
                                accelParsed = parseAccelStreaming(br)
                            }

                            // accept baro / barometer / pressure files
                            (name.contains("baro") || name.contains("barometer") || name.contains("pressure")) &&
                                    (name.endsWith(".csv") || name.endsWith(".tsv") || name.endsWith(".txt")) -> {
                                baroParsed = parseBaroStreaming(br)
                            }

                            // Some sensors drop a single SensorRecord .txt inside the ZIP
                            name.endsWith(".txt") -> {
                                combinedParsed = parseSensorRecordStreaming(br)
                            }
                        }
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }

                combinedParsed?.let { return it }
                val a = accelParsed ?: error("ZIP missing accel file (e.g. accel.csv/accel.txt)")
                val b = baroParsed ?: emptyBaro()
                return mergeAccelBaro(a, b)
            }
        }
        error("Unable to open zip")
    }

    // ---------- TEXT / CSV (streaming) ----------

    private fun parseTextStreaming(resolver: ContentResolver, uri: Uri): Recording {
        val fmt = sniffFormat(resolver, uri)
        resolver.openInputStream(uri)?.use { raw ->
            BufferedReader(InputStreamReader(raw)).use { br ->
                return when (fmt) {
                    FileFormat.SENSOR_RECORD -> parseSensorRecordStreaming(br)
                    FileFormat.COMBINED_TABLE -> parseCombinedStreaming(br)
                }
            }
        }
        error("Unable to open input")
    }

    private enum class FileFormat { SENSOR_RECORD, COMBINED_TABLE }

    private fun sniffFormat(resolver: ContentResolver, uri: Uri): FileFormat {
        resolver.openInputStream(uri)?.use { raw ->
            BufferedReader(InputStreamReader(raw)).use { br ->
                var inspected = 0
                while (true) {
                    val line = br.readLine() ?: break
                    if (line.isBlank()) continue
                    inspected++
                    if (looksLikeSensorRecordLine(line)) return FileFormat.SENSOR_RECORD
                    if (inspected >= 32) break
                }
            }
        }
        return FileFormat.COMBINED_TABLE
    }

    private fun looksLikeSensorRecordLine(line: String): Boolean {
        if (line.indexOf(';') >= 0 && line.count { it == ';' } == 1) return true // markers like "Landing;..."
        var c = 0
        for (ch in line) if (ch == ':') { c++; if (c >= 1) return true }        // colon-style sensor rows
        return false
    }

    // ---------- Streaming parse implementations ----------

    private data class AccelParsed(
        val ts: DoubleArray,
        val ax: DoubleArray,
        val ay: DoubleArray,
        val az: DoubleArray,
        val markers: List<Marker> = emptyList()
    )

    private data class BaroParsed(
        val ts: DoubleArray,
        val p: DoubleArray,
        val markers: List<Marker> = emptyList()
    )

    private data class TimeBase(val t0: Double, val scaleToSec: Double)

    private fun inferTimeBase(ticks: DoubleArray): TimeBase {
        if (ticks.isEmpty()) return TimeBase(0.0, 1.0)

        val diffs = ArrayList<Double>(max(1, ticks.size - 1))
        for (i in 1 until ticks.size) {
            val d = ticks[i] - ticks[i - 1]
            if (d > 0) diffs += d
        }
        val med = diffs.medianOrDefault(10.0)

        val scaleToSec = when {
            med >= 1e6 -> 1.0 / 1e9 // ns
            med >= 1e3 -> 1.0 / 1e6 // µs
            med >= 1.0 -> 1.0 / 1e3 // ms
            else       -> 1.0       // seconds
        }
        return TimeBase(t0 = ticks[0], scaleToSec = scaleToSec)
    }

    private fun normalizeWithBase(ticks: DoubleArray, base: TimeBase): DoubleArray {
        if (ticks.isEmpty()) return ticks
        val out = DoubleArray(ticks.size)
        for (i in ticks.indices) out[i] = (ticks[i] - base.t0) * base.scaleToSec
        return out
    }

    private fun tickToSec(tick: Double, base: TimeBase): Double =
        (tick - base.t0) * base.scaleToSec

    private fun normalizeMarkerName(labelRaw: String): String {
        return when (labelRaw.trim().lowercase(Locale.US)) {
            "takeoff" -> "TAKEOFF"
            "landing" -> "LANDING"
            else -> labelRaw.trim().uppercase(Locale.US)
        }
    }

    /**
     * Full SensorRecord reader:
     * Accepts mixed lines:
     *  - ts:ax:ay:az   → accel
     *  - ts:p          → baro
     *  - Landing;ts    → marker
     */
    private fun parseSensorRecordStreaming(br: BufferedReader): Recording {
        val tA = GrowDoubles(1 shl 17)  // 128K capacity
        val ax = GrowDoubles(1 shl 17)
        val ay = GrowDoubles(1 shl 17)
        val az = GrowDoubles(1 shl 17)
        val tB = GrowDoubles(1 shl 16)  // 64K capacity
        val pp = GrowDoubles(1 shl 16)

        val markerTicks = ArrayList<Pair<String, Double>>(8)

        while (true) {
            val line = br.readLine() ?: break
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) continue

            // Marker line: "Landing;12345"
            if (s.indexOf(';') >= 0 && s.count { it == ';' } == 1) {
                val parts = s.split(';', limit = 2)
                val label = parts.getOrNull(0)?.trim().orEmpty()
                val tRaw = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
                if (label.isNotBlank() && tRaw != null) {
                    markerTicks += normalizeMarkerName(label) to tRaw
                }
                continue
            }

            val parts = fastSplitGeneric(s) ?: continue
            when {
                parts.size >= 4 -> {
                    val t = parts[0].toDoubleOrNull() ?: continue
                    val x = parts[1].toDoubleOrNull() ?: continue
                    val y = parts[2].toDoubleOrNull() ?: continue
                    val z = parts[3].toDoubleOrNull() ?: continue
                    tA.add(t); ax.add(x); ay.add(y); az.add(z)
                }
                parts.size == 2 -> {
                    val t = parts[0].toDoubleOrNull() ?: continue
                    val p = parts[1].toDoubleOrNull() ?: continue
                    tB.add(t); pp.add(p)
                }
            }
        }

        if (tA.isEmpty() && tB.isEmpty()) error("No rows parsed")

        val refTicks = if (!tA.isEmpty()) tA.toArray() else tB.toArray()
        val base = inferTimeBase(refTicks)

        val aTs = normalizeWithBase(tA.toArray(), base)
        val bTs = normalizeWithBase(tB.toArray(), base)

        val markers = markerTicks.map { (name, tick) ->
            Marker(name = name, tSec = tickToSec(tick, base))
        }.sortedBy { it.tSec }

        return Recording(
            accelTs = aTs,
            ax = ax.toArray(),
            ay = ay.toArray(),
            az = az.toArray(),
            baroTs = bTs,
            pHpa = pp.toArray(),
            markers = markers
        )
    }

    private fun parseAccelStreaming(br: BufferedReader): AccelParsed {
            val tBuf = GrowDoubles(1 shl 17)  // ✅ 128K capacity
            val xBuf = GrowDoubles(1 shl 17)
            val yBuf = GrowDoubles(1 shl 17)
            val zBuf = GrowDoubles(1 shl 17)

        val markerTicks = ArrayList<Pair<String, Double>>(8)

        while (true) {
            val line = br.readLine() ?: break
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) continue

            if (s.indexOf(';') >= 0 && s.count { it == ';' } == 1) {
                val parts = s.split(';', limit = 2)
                val label = parts.getOrNull(0)?.trim().orEmpty()
                val tRaw = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
                if (label.isNotBlank() && tRaw != null) {
                    markerTicks += normalizeMarkerName(label) to tRaw
                }
                continue
            }

            val parts = fastSplit4(s) ?: continue
            val t = parts[0].toDoubleOrNull() ?: continue
            val ax = parts[1].toDoubleOrNull() ?: continue
            val ay = parts[2].toDoubleOrNull() ?: continue
            val az = parts[3].toDoubleOrNull() ?: continue

            tBuf.add(t); xBuf.add(ax); yBuf.add(ay); zBuf.add(az)
        }

        if (tBuf.isEmpty()) error("No accelerometer rows")

        val base = inferTimeBase(tBuf.toArray())
        val tsSec = normalizeWithBase(tBuf.toArray(), base)

        val markers = markerTicks.map { (name, tick) ->
            Marker(name = name, tSec = tickToSec(tick, base))
        }.sortedBy { it.tSec }

        return AccelParsed(tsSec, xBuf.toArray(), yBuf.toArray(), zBuf.toArray(), markers)
    }

    private fun parseBaroStreaming(br: BufferedReader): BaroParsed {
        val tBuf = GrowDoubles(1 shl 16)
        val pBuf = GrowDoubles(1 shl 16)

        val markerTicks = ArrayList<Pair<String, Double>>(8)

        while (true) {
            val line = br.readLine() ?: break
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) continue

            if (s.indexOf(';') >= 0 && s.count { it == ';' } == 1) {
                val parts = s.split(';', limit = 2)
                val label = parts.getOrNull(0)?.trim().orEmpty()
                val tRaw = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
                if (label.isNotBlank() && tRaw != null) {
                    markerTicks += normalizeMarkerName(label) to tRaw
                }
                continue
            }

            val parts = fastSplitAtLeast2(s) ?: continue
            val t = parts[0].toDoubleOrNull() ?: continue
            val p = parts[1].toDoubleOrNull() ?: continue
            tBuf.add(t); pBuf.add(p)
        }

        if (tBuf.isEmpty()) {
            // No baro rows; without a reference timebase we skip markers here.
            return emptyBaro()
        }

        val base = inferTimeBase(tBuf.toArray())
        val tsSec = normalizeWithBase(tBuf.toArray(), base)

        val markers = markerTicks.map { (name, tick) ->
            Marker(name = name, tSec = tickToSec(tick, base))
        }.sortedBy { it.tSec }

        return BaroParsed(tsSec, pBuf.toArray(), markers)
    }

    private fun parseCombinedStreaming(br: BufferedReader): Recording {
        var headerIdx: IntArray? = null // [iTs,iAx,iAy,iAz,iP]
        val ts = GrowDoubles(1 shl 17)
        val ax = GrowDoubles(1 shl 17)
        val ay = GrowDoubles(1 shl 17)
        val az = GrowDoubles(1 shl 17)
        val bt = GrowDoubles(1 shl 16)
        val pp = GrowDoubles(1 shl 16)

        val markerTicks = ArrayList<Pair<String, Double>>(8)

        var row = 0
        while (true) {
            val line = br.readLine() ?: break
            val raw = line.trim()
            if (raw.isEmpty() || raw.startsWith("#")) continue

            // marker line: "Landing;12345"
            if (raw.indexOf(';') >= 0 && raw.count { it == ';' } == 1) {
                val parts = raw.split(';', limit = 2)
                val label = parts.getOrNull(0)?.trim().orEmpty()
                val tRaw = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
                if (label.isNotBlank() && tRaw != null) {
                    markerTicks += normalizeMarkerName(label) to tRaw
                }
                continue
            }

            val parts = fastSplitGeneric(raw) ?: continue
            if (parts.isEmpty()) continue

            if (row == 0 && parts.any { it.any { ch -> ch.isLetter() || ch == '_' } }) {
                headerIdx = mapHeader(parts)
                row++
                continue
            }

            val idx = headerIdx ?: intArrayOf(0, 1, 2, 3, -1)
            fun col(i: Int): String? = if (i >= 0 && i < parts.size) parts[i] else null

            val tS = col(idx[0]) ?: continue
            val axS = col(idx[1]) ?: continue
            val ayS = col(idx[2]) ?: continue
            val azS = col(idx[3]) ?: continue

            val t = tS.toDoubleOrNull() ?: continue
            val x = axS.toDoubleOrNull() ?: continue
            val y = ayS.toDoubleOrNull() ?: continue
            val z = azS.toDoubleOrNull() ?: continue

            ts.add(t); ax.add(x); ay.add(y); az.add(z)

            val ip = idx[4]
            if (ip >= 0) {
                val pS = col(ip)
                val p = pS?.toDoubleOrNull()
                if (p != null) { bt.add(t); pp.add(p) }
            }

            row++
        }

        if (ts.isEmpty()) error("No rows parsed")

        val base = inferTimeBase(ts.toArray())
        val tsSec = normalizeWithBase(ts.toArray(), base)
        val bSec = if (bt.isEmpty()) DoubleArray(0) else normalizeWithBase(bt.toArray(), base)

        val markers = markerTicks.map { (name, tick) ->
            Marker(name = name, tSec = tickToSec(tick, base))
        }.sortedBy { it.tSec }

        return Recording(
            accelTs = tsSec,
            ax = ax.toArray(),
            ay = ay.toArray(),
            az = az.toArray(),
            baroTs = bSec,
            pHpa = pp.toArray(),
            markers = markers
        )
    }

    // ---------- header & splitting helpers ----------

    private fun mapHeader(names: List<String>): IntArray {
        val lc = names.map { it.lowercase(Locale.US) }
        val iTs = lc.indexOfFirst { it.contains("ts") && it.contains("sec") }
            .takeIf { it >= 0 } ?: lc.indexOfFirst { it == "t" || it.startsWith("time") }
            .takeIf { it >= 0 } ?: 0
        val iAx = lc.indexOfFirst { it.startsWith("ax") }.takeIf { it >= 0 } ?: 1
        val iAy = lc.indexOfFirst { it.startsWith("ay") }.takeIf { it >= 0 } ?: 2
        val iAz = lc.indexOfFirst { it.startsWith("az") }.takeIf { it >= 0 } ?: 3
        val iP = lc.indexOfFirst { (it.startsWith("p") && it.contains("hpa")) || it.startsWith("pressure") }
            .takeIf { it >= 0 } ?: -1
        return intArrayOf(iTs, iAx, iAy, iAz, iP)
    }

    /** Expect exactly 4 columns (ts:ax:ay:az) using common delimiters. */
    private fun fastSplit4(s: String): Array<String>? {
        var a = -1; var b = -1; var c = -1
        val n = s.length
        val buf = CharArray(n)
        var i = 0
        while (i < n) {
            val ch = s[i]
            buf[i] = if (ch == ',') '.' else ch
            if (a < 0 && (ch == ':' || ch == ';' || ch == ',' || ch == '\t')) a = i
            else if (a >= 0 && b < 0 && (ch == ':' || ch == ';' || ch == ',' || ch == '\t')) b = i
            else if (b >= 0 && c < 0 && (ch == ':' || ch == ';' || ch == ',' || ch == '\t')) c = i
            i++
        }
        if (a <= 0 || b <= a + 1 || c <= b + 1) return null
        val d = s.indexOfAny(charArrayOf(':', ';', ',', '\t'), startIndex = c + 1)
        if (d < 0) return arrayOf(
            String(buf, 0, a),
            String(buf, a + 1, b - a - 1),
            String(buf, b + 1, c - b - 1),
            String(buf, c + 1, n - c - 1)
        )
        return arrayOf(
            String(buf, 0, a),
            String(buf, a + 1, b - a - 1),
            String(buf, b + 1, c - b - 1),
            String(buf, c + 1, d - c - 1)
        )
    }

    /** At least 2 columns (t, p) for baro files. */
    private fun fastSplitAtLeast2(s: String): Array<String>? {
        var a = -1
        val n = s.length
        val buf = CharArray(n)
        for (i in 0 until n) {
            val ch = s[i]
            buf[i] = if (ch == ',') '.' else ch
            if (a < 0 && (ch == ':' || ch == ';' || ch == ',' || ch == '\t')) a = i
        }
        if (a <= 0 || a >= n - 1) return null
        return arrayOf(String(buf, 0, a), String(buf, a + 1, n - a - 1))
    }

    /** Generic small splitter for header/CSV rows (keeps memory low). */
    private fun fastSplitGeneric(s: String): List<String>? {
        val out = ArrayList<String>(8)
        val n = s.length
        var start = 0
        var i = 0
        while (i <= n) {
            if (i == n || s[i] == ':' || s[i] == ';' || s[i] == ',' || s[i] == '\t') {
                if (i > start) {
                    val sub = s.substring(start, i).trim().replace(',', '.')
                    if (sub.isNotEmpty()) out.add(sub)
                }
                start = i + 1
            }
            i++
        }
        return out
    }

    // ---------- arrays & helpers ----------

    private fun emptyBaro() = BaroParsed(DoubleArray(0), DoubleArray(0), emptyList())

    private fun mergeAccelBaro(a: AccelParsed, b: BaroParsed): Recording {
        val markers = (a.markers + b.markers).sortedBy { it.tSec }
        return Recording(
            accelTs = a.ts,
            ax = a.ax,
            ay = a.ay,
            az = a.az,
            baroTs = b.ts,
            pHpa = b.p,
            markers = markers
        )
    }

    private fun List<Double>.medianOrDefault(def: Double): Double {
        if (isEmpty()) return def
        val s = this.sorted()
        val m = s.size
        return if (m % 2 == 1) s[m / 2] else 0.5 * (s[m / 2 - 1] + s[m / 2])
    }

    /** Growable primitive double buffer (no boxing). */
    private class GrowDoubles(initCap: Int = 1024) {
        private var a = DoubleArray(initCap)
        private var n = 0
        fun add(v: Double) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v }
        fun isEmpty() = n == 0
        fun toArray(): DoubleArray = a.copyOf(n)
    }
}
