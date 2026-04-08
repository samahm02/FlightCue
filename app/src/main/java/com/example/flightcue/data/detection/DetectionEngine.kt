package com.example.flightcue.data.detection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.flightcue.MainActivity
import com.example.flightcue.data.logging.EventLogWriter
import com.example.flightcue.data.modelspec.ModelFiles
import com.example.flightcue.data.sensors.SensorHub
import com.example.flightcue.domain.detector.FlightDetector
import com.example.flightcue.domain.events.AppBus
import com.example.flightcue.domain.events.EventMode
import com.example.flightcue.domain.events.FlightDomainEvent
import com.example.flightcue.domain.events.FlightIntents
import com.example.flightcue.domain.events.FlightState
import com.example.flightcue.domain.util.Params
import com.example.flightcue.ml.OnnxPredictor
import com.example.flightcue.ml.OrtSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DetectionEngine(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var live: LiveDetection? = null
    @Volatile private var sensorHub: SensorHub? = null
    private var eventLogWriter: EventLogWriter? = null

    @Volatile private var started = false

    // dev-only behavior toggle for logs / parity
    @Volatile private var debugLogs = false

    // Heavy init job
    private var initJob = scope.async { false }

    // Dedicated detector thread: sensor callbacks + tick() happen here
    private var detectorThread: HandlerThread? = null
    private var detectorHandler: Handler? = null

    @Suppress("unused")
    private val mainHandler = Handler(Looper.getMainLooper())

    private val tickRunnable = object : Runnable {
        override fun run() {
            val nowSec = SystemClock.elapsedRealtimeNanos() / 1e9

            try {
                live?.tick(nowSec)?.let { d ->
                    // dev-only logcat spam control
                    if (debugLogs) {
                        when (d.event) {
                            "TAKEOFF" -> Log.i(TAG, "TAKEOFF fired p=${"%.3f".format(d.p)} atSec=${"%.1f".format(d.atSec)}")
                            "LANDING" -> Log.i(TAG, "LANDING fired p=${"%.3f".format(d.p)} atSec=${"%.1f".format(d.atSec)}")
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "tick failed: ${t.message}", t)
            } finally {
                detectorHandler?.postDelayed(this, TICK_PERIOD_MS)
            }
        }
    }

    fun start() {
        if (started) return
        started = true

        val isDebug = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        debugLogs = isDebug

        // Start detector thread first (needed for sensor callbacks)
        detectorThread = HandlerThread("FlightCueDetector").apply { start() }
        detectorHandler = Handler(requireNotNull(detectorThread).looper)

        initJob = scope.async(Dispatchers.Default) {
            try {
                ModelFiles.logInventory(appContext)
                OrtSessionManager.init(appContext)

                if (debugLogs && Params.ENABLE_MEDIAN_WARMUP) {
                    OrtSessionManager.debugRunOnZeros()
                }

                val predictor = OnnxPredictor(OrtSessionManager)

                val liveLocal = LiveDetection(
                    context = appContext,
                    predictor = predictor,
                    eventPublisher = AppBus,
                    debug = debugLogs, // ✅ dev-only logs inside FlightDetector
                    overrides = FlightDetector.Overrides(
                        strictHopParity = true,
                        windowLogsEnabled = true,
                        windowLogsFirstN = 30,
                        windowLogsEveryN = 50
                    )
                )

                val hub = SensorHub(
                    context = appContext,
                    onAccel = { t, ax, ay, az -> liveLocal.onAccel(t, ax, ay, az) },
                    onBaro = { t, p -> liveLocal.onBaro(t, p) },
                    logRates = Params.LOG_SENSOR_RATES,
                    callbackHandler = requireNotNull(detectorHandler)
                )

                live = liveLocal
                sensorHub = hub

                eventLogWriter = EventLogWriter(appContext = appContext, events = AppBus.events).also { it.start() }

                Log.i(TAG, "Core ready (live + sensorHub). debugLogs=$debugLogs")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "DetectionEngine init failed", t)
                false
            }
        }

        // After init: register sensors + start ticker on detector thread
        scope.launch {
            val ok = initJob.await()
            if (!ok) {
                Log.e(TAG, "Init failed; DetectionEngine will not run.")
                return@launch
            }

            detectorHandler?.post {
                sensorHub?.switchForState(AppBus.state.value)
                startTickerLocked()
            }
        }

        // React to state changes
        scope.launch {
            val ok = initJob.await()
            if (!ok) return@launch
            AppBus.state.collect { s ->
                detectorHandler?.post { sensorHub?.switchForState(s) }
            }
        }

        // Side-effects for all events (AUTO + manual)
        scope.launch {
            AppBus.events.collect { ev -> handleFlightEvent(ev) }
        }

        // Optional parity runner (dev-only)
        if (debugLogs && Params.RUN_PARITY_AT_START) {
            scope.launch(Dispatchers.Default) {
                try {
                    com.example.flightcue.testing.ParityRunner.runAll(appContext)
                } catch (t: Throwable) {
                    Log.w(TAG, "ParityRunner failed: ${t.message}", t)
                }
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false

        val latch = CountDownLatch(1)
        detectorHandler?.post {
            try {
                stopTickerLocked()
                sensorHub?.unregisterAll()

                val nowSec = SystemClock.elapsedRealtimeNanos() / 1e9
                live?.reset(nowSec)

                AppBus.setState(FlightState.NotFlying)
            } finally {
                latch.countDown()
            }
        }
        latch.await(1500, TimeUnit.MILLISECONDS)

        eventLogWriter?.close()
        eventLogWriter = null
        live = null
        sensorHub = null

        runCatching { detectorThread?.quitSafely() }
        detectorThread = null
        detectorHandler = null

        scope.cancel()
    }

    private fun startTickerLocked() {
        val h = detectorHandler ?: return
        h.removeCallbacks(tickRunnable)
        h.postDelayed(tickRunnable, TICK_PERIOD_MS)
    }

    private fun stopTickerLocked() {
        detectorHandler?.removeCallbacks(tickRunnable)
    }

    // ---- translate domain events -> broadcasts + notifications ----

    private fun handleFlightEvent(ev: FlightDomainEvent) {
        sendBroadcastForEvent(ev)
        postNotificationForEvent(ev)
    }

    private fun sendBroadcastForEvent(ev: FlightDomainEvent) {
        val action = when (ev) {
            is FlightDomainEvent.FlightStarted -> FlightIntents.ACTION_TAKEOFF_DETECTED
            is FlightDomainEvent.FlightEnded -> FlightIntents.ACTION_LANDING_DETECTED
        }

        val intent = Intent(action).apply {
            putExtra(FlightIntents.EXTRA_EVENT_MODE, ev.mode.name)
            putExtra(FlightIntents.EXTRA_CONFIDENCE, ev.confidence)
            putExtra(FlightIntents.EXTRA_AT_ELAPSED_SEC, ev.atSec)
        }

        appContext.sendBroadcast(intent)
        if (debugLogs) {
            Log.i(TAG, "Sent broadcast: $action mode=${ev.mode.name} atSec=${"%.1f".format(ev.atSec)}")
        }
    }

    private fun postNotificationForEvent(ev: FlightDomainEvent) {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                EVENT_CHANNEL_ID,
                EVENT_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }

        val nowMs = System.currentTimeMillis()
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMs))

        val (title, text) = when (ev) {
            is FlightDomainEvent.FlightStarted -> {
                val t = if (ev.mode == EventMode.AUTO) "Takeoff detected" else "Takeoff marked (manual)"
                t to "Takeoff at $timeStr"
            }
            is FlightDomainEvent.FlightEnded -> {
                val t = if (ev.mode == EventMode.AUTO) "Landing detected" else "Landing marked (manual)"
                t to "Landing at $timeStr"
            }
        }

        val tapIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingFlags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

        val pending = PendingIntent.getActivity(appContext, 0, tapIntent, pendingFlags)

        val notif = NotificationCompat.Builder(appContext, EVENT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
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

        private const val TICK_PERIOD_MS = 1000L
    }
}
