// file: app/src/main/java/com/example/flightcue/data/replay/ReplaySource.kt
package com.example.flightcue.data.replay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

data class ReplayStatus(
    val loaded: Boolean = false,
    val playing: Boolean = false,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val speed: Double = 1.0,
    val loop: Boolean = false
)

class ReplaySource(
    val rec: Recording,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private var job: Job? = null

    private var iAx = 0
    private var iBp = 0
    private var posSec = 0.0
    private var speed = 1.0
    private var loop = false

    private var onAccel: ((Double, Double, Double, Double) -> Unit)? = null
    private var onBaro: ((Double, Double) -> Unit)? = null

    private val _status = MutableStateFlow(
        ReplayStatus(
            loaded = true,
            playing = false,
            positionSec = 0.0,
            durationSec = rec.durationSec,
            speed = speed,
            loop = loop
        )
    )
    val status: StateFlow<ReplayStatus> = _status

    fun start(
        onAccel: (tSec: Double, ax: Double, ay: Double, az: Double) -> Unit,
        onBaro: (tSec: Double, pHpa: Double) -> Unit
    ) {
        this.onAccel = onAccel
        this.onBaro = onBaro
    }

    fun setLoop(enabled: Boolean) {
        loop = enabled
        _status.value = _status.value.copy(loop = enabled)
    }

    fun setSpeed(mult: Double) {
        speed = mult.coerceIn(0.1, 8.0)
        _status.value = _status.value.copy(speed = speed)
    }

    fun seekFraction(f: Float) {
        val target = (f.coerceIn(0f, 1f).toDouble() * rec.durationSec)
        seekAbs(target)
    }

    fun seekAbs(sec: Double) {
        posSec = sec.coerceIn(0.0, rec.durationSec)
        iAx = rec.accelTs.indexOfFirst { it >= posSec }.let { if (it < 0) rec.accelTs.size else it }
        iBp = rec.baroTs.indexOfFirst { it >= posSec }.let { if (it < 0) rec.baroTs.size else it }
        _status.value = _status.value.copy(positionSec = posSec)
    }

    fun play() {
        if (job?.isActive == true) return
        job = scope.launch {
            _status.value = _status.value.copy(playing = true)
            val tickDtMs = 20L
            while (isActive) {
                val nextA = rec.accelTs.getOrNull(iAx) ?: Double.POSITIVE_INFINITY
                val nextB = rec.baroTs.getOrNull(iBp) ?: Double.POSITIVE_INFINITY
                val nextTs = min(nextA, nextB)

                if (nextTs == Double.POSITIVE_INFINITY) {
                    if (loop) {
                        seekAbs(0.0)
                        continue
                    } else {
                        _status.value = _status.value.copy(playing = false, positionSec = posSec)
                        break
                    }
                }

                val stepSec = (tickDtMs.toDouble() / 1000.0) * speed
                posSec = min(rec.durationSec, posSec + stepSec)

                // emit all samples up to current posSec
                while (rec.accelTs.getOrNull(iAx)?.let { it <= posSec } == true) {
                    onAccel?.invoke(rec.accelTs[iAx], rec.ax[iAx], rec.ay[iAx], rec.az[iAx])
                    iAx++
                }
                while (rec.baroTs.getOrNull(iBp)?.let { it <= posSec } == true) {
                    onBaro?.invoke(rec.baroTs[iBp], rec.pHpa[iBp])
                    iBp++
                }

                _status.value = _status.value.copy(positionSec = posSec)
                delay(tickDtMs)
            }
        }
    }

    fun pause() {
        job?.cancel()
        job = null
        _status.value = _status.value.copy(playing = false)
    }

    fun stop() {
        pause()
        seekAbs(0.0)
    }
}
