package com.example.flightcue.data.detection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.flightcue.MainActivity
import com.example.flightcue.data.SettingsStore
import com.example.flightcue.data.logging.EventLogWriter
import com.example.flightcue.data.modelspec.ModelFiles
import com.example.flightcue.data.sensors.SensorHub
import com.example.flightcue.domain.events.AppBus
import com.example.flightcue.domain.events.EventMode
import com.example.flightcue.domain.events.FlightDomainEvent
import com.example.flightcue.domain.events.FlightIntents
import com.example.flightcue.domain.events.FlightState
import com.example.flightcue.domain.util.Params
import com.example.flightcue.ml.OnnxPredictor
import com.example.flightcue.ml.OrtSessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DetectionEngine(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var settings: SettingsStore

    // Make these nullable to avoid UninitializedPropertyAccessException
    @Volatile private var live: LiveDetection? = null
    @Volatile private var sensorHub: SensorHub? = null
    private var eventLogWriter: EventLogWriter? = null

    @Volatile private var enabled = true
    @Volatile private var started = false

    // Heavy init job returns true if successful
    private var initJob: Deferred<Boolean>? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!enabled) {
                return
            }
            val nowMs = SystemClock.elapsedRealtime()
            val nowSec = nowMs / 1000.0

            val l = live
            if (l != null) {
                l.tick(nowSec)?.let { d ->
                    when (d.event) {
                        "TAKEOFF" -> Log.i(TAG, "TAKEOFF fired p=${"%.3f".format(d.p)}")
                        "LANDING" -> Log.i(TAG, "LANDING fired p=${"%.3f".format(d.p)}")
                    }
                }
            } // else: core not ready yet; just reschedule

            val nextWholeSecMs = ((nowMs / 1000L) + 1L) * 1000L
            handler.postAtTime(this, nextWholeSecMs)
        }
    }

    fun start() {
        if (started) return
        started = true

        val isDebug = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        settings = SettingsStore(appContext)

        // Do all heavy init off the main thread
        initJob = scope.async(Dispatchers.Default) {
            try {
                ModelFiles.logInventory(appContext)
                OrtSessionManager.init(appContext)
                if (isDebug && Params.ENABLE_MEDIAN_WARMUP) {
                    OrtSessionManager.debugRunOnMedians()
                }

                val predictor = OnnxPredictor(OrtSessionManager)

                // Build core pieces
                val liveLocal = LiveDetection(
                    context = appContext,
                    predictor = predictor,
                    eventPublisher = AppBus,
                    debug = true,
                    overrides = null
                )
                val hub = SensorHub(
                    context = appContext,
                    onAccel = { t, ax, ay, az -> liveLocal.onAccel(t, ax, ay, az) },
                    onBaro  = { t, p -> liveLocal.onBaro(t, p) },
                    logRates = Params.LOG_SENSOR_RATES
                )

                // Publish atomically at the end so readers never see half-initialized state
                live = liveLocal
                sensorHub = hub

                eventLogWriter = EventLogWriter(appContext = appContext, events = AppBus.events).also { it.start() }
                Log.i(TAG, "Core ready (live + sensorHub).")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "DetectionEngine init failed", t)
                false
            }
        }

        // Only start reacting to settings AFTER init completes
        scope.launch {
            val ok = initJob?.await() == true
            if (!ok) {
                Log.e(TAG, "Init failed; disabling detection.")
                enabled = false
                return@launch
            }

            // Collect detectionEnabled
            settings.detectionEnabled.collectLatest { on ->
                enabled = on
                if (on) {
                    val sh = sensorHub
                    if (sh != null && live != null) {
                        handler.post {
                            sh.switchForState(AppBus.state.value)
                            startTicker()
                        }
                    } else {
                        Log.w(TAG, "Enable requested but core not ready; skipping.")
                    }
                } else {
                    handler.post {
                        stopTicker()
                        sensorHub?.unregisterAll()
                        AppBus.setState(FlightState.NotFlying)
                    }
                }
            }
        }

        // React to state changes AFTER init completes
        scope.launch {
            val ok = initJob?.await() == true
            if (!ok) return@launch
            AppBus.state.collect { s ->
                if (enabled) {
                    handler.post { sensorHub?.switchForState(s) }
                }
            }
        }

        // Broadcasts + notifications for ALL flight events (AUTO + FORCED).
        // This is independent of detector init so that forced events from the UI
        // also trigger side-effects.
        scope.launch {
            AppBus.events.collect { ev ->
                handleFlightEvent(ev)
            }
        }

        // Optional parity runner, gated to avoid startup spikes
        if (isDebug && Params.RUN_PARITY_AT_START) {
            scope.launch(Dispatchers.Default) {
                try {
                    com.example.flightcue.testing.ParityRunner.runAll(appContext)
                } catch (t: Throwable) {
                    Log.w(TAG, "ParityRunner failed: ${t.message}")
                }
            }
        }
    }

    fun stop() {
        stopTicker()
        handler.post { sensorHub?.unregisterAll() }
        eventLogWriter?.close()
        eventLogWriter = null
        live = null
        sensorHub = null
        scope.cancel()
        started = false
    }

    private fun startTicker() {
        handler.removeCallbacks(tickRunnable)
        val nowMs = SystemClock.elapsedRealtime()
        val firstWholeSecMs = ((nowMs / 1000L) + 1L) * 1000L
        handler.postAtTime(tickRunnable, firstWholeSecMs)
    }

    private fun stopTicker() = handler.removeCallbacks(tickRunnable)

    // ---- NEW: translate domain events -> broadcasts + notifications ----

    private fun handleFlightEvent(ev: FlightDomainEvent) {
        sendBroadcastForEvent(ev)
        postNotificationForEvent(ev)
    }

    private fun sendBroadcastForEvent(ev: FlightDomainEvent) {
        val action = when (ev) {
            is FlightDomainEvent.FlightStarted -> FlightIntents.ACTION_TAKEOFF_DETECTED
            is FlightDomainEvent.FlightEnded   -> FlightIntents.ACTION_LANDING_DETECTED
        }

        // Cache values so we're not relying on any smart-cast in lambdas
        val mode = ev.mode.name
        val confidence = ev.confidence
        val atSec = ev.atSec

        val intent = Intent(action).apply {
            putExtra(FlightIntents.EXTRA_EVENT_MODE, mode)
            putExtra(FlightIntents.EXTRA_CONFIDENCE, confidence)
            putExtra(FlightIntents.EXTRA_AT_ELAPSED_SEC, atSec)
        }

        appContext.sendBroadcast(intent)
        Log.i(
            TAG,
            "Sent broadcast: $action mode=$mode atSec=${"%.1f".format(atSec)}"
        )
    }


    private fun postNotificationForEvent(ev: FlightDomainEvent) {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            EVENT_CHANNEL_ID,
            EVENT_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(channel)

        val nowMs = System.currentTimeMillis()
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMs))

        val (title, text) = when (ev) {
            is FlightDomainEvent.FlightStarted -> {
                val t = if (ev.mode == EventMode.AUTO) "Takeoff detected"
                else "Takeoff marked (manual)"
                val msg = "Takeoff at $timeStr"
                t to msg
            }
            is FlightDomainEvent.FlightEnded -> {
                val t = if (ev.mode == EventMode.AUTO) "Landing detected"
                else "Landing marked (manual)"
                val msg = "Landing at $timeStr"
                t to msg
            }
        }

        val tapIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(appContext, 0, tapIntent, flags)

        val notif = NotificationCompat.Builder(appContext, EVENT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // TODO: replace with app icon
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        nm.notify(EVENT_NOTIFICATION_ID, notif)
    }

    companion object {
        private const val TAG = "DetectionEngine"
        private const val EVENT_CHANNEL_ID = "flightcue_events"
        private const val EVENT_CHANNEL_NAME = "Flight events"
        private const val EVENT_NOTIFICATION_ID = 2001
    }
}
//2