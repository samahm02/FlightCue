package com.example.flightcue.ml

import android.content.Context
import android.util.Log
import com.example.flightcue.data.modelspec.FeatureSchema
import com.example.flightcue.data.modelspec.ModelConfigs
import com.example.flightcue.data.modelspec.ModelProfile
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "OrtSessionManager"
private const val TAKEOFF_DIR = "models/takeoff"
private const val LANDING_DIR = "models/landing"

object OrtSessionManager {

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var takeoff: Bundle? = null
    @Volatile private var landing: Bundle? = null
    private val warmedUpOnce = AtomicBoolean(false)

    data class Bundle(
        val event: String,
        val dir: String,
        val session: OrtSession,
        val inputName: String,
        val schema: FeatureSchema,
        val medians: DoubleArray,
        val profile: ModelProfile
    )

    fun getInstance(@Suppress("UNUSED_PARAMETER") ctx: Context): OrtSessionManager = this

    @Synchronized
    fun init(context: Context) {
        if (env != null && takeoff != null && landing != null) {
            Log.i(TAG, "Already initialized")
            return
        }

        val e = OrtEnvironment.getEnvironment()
        env = e

        // TAKEOFF
        val toSchema  = ModelConfigs.parseFeatures(context, isTakeoff = true)
        val toMedians = ModelConfigs.parseMedians(context, toSchema, isTakeoff = true)
        val toProfile = ModelConfigs.parseProfile(context, isTakeoff = true)
        val toSess    = createSessionFromAssets(context, TAKEOFF_DIR)
        val toInput   = firstInputName(toSess)
        takeoff = Bundle("TAKEOFF", TAKEOFF_DIR, toSess, toInput, toSchema, toMedians, toProfile)
        Log.i(TAG, "TAKEOFF: session ready. input=$toInput, features=${toSchema.names.size}, pos_idx=${toProfile.posIndex}")

        // LANDING
        val ldSchema  = ModelConfigs.parseFeatures(context, isTakeoff = false)
        val ldMedians = ModelConfigs.parseMedians(context, ldSchema, isTakeoff = false)
        val ldProfile = ModelConfigs.parseProfile(context, isTakeoff = false)
        val ldSess    = createSessionFromAssets(context, LANDING_DIR)
        val ldInput   = firstInputName(ldSess)
        landing = Bundle("LANDING", LANDING_DIR, ldSess, ldInput, ldSchema, ldMedians, ldProfile)
        Log.i(TAG, "LANDING: session ready. input=$ldInput, features=${ldSchema.names.size}, pos_idx=${ldProfile.posIndex}")
    }

    fun isReady(): Boolean = env != null && takeoff != null && landing != null

    // Small public helpers for parity tooling
    fun featureNamesTakeoff(): List<String> = (takeoff ?: notReady("TAKEOFF")).schema.names
    fun featureNamesLanding(): List<String> = (landing ?: notReady("LANDING")).schema.names
    fun profileTakeoff(): ModelProfile = (takeoff ?: notReady("TAKEOFF")).profile
    fun profileLanding(): ModelProfile = (landing ?: notReady("LANDING")).profile

    // Detector compatibility (DoubleArray in, Double out)
    fun scoreTakeoff(features: DoubleArray): Double = runTakeoff(features.toFloatArray()).toDouble()
    fun scoreLanding(features: DoubleArray): Double = runLanding(features.toFloatArray()).toDouble()

    // Primary API (floats)
    fun runTakeoff(features: FloatArray): Float {
        val b = takeoff ?: notReady("TAKEOFF")
        return runOne(b, features)
    }
    fun runLanding(features: FloatArray): Float {
        val b = landing ?: notReady("LANDING")
        return runOne(b, features)
    }

    fun debugRunOnMedians() {
        if (!warmedUpOnce.compareAndSet(false, true)) {
            Log.d(TAG, "debugRunOnMedians() skipped (already ran)")
            return
        }
        takeoff?.let {
            val p = runOne(it, it.medians.toFloatArray())
            Log.i(TAG, "TAKEOFF@medians => p=$p (T_on=${it.profile.threshold}, T_off=${it.profile.threshold * it.profile.hystRatio})")
        }
        landing?.let {
            val p = runOne(it, it.medians.toFloatArray())
            Log.i(TAG, "LANDING@medians => p=$p (T_on=${it.profile.threshold}, T_off=${it.profile.threshold * it.profile.hystRatio})")
        }
        Log.i(TAG, "debugRunOnMedians() finished")
    }

    @Synchronized
    fun close() {
        takeoff?.session?.close()
        landing?.session?.close()
        env?.close()
        takeoff = null
        landing = null
        env = null
        Log.i(TAG, "Closed ONNX sessions and environment")
    }

    // ---- internals ----

    private fun createSessionFromAssets(context: Context, dir: String): OrtSession {
        val e = env ?: throw IllegalStateException("Environment not ready")
        val onnxName = pickOneAsset(context, dir, ".onnx")
        val bytes = context.assets.open("$dir/$onnxName").use { it.readBytes() }
        return e.createSession(bytes)
    }

    private fun firstInputName(session: OrtSession): String {
        val it = session.inputNames.iterator()
        if (!it.hasNext()) throw IllegalArgumentException("ONNX model has no inputs")
        return it.next()
    }

    private fun runOne(b: Bundle, feats: FloatArray): Float {
        val n = b.schema.names.size
        require(feats.size == n) { "${b.event}: features length ${feats.size} != expected $n" }
        val prob = runTensor2D(b, feats, longArrayOf(1, n.toLong()))
        if (prob.isNaN() || prob < -1e-3f || prob > 1f + 1e-3f) {
            Log.w(TAG, "${b.event}: suspicious probability=$prob; check model output mapping/pos_index=${b.profile.posIndex}")
        }
        return prob.coerceIn(0f, 1f)
    }

    private fun runTensor2D(b: Bundle, feats: FloatArray, shape: LongArray): Float {
        val e = env ?: throw IllegalStateException("Environment not ready")

        val fb: FloatBuffer = ByteBuffer
            .allocateDirect(feats.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(feats)
            .apply { rewind() }

        OnnxTensor.createTensor(e, fb, shape).use { input ->
            b.session.run(mapOf(b.inputName to input)).use { results ->
                if (results.size() == 0) throw IllegalStateException("${b.event}: no outputs from ONNX run")

                // (A) Try to find an output whose NAME suggests probabilities; fetch by INDEX.
                val outNames = b.session.outputNames.toList()
                val probIdx = outNames.indexOfFirst {
                    it.equals("probabilities", true) ||
                            it.equals("probability", true) ||
                            it.equals("probs", true)
                }
                if (probIdx >= 0) {
                    val ov: OnnxValue = results.get(probIdx)
                    val p = extractPositiveProb(ov.value, b.profile.posIndex)
                    Log.d(TAG, "${b.event}: used output '${outNames[probIdx]}' => p=$p")
                    return p
                }

                // (B) Otherwise pick the first "prob-like" output by shape/value type.
                for (i in 0 until results.size()) {
                    val ov: OnnxValue = results.get(i)
                    if (looksLikeProbability(ov.value)) {
                        val p = extractPositiveProb(ov.value, b.profile.posIndex)
                        Log.d(TAG, "${b.event}: used first prob-like output(index=$i) => p=$p")
                        return p
                    }
                }

                // (C) Fallback: treat the first output as label-like (class index).
                return labelLikeToProb(results.get(0).value, b.profile.posIndex)
            }
        }
    }

    private fun looksLikeProbability(v: Any?): Boolean = when (v) {
        is Number -> true
        is FloatArray, is DoubleArray -> true
        is Array<*> -> v.isNotEmpty() && (
                v[0] is FloatArray || v[0] is DoubleArray || v[0] is Number || v[0] is LongArray
                )
        is Map<*, *> -> v.isNotEmpty() && run {
            val anyVal = v.values.first()
            anyVal is Number || anyVal is FloatArray || anyVal is DoubleArray
        }
        else -> false
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractPositiveProb(value: Any?, posIndex: Int): Float = when (value) {
        is Number -> value.toFloat()

        is FloatArray -> if (value.size == 1) value[0] else value.getOrElse(posIndex) { value.last() }
        is DoubleArray -> if (value.size == 1) value[0].toFloat() else value.getOrElse(posIndex) { value.last() }.toFloat()

        is Array<*> -> {
            if (value.isEmpty()) error("Empty output array")
            when (val e0 = value[0]) {
                is FloatArray -> {
                    val row = e0
                    if (row.size == 1) row[0] else row.getOrElse(posIndex) { row.last() }
                }
                is DoubleArray -> {
                    val row = e0
                    if (row.size == 1) row[0].toFloat() else row.getOrElse(posIndex) { row.last() }.toFloat()
                }
                is Number -> {
                    val row = value as Array<Number>
                    if (row.size == 1) row[0].toFloat() else row.getOrElse(posIndex) { row.last() }.toFloat()
                }
                is LongArray -> {
                    val lbl = if (e0.isNotEmpty()) e0[0] else 0L
                    if (lbl == posIndex.toLong()) 1f else 0f
                }
                else -> error("Unsupported ONNX output array element: ${e0?.javaClass?.name}")
            }
        }

        is Map<*, *> -> {
            val key1 = posIndex.toString()
            when (val exact = value[key1]) {
                is Number     -> exact.toFloat()
                is FloatArray -> if (exact.size == 1) exact[0] else exact.getOrElse(0) { exact.last() }
                is DoubleArray-> if (exact.size == 1) exact[0].toFloat() else exact.getOrElse(0) { exact.last() }.toFloat()
                else -> {
                    val alt = sequenceOf("1", "true", "positive", "pos")
                        .mapNotNull { k -> value[k] as? Number }.firstOrNull()
                    if (alt != null) alt.toFloat() else run {
                        val nums = value.values.filterIsInstance<Number>()
                        if (nums.isNotEmpty()) (nums.maxOf { it.toDouble() }).toFloat()
                        else error("Probability map has no numeric values: $value")
                    }
                }
            }
        }

        is LongArray -> {
            val lbl = if (value.isNotEmpty()) value[0] else 0L
            if (lbl == posIndex.toLong()) 1f else 0f
        }

        else -> error("Unsupported ONNX output type: ${value?.javaClass?.name}")
    }

    private fun labelLikeToProb(value: Any?, posIndex: Int): Float = when (value) {
        is LongArray -> {
            val lbl = if (value.isNotEmpty()) value[0] else 0L
            if (lbl == posIndex.toLong()) 1f else 0f
        }
        is Array<*> -> {
            if (value.isNotEmpty() && value[0] is LongArray) {
                val arr = value[0] as LongArray
                val lbl = if (arr.isNotEmpty()) arr[0] else 0L
                if (lbl == posIndex.toLong()) 1f else 0f
            } else {
                error("Label-like fallback doesn’t understand: ${value?.javaClass?.name}")
            }
        }
        else -> error("Label-like fallback doesn’t understand: ${value?.javaClass?.name}")
    }

    private fun pickOneAsset(context: Context, dir: String, suffix: String): String {
        val files = (context.assets.list(dir) ?: emptyArray()).filter { it.endsWith(suffix) }
        require(files.isNotEmpty()) { "@$dir: no *$suffix file found" }
        return files.maxOrNull()!! // lexicographic max
    }

    private fun notReady(which: String): Nothing =
        throw IllegalStateException("OrtSessionManager not initialized ($which)")

    private fun DoubleArray.toFloatArray(): FloatArray {
        val out = FloatArray(size)
        for (i in indices) out[i] = this[i].toFloat()
        return out
    }
}
