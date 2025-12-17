@file:Suppress("MemberVisibilityCanBePrivate")

package com.example.flightcue.data.modelspec

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream
import java.util.Locale
import kotlin.math.round

private const val TAG = "ModelConfigs"
private const val TAKEOFF_DIR = "models/takeoff"
private const val LANDING_DIR = "models/landing"

/** Parsed profile used by the detector. */
data class ModelProfile(
    val profile: String,         // e.g., "takeoff_0815"
    val event: String,           // e.g., "TAKEOFF" or "LANDING"
    val threshold: Double,       // T_on
    val hystRatio: Double,       // ratio in (0,1); T_off = threshold * hystRatio
    val nFeatures: Int,          // sanity check / diagnostics
    val seed: Int?,              // optional
    val posIndex: Int,           // ONNX positive-class output index
    // Optional post-processing knobs (defaults are NO-OP and logged)
    val smoothing: Int = 0,      // moving-average window (0 = off)
    val minRun: Int = 0,         // consecutive windows to confirm (0 = off)
    val cooldownMs: Long = 0,    // lockout after fire (0 = off)
    val minSepMs: Long = 0       // minimal separation between events (0 = off)
) {
    /** Returns Pair(T_on, T_off). */
    fun thresholds(): Pair<Double, Double> {
        val on = threshold
        val off = (threshold * hystRatio).coerceAtLeast(0.0)
        return on to off
    }
}

data class FeatureSchema(
    val names: List<String>,
    val version: String? = null
)

object ModelConfigs {

    // ------------------------------------------------------------
    // Public API used by FlightDetectionService (Context helpers)
    // ------------------------------------------------------------

    /** Load & parse the *features* schema for TAKEOFF/LANDING from assets. */
    @JvmStatic
    fun parseFeatures(context: Context, isTakeoff: Boolean): FeatureSchema {
        val (event, dir) = eventAndDir(isTakeoff)
        val fname = pickOneAsset(context, dir, ".features.json")
        context.assets.open("$dir/$fname").use { stream ->
            val text = stream.readBytes().decodeToString()
            val schema = parseFeaturesText(text, fname, event)
            val first = schema.names.firstOrNull() ?: "n/a"
            val last = schema.names.lastOrNull() ?: "n/a"
            Log.i(TAG, "$event features: count=${schema.names.size}, first=$first, last=$last, version=${schema.version ?: "n/a"}")
            return schema
        }
    }

    /** Load & parse the *medians* for TAKEOFF/LANDING, aligned to the provided schema. */
    @JvmStatic
    fun parseMedians(context: Context, schema: FeatureSchema, isTakeoff: Boolean): DoubleArray {
        val (event, dir) = eventAndDir(isTakeoff)
        val fname = pickOneAsset(context, dir, ".medians.json")
        val text = context.assets.open("$dir/$fname").use { it.readBytes().decodeToString() }

        val medians = when (val root = JSONTokener(text).nextValue()) {
            is JSONObject -> mediansFromObject(root, schema, fname)
            is JSONArray  -> mediansFromArray(root, schema, fname)
            else -> throw IllegalArgumentException("$fname: expected JSONObject or JSONArray for medians")
        }

        Log.i(TAG, "$event medians: OK (${medians.size} matched)")
        return medians
    }

    /** Load & parse the *profile* for TAKEOFF/LANDING, with safe hysteresis defaults. */
    @JvmStatic
    fun parseProfile(context: Context, isTakeoff: Boolean): ModelProfile {
        val (event, dir) = eventAndDir(isTakeoff)
        val fname = pickOneAsset(context, dir, ".profile.json")
        context.assets.open("$dir/$fname").use { stream ->
            val profile = parseProfile(stream, fname)  // delegates to the stream version below
            // Concise summary (threshold + hysteresis + basic post-proc knobs).
            val (on, off) = profile.thresholds()
            Log.i(
                TAG,
                "$event profile: T_on=${round4(on)}, hyst_ratio=${round3(profile.hystRatio)}, " +
                        "T_off=${round4(off)}, smoothing=${profile.smoothing}, " +
                        "min_run=${profile.minRun}, cooldown=${profile.cooldownMs}ms, " +
                        "min_sep=${profile.minSepMs}ms, n_features=${profile.nFeatures}, pos_idx=${profile.posIndex}"
            )
            return profile
        }
    }

    // ------------------------------------------------------------
    // Stream / JSONObject parsers (also usable in tests)
    // ------------------------------------------------------------

    /** Parse profile from an InputStream (typical when loading from assets). */
    @JvmStatic
    fun parseProfile(stream: InputStream, sourceName: String = "profile.json"): ModelProfile {
        val text = stream.readBytes().decodeToString()
        return parseProfile(JSONObject(text), sourceName)
    }

    /** Parse profile from a JSONObject (flexible and test-friendly). */
    @JvmStatic
    fun parseProfile(json: JSONObject, sourceName: String = "profile.json"): ModelProfile {
        val event = json.optString("event", "UNKNOWN").ifBlank { "UNKNOWN" }.uppercase(Locale.US)
        val profileName = json.optString("profile", "unknown")

        val threshold = needDouble(json, sourceName, "threshold", default = null) // required
        val nFeatures = needInt(json, sourceName, "n_features", default = null)   // required in your assets
        val seed: Int? = json.optOrNullInt("seed")
        val posIndex = json.optInt("onnx_output_pos_index", 1)

        // hysteresis (safe default per event)
        val hystRaw = getFlexibleDouble(json, "hyst_ratio")
        val hystDefault = defaultHystRatioFor(event)
        var hyst = hystRaw ?: run {
            Log.w(TAG, "$sourceName: 'hyst_ratio' missing or non-numeric. Using default=$hystDefault for event=$event")
            hystDefault
        }
        val clamped = hyst.coerceIn(0.05, 0.99)
        if (clamped != hyst) {
            Log.w(TAG, "$sourceName: 'hyst_ratio'=$hyst out of range. Clamped to $clamped")
            hyst = clamped
        }

        if (nFeatures <= 0) {
            Log.w(TAG, "$sourceName: n_features=$nFeatures looks invalid.")
        }

        // Optional post-processing knobs (JSON may omit; defaults are NO-OP)
        var smoothing = json.optOrNullInt("smoothing") ?: 0
        var minRun = json.optOrNullInt("min_run") ?: 0
        var cooldownMs = json.optOrNullLong("cooldown_ms") ?: 0L
        var minSepMs = json.optOrNullLong("min_sep_ms") ?: 0L

        if (smoothing < 0) { Log.w(TAG, "$sourceName: smoothing=$smoothing < 0; clamped to 0"); smoothing = 0 }
        if (minRun < 0)    { Log.w(TAG, "$sourceName: min_run=$minRun < 0; clamped to 0"); minRun = 0 }
        if (cooldownMs < 0){ Log.w(TAG, "$sourceName: cooldown_ms=$cooldownMs < 0; clamped to 0"); cooldownMs = 0 }
        if (minSepMs < 0)  { Log.w(TAG, "$sourceName: min_sep_ms=$minSepMs < 0; clamped to 0"); minSepMs = 0 }

        return ModelProfile(
            profile = profileName,
            event = event,
            threshold = threshold,
            hystRatio = hyst,
            nFeatures = nFeatures,
            seed = seed,
            posIndex = posIndex,
            smoothing = smoothing,
            minRun = minRun,
            cooldownMs = cooldownMs,
            minSepMs = minSepMs
        )
    }

    // ------------------------------------------------------------
    // Feature schema parsing (text → FeatureSchema)
    // ------------------------------------------------------------

    private fun parseFeaturesText(text: String, sourceName: String, event: String): FeatureSchema {
        val root = JSONTokener(text).nextValue()
        return when (root) {
            is JSONObject -> {
                // Look for common shapes:
                // { "features":[...], "version":"..." }  OR
                // { "names":[...]}  OR  { "columns":[...] }  OR  { "schema":{ "features":[...] } }
                val version = root.optString("version", root.optString("schema_version", null))
                    .ifBlank { null }

                val arr = root.optJSONArray("features")
                    ?: root.optJSONArray("names")
                    ?: root.optJSONArray("columns")
                    ?: root.optJSONObject("schema")?.optJSONArray("features")
                    ?: throw IllegalArgumentException("$sourceName: could not find features array (expected keys: features/names/columns/schema.features)")

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
    // Medians helpers
    // ------------------------------------------------------------

    private fun mediansFromObject(obj: JSONObject, schema: FeatureSchema, fname: String): DoubleArray {
        val names = schema.names
        val out = DoubleArray(names.size)
        val missing = ArrayList<String>()
        for ((i, name) in names.withIndex()) {
            val v = getFlexibleDouble(obj, name)
            if (v == null) {
                missing += name
            } else {
                out[i] = v
            }
        }
        if (missing.isNotEmpty()) {
            val head = missing.take(8).joinToString(", ")
            val more = missing.size - minOf(8, missing.size)
            val suffix = if (more > 0) " … (+$more more)" else ""
            throw IllegalArgumentException("$fname: missing or non-numeric medians for features: $head$suffix")
        }
        return out
    }

    private fun mediansFromArray(arr: JSONArray, schema: FeatureSchema, fname: String): DoubleArray {
        val names = schema.names
        if (arr.length() != names.size) {
            throw IllegalArgumentException("$fname: length ${arr.length()} does not match features ${names.size}")
        }
        val out = DoubleArray(names.size)
        for (i in 0 until arr.length()) {
            val v = when (val any = arr.get(i)) {
                is Number -> any.toDouble()
                is String -> any.trim().replace(',', '.').toDoubleOrNull()
                else -> null
            } ?: throw IllegalArgumentException("$fname: non-numeric median at index $i (feature ${names[i]})")
            out[i] = v
        }
        return out
    }

    // ------------------------------------------------------------
    // Shared JSON helpers
    // ------------------------------------------------------------

    /** Required double: accept number or numeric string (also "0,7"). Throws if missing unless default provided. */
    private fun needDouble(
        json: JSONObject,
        source: String,
        key: String,
        default: Double?
    ): Double {
        getFlexibleDouble(json, key)?.let { return it }
        if (default != null) {
            Log.w(TAG, "$source: '$key' missing or non-numeric. Using default=$default")
            return default
        }
        throw IllegalArgumentException("$source: missing or non-numeric '$key'")
    }

    /** Required int: accept int or numeric string. Throws if missing unless default provided. */
    private fun needInt(
        json: JSONObject,
        source: String,
        key: String,
        default: Int?
    ): Int {
        getFlexibleDouble(json, key)?.let { return it.toInt() }
        json.optString(key, "").trim().toIntOrNull()?.let { return it }
        if (json.has(key) && !json.isNull(key)) {
            val v = json.optInt(key, Int.MIN_VALUE)
            if (v != Int.MIN_VALUE) return v
        }
        if (default != null) {
            Log.w(TAG, "$source: '$key' missing or non-numeric. Using default=$default")
            return default
        }
        throw IllegalArgumentException("$source: missing or non-numeric '$key'")
    }

    /** Try to read a JSON number or a numeric string; also supports comma decimals ("0,7"). */
    private fun getFlexibleDouble(json: JSONObject, key: String): Double? {
        if (!json.has(key) || json.isNull(key)) return null

        // Prefer a true JSON number
        val asNum = json.optDouble(key, Double.NaN)
        if (!asNum.isNaN()) return asNum

        // Then tolerate strings
        val s = json.optString(key, "").trim()
        if (s.isEmpty()) return null
        s.replace(',', '.').toDoubleOrNull()?.let { return it }

        return null
    }

    /** Optional Int read; supports numeric strings too. */
    private fun JSONObject.optOrNullInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        optInt(key, Int.MIN_VALUE).let { if (it != Int.MIN_VALUE) return it }
        optString(key, "").trim().toIntOrNull()?.let { return it }
        getFlexibleDouble(this, key)?.toInt()?.let { return it }
        return null
    }

    /** Optional Long read; supports numeric strings too. */
    private fun JSONObject.optOrNullLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        // Try as a true number first
        val dbl = getFlexibleDouble(this, key)
        if (dbl != null) return dbl.toLong()
        // Then try plain long-ish string
        optString(key, "").trim().toLongOrNull()?.let { return it }
        return null
    }

    private fun jsonArrayToStringList(arr: JSONArray): List<String> {
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.get(i)
            val s = when (v) {
                is String -> v
                is JSONObject -> (
                        v.optString("name",
                            v.optString("feature",
                                v.optString("id", "")))
                        ).ifBlank { null }
                else -> null
            } ?: throw IllegalArgumentException("features: element $i is not a string or object-with-name")
            out += s
        }
        return out
    }

    // ------------------------------------------------------------
    // Asset selection helpers
    // ------------------------------------------------------------

    private fun eventAndDir(isTakeoff: Boolean): Pair<String, String> =
        if (isTakeoff) "TAKEOFF" to TAKEOFF_DIR else "LANDING" to LANDING_DIR

    /** Pick a single asset with the given suffix; prefers the lexicographically latest if multiple. */
    private fun pickOneAsset(context: Context, dir: String, suffix: String): String {
        val files = (context.assets.list(dir) ?: emptyArray())
            .filter { it.endsWith(suffix) }
        if (files.isEmpty()) {
            throw IllegalArgumentException("@$dir: no *$suffix file found")
        }
        return files.maxOrNull()!!  // choose the latest, e.g., takeoff_0815.* over older ones
    }

    private fun round3(x: Double): Double = (round(x * 1000.0) / 1000.0)
    private fun round4(x: Double): Double = (round(x * 10000.0) / 10000.0)

    /** Event-specific defaults so you don't have to touch the JSON. */
    private fun defaultHystRatioFor(eventUpper: String): Double = when (eventUpper) {
        "TAKEOFF" -> 0.70   // sharp, short event
        "LANDING" -> 0.60   // longer, noisier lead-in
        else      -> 0.75   // sensible general default
    }
}
