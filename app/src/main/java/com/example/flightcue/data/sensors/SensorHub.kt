package com.example.flightcue.data.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.example.flightcue.domain.events.FlightState
import com.example.flightcue.domain.util.Params

/**
 * Single place that owns SensorManager + registration for both ACCEL & BARO.
 * Pushes parsed samples to the provided callbacks.
 */
class SensorHub(
    context: Context,
    private val onAccel: (tSec: Double, ax: Double, ay: Double, az: Double) -> Unit,
    private val onBaro: (tSec: Double, pressure: Double) -> Unit,
    private val logRates: Boolean = false
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val baro:  Sensor? = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)

    // rate logging
    private var accCount = 0
    private var accWindowStartNs = 0L
    private var baroCount = 0
    private var baroWindowStartNs = 0L

    companion object {
        private const val TAG = "SensorHub"
        private const val RATE_LOG_WINDOW_NS = 2_000_000_000L
    }

    /** Public API **/

    fun unregisterAll() = runCatching { sm.unregisterListener(this) }.onFailure { }.let {}

    fun switchForState(state: FlightState) {
        unregisterAll()
        when (state) {
            FlightState.NotFlying -> startAccel(Params.ACCEL_HZ, batchSec = 2)
            FlightState.Flying -> {
                if (baro == null) {
                    Log.w(TAG, "No barometer — landing disabled; keeping ACCEL")
                    startAccel(Params.ACCEL_HZ, batchSec = 2)
                } else {
                    startBaro(Params.BARO_HZ, batchSec = 5)
                }
            }
        }
    }

    /** Internals **/

    private fun startAccel(rateHz: Double, batchSec: Int) {
        val periodUs = hzToPeriodUs(rateHz)
        val latencyUs = batchSec * 1_000_000
        accel?.let { sm.registerListener(this, it, periodUs, latencyUs) }
        Log.i(TAG, "Registered ACCEL @~${"%.2f".format(rateHz)} Hz, batch ${batchSec}s (periodUs=$periodUs)")
    }

    private fun startBaro(rateHz: Double, batchSec: Int) {
        val periodUs = hzToPeriodUs(rateHz)
        val latencyUs = batchSec * 1_000_000
        baro?.let { sm.registerListener(this, it, periodUs, latencyUs) }
        Log.i(TAG, "Registered BARO  @~${"%.2f".format(rateHz)} Hz, batch ${batchSec}s (periodUs=$periodUs)")
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
            accWindowStartNs = ts; accCount = 0
        }
    }

    private fun rateLogBaro(ts: Long) {
        if (baroWindowStartNs == 0L) baroWindowStartNs = ts
        baroCount++
        val dt = ts - baroWindowStartNs
        if (dt >= RATE_LOG_WINDOW_NS) {
            val hz = baroCount.toDouble() * 1e9 / dt.toDouble()
            Log.i(TAG, "BARO  ≈ ${"%.1f".format(hz)} Hz (Δ=${"%.1f".format(dt / 1e9)}s)")
            baroWindowStartNs = ts; baroCount = 0
        }
    }
}
