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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

private const val TAG = "OrtSessionManager"

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
        val outputName: String?,
        val outputIndex: Int,      // ✅ cached once
        val schema: FeatureSchema,
        val scalerMean: DoubleArray,   // (kept for compatibility / debugging)
        val scalerScale: DoubleArray,  // (kept for compatibility / debugging)
        val profile: ModelProfile,
        val shape3d: LongArray     // ✅ cached once: [1, seqLen, nFeat]
    )

    fun getInstance(@Suppress("UNUSED_PARAMETER") ctx: Context): OrtSessionManager = this

    // -------------------------
    // Reusable direct buffers (per thread)
    // -------------------------

    private class TensorScratch(
        var bb: ByteBuffer,
        var fb: FloatBuffer,
        var capFloats: Int
    )

    private val scratchLocal = ThreadLocal<TensorScratch>()

    /** Ensure a per-thread Direct buffer that can hold at least nFloats floats. */
    private fun scratch(nFloats: Int): TensorScratch {
        val cur = scratchLocal.get()
        if (cur == null || cur.capFloats < nFloats) {
            val newCap = if (cur == null) {
                nFloats
            } else {
                // grow geometrically to reduce future native allocs
                max(nFloats, cur.capFloats * 2)
            }

            val bb = ByteBuffer
                .allocateDirect(newCap * 4)
                .order(ByteOrder.nativeOrder())

            val fb = bb.asFloatBuffer()
            val ns = TensorScratch(bb, fb, newCap)
            scratchLocal.set(ns)
            return ns
        }
        return cur
    }

    @Synchronized
    fun init(context: Context) {
        if (env != null && takeoff != null && landing != null) {
            Log.i(TAG, "Already initialized")
            return
        }

        val e = OrtEnvironment.getEnvironment()
        env = e

        // TAKEOFF
        run {
            val schema = ModelConfigs.parseFeatures(context, isTakeoff = true)
            val (mean, scale) = ModelConfigs.parseScaler(context, schema, isTakeoff = true)
            val profile = ModelConfigs.parseProfile(context, isTakeoff = true)

            val sess = createSessionFromAssets(context, AppPaths.TAKEOFF_DIR)

            val input = profile.onnxInputName ?: firstInputName(sess)
            val out = profile.onnxOutputName

            require(schema.names.isNotEmpty()) { "TAKEOFF: empty schema.names" }
            require(profile.seqLen > 0) { "TAKEOFF: invalid seqLen=${profile.seqLen}" }

            val nFeat = schema.names.size
            if (profile.nFeatures > 0 && profile.nFeatures != nFeat) {
                throw IllegalStateException("TAKEOFF: profile.nFeatures=${profile.nFeatures} != schema.names.size=$nFeat")
            }

            validateInputShape(sess, input, expectedSeqLen = profile.seqLen, expectedNFeat = nFeat, event = "TAKEOFF")

            val shape3d = longArrayOf(1L, profile.seqLen.toLong(), nFeat.toLong())
            val outIdx = resolveOutputIndex(sess, out, "TAKEOFF")

            takeoff = Bundle(
                event = "TAKEOFF",
                dir = AppPaths.TAKEOFF_DIR,
                session = sess,
                inputName = input,
                outputName = out,
                outputIndex = outIdx,
                schema = schema,
                scalerMean = mean,
                scalerScale = scale,
                profile = profile,
                shape3d = shape3d
            )

            Log.i(TAG, "TAKEOFF: session ready. input=$input, output=${out ?: "first"}, outIdx=$outIdx, seq_len=${profile.seqLen}, features=$nFeat")
        }

        // LANDING
        run {
            val schema = ModelConfigs.parseFeatures(context, isTakeoff = false)
            val (mean, scale) = ModelConfigs.parseScaler(context, schema, isTakeoff = false)
            val profile = ModelConfigs.parseProfile(context, isTakeoff = false)
            val sess = createSessionFromAssets(context, AppPaths.LANDING_DIR)

            val input = profile.onnxInputName ?: firstInputName(sess)
            val out = profile.onnxOutputName

            require(schema.names.isNotEmpty()) { "LANDING: empty schema.names" }
            require(profile.seqLen > 0) { "LANDING: invalid seqLen=${profile.seqLen}" }

            val nFeat = schema.names.size
            if (profile.nFeatures > 0 && profile.nFeatures != nFeat) {
                throw IllegalStateException("LANDING: profile.nFeatures=${profile.nFeatures} != schema.names.size=$nFeat")
            }

            validateInputShape(sess, input, expectedSeqLen = profile.seqLen, expectedNFeat = nFeat, event = "LANDING")

            val shape3d = longArrayOf(1L, profile.seqLen.toLong(), nFeat.toLong())
            val outIdx = resolveOutputIndex(sess, out, "LANDING")

            landing = Bundle(
                event = "LANDING",
                dir = AppPaths.LANDING_DIR,
                session = sess,
                inputName = input,
                outputName = out,
                outputIndex = outIdx,
                schema = schema,
                scalerMean = mean,
                scalerScale = scale,
                profile = profile,
                shape3d = shape3d
            )

            Log.i(TAG, "LANDING: session ready. input=$input, output=${out ?: "first"}, outIdx=$outIdx, seq_len=${profile.seqLen}, features=$nFeat")
        }
    }

    fun isReady(): Boolean = env != null && takeoff != null && landing != null

    // ✅ PUBLIC ACCESSORS: Use these instead of re-parsing ModelConfigs
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

    /**
     * Fast path: avoid allocating FloatArray.
     * Assumes incoming features are already in the correct space (raw or scaled) per your pipeline.
     */
    fun scoreTakeoff(features: DoubleArray): Double {
        val b = takeoff ?: notReady("TAKEOFF")
        return runSeqFromDouble(b, features).toDouble()
    }

    fun scoreLanding(features: DoubleArray): Double {
        val b = landing ?: notReady("LANDING")
        return runSeqFromDouble(b, features).toDouble()
    }

    // Keep these for parity/tests / existing callers
    fun runTakeoffSeq(seqFlat: FloatArray): Float {
        val b = takeoff ?: notReady("TAKEOFF")
        return runSeqFromFloat(b, seqFlat)
    }

    fun runLandingSeq(seqFlat: FloatArray): Float {
        val b = landing ?: notReady("LANDING")
        return runSeqFromFloat(b, seqFlat)
    }

    /**
     * Warm up ONNX runtime by running inference with zero-filled sequences.
     */
    fun debugRunOnZeros() {
        if (!warmedUpOnce.compareAndSet(false, true)) {
            Log.d(TAG, "debugRunOnZeros() skipped (already ran)")
            return
        }

        takeoff?.let { b ->
            val seq = createZeroSeq(b)
            val p = runSeqFromFloat(b, seq)
            Log.i(TAG, "TAKEOFF warmup (zeros) => p=$p (expected ~0.5 for calibrated model)")
        }

        landing?.let { b ->
            val seq = createZeroSeq(b)
            val p = runSeqFromFloat(b, seq)
            Log.i(TAG, "LANDING warmup (zeros) => p=$p (expected ~0.5 for calibrated model)")
        }

        Log.i(TAG, "debugRunOnZeros() finished - ONNX runtime warmed up")
    }

    private fun createZeroSeq(b: Bundle): FloatArray {
        val seqLen = b.profile.seqLen
        val nFeatures = b.schema.names.size
        return FloatArray(seqLen * nFeatures) { 0.0f }
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

        // ✅ SessionOptions: Reduces ORT's internal malloc/free churn
        val opts = OrtSession.SessionOptions().apply {
            setCPUArenaAllocator(true)           // Reuses memory internally
            setMemoryPatternOptimization(true)   // Pre-allocates when shapes are stable
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(1)              // Single-threaded for mobile
        }
        // ✅ ADD THIS ONE LINE:
        Log.i(TAG, "SessionOptions: arena=true memPattern=true intra=1 inter=1")

        return e.createSession(bytes, opts)
    }

    private fun firstInputName(session: OrtSession): String {
        val it = session.inputNames.iterator()
        if (!it.hasNext()) throw IllegalArgumentException("ONNX model has no inputs")
        return it.next()
    }

    private fun runSeqFromFloat(b: Bundle, seqFlat: FloatArray): Float {
        val seqLen = b.profile.seqLen
        val nFeat = b.schema.names.size

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
        val nFeat = b.schema.names.size

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
        val e = env ?: throw IllegalStateException("Environment not ready")

        val s = scratch(seqFlat.size)
        val fb = s.fb
        fb.clear()
        fb.put(seqFlat, 0, seqFlat.size)
        fb.flip() // position=0, limit=nFloats

        OnnxTensor.createTensor(e, fb, b.shape3d).use { input ->
            b.session.run(mapOf(b.inputName to input)).use { results ->
                if (results.size() == 0) throw IllegalStateException("${b.event}: no outputs from ONNX run")
                val ov: OnnxValue = results.get(b.outputIndex)
                return firstScalarAsFloat(ov.value)
            }
        }
    }

    private fun runTensor3DFromDouble(b: Bundle, seqFlat: DoubleArray): Float {
        val e = env ?: throw IllegalStateException("Environment not ready")

        val s = scratch(seqFlat.size)
        val fb = s.fb
        fb.clear()

        // ✅ no FloatArray allocation; write doubles -> float buffer directly
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
        val names = session.outputNames.toList() // done once per model
        val idx = names.indexOf(outputName)
        return if (idx >= 0) idx else {
            Log.w(TAG, "$event: outputName='$outputName' not found in outputs=$names; using index 0")
            0
        }
    }

    private fun firstScalarAsFloat(v: Any?): Float = when (v) {
        null -> error("Null ONNX output value")
        is Number -> v.toFloat()

        is FloatArray -> v.firstOrNull() ?: error("Empty FloatArray output")
        is DoubleArray -> (v.firstOrNull() ?: error("Empty DoubleArray output")).toFloat()
        is LongArray -> (v.firstOrNull() ?: error("Empty LongArray output")).toFloat()
        is IntArray -> (v.firstOrNull() ?: error("Empty IntArray output")).toFloat()

        is Array<*> -> {
            val e0 = v.firstOrNull() ?: error("Empty Array output")
            firstScalarAsFloat(e0)
        }

        is FloatBuffer -> {
            if (v.capacity() <= 0) error("Empty FloatBuffer output")
            v.get(0)
        }

        else -> error("Unsupported ONNX output type: ${v.javaClass.name}")
    }

    private fun pickOneAsset(context: Context, dir: String, suffix: String): String {
        val files = (context.assets.list(dir) ?: emptyArray()).filter { it.endsWith(suffix) }
        require(files.isNotEmpty()) { "@$dir: no *$suffix file found" }
        if (files.size > 1) {
            Log.w(TAG, "@$dir: multiple $suffix files found=$files; using ${files.maxOrNull()}")
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
        val ti = info as? TensorInfo ?: return

        val shape = ti.shape
        if (shape.size != 3) {
            Log.w(TAG, "$event: input '$inputName' rank=${shape.size} (expected 3). shape=${shape.contentToString()}")
            return
        }

        val seqDim = shape[1]
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