package com.example.flightcue.testing

import android.content.Context
import android.util.Log
import com.example.flightcue.ml.OrtSessionManager
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs

object ParityRunner {
    private const val TAG = "ParityRunner"
    // Slightly looser tolerance to accommodate ORT vs XGB numerical drift
    private const val EPS = 1e-4f

    fun runAll(context: Context) {
        try {
            // Safe if already initialized
            OrtSessionManager.init(context)

            val take = runDir(context, "models/takeoff", isTakeoff = true)
            val land = runDir(context, "models/landing", isTakeoff = false)

            Log.i(TAG, "DONE parity: TAKEOFF rows=${take.rows} worstErr=${fmt(take.worst)}; " +
                    "LANDING rows=${land.rows} worstErr=${fmt(land.worst)}")
        } catch (t: Throwable) {
            Log.e(TAG, "Parity run failed", t)
        }
    }

    private data class Result(val rows: Int, val worst: Float)
    private data class Case(val x: FloatArray, val expected: Float)

    private fun runDir(ctx: Context, dir: String, isTakeoff: Boolean): Result {
        val golden = pickGolden(ctx, dir)

        // Get feature names from the model/profile — must match exporter order
        val feats: List<String> = if (isTakeoff)
            OrtSessionManager.featureNamesTakeoff()
        else
            OrtSessionManager.featureNamesLanding()

        val cases = readCases(ctx, "$dir/$golden", feats)
        var worst = 0f
        var rowIx = 0
        for ((x, expected) in cases) {
            val p = if (isTakeoff) OrtSessionManager.runTakeoff(x) else OrtSessionManager.runLanding(x)
            val err = abs(p - expected)
            if (err > worst) worst = err
            if (err > EPS) {
                Log.w(TAG, "${if (isTakeoff) "TAKEOFF" else "LANDING"} row=$rowIx prob=${fmt(p)} expected=${fmt(expected)} err=${fmt(err)} > EPS=$EPS")
            }
            rowIx++
        }
        Log.i(TAG, "${if (isTakeoff) "TAKEOFF" else "LANDING"} golden='$golden' rows=$rowIx worstErr=${fmt(worst)}")
        return Result(rowIx, worst)
    }

    private fun pickGolden(ctx: Context, dir: String): String {
        val files = ctx.assets.list(dir)?.filter { it.endsWith(".golden.csv") }.orEmpty()
        require(files.isNotEmpty()) { "@$dir: no *.golden.csv found" }
        // highest/lexicographically last (name with timestamp recommended)
        return files.maxOrNull()!!
    }

    private fun readCases(ctx: Context, path: String, featureNames: List<String>): List<Case> {
        ctx.assets.open(path).use { ins ->
            BufferedReader(InputStreamReader(ins)).use { reader ->
                val header = reader.readLine() ?: error("$path: empty csv")
                val cols = header.split(',')

                // Locate expected prob column
                var pIdx = cols.indexOfFirst {
                    it.equals("expected", true) ||
                            it.equals("prob", true) ||
                            it.equals("p", true) ||
                            it.equals("y_true", true) ||   // we won't use this if found, but kept for robustness
                            it.equals("target", true)
                }
                if (pIdx < 0) pIdx = cols.lastIndex

                // Map header -> index and build feature index list in exported order
                val colToIx = cols.withIndex().associate { it.value to it.index }
                val featIdx = featureNames.map { f ->
                    colToIx[f] ?: error("$path: missing feature column '$f'")
                }

                val out = mutableListOf<Case>()
                var line = reader.readLine()
                while (line != null) {
                    val t = line.trim()
                    if (t.isNotEmpty()) {
                        val parts = t.split(',')
                        require(parts.size >= cols.size) { "$path: malformed row '${t.take(120)}...'" }
                        val x = FloatArray(featureNames.size) { i -> parts[featIdx[i]].toFloat() }
                        val expected = parts[pIdx].toFloat()
                        out.add(Case(x, expected))
                    }
                    line = reader.readLine()
                }
                return out
            }
        }
    }

    private fun fmt(f: Float): String = String.format("%.6f", f)
}
