package com.example.flightcue.data.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.util.Log
import com.example.flightcue.domain.events.FlightState
import com.example.flightcue.domain.util.Params

class SensorHub(
    context: Context,
    private val onAccel: (tSec: Double, ax: Double, ay: Double, az: Double) -> Unit,
    private val onBaro: (tSec: Double, pressure: Double) -> Unit,
    private val logRates: Boolean = false,
    private val callbackHandler: Handler? = null
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val baro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private var registered = false

    // rate logging
    private var accCount = 0
    private var accWindowStartNs = 0L
    private var baroCount = 0
    private var baroWindowStartNs = 0L

    companion object {
        private const val TAG = "SensorHub"
        private const val RATE_LOG_WINDOW_NS = 2_000_000_000L
    }

    fun unregisterAll() {
        runCatching { sm.unregisterListener(this) }
        registered = false

        // reset rate stats too
        accCount = 0
        baroCount = 0
        accWindowStartNs = 0L
        baroWindowStartNs = 0L
    }

    /**
     * Your schema uses accel+baro features for BOTH events.
     * Therefore, we keep ACCEL always on and BARO on if available.
     *
     * Idempotent.
     */
    fun switchForState(@Suppress("UNUSED_PARAMETER") state: FlightState) {
        if (registered) return

        startAccel(Params.ACCEL_HZ, batchSec = Params.SENSOR_BATCH_SEC)

        if (baro == null) {
            Log.w(TAG, "No barometer sensor available; baro features will degrade to medians.")
        } else {
            startBaro(Params.BARO_HZ, batchSec = Params.SENSOR_BATCH_SEC)
        }

        registered = true
        Log.i(TAG, "Sensors registered (accel=${accel != null}, baro=${baro != null})")
    }

    private fun startAccel(rateHz: Double, batchSec: Int) {
        val periodUs = hzToPeriodUs(rateHz)
        val latencyUs = batchSec * 1_000_000
        accel?.let {
            if (callbackHandler != null) sm.registerListener(this, it, periodUs, latencyUs, callbackHandler)
            else sm.registerListener(this, it, periodUs, latencyUs)
        }
    }

    private fun startBaro(rateHz: Double, batchSec: Int) {
        val periodUs = hzToPeriodUs(rateHz)
        val latencyUs = batchSec * 1_000_000
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
