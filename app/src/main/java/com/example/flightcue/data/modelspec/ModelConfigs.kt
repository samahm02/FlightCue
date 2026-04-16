@file:Suppress("MemberVisibilityCanBePrivate")

package com.example.flightcue.data.modelspec

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.round

private const val TAG = "ModelConfigs"

/**
 * Parsed detection profile loaded from profile.json.
 * Drives feature windowing, GRU sequence construction, and the detector FSM.
 */
data class ModelProfile(
    val profile: String,      // e.g. "takeoff"
    val event: String,        // "TAKEOFF" / "LANDING"
    val threshold: Double,    // detection threshold (T_on)
    val hystRatio: Double,    // hysteresis ratio (T_off = threshold * hystRatio); GRU: 1.0 (disabled)
    val nFeatures: Int,
    val seed: Int?,
    val posIndex: Int,        // legacy; usually 0 for scalar probability output

    val modelType: String = "unknown", // e.g. "gru"
    val triggerK: Int = 1,             // consecutive windows required to fire

    // GRU / sequence settings
    val seqLen: Int = 1,
    val labelIdx: Int = 0,
    val futureWindows: Int = 0,
    val hopSec: Double = Double.NaN,
    val winLenSec: Double = Double.NaN,
    val onnxInputName: String? = null,
    val onnxOutputName: String? = null,

    // Optional post-processing knobs (defaults are no-ops)
    val smoothing: Int = 0,
    val minRun: Int = 0,
    val cooldownMs: Long = 0,
    val minSepMs: Long = 0
) {
    /** Returns (T_on, T_off). For GRU hysteresis is disabled, so both equal [threshold]. */
    fun thresholds(): Pair<Double, Double> {
        val on = threshold
        val off = threshold
        return on to off
    }
}

/** Ordered feature names and optional schema version loaded from features.json. */
data class FeatureSchema(
    val names: List<String>,
    val version: String? = null
)

/** Parses model asset files (features, scaler, profile) from the app's assets directory. */
object ModelConfigs {

    /** Loads and parses the feature schema for the given model direction. */
    @JvmStatic
    fun parseFeatures(context: Context, isTakeoff: Boolean): FeatureSchema {
        val (event, dir) = eventAndDir(isTakeoff)
        val eventLower = event.lowercase(Locale.US)

        val fname = pickAsset(
            context = context,
            dir = dir,
            label = "features (*.features.json)",
            preferredNames = listOf(
                "$eventLower.features.json",
                "features.json"
            ),
            suffixes = listOf(".features.json", "features.json"),
            preferredSubstrings = listOf(eventLower)
        )

        context.assets.open("$dir/$fname").use { stream ->
            val text = stream.readBytes().decodeToString()
            val schema = parseFeaturesText(text, fname)
            val first = schema.names.firstOrNull() ?: "n/a"
            val last = schema.names.lastOrNull() ?: "n/a"
            Log.i(
                TAG,
                "$event features: count=${schema.names.size}, first=$first, last=$last, version=${schema.version ?: "n/a"} (file=$fname)"
            )
            return schema
        }
    }

    /** Loads and parses the detection profile for the given model direction. */
    @JvmStatic
    fun parseProfile(context: Context, isTakeoff: Boolean): ModelProfile {
        val (event, dir) = eventAndDir(isTakeoff)
        val eventLower = event.lowercase(Locale.US)

        val fname = pickAsset(
            context = context,
            dir = dir,
            label = "profile (*profile*.json)",
            preferredNames = listOf(
                "profile.json",
                "$eventLower.profile.json"
            ),
            suffixes = listOf("profile.json", ".profile.json"),
            preferredSubstrings = listOf(eventLower)
        )

        context.assets.open("$dir/$fname").use { stream ->
            // Pass the directory-derived event so the profile never falls back to UNKNOWN.
            val profile = parseProfile(stream, sourceName = fname, defaultEventUpper = event)

            val (on, off) = profile.thresholds()
            Log.i(
                TAG,
                "$event profile: model_type=${profile.modelType}, trigger_k=${profile.triggerK}, " +
                        "T_on=${round4(on)}, hyst_ratio=${round3(profile.hystRatio)}, T_off=${round4(off)}, " +
                        "seq_len=${profile.seqLen}, label_idx=${profile.labelIdx}, future_windows=${profile.futureWindows}, " +
                        "hop_s=${round3(profile.hopSec)}, win_len_s=${round3(profile.winLenSec)}, " +
                        "onnx_in=${profile.onnxInputName ?: "n/a"}, onnx_out=${profile.onnxOutputName ?: "n/a"}, " +
                        "n_features=${profile.nFeatures} (file=$fname)"
            )
            return profile
        }
    }

    /**
     * Loads mean and scale arrays from scaler.npz.
     * Both are required for StandardScaler normalisation: (x - mean) / scale.
     */
    @JvmStatic
    fun parseScaler(context: Context, schema: FeatureSchema, isTakeoff: Boolean): Pair<DoubleArray, DoubleArray> {
        val (event, dir) = eventAndDir(isTakeoff)

        val scalerName = tryPickAssetOrNull(
            context = context,
            dir = dir,
            preferredNames = listOf("scaler.npz"),
            suffixes = listOf(".npz")
        ) ?: throw IllegalArgumentException("$event: scaler.npz not found in @$dir")

        context.assets.open("$dir/$scalerName").use { stream ->
            val (mean, scale) = readScalerNpz(stream, expectedLen = schema.names.size)
                ?: throw IllegalArgumentException("$event: failed to parse scaler.npz")

            Log.i(TAG, "$event scaler: mean[0]=${mean[0]}, scale[0]=${scale[0]} (file=$scalerName)")
            return mean to scale
        }
    }

    // ------------------------------------------------------------
    // Profile parsers
    // ------------------------------------------------------------

    /** Parses a profile from a stream, using [defaultEventUpper] if the JSON omits the event field. */
    private fun parseProfile(stream: InputStream, sourceName: String, defaultEventUpper: String): ModelProfile {
        val text = stream.readBytes().decodeToString()
        return parseProfile(JSONObject(text), sourceName, defaultEventUpper = defaultEventUpper)
    }

    private fun parseProfile(json: JSONObject, sourceName: String, defaultEventUpper: String?): ModelProfile {
        // Profile name is optional; use a sensible fallback.
        val profileName = json.optString("profile", json.optString("model_name", json.optString("name", "")))
            .ifBlank { "unknown" }

        // threshold and n_features are required; throw if absent.
        val threshold = getFlexibleDouble(json, "threshold")
            ?: throw IllegalArgumentException("$sourceName: missing/non-numeric 'threshold'")
        val nFeatures = getFlexibleDouble(json, "n_features")?.toInt()
            ?: json.optString("n_features", "").trim().toIntOrNull()
            ?: throw IllegalArgumentException("$sourceName: missing/non-numeric 'n_features'")

        val seed: Int? = json.optOrNullInt("seed")

        // Scalar probability output is standard; posIndex kept for legacy compatibility.
        val posIndex = json.optOrNullInt("head_index")
            ?: json.optOrNullInt("onnx_output_pos_index")
            ?: 0

        val modelType = json.optString("model_type", json.optString("modelType", "unknown"))
            .trim()
            .lowercase(Locale.US)

        val triggerK = (json.optOrNullInt("trigger_k")
            ?: json.optOrNullInt("triggerK")
            ?: 1).coerceAtLeast(1)

        val seqLen = json.optInt("seq_len", 1).coerceAtLeast(1)
        val labelIdxRaw = json.optInt("label_idx", 0)
        val labelIdx = labelIdxRaw.coerceIn(0, seqLen - 1)

        // Timing fields can be nested under "timing" or placed at root; support both.
        val timing = json.optJSONObject("timing")
        val hopSec = readTimingDouble(json, timing, "hop_s")
        val winLenSec = readTimingDouble(json, timing, "win_len_s")

        val futureWindowsDefault = maxOf(0, (seqLen - 1 - labelIdx))
        val futureWindows =
            timing?.optOrNullInt("future_windows")
                ?: json.optOrNullInt("future_windows")
                ?: futureWindowsDefault

        val onnx = json.optJSONObject("onnx")
        // optString requires a non-null fallback; use "" and convert blank to null.
        val inName = onnx?.optString("input_name", "")?.takeIf { it.isNotBlank() }
        val outName = onnx?.optString("output_name", "")?.takeIf { it.isNotBlank() }

        // Event can be under various keys; fall back to directory-provided event if missing.
        val eventFromJson = readEventUpper(json)
        val event = (
                eventFromJson
                    ?: defaultEventUpper?.trim()?.uppercase(Locale.US)
                    ?: inferEventFromText(profileName)
                    ?: "UNKNOWN"
                ).uppercase(Locale.US)

        if (event == "UNKNOWN") {
            Log.w(TAG, "$sourceName: event missing; could not infer. Using UNKNOWN.")
        }

        // Hysteresis is legacy. GRU defaults to 1.0 (disabled) unless explicitly provided.
        val hystRaw = getFlexibleDouble(json, "hyst_ratio")
        val hystDefault = when (modelType) {
            "gru" -> 1.0
            else  -> defaultHystRatioFor(event)
        }
        var hyst = hystRaw ?: run {
            Log.i(TAG, "$sourceName: hyst_ratio missing -> using default=$hystDefault for model_type=$modelType event=$event")
            hystDefault
        }

        // Clamp for safety; allow exactly 1.0 for GRU (disabled hysteresis).
        val clamped = when (modelType) {
            "gru" -> hyst.coerceIn(0.05, 1.0)
            else  -> hyst.coerceIn(0.05, 0.99)
        }
        if (clamped != hyst) {
            Log.w(TAG, "$sourceName: hyst_ratio=$hyst out of range. Clamped to $clamped")
            hyst = clamped
        }

        // Optional post-processing knobs; clamp negatives to 0.
        var smoothing  = json.optOrNullInt("smoothing")   ?: 0
        var minRun     = json.optOrNullInt("min_run")      ?: 0
        var cooldownMs = json.optOrNullLong("cooldown_ms") ?: 0L
        var minSepMs   = json.optOrNullLong("min_sep_ms")  ?: 0L

        if (smoothing  < 0) { Log.w(TAG, "$sourceName: smoothing=$smoothing < 0; clamped to 0");    smoothing  = 0  }
        if (minRun     < 0) { Log.w(TAG, "$sourceName: min_run=$minRun < 0; clamped to 0");         minRun     = 0  }
        if (cooldownMs < 0) { Log.w(TAG, "$sourceName: cooldown_ms=$cooldownMs < 0; clamped to 0"); cooldownMs = 0L }
        if (minSepMs   < 0) { Log.w(TAG, "$sourceName: min_sep_ms=$minSepMs < 0; clamped to 0");   minSepMs   = 0L }

        return ModelProfile(
            profile = profileName,
            event = event,
            threshold = threshold,
            hystRatio = hyst,
            nFeatures = nFeatures,
            seed = seed,
            posIndex = posIndex,

            modelType = modelType,
            triggerK = triggerK,

            seqLen = seqLen,
            labelIdx = labelIdx,
            futureWindows = futureWindows,
            hopSec = hopSec,
            winLenSec = winLenSec,
            onnxInputName = inName,
            onnxOutputName = outName,

            smoothing = smoothing,
            minRun = minRun,
            cooldownMs = cooldownMs,
            minSepMs = minSepMs
        )
    }

    private fun readTimingDouble(json: JSONObject, timing: JSONObject?, key: String): Double {
        val v1 = timing?.optDouble(key, Double.NaN) ?: Double.NaN
        if (!v1.isNaN()) return v1

        val v2 = json.optDouble(key, Double.NaN)
        if (!v2.isNaN()) return v2

        val altKey = when (key) {
            "hop_s"     -> "hop_sec"
            "win_len_s" -> "win_len_sec"
            else        -> null
        }
        if (altKey != null) {
            val v3 = json.optDouble(altKey, Double.NaN)
            if (!v3.isNaN()) return v3
        }
        return Double.NaN
    }

    private fun readEventUpper(json: JSONObject): String? {
        val keys = listOf("event", "export_event", "exportEvent", "exported_event", "model_event")
        for (k in keys) {
            val s = json.optString(k, "").trim()
            if (s.isNotEmpty() && !s.equals("unknown", ignoreCase = true)) {
                return s.uppercase(Locale.US)
            }
        }
        return null
    }

    private fun inferEventFromText(text: String): String? {
        val t = text.lowercase(Locale.US)
        return when {
            "takeoff" in t -> "TAKEOFF"
            "landing" in t -> "LANDING"
            else           -> null
        }
    }

    // ------------------------------------------------------------
    // Feature schema parsing
    // ------------------------------------------------------------

    private fun parseFeaturesText(text: String, sourceName: String): FeatureSchema {
        val root = JSONTokener(text).nextValue()
        return when (root) {
            is JSONObject -> {
                // optString requires a non-null fallback; use "" and convert blank to null.
                val version = root.optString("version", root.optString("schema_version", ""))
                    .ifBlank { null }

                val arr = root.optJSONArray("features")
                    ?: root.optJSONArray("names")
                    ?: root.optJSONArray("columns")
                    ?: root.optJSONObject("schema")?.optJSONArray("features")
                    ?: throw IllegalArgumentException("$sourceName: could not find features array")

                val names = jsonArrayToStringList(arr)
                if (names.isEmpty()) throw IllegalArgumentException("$sourceName: features list is empty")
                FeatureSchema(names, version)
            }
            is JSONArray -> {
                val names = jsonArrayToStringList(root)
                if (names.isEmpty()) throw IllegalArgumentException("$sourceName: features list is empty")
                FeatureSchema(names, version = null)
            }
            else -> throw IllegalArgumentException("$sourceName: expected JSONObject or JSONArray for features")
        }
    }

    // ------------------------------------------------------------
    // scaler.npz parsers
    // ------------------------------------------------------------

    /**
     * Minimal .npy parser for 1D float32/float64 little-endian arrays.
     * Supports .npy format versions 1, 2, and 3.
     */
    private fun parseNpy1dToDouble(npyBytes: ByteArray): DoubleArray? {
        if (npyBytes.size < 12) return null
        val magic = byteArrayOf(
            0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(),
            'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte()
        )
        for (i in magic.indices) if (npyBytes[i] != magic[i]) return null

        val major = npyBytes[6].toInt() and 0xFF

        val headerLen: Int
        val headerStart: Int
        if (major == 1) {
            headerLen = (npyBytes[8].toInt() and 0xFF) or ((npyBytes[9].toInt() and 0xFF) shl 8)
            headerStart = 10
        } else {
            headerLen =
                (npyBytes[8].toInt() and 0xFF) or
                        ((npyBytes[9].toInt() and 0xFF) shl 8) or
                        ((npyBytes[10].toInt() and 0xFF) shl 16) or
                        ((npyBytes[11].toInt() and 0xFF) shl 24)
            headerStart = 12
        }

        if (headerStart + headerLen > npyBytes.size) return null
        val header = npyBytes.copyOfRange(headerStart, headerStart + headerLen).decodeToString()

        val descr   = Regex("'descr'\\s*:\\s*'([^']+)'").find(header)?.groupValues?.get(1) ?: return null
        val fortran = Regex("'fortran_order'\\s*:\\s*(True|False)").find(header)?.groupValues?.get(1) ?: "False"
        if (fortran == "True") return null

        // [^)] is sufficient inside a character class; no escaping needed.
        val shapeText = Regex("'shape'\\s*:\\s*\\(([^)]*)\\)").find(header)?.groupValues?.get(1) ?: return null
        val dims = shapeText.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull() }

        if (dims.size != 1) return null
        val n = dims[0]
        if (n <= 0) return null

        val dataStart = headerStart + headerLen
        if (dataStart >= npyBytes.size) return null
        val data = npyBytes.copyOfRange(dataStart, npyBytes.size)

        return when (descr) {
            "<f8" -> {
                val need = n * 8
                if (data.size < need) return null
                val bb = ByteBuffer.wrap(data, 0, need).order(ByteOrder.LITTLE_ENDIAN)
                DoubleArray(n) { bb.double }
            }
            "<f4" -> {
                val need = n * 4
                if (data.size < need) return null
                val bb = ByteBuffer.wrap(data, 0, need).order(ByteOrder.LITTLE_ENDIAN)
                DoubleArray(n) { bb.float.toDouble() }
            }
            else -> null
        }
    }

    /** Reads scaler.npz and extracts mean and scale arrays required for StandardScaler. */
    private fun readScalerNpz(npzStream: InputStream, expectedLen: Int): Pair<DoubleArray, DoubleArray>? {
        val entries = HashMap<String, ByteArray>()

        ZipInputStream(npzStream).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name ?: continue
                if (!name.endsWith(".npy")) continue

                val base = name.substringAfterLast('/').removeSuffix(".npy").lowercase(Locale.US)
                entries[base] = zis.readAllBytesCompat()
            }
        }

        val meanBytes  = entries["mean"]  ?: entries["center"]                    ?: return null
        val scaleBytes = entries["scale"] ?: entries["std"] ?: entries["scale_"]  ?: return null

        val mean  = parseNpy1dToDouble(meanBytes) ?.takeIf { it.size == expectedLen } ?: return null
        val scale = parseNpy1dToDouble(scaleBytes)?.takeIf { it.size == expectedLen } ?: return null

        return mean to scale
    }

    private fun InputStream.readAllBytesCompat(): ByteArray {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val r = read(buf)
            if (r <= 0) break
            baos.write(buf, 0, r)
        }
        return baos.toByteArray()
    }

    // ------------------------------------------------------------
    // JSON helpers
    // ------------------------------------------------------------

    private fun getFlexibleDouble(json: JSONObject, key: String): Double? {
        if (!json.has(key) || json.isNull(key)) return null
        val asNum = json.optDouble(key, Double.NaN)
        if (!asNum.isNaN()) return asNum
        val s = json.optString(key, "").trim()
        if (s.isEmpty()) return null
        return s.replace(',', '.').toDoubleOrNull()
    }

    private fun JSONObject.optOrNullInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        optInt(key, Int.MIN_VALUE).let { if (it != Int.MIN_VALUE) return it }
        optString(key, "").trim().toIntOrNull()?.let { return it }
        getFlexibleDouble(this, key)?.toInt()?.let { return it }
        return null
    }

    private fun JSONObject.optOrNullLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        val dbl = getFlexibleDouble(this, key)
        if (dbl != null) return dbl.toLong()
        optString(key, "").trim().toLongOrNull()?.let { return it }
        return null
    }

    private fun jsonArrayToStringList(arr: JSONArray): List<String> {
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.get(i)
            val s = when (v) {
                is String     -> v
                is JSONObject -> (
                        v.optString("name", v.optString("feature", v.optString("id", "")))
                        ).ifBlank { null }
                else          -> null
            } ?: throw IllegalArgumentException("features: element $i is not string or object-with-name")
            out += s
        }
        return out
    }

    // ------------------------------------------------------------
    // Asset selection helpers
    // ------------------------------------------------------------

    private fun eventAndDir(isTakeoff: Boolean): Pair<String, String> =
        if (isTakeoff) "TAKEOFF" to AppPaths.TAKEOFF_DIR else "LANDING" to AppPaths.LANDING_DIR

    private fun tryPickAssetOrNull(
        context: Context,
        dir: String,
        preferredNames: List<String>,
        suffixes: List<String>
    ): String? {
        val files = (context.assets.list(dir) ?: emptyArray()).toList()
        if (files.isEmpty()) return null

        for (name in preferredNames) {
            if (name.isNotBlank() && files.contains(name)) return name
        }

        val normalizedSuffixes = suffixes
            .flatMap { raw ->
                val s = raw.trim().removePrefix("*")
                if (s.isBlank()) emptyList()
                else if (s.startsWith(".")) listOf(s, s.drop(1))
                else listOf(s, ".$s")
            }
            .distinct()

        val matches = files.filter { f -> normalizedSuffixes.any { suf -> f.endsWith(suf) } }
        return matches.maxOrNull()
    }

    private fun pickAsset(
        context: Context,
        dir: String,
        label: String,
        preferredNames: List<String>,
        suffixes: List<String>,
        preferredSubstrings: List<String> = emptyList()
    ): String {
        val files = (context.assets.list(dir) ?: emptyArray()).toList()
        if (files.isEmpty()) throw IllegalArgumentException("@$dir: no assets found")

        for (name in preferredNames) {
            if (name.isNotBlank() && files.contains(name)) return name
        }

        val normalizedSuffixes = suffixes
            .flatMap { raw ->
                val s = raw.trim().removePrefix("*")
                if (s.isBlank()) emptyList()
                else if (s.startsWith(".")) listOf(s, s.drop(1))
                else listOf(s, ".$s")
            }
            .distinct()

        val matches = files.filter { f -> normalizedSuffixes.any { suf -> f.endsWith(suf) } }
        if (matches.isEmpty()) {
            throw IllegalArgumentException(
                "@$dir: no $label file found. Tried exact=$preferredNames, suffixes=$suffixes. Available=$files"
            )
        }

        fun score(file: String): Int {
            val lower = file.lowercase(Locale.US)
            var sc = 0
            for (sub in preferredSubstrings) {
                val s = sub.lowercase(Locale.US)
                if (s.isNotBlank() && lower.contains(s)) sc += 100
            }
            for (suf in normalizedSuffixes) {
                if (file.endsWith(suf)) sc += if (suf.startsWith(".")) 20 else 18
            }
            sc += minOf(file.length, 200)
            return sc
        }

        val best = matches.maxByOrNull { score(it) }!!
        if (matches.size > 1) {
            Log.w(TAG, "@$dir: multiple $label matches=$matches -> picked=$best")
        }
        return best
    }

    private fun round3(x: Double): Double = (round(x * 1000.0) / 1000.0)
    private fun round4(x: Double): Double = (round(x * 10000.0) / 10000.0)

    private fun defaultHystRatioFor(eventUpper: String): Double = when (eventUpper) {
        "TAKEOFF" -> 0.70
        "LANDING" -> 0.60
        else      -> 0.75
    }
}