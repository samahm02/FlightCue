package com.example.flightcue.testing

import android.content.Context
import android.util.Log
import com.example.flightcue.data.modelspec.AppPaths
import com.example.flightcue.ml.OrtSessionManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.abs

/**
 * Offline parity checker that verifies the on-device ONNX model produces the same
 * probabilities as the Python/PyTorch reference model.
 *
 * Each model directory (takeoff / landing) must contain a golden.npz file produced
 * by the Python exporter. The file holds a set of pre-built input sequences (x) and
 * their expected output probabilities (p). [runAll] feeds each sequence through the
 * live ONNX session and checks the result is within [EPS] of the reference.
 *
 * This runs on debug builds only (called from DetectionEngine when debugLogs is true).
 */
object ParityRunner {
    private const val TAG = "ParityRunner"

    // ORT vs PyTorch parity in a clean export is ~1e-7 max abs diff.
    // EPS is kept looser to tolerate minor float precision differences across devices/ABIs.
    private const val EPS = 1e-4f

    /** Runs parity checks for both takeoff and landing models. Logs results and any failures. */
    fun runAll(context: Context) {
        try {
            OrtSessionManager.init(context)
            val take = runDir(context, AppPaths.TAKEOFF_DIR, isTakeoff = true)
            val land = runDir(context, AppPaths.LANDING_DIR, isTakeoff = false)
            Log.i(TAG, "DONE parity: TAKEOFF rows=${take.rows} worstErr=${fmt(take.worst)}; " +
                    "LANDING rows=${land.rows} worstErr=${fmt(land.worst)}")
        } catch (t: Throwable) {
            Log.e(TAG, "Parity run failed", t)
        }
    }

    private data class Result(val rows: Int, val worst: Float)

    /**
     * Loads golden.npz from [dir], feeds each row through the ONNX session,
     * and checks the output probability against the reference value.
     */
    private fun runDir(ctx: Context, dir: String, isTakeoff: Boolean): Result {
        val label = if (isTakeoff) "TAKEOFF" else "LANDING"
        val npzName = pickGoldenNpz(ctx, dir)
        val path = "$dir/$npzName"

        val bundleProfile = if (isTakeoff) OrtSessionManager.profileTakeoff()
        else           OrtSessionManager.profileLanding()
        val seqLenModel = bundleProfile.seqLen

        val modelNames = if (isTakeoff) OrtSessionManager.featureNamesTakeoff()
        else           OrtSessionManager.featureNamesLanding()
        val nFeatModel   = modelNames.size
        val rowWidthModel = seqLenModel * nFeatModel

        val npz  = NpzReader.read(ctx.assets.open(path))
        val xArr = npz.requireArrayAny("x", "X")

        // p_out / y_takeoff / y_landing are the common names the Python exporter uses.
        val pArr = if (isTakeoff) {
            npz.requireArrayAny("p", "p_out", "pOut", "p_ref", "pRef", "y_takeoff", "yTakeoff")
        } else {
            npz.requireArrayAny("p", "p_out", "pOut", "p_ref", "pRef", "y_landing", "yLanding")
        }

        val rows = xArr.shape.firstOrNull() ?: error("$path: x has no shape?")
        val pRows = pArr.shape.firstOrNull() ?: error("$path: p has no shape?")
        require(rows == pRows) { "$path: x rows=$rows != p rows=$pRows" }

        Log.i(TAG, "$label NPZ='$npzName' xShape=${xArr.shape.contentToString()} " +
                "pShape=${pArr.shape.contentToString()} model(seqLen=$seqLenModel nFeat=$nFeatModel)")

        val xFlat = xArr.asFloatArray()
        val pFlat = pArr.asFloatArray()

        // Support both 3D (rows, seqLen, nFeat) and 2D (rows, rowWidth) x arrays.
        val fileSeqLen: Int
        val fileNFeat: Int
        val fileRowWidth: Int

        when (xArr.shape.size) {
            3 -> {
                fileSeqLen   = xArr.shape[1]
                fileNFeat    = xArr.shape[2]
                fileRowWidth = fileSeqLen * fileNFeat
            }
            2 -> {
                fileRowWidth = xArr.shape[1]
                fileSeqLen   = seqLenModel
                require(fileSeqLen > 0) { "$path: invalid model seqLen=$fileSeqLen" }
                require(fileRowWidth % fileSeqLen == 0) {
                    "$path: x rowWidth=$fileRowWidth not divisible by seqLen=$fileSeqLen"
                }
                fileNFeat = fileRowWidth / fileSeqLen
            }
            else -> error("$path: unsupported x rank=${xArr.shape.size}, shape=${xArr.shape.contentToString()}")
        }

        require(fileSeqLen == seqLenModel) {
            "$path: x seqLen=$fileSeqLen != model seqLen=$seqLenModel (regenerate golden.npz)"
        }
        require(xFlat.size >= rows * fileRowWidth) {
            "$path: xFlat size=${xFlat.size} < rows*fileRowWidth=${rows * fileRowWidth}"
        }

        // If the golden file has a different feature count, use feature_names to remap columns.
        val fileNames = npz.optionalArrayAny("feature_names", "feat_names", "features", "cols")
            ?.asStringArray()
        val needMap = (fileNFeat != nFeatModel)

        val mapIdx: IntArray? = if (needMap) {
            require(fileNames != null) {
                "$path: NPZ nFeat=$fileNFeat but model expects nFeat=$nFeatModel. " +
                        "NPZ has no feature_names to map. Regenerate golden.npz with feature_names."
            }
            require(fileNames.size == fileNFeat) {
                "$path: feature_names count=${fileNames.size} != file nFeat=$fileNFeat"
            }
            val pos = HashMap<String, Int>(fileNames.size)
            for (i in fileNames.indices) pos[fileNames[i]] = i
            IntArray(nFeatModel) { j ->
                val nm = modelNames[j]
                pos[nm] ?: error("$path: feature '$nm' missing in NPZ feature_names")
            }
        } else {
            null
        }

        if (mapIdx == null) {
            require(fileRowWidth == rowWidthModel) {
                "$path: x rowWidth=$fileRowWidth, expected seqLen*nFeat=$rowWidthModel"
            }
        } else {
            require(fileSeqLen * fileNFeat == fileRowWidth) { "$path: internal shape inconsistency" }
        }

        require(pFlat.isNotEmpty()) { "$path: p is empty" }

        var worst = 0f

        for (r in 0 until rows) {
            val off = r * fileRowWidth

            val seqFlat: FloatArray = if (mapIdx == null) {
                xFlat.copyOfRange(off, off + rowWidthModel)
            } else {
                val out = FloatArray(seqLenModel * nFeatModel)
                for (t in 0 until seqLenModel) {
                    val baseIn  = off + t * fileNFeat
                    val baseOut = t * nFeatModel
                    for (j in 0 until nFeatModel) out[baseOut + j] = xFlat[baseIn + mapIdx[j]]
                }
                out
            }

            val expected = expectedProbForRow(pArr, pFlat, r)
            val got = if (isTakeoff) OrtSessionManager.runTakeoffSeq(seqFlat)
            else           OrtSessionManager.runLandingSeq(seqFlat)

            val err = abs(got - expected)
            if (err > worst) worst = err

            if (err > EPS) {
                Log.w(TAG, "$label row=$r got=${fmt(got)} expected=${fmt(expected)} " +
                        "err=${fmt(err)} > EPS=$EPS")
            }
        }

        Log.i(TAG, "$label npz='$npzName' rows=$rows worstErr=${fmt(worst)}")
        return Result(rows, worst)
    }

    /**
     * Extracts the reference probability for row [r] from [pArr].
     * Handles both 1D (one prob per row) and 2D (multi-class) p arrays.
     */
    private fun expectedProbForRow(pArr: NpyArray, pFlat: FloatArray, r: Int): Float {
        return when (pArr.shape.size) {
            1 -> {
                require(r < pArr.shape[0]) { "p row OOB: r=$r rows=${pArr.shape[0]}" }
                pFlat[r]
            }
            2 -> {
                val rows = pArr.shape[0]
                val cols = pArr.shape[1]
                require(r < rows)  { "p row OOB: r=$r rows=$rows" }
                require(cols >= 1) { "p has 0 cols? shape=${pArr.shape.contentToString()}" }
                require(pFlat.size >= rows * cols) { "pFlat too small: size=${pFlat.size} need=${rows * cols}" }
                when (cols) {
                    1    -> pFlat[r]               // (rows, 1)
                    2    -> pFlat[r * 2 + 1]       // (rows, 2) — positive class column
                    else -> pFlat[r * cols + (cols - 1)] // fallback: last column
                }
            }
            else -> error("Unsupported p rank=${pArr.shape.size}, shape=${pArr.shape.contentToString()}")
        }
    }

    private fun pickGoldenNpz(ctx: Context, dir: String): String {
        val files = ctx.assets.list(dir)?.toList().orEmpty()
        files.firstOrNull { it.equals("golden.npz", ignoreCase = true) }?.let { return it }
        val npz = files.filter { it.endsWith(".npz", ignoreCase = true) }
        require(npz.isNotEmpty()) { "@$dir: no *.npz found (expected golden.npz)" }
        return npz.maxOrNull()!!
    }

    private fun fmt(f: Float): String = String.format(Locale.US, "%.6f", f)

    // -------------------------------------------------------------------------
    // Minimal NPZ / NPY reader (no external libs, Android-safe)
    //
    // Supports float32 and float64 arrays and fixed-width byte strings (dtype='S')
    // as produced by numpy.savez. Only C-order (row-major) arrays are supported.
    // -------------------------------------------------------------------------

    private class Npz(private val arrays: Map<String, NpyArray>) {

        /** Returns the first array whose key matches any of [keys], or throws. */
        fun requireArrayAny(vararg keys: String): NpyArray {
            for (k in keys) arrays[k]?.let { return it }
            val present = arrays.keys.sorted().joinToString(", ")
            error("NPZ missing array anyOf=${keys.toList()}. Present keys: [$present]")
        }

        /** Returns the first array whose key matches any of [keys], or null. */
        fun optionalArrayAny(vararg keys: String): NpyArray? {
            for (k in keys) arrays[k]?.let { return it }
            return null
        }
    }

    /** Parsed contents of a single .npy entry inside a .npz archive. */
    @Suppress("ArrayInDataClass")
    private data class NpyArray(
        val name: String,
        val descr: String,
        val fortranOrder: Boolean,
        val shape: IntArray,
        val raw: ByteArray
    ) {
        /** Decodes raw bytes to a FloatArray. Supports float32 (f4) and float64 (f8). */
        fun asFloatArray(): FloatArray {
            val order = if (descr.startsWith("<") || descr.startsWith("|"))
                ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            val count = shape.fold(1) { acc, v -> acc * v }
            val bb = ByteBuffer.wrap(raw).order(order)
            return when {
                descr.endsWith("f4") -> FloatArray(count) { bb.float }
                descr.endsWith("f8") -> FloatArray(count) { bb.double.toFloat() }
                else -> error("Unsupported dtype descr='$descr' for array '$name' (expected f4/f8)")
            }
        }

        /** Decodes a fixed-width byte string array (dtype '|S<width>'). */
        fun asStringArray(): Array<String> {
            require(descr.startsWith("|S")) {
                "Unsupported string dtype descr='$descr' for '$name' (export feature_names with dtype='S')"
            }
            val width = descr.substring(2).toIntOrNull()
                ?: error("Bad string descr='$descr' for '$name'")
            val count = shape.fold(1) { acc, v -> acc * v }
            require(raw.size >= count * width) {
                "String array '$name' raw too small: raw=${raw.size} need=${count * width}"
            }
            return Array(count) { i ->
                val start = i * width
                val slice = raw.copyOfRange(start, start + width)
                val nul   = slice.indexOfFirst { (it.toInt() and 0xFF) == 0 }
                val usable = if (nul >= 0) slice.copyOfRange(0, nul) else slice
                usable.toString(Charsets.UTF_8).trim()
            }
        }
    }

    /** Reads all .npy entries from a .npz (zip) stream into a [Npz]. */
    private object NpzReader {
        fun read(ins: InputStream): Npz {
            val map = LinkedHashMap<String, NpyArray>()
            ZipInputStream(ins).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.endsWith(".npy", ignoreCase = true)) {
                        val bytes = zis.readAllBytesCompat()
                        val npy   = NpyReader.parse(entry.name, bytes)
                        val key   = entry.name.substringAfterLast('/').removeSuffix(".npy")
                        map[key]  = npy.copy(name = key)
                    }
                    zis.closeEntry()
                }
            }
            return Npz(map)
        }
    }

    /** Parses a single .npy byte array into a [NpyArray]. Supports format versions 1, 2, and 3. */
    private object NpyReader {
        fun parse(entryName: String, bytes: ByteArray): NpyArray {
            val bais = ByteArrayInputStream(bytes)

            // Verify numpy magic bytes: \x93NUMPY
            val magic = ByteArray(6)
            require(bais.read(magic) == 6) { "$entryName: invalid npy (no magic)" }
            require(magic.contentEquals(byteArrayOf(
                0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(),
                'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte()
            ))) { "$entryName: invalid npy magic" }

            val major = bais.read()
            val minor = bais.read()
            require(major >= 1) { "$entryName: unsupported npy version $major.$minor" }

            val headerLen = when (major) {
                1    -> readU16LE(bais)
                2, 3 -> readU32LE(bais)
                else -> error("$entryName: unsupported npy version $major.$minor")
            }

            val headerBytes = ByteArray(headerLen)
            require(bais.read(headerBytes) == headerLen) { "$entryName: truncated header" }
            val header = headerBytes.toString(Charsets.US_ASCII)

            val descr   = extractDescr(header)
            val fortran = extractFortranOrder(header)
            val shape   = extractShape(header)

            // Fortran (column-major) order is not supported — numpy exports should use C order.
            require(!fortran) { "$entryName: fortran_order=True not supported (export with order='C')" }

            return NpyArray(
                name = entryName,
                descr = descr,
                fortranOrder = fortran,
                shape = shape,
                raw = bais.readAllBytesCompat()
            )
        }

        private fun readU16LE(ins: InputStream): Int {
            val b0 = ins.read(); val b1 = ins.read()
            require(b0 >= 0 && b1 >= 0) { "Unexpected EOF" }
            return (b0 and 0xFF) or ((b1 and 0xFF) shl 8)
        }

        private fun readU32LE(ins: InputStream): Int {
            val b0 = ins.read(); val b1 = ins.read()
            val b2 = ins.read(); val b3 = ins.read()
            require(b0 >= 0 && b1 >= 0 && b2 >= 0 && b3 >= 0) { "Unexpected EOF" }
            return (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or
                    ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
        }

        /** Extracts the 'descr' (dtype) string from the npy header dict. */
        private fun extractDescr(header: String): String {
            val idx   = header.indexOf("'descr'")
            require(idx >= 0) { "NPY header missing 'descr': $header" }
            val after = header.substring(idx)
            val q1    = after.indexOf('\'', after.indexOf(':') + 1)
            val q2    = after.indexOf('\'', q1 + 1)
            require(q1 >= 0 && q2 > q1) { "NPY header parse fail for 'descr': $header" }
            return after.substring(q1 + 1, q2)
        }

        /** Extracts the 'fortran_order' boolean from the npy header dict. */
        private fun extractFortranOrder(header: String): Boolean {
            val idx   = header.indexOf("'fortran_order'")
            require(idx >= 0) { "NPY header missing 'fortran_order': $header" }
            val after = header.substring(idx)
            val colon = after.indexOf(':')
            require(colon >= 0) { "NPY header parse fail for 'fortran_order': $header" }
            val tail  = after.substring(colon + 1).trim()
            return when {
                tail.startsWith("True")  -> true
                tail.startsWith("False") -> false
                else -> error("NPY header 'fortran_order' not boolean: $header")
            }
        }

        /** Extracts the 'shape' tuple from the npy header dict as an IntArray. */
        private fun extractShape(header: String): IntArray {
            val idx  = header.indexOf("'shape'")
            require(idx >= 0) { "NPY header missing 'shape': $header" }
            val after = header.substring(idx)
            val lpar  = after.indexOf('(')
            val rpar  = after.indexOf(')', startIndex = lpar + 1)
            require(lpar >= 0 && rpar > lpar) { "NPY header shape parse fail: $header" }
            val inside = after.substring(lpar + 1, rpar).trim()
            if (inside.isEmpty()) return intArrayOf()
            return inside.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.toIntOrNull() ?: error("NPY header shape has non-int dim '$it': $header") }
                .toIntArray()
        }
    }

    private fun InputStream.readAllBytesCompat(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = this.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}