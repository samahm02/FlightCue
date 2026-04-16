package com.example.flightcue.ml

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.example.flightcue.data.modelspec.AppPaths
import com.example.flightcue.data.modelspec.FeatureSchema
import com.example.flightcue.data.modelspec.ModelConfigs
import com.example.flightcue.data.modelspec.ModelProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

private const val TAG = "OrtSessionManager"

/**
 * Singleton that owns both ONNX sessions (takeoff and landing).
 *
 * Call [init] once (e.g. from ["DetectionEngine"]) before any scoring.
 * Sessions and the ORT environment are kept alive for the lifetime of the process.
 * All public scoring functions are safe to call from any thread.
 */
object OrtSessionManager {

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var takeoff: Bundle? = null
    @Volatile private var landing: Bundle? = null
    /**
     * All parsed artifacts for one model direction, cached after [init].
     * Avoids re-parsing asset files on every inference call.
     */
    @Suppress("ArrayInDataClass")
    data class Bundle(
        val event: String,
        val dir: String,
        val session: OrtSession,
        val inputName: String,
        val outputName: String?,
        val outputIndex: Int,
        val schema: FeatureSchema,
        val scalerMean: DoubleArray,
        val scalerScale: DoubleArray,
        val profile: ModelProfile,
        val shape3d: LongArray         // [1, seqLen, nFeat] — cached to avoid allocation per call
    )

    // ---- Per-thread reusable direct buffers ----
    // Avoids allocating a new FloatArray on every inference call.

    private class TensorScratch(
        var fb: FloatBuffer,
        var capFloats: Int
    )

    private val scratchLocal = ThreadLocal<TensorScratch>()

    /** Returns a per-thread direct FloatBuffer large enough for [nFloats] floats. */
    private fun scratch(nFloats: Int): TensorScratch {
        val cur = scratchLocal.get()
        if (cur == null || cur.capFloats < nFloats) {
            val newCap = if (cur == null) nFloats else max(nFloats, cur.capFloats * 2)
            val fb = ByteBuffer
                .allocateDirect(newCap * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            val ns = TensorScratch(fb, newCap)
            scratchLocal.set(ns)
            return ns
        }
        return cur
    }

    /** Loads both ONNX sessions from assets. No-op if already initialised. */
    @Synchronized
    fun init(context: Context) {
        if (env != null && takeoff != null && landing != null) {
            Log.i(TAG, "Already initialized")
            return
        }

        val e = OrtEnvironment.getEnvironment()
        env = e

        // TAKEOFF session
        run {
            val schema  = ModelConfigs.parseFeatures(context, isTakeoff = true)
            val (mean, scale) = ModelConfigs.parseScaler(context, schema, isTakeoff = true)
            val profile = ModelConfigs.parseProfile(context, isTakeoff = true)
            val sess    = createSessionFromAssets(context, AppPaths.TAKEOFF_DIR)

            val input  = profile.onnxInputName ?: firstInputName(sess)
            val out    = profile.onnxOutputName
            val nFeat  = schema.names.size

            require(schema.names.isNotEmpty()) { "TAKEOFF: empty schema.names" }
            require(profile.seqLen > 0) { "TAKEOFF: invalid seqLen=${profile.seqLen}" }

            if (profile.nFeatures > 0 && profile.nFeatures != nFeat) {
                throw IllegalStateException("TAKEOFF: profile.nFeatures=${profile.nFeatures} != schema.names.size=$nFeat")
            }

            validateInputShape(sess, input, expectedSeqLen = profile.seqLen, expectedNFeat = nFeat, event = "TAKEOFF")

            val outIdx  = resolveOutputIndex(sess, out, "TAKEOFF")
            val shape3d = longArrayOf(1L, profile.seqLen.toLong(), nFeat.toLong())

            takeoff = Bundle(
                event = "TAKEOFF", dir = AppPaths.TAKEOFF_DIR,
                session = sess, inputName = input, outputName = out,
                outputIndex = outIdx, schema = schema,
                scalerMean = mean, scalerScale = scale,
                profile = profile, shape3d = shape3d
            )
            Log.i(TAG, "TAKEOFF: session ready. input=$input, output=${out ?: "first"}, outIdx=$outIdx, seq_len=${profile.seqLen}, features=$nFeat")
        }

        // LANDING session
        run {
            val schema  = ModelConfigs.parseFeatures(context, isTakeoff = false)
            val (mean, scale) = ModelConfigs.parseScaler(context, schema, isTakeoff = false)
            val profile = ModelConfigs.parseProfile(context, isTakeoff = false)
            val sess    = createSessionFromAssets(context, AppPaths.LANDING_DIR)

            val input  = profile.onnxInputName ?: firstInputName(sess)
            val out    = profile.onnxOutputName
            val nFeat  = schema.names.size

            require(schema.names.isNotEmpty()) { "LANDING: empty schema.names" }
            require(profile.seqLen > 0) { "LANDING: invalid seqLen=${profile.seqLen}" }

            if (profile.nFeatures > 0 && profile.nFeatures != nFeat) {
                throw IllegalStateException("LANDING: profile.nFeatures=${profile.nFeatures} != schema.names.size=$nFeat")
            }

            validateInputShape(sess, input, expectedSeqLen = profile.seqLen, expectedNFeat = nFeat, event = "LANDING")

            val outIdx  = resolveOutputIndex(sess, out, "LANDING")
            val shape3d = longArrayOf(1L, profile.seqLen.toLong(), nFeat.toLong())

            landing = Bundle(
                event = "LANDING", dir = AppPaths.LANDING_DIR,
                session = sess, inputName = input, outputName = out,
                outputIndex = outIdx, schema = schema,
                scalerMean = mean, scalerScale = scale,
                profile = profile, shape3d = shape3d
            )
            Log.i(TAG, "LANDING: session ready. input=$input, output=${out ?: "first"}, outIdx=$outIdx, seq_len=${profile.seqLen}, features=$nFeat")
        }
    }

    // ---- Public accessors (use these instead of re-parsing ModelConfigs) ----

    fun featureNamesTakeoff(): List<String> = (takeoff ?: notReady("TAKEOFF")).schema.names
    fun featureNamesLanding(): List<String> = (landing ?: notReady("LANDING")).schema.names

    fun schemaTakeoff(): FeatureSchema = (takeoff ?: notReady("TAKEOFF")).schema
    fun schemaLanding(): FeatureSchema = (landing ?: notReady("LANDING")).schema

    fun scalerTakeoff(): Pair<DoubleArray, DoubleArray> =
        (takeoff ?: notReady("TAKEOFF")).let { it.scalerMean to it.scalerScale }
    fun scalerLanding(): Pair<DoubleArray, DoubleArray> =
        (landing ?: notReady("LANDING")).let { it.scalerMean to it.scalerScale }

    fun profileTakeoff(): ModelProfile = (takeoff ?: notReady("TAKEOFF")).profile
    fun profileLanding(): ModelProfile = (landing ?: notReady("LANDING")).profile

    // ---- Scoring ----

    /** Runs takeoff inference on a flat feature sequence. Returns probability in [0, 1]. */
    fun scoreTakeoff(features: DoubleArray): Double {
        val b = takeoff ?: notReady("TAKEOFF")
        return runSeqFromDouble(b, features).toDouble()
    }

    /** Runs landing inference on a flat feature sequence. Returns probability in [0, 1]. */
    fun scoreLanding(features: DoubleArray): Double {
        val b = landing ?: notReady("LANDING")
        return runSeqFromDouble(b, features).toDouble()
    }

    /** Float overloads kept for parity tests and existing callers. */
    fun runTakeoffSeq(seqFlat: FloatArray): Float {
        val b = takeoff ?: notReady("TAKEOFF")
        return runSeqFromFloat(b, seqFlat)
    }

    fun runLandingSeq(seqFlat: FloatArray): Float {
        val b = landing ?: notReady("LANDING")
        return runSeqFromFloat(b, seqFlat)
    }

    // ---- Private helpers ----

    private fun createSessionFromAssets(context: Context, dir: String): OrtSession {
        val e = env ?: throw IllegalStateException("ORT environment not ready")
        val onnxName = pickOnnxAsset(context, dir)
        val bytes = context.assets.open("$dir/$onnxName").use { it.readBytes() }

        val opts = OrtSession.SessionOptions().apply {
            setCPUArenaAllocator(true)           // reuses memory internally
            setMemoryPatternOptimization(true)   // pre-allocates when shapes are stable
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(1)              // single-threaded for mobile
        }
        Log.i(TAG, "SessionOptions: arena=true memPattern=true intra=1 opt=ALL ($dir)")
        return e.createSession(bytes, opts)
    }

    private fun firstInputName(session: OrtSession): String {
        val it = session.inputNames.iterator()
        if (!it.hasNext()) throw IllegalArgumentException("ONNX model has no inputs")
        return it.next()
    }

    private fun runSeqFromFloat(b: Bundle, seqFlat: FloatArray): Float {
        val seqLen = b.profile.seqLen
        val nFeat  = b.schema.names.size
        require(seqFlat.size == seqLen * nFeat) {
            "${b.event}: seqFlat size ${seqFlat.size} != ${seqLen}*${nFeat}"
        }
        val prob = runTensor3DFromFloat(b, seqFlat)
        if (prob.isNaN() || prob < -1e-3f || prob > 1f + 1e-3f) {
            Log.w(TAG, "${b.event}: suspicious probability=$prob")
        }
        return prob.coerceIn(0f, 1f)
    }

    private fun runSeqFromDouble(b: Bundle, seqFlat: DoubleArray): Float {
        val seqLen = b.profile.seqLen
        val nFeat  = b.schema.names.size
        require(seqFlat.size == seqLen * nFeat) {
            "${b.event}: seqFlat size ${seqFlat.size} != ${seqLen}*${nFeat}"
        }
        val prob = runTensor3DFromDouble(b, seqFlat)
        if (prob.isNaN() || prob < -1e-3f || prob > 1f + 1e-3f) {
            Log.w(TAG, "${b.event}: suspicious probability=$prob")
        }
        return prob.coerceIn(0f, 1f)
    }

    private fun runTensor3DFromFloat(b: Bundle, seqFlat: FloatArray): Float {
        val e  = env ?: throw IllegalStateException("ORT environment not ready")
        val s  = scratch(seqFlat.size)
        val fb = s.fb
        fb.clear()
        fb.put(seqFlat, 0, seqFlat.size)
        fb.flip()

        OnnxTensor.createTensor(e, fb, b.shape3d).use { input ->
            b.session.run(mapOf(b.inputName to input)).use { results ->
                if (results.size() == 0) throw IllegalStateException("${b.event}: no outputs from ONNX run")
                val ov: OnnxValue = results.get(b.outputIndex)
                return firstScalarAsFloat(ov.value)
            }
        }
    }

    private fun runTensor3DFromDouble(b: Bundle, seqFlat: DoubleArray): Float {
        val e  = env ?: throw IllegalStateException("ORT environment not ready")
        val s  = scratch(seqFlat.size)
        val fb = s.fb
        fb.clear()
        // Write doubles directly into the float buffer — avoids an intermediate FloatArray allocation.
        for (i in seqFlat.indices) {
            val v = seqFlat[i]
            fb.put(if (v.isFinite()) v.toFloat() else 0.0f)
        }
        fb.flip()

        OnnxTensor.createTensor(e, fb, b.shape3d).use { input ->
            b.session.run(mapOf(b.inputName to input)).use { results ->
                if (results.size() == 0) throw IllegalStateException("${b.event}: no outputs from ONNX run")
                val ov: OnnxValue = results.get(b.outputIndex)
                return firstScalarAsFloat(ov.value)
            }
        }
    }

    private fun resolveOutputIndex(session: OrtSession, outputName: String?, event: String): Int {
        if (outputName.isNullOrBlank()) return 0
        val names = session.outputNames.toList()
        val idx   = names.indexOf(outputName)
        return if (idx >= 0) idx else {
            Log.w(TAG, "$event: outputName='$outputName' not found in outputs=$names; using index 0")
            0
        }
    }

    private fun firstScalarAsFloat(v: Any?): Float = when (v) {
        null         -> error("Null ONNX output value")
        is Number    -> v.toFloat()
        is FloatArray  -> v.firstOrNull() ?: error("Empty FloatArray output")
        is DoubleArray -> (v.firstOrNull() ?: error("Empty DoubleArray output")).toFloat()
        is LongArray   -> (v.firstOrNull() ?: error("Empty LongArray output")).toFloat()
        is IntArray    -> (v.firstOrNull() ?: error("Empty IntArray output")).toFloat()
        is Array<*>    -> firstScalarAsFloat(v.firstOrNull() ?: error("Empty Array output"))
        is FloatBuffer -> { if (v.capacity() <= 0) error("Empty FloatBuffer output"); v.get(0) }
        else -> error("Unsupported ONNX output type: ${v.javaClass.name}")
    }

    /** Picks the single .onnx file from the given asset directory. */
    private fun pickOnnxAsset(context: Context, dir: String): String {
        val files = (context.assets.list(dir) ?: emptyArray()).filter { it.endsWith(".onnx") }
        require(files.isNotEmpty()) { "@$dir: no .onnx file found" }
        if (files.size > 1) {
            Log.w(TAG, "@$dir: multiple .onnx files=$files; using ${files.maxOrNull()}")
        }
        return files.maxOrNull()!!
    }

    private fun validateInputShape(
        session: OrtSession,
        inputName: String,
        expectedSeqLen: Int,
        expectedNFeat: Int,
        event: String
    ) {
        val info = session.inputInfo[inputName]?.info
        val ti   = info as? TensorInfo ?: return
        val shape = ti.shape

        if (shape.size != 3) {
            Log.w(TAG, "$event: input '$inputName' rank=${shape.size} (expected 3). shape=${shape.contentToString()}")
            return
        }

        val seqDim  = shape[1]
        val featDim = shape[2]

        if (seqDim > 0 && seqDim.toInt() != expectedSeqLen) {
            throw IllegalStateException("$event: ONNX input seq dim=$seqDim != expected $expectedSeqLen")
        }
        if (featDim > 0 && featDim.toInt() != expectedNFeat) {
            throw IllegalStateException("$event: ONNX input feat dim=$featDim != expected $expectedNFeat")
        }
    }

    private fun notReady(which: String): Nothing =
        throw IllegalStateException("OrtSessionManager not initialized ($which)")
}