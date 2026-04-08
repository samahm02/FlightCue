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

object ParityRunner {
    private const val TAG = "ParityRunner"

    // ORT vs PyTorch parity in your export is ~1e-7 max abs diff.
    // Keep this looser so different devices/ABIs don’t false-fail.
    private const val EPS = 1e-4f

    fun runAll(context: Context) {
        try {
            OrtSessionManager.init(context)

            val take = runDir(context, AppPaths.TAKEOFF_DIR, isTakeoff = true)
            val land = runDir(context, AppPaths.LANDING_DIR, isTakeoff = false)

            Log.i(
                TAG,
                "DONE parity: TAKEOFF rows=${take.rows} worstErr=${fmt(take.worst)}; " +
                        "LANDING rows=${land.rows} worstErr=${fmt(land.worst)}"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Parity run failed", t)
        }
    }

    private data class Result(val rows: Int, val worst: Float)

    private fun runDir(ctx: Context, dir: String, isTakeoff: Boolean): Result {
        // You ship:
        // models/gru/takeoff/golden.npz
        // models/gru/landing/golden.npz
        val npzName = pickGoldenNpz(ctx, dir)
        val path = "$dir/$npzName"

        val bundleProfile =
            if (isTakeoff) OrtSessionManager.profileTakeoff() else OrtSessionManager.profileLanding()
        val seqLenModel = bundleProfile.seqLen

        val modelNames =
            if (isTakeoff) OrtSessionManager.featureNamesTakeoff() else OrtSessionManager.featureNamesLanding()
        val nFeatModel = modelNames.size
        val rowWidthModel = seqLenModel * nFeatModel

        val npz = NpzReader.read(ctx.assets.open(path))
        val xArr = npz.requireArrayAny("x", "X")

        // ✅ FIX: exporter uses p_out (and sometimes y_takeoff / y_landing)
        val pArr = if (isTakeoff) {
            npz.requireArrayAny("p", "p_out", "pOut", "p_ref", "pRef", "y_takeoff", "yTakeoff")
        } else {
            npz.requireArrayAny("p", "p_out", "pOut", "p_ref", "pRef", "y_landing", "yLanding")
        }

        val rows = xArr.shape.firstOrNull() ?: error("$path: x has no shape?")
        val pRows = pArr.shape.firstOrNull() ?: error("$path: p has no shape?")
        require(rows == pRows) { "$path: x rows=$rows != p rows=$pRows" }

        // Log shapes (helps when parity breaks)
        Log.i(
            TAG,
            "${if (isTakeoff) "TAKEOFF" else "LANDING"} NPZ='$npzName' " +
                    "xShape=${xArr.shape.contentToString()} pShape=${pArr.shape.contentToString()} " +
                    "model(seqLen=$seqLenModel nFeat=$nFeatModel)"
        )

        val xFlat = xArr.asFloatArray()
        val pFlat = pArr.asFloatArray()

        // Support both:
        //  - x shape (rows, seqLen, nFeat)
        //  - x shape (rows, rowWidth)
        val fileRowWidth: Int
        val fileSeqLen: Int
        val fileNFeat: Int

        when (xArr.shape.size) {
            3 -> {
                fileSeqLen = xArr.shape[1]
                fileNFeat = xArr.shape[2]
                fileRowWidth = fileSeqLen * fileNFeat
            }
            2 -> {
                fileRowWidth = xArr.shape[1]
                fileSeqLen = seqLenModel // assume same seq_len as model (export should match)
                require(fileSeqLen > 0) { "$path: invalid model seqLen=$fileSeqLen" }
                require(fileRowWidth % fileSeqLen == 0) {
                    "$path: x rowWidth=$fileRowWidth not divisible by seqLen=$fileSeqLen (cannot infer nFeat)"
                }
                fileNFeat = fileRowWidth / fileSeqLen
            }
            else -> error("$path: unsupported x rank=${xArr.shape.size}, shape=${xArr.shape.contentToString()}")
        }

        // Basic compatibility checks
        require(fileSeqLen == seqLenModel) {
            "$path: x seqLen=$fileSeqLen != model seqLen=$seqLenModel (regenerate golden.npz with correct seqLen)"
        }
        require(xFlat.size >= rows * fileRowWidth) {
            "$path: xFlat size=${xFlat.size} < rows*fileRowWidth=${rows * fileRowWidth}"
        }

        // Optional mapping if file feature count differs
        val fileNamesArr = npz.optionalArrayAny("feature_names", "feat_names", "features", "cols")
        val fileNames: Array<String>? = fileNamesArr?.asStringArray()

        val needMap = (fileNFeat != nFeatModel)

        val mapIdx: IntArray? = if (needMap) {
            require(fileNames != null) {
                "$path: NPZ nFeat=$fileNFeat but model expects nFeat=$nFeatModel. " +
                        "NPZ has no feature_names to map/reorder. Regenerate golden.npz with feature_names."
            }
            require(fileNames.size == fileNFeat) {
                "$path: feature_names count=${fileNames.size} != file nFeat=$fileNFeat"
            }

            val pos = HashMap<String, Int>(fileNames.size)
            for (i in fileNames.indices) pos[fileNames[i]] = i

            IntArray(nFeatModel) { j ->
                val nm = modelNames[j]
                pos[nm] ?: error("$path: feature '$nm' missing in NPZ feature_names (cannot map)")
            }
        } else {
            null
        }

        if (mapIdx == null) {
            require(fileRowWidth == rowWidthModel) {
                "$path: x rowWidth=$fileRowWidth, expected seqLen*nFeat=$rowWidthModel (seqLen=$seqLenModel, nFeat=$nFeatModel)"
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
                    val baseIn = off + t * fileNFeat
                    val baseOut = t * nFeatModel
                    for (j in 0 until nFeatModel) {
                        out[baseOut + j] = xFlat[baseIn + mapIdx[j]]
                    }
                }
                out
            }

            val expected = expectedProbForRow(pArr, pFlat, r)
            val got =
                if (isTakeoff) OrtSessionManager.runTakeoffSeq(seqFlat)
                else OrtSessionManager.runLandingSeq(seqFlat)

            val err = abs(got - expected)
            if (err > worst) worst = err

            if (err > EPS) {
                Log.w(
                    TAG,
                    "${if (isTakeoff) "TAKEOFF" else "LANDING"} row=$r got=${fmt(got)} expected=${fmt(expected)} " +
                            "err=${fmt(err)} > EPS=$EPS"
                )
            }
        }

        Log.i(TAG, "${if (isTakeoff) "TAKEOFF" else "LANDING"} npz='$npzName' rows=$rows worstErr=${fmt(worst)}")
        return Result(rows, worst)
    }

    private fun expectedProbForRow(pArr: NpyArray, pFlat: FloatArray, r: Int): Float {
        return when (pArr.shape.size) {
            1 -> {
                require(r < pArr.shape[0]) { "p row OOB: r=$r rows=${pArr.shape[0]}" }
                pFlat[r]
            }
            2 -> {
                val rows = pArr.shape[0]
                val cols = pArr.shape[1]
                require(r < rows) { "p row OOB: r=$r rows=$rows" }
                require(cols >= 1) { "p has 0 cols? shape=${pArr.shape.contentToString()}" }
                require(pFlat.size >= rows * cols) { "pFlat too small: size=${pFlat.size} need=${rows * cols}" }

                when (cols) {
                    1 -> pFlat[r]                 // (rows,1)
                    2 -> pFlat[r * 2 + 1]         // (rows,2) -> positive class column
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
        return npz.maxOrNull()!! // lexicographically latest
    }

    private fun fmt(f: Float): String = String.format(Locale.US, "%.6f", f)

    // ---------------------------------------------------------------------------------------------
    // Minimal NPZ/NPY reader (Android-safe, no external libs)
    // Supports float32/float64 arrays saved with numpy.savez (x.npy, p.npy)
    // Also supports fixed-width byte strings for feature_names (dtype='S', e.g. '|S32')
    // ---------------------------------------------------------------------------------------------

    private class Npz(private val arrays: Map<String, NpyArray>) {

        fun requireArrayAny(vararg keys: String): NpyArray {
            for (k in keys) arrays[k]?.let { return it }
            val present = arrays.keys.sorted().joinToString(", ")
            error("NPZ missing array anyOf=${keys.toList()}. Present keys: [$present]")
        }

        fun optionalArrayAny(vararg keys: String): NpyArray? {
            for (k in keys) arrays[k]?.let { return it }
            return null
        }
    }

    private data class NpyArray(
        val name: String,
        val descr: String,
        val fortranOrder: Boolean,
        val shape: IntArray,
        val raw: ByteArray
    ) {
        fun asFloatArray(): FloatArray {
            val little = descr.startsWith("<") || descr.startsWith("|")
            val order = if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

            val count = shape.fold(1) { acc, v -> acc * v }
            val bb = ByteBuffer.wrap(raw).order(order)

            return when {
                descr.endsWith("f4") -> {
                    val out = FloatArray(count)
                    for (i in 0 until count) out[i] = bb.float
                    out
                }
                descr.endsWith("f8") -> {
                    val out = FloatArray(count)
                    for (i in 0 until count) out[i] = bb.double.toFloat()
                    out
                }
                else -> error("Unsupported dtype descr='$descr' for array '$name' (expected f4/f8)")
            }
        }

        fun asStringArray(): Array<String> {
            require(descr.startsWith("|S")) {
                "Unsupported string dtype descr='$descr' for '$name' (export feature_names with dtype='S')"
            }
            val width = descr.substring(2).toIntOrNull()
                ?: error("Bad string descr='$descr' for '$name'")
            val count = shape.fold(1) { acc, v -> acc * v }
            val need = count * width
            require(raw.size >= need) {
                "String array '$name' raw too small: raw=${raw.size} need=$need"
            }

            return Array(count) { i ->
                val start = i * width
                val end = start + width
                val slice = raw.copyOfRange(start, end)
                val nul = slice.indexOfByte(0)
                val usable = if (nul >= 0) slice.copyOfRange(0, nul) else slice
                usable.toString(Charsets.UTF_8).trim()
            }
        }

        private fun ByteArray.indexOfByte(v: Int): Int {
            for (i in indices) if ((this[i].toInt() and 0xFF) == v) return i
            return -1
        }
    }

    private object NpzReader {
        fun read(ins: InputStream): Npz {
            val map = LinkedHashMap<String, NpyArray>()
            ZipInputStream(ins).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.endsWith(".npy", ignoreCase = true)) {
                        val bytes = zis.readAllBytesCompat()
                        val npy = NpyReader.parse(entry.name, bytes)
                        val key = entry.name.substringAfterLast('/').removeSuffix(".npy")
                        map[key] = npy.copy(name = key)
                    }
                    zis.closeEntry()
                }
            }
            return Npz(map)
        }
    }

    private object NpyReader {
        fun parse(entryName: String, bytes: ByteArray): NpyArray {
            val bais = ByteArrayInputStream(bytes)

            val magic = ByteArray(6)
            require(bais.read(magic) == 6) { "$entryName: invalid npy (no magic)" }
            require(
                magic.contentEquals(
                    byteArrayOf(
                        0x93.toByte(),
                        'N'.code.toByte(),
                        'U'.code.toByte(),
                        'M'.code.toByte(),
                        'P'.code.toByte(),
                        'Y'.code.toByte()
                    )
                )
            ) { "$entryName: invalid npy magic" }

            val major = bais.read()
            val minor = bais.read()
            require(major >= 1) { "$entryName: unsupported npy version $major.$minor" }

            val headerLen = when (major) {
                1 -> readU16LE(bais)
                2, 3 -> readU32LE(bais)
                else -> error("$entryName: unsupported npy version $major.$minor")
            }

            val headerBytes = ByteArray(headerLen)
            require(bais.read(headerBytes) == headerLen) { "$entryName: truncated header" }
            val header = headerBytes.toString(Charsets.US_ASCII)

            val descr = extractQuoted(header, "descr")
            val fortran = extractBool(header, "fortran_order")
            val shape = extractShape(header)

            require(!fortran) { "$entryName: fortran_order=True not supported (export should be C-order)" }

            val data = bais.readAllBytesCompat()
            return NpyArray(
                name = entryName,
                descr = descr,
                fortranOrder = fortran,
                shape = shape,
                raw = data
            )
        }

        private fun readU16LE(ins: InputStream): Int {
            val b0 = ins.read(); val b1 = ins.read()
            require(b0 >= 0 && b1 >= 0) { "Unexpected EOF" }
            return (b0 and 0xFF) or ((b1 and 0xFF) shl 8)
        }

        private fun readU32LE(ins: InputStream): Int {
            val b0 = ins.read(); val b1 = ins.read(); val b2 = ins.read(); val b3 = ins.read()
            require(b0 >= 0 && b1 >= 0 && b2 >= 0 && b3 >= 0) { "Unexpected EOF" }
            return (b0 and 0xFF) or
                    ((b1 and 0xFF) shl 8) or
                    ((b2 and 0xFF) shl 16) or
                    ((b3 and 0xFF) shl 24)
        }

        private fun extractQuoted(header: String, key: String): String {
            val idx = header.indexOf("'$key'")
            require(idx >= 0) { "NPY header missing '$key': $header" }
            val after = header.substring(idx)
            val q1 = after.indexOf('\'', after.indexOf(':') + 1)
            val q2 = after.indexOf('\'', q1 + 1)
            require(q1 >= 0 && q2 > q1) { "NPY header parse fail for '$key': $header" }
            return after.substring(q1 + 1, q2)
        }

        private fun extractBool(header: String, key: String): Boolean {
            val idx = header.indexOf("'$key'")
            require(idx >= 0) { "NPY header missing '$key': $header" }
            val after = header.substring(idx)
            val colon = after.indexOf(':')
            require(colon >= 0) { "NPY header parse fail for '$key': $header" }
            val tail = after.substring(colon + 1).trim()
            return when {
                tail.startsWith("True") -> true
                tail.startsWith("False") -> false
                else -> error("NPY header '$key' not boolean: $header")
            }
        }

        private fun extractShape(header: String): IntArray {
            val keyIdx = header.indexOf("'shape'")
            require(keyIdx >= 0) { "NPY header missing 'shape': $header" }
            val after = header.substring(keyIdx)
            val lpar = after.indexOf('(')
            val rpar = after.indexOf(')', startIndex = lpar + 1)
            require(lpar >= 0 && rpar > lpar) { "NPY header shape parse fail: $header" }
            val inside = after.substring(lpar + 1, rpar).trim()

            if (inside.isEmpty()) return intArrayOf()

            val parts = inside.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val dims = parts.map {
                it.toIntOrNull() ?: error("NPY header shape has non-int dim '$it': $header")
            }
            return dims.toIntArray()
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
