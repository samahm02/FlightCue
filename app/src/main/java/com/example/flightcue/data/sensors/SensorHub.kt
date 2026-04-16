package com.example.flightcue.data.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.util.Log
import com.example.flightcue.domain.util.Params

/**
 * Registers and manages accelerometer and barometer sensors.
 * Sensor callbacks are forwarded to [onAccel] and [onBaro] on the provided [callbackHandler]
 * thread, or the calling thread if no handler is given.
 *
 * Both sensors are always active; sensor registration is not state-dependent because
 * the ML pipeline uses both modalities for takeoff and landing detection.
 */
class SensorHub(
    context: Context,
    private val onAccel: (tSec: Double, ax: Double, ay: Double, az: Double) -> Unit,
    private val onBaro: (tSec: Double, pressure: Double) -> Unit,
    private val logRates: Boolean = false,
    private val callbackHandler: Handler? = null
) : SensorEventListener {

    private val sm    = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val baro  = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private var registered = false

    // Rate-logging state (only used when logRates = true)
    private var accCount = 0
    private var accWindowStartNs = 0L
    private var baroCount = 0
    private var baroWindowStartNs = 0L

    companion object {
        private const val TAG = "SensorHub"
        private const val RATE_LOG_WINDOW_NS = 2_000_000_000L
    }

    /** Unregisters all sensors and resets rate-logging counters. */
    fun unregisterAll() {
        runCatching { sm.unregisterListener(this) }
        registered = false
        accCount = 0; baroCount = 0
        accWindowStartNs = 0L; baroWindowStartNs = 0L
    }

    /** Registers both sensors. No-op if already registered. */
    fun ensureRegistered() {
        if (registered) return
        startAccel()
        startBaro()
        registered = true
        Log.i(TAG, "Sensors registered (accel=${accel != null}, baro=${baro != null})")
    }

    private fun startAccel() {
        val periodUs  = hzToPeriodUs(Params.ACCEL_HZ)
        // latencyUs tells the hardware how long it may batch samples before
        // flushing to the app. Larger values reduce wake-ups at the cost of latency.
        val latencyUs = Params.SENSOR_BATCH_SEC * 1_000_000
        accel?.let {
            if (callbackHandler != null) sm.registerListener(this, it, periodUs, latencyUs, callbackHandler)
            else sm.registerListener(this, it, periodUs, latencyUs)
        }
    }

    private fun startBaro() {
        val periodUs  = hzToPeriodUs(Params.BARO_HZ)
        // Same batching window as accel — both sensors flush on the same schedule.
        val latencyUs = Params.SENSOR_BATCH_SEC * 1_000_000
        baro?.let {
            if (callbackHandler != null) sm.registerListener(this, it, periodUs, latencyUs, callbackHandler)
            else sm.registerListener(this, it, periodUs, latencyUs)
        }
    }

    private fun hzToPeriodUs(hz: Double): Int =
        (1_000_000.0 / hz.coerceAtLeast(1.0)).toInt().coerceAtLeast(1)

    override fun onSensorChanged(e: SensorEvent) {
        val tSec = e.timestamp.toDouble() / 1e9
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                onAccel(tSec, e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
                if (logRates) rateLogAccel(e.timestamp)
            }
            Sensor.TYPE_PRESSURE -> {
                onBaro(tSec, e.values[0].toDouble())
                if (logRates) rateLogBaro(e.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun rateLogAccel(ts: Long) {
        if (accWindowStartNs == 0L) accWindowStartNs = ts
        accCount++
        val dt = ts - accWindowStartNs
        if (dt >= RATE_LOG_WINDOW_NS) {
            val hz = accCount.toDouble() * 1e9 / dt.toDouble()
            Log.i(TAG, "ACCEL ≈ ${"%.1f".format(hz)} Hz (Δ=${"%.1f".format(dt / 1e9)}s)")
            accWindowStartNs = ts
            accCount = 0
        }
    }

    private fun rateLogBaro(ts: Long) {
        if (baroWindowStartNs == 0L) baroWindowStartNs = ts
        baroCount++
        val dt = ts - baroWindowStartNs
        if (dt >= RATE_LOG_WINDOW_NS) {
            val hz = baroCount.toDouble() * 1e9 / dt.toDouble()
            Log.i(TAG, "BARO  ≈ ${"%.1f".format(hz)} Hz (Δ=${"%.1f".format(dt / 1e9)}s)")
            baroWindowStartNs = ts
            baroCount = 0
        }
    }
}