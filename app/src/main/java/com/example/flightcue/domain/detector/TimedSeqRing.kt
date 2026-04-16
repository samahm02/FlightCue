package com.example.flightcue.domain.detector

/**
 * TimedSeqRing stores a rolling sequence of window feature vectors plus timing columns.
 *
 * - Each push writes one window (feature vector + t_end + t_anchor).
 * - When full, `flattenReusable()` returns data in oldest→newest order (model input order).
 * - Right-anchored parity: in this app we typically set t_anchor == t_end.
 */
class TimedSeqRing(private val seqLen: Int, private val nFeat: Int) {
    private val data = DoubleArray(seqLen * nFeat)
    private val flatBuffer = DoubleArray(seqLen * nFeat)

    private val tEnd = DoubleArray(seqLen) { Double.NaN }
    private val tAnchor = DoubleArray(seqLen) { Double.NaN }

    private var filled = 0
    private var head = 0 // next write slot; when full, this equals the oldest slot

    /** Clear logical contents (does not zero arrays for performance). */
    fun clear() {
        filled = 0
        head = 0
    }

    /** True when exactly seqLen windows have been pushed. */
    fun isFull(): Boolean = filled == seqLen

    /** Push one window vector + timing into the ring. */
    fun push(x: DoubleArray, tEndSec: Double, tAnchorSec: Double) {
        require(x.size == nFeat) { "TimedSeqRing.push: x.size=${x.size} != nFeat=$nFeat" }

        val base = head * nFeat
        System.arraycopy(x, 0, data, base, nFeat)
        tEnd[head] = tEndSec
        tAnchor[head] = tAnchorSec

        head = (head + 1) % seqLen
        filled = minOf(seqLen, filled + 1)
    }

    /** Flatten into a caller-provided output buffer (oldest→newest). */
    fun flattenInto(out: DoubleArray) {
        require(isFull()) { "TimedSeqRing.flatten: not full yet (filled=$filled seqLen=$seqLen)" }
        require(out.size >= seqLen * nFeat) { "TimedSeqRing.flattenInto: out too small" }

        val start = head // oldest when full
        var outPos = 0
        for (k in 0 until seqLen) {
            val slot = (start + k) % seqLen
            val base = slot * nFeat
            System.arraycopy(data, base, out, outPos, nFeat)
            outPos += nFeat
        }
    }

    /** Flatten into an internal reusable buffer to avoid allocations. */
    fun flattenReusable(): DoubleArray {
        flattenInto(flatBuffer)
        return flatBuffer
    }


    /** Get t_anchor at index counted from oldest (0..seqLen-1). */
    fun tAnchorAt(idxFromOldest: Int): Double {
        require(isFull()) { "TimedSeqRing.tAnchorAt: not full" }
        require(idxFromOldest in 0 until seqLen) { "tAnchorAt idx=$idxFromOldest out of range" }
        val slot = (head + idxFromOldest) % seqLen
        return tAnchor[slot]
    }
}
