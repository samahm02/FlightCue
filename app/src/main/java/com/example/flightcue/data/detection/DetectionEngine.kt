package com.example.flightcue.data.detection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.HandlerThread
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

/**
 * Top-level detection orchestrator (data layer).
 *
 * Responsibilities:
 *  - Owns the lifecycle of [LiveDetection], [SensorHub], and [EventLogWriter].
 *  - Runs a dedicated [HandlerThread] ("FlightCueDetector") so that sensor
 *    callbacks and [LiveDetection.tick] never block the main thread.
 *  - Forwards domain events from [AppBus] to system broadcasts and notifications.
 *
 * Call [start] once (e.g. from FlightCueService.onStartCommand) and [stop] when
 * the service is destroyed. The class is not reusable after [stop].
 */
class DetectionEngine(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var live: LiveDetection? = null
    @Volatile private var sensorHub: SensorHub? = null
    private var eventLogWriter: EventLogWriter? = null

    @Volatile private var started = false

    // Enabled only on debug builds; gates verbose logcat output and parity runner.
    @Volatile private var debugLogs = false

    // Deferred heavy init (ORT session load, model file checks).
    private var initJob = scope.async { false }

    // All sensor callbacks and tick() run on this thread to avoid main-thread work.
    private var detectorThread: HandlerThread? = null
    private var detectorHandler: Handler? = null

    /**
     * Periodic runnable that drives [LiveDetection.tick] at [TICK_PERIOD_MS] intervals.
     * Runs on [detectorHandler] so it is serialised with sensor callbacks.
     */
    private val tickRunnable = object : Runnable {
        override fun run() {
            val nowSec = SystemClock.elapsedRealtimeNanos() / 1e9

            try {
                live?.tick(nowSec)?.let { d ->
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

    /**
     * Initialises all detection components and begins sensor streaming.
     * Safe to call from any thread. No-op if already started.
     */
    fun start() {
        if (started) return
        started = true

        debugLogs = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        // Detector thread must exist before sensor callbacks can be routed to it.
        detectorThread = HandlerThread("FlightCueDetector").apply { start() }
        detectorHandler = Handler(requireNotNull(detectorThread).looper)

        // Heavy init (ORT session, model files) runs off the main thread.
        initJob = scope.async(Dispatchers.Default) {
            try {
                ModelFiles.logInventory(appContext)
                OrtSessionManager.init(appContext)

                val predictor = OnnxPredictor(OrtSessionManager)

                val liveLocal = LiveDetection(
                    predictor = predictor,
                    eventPublisher = AppBus,
                    debug = debugLogs,
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

                eventLogWriter = EventLogWriter(
                    appContext = appContext,
                    events = AppBus.events
                ).also { it.start() }

                Log.i(TAG, "DetectionEngine ready. debugLogs=$debugLogs")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "DetectionEngine init failed", t)
                false
            }
        }

        // Once init completes: register sensors and start the tick loop.
        scope.launch {
            if (!initJob.await()) {
                Log.e(TAG, "Init failed; DetectionEngine will not run.")
                return@launch
            }
            detectorHandler?.post {
                sensorHub?.ensureRegistered()
                startTickerLocked()
            }
        }

        // Translate all domain events (AUTO + FORCED) to broadcasts and notifications.
        scope.launch {
            if (!initJob.await()) return@launch
            AppBus.events.collect { ev -> handleFlightEvent(ev) }
        }

        // Parity runner is a dev-only self-test; only runs on debug builds.
        if (debugLogs) {
            scope.launch(Dispatchers.Default) {
                try {
                    com.example.flightcue.testing.ParityRunner.runAll(appContext)
                } catch (t: Throwable) {
                    Log.w(TAG, "ParityRunner failed: ${t.message}", t)
                }
            }
        }
    }

    /**
     * Stops sensor streaming, cancels the tick loop, and releases all resources.
     * Blocks briefly (≤ 1.5 s) to let the detector thread flush cleanly.
     */
    fun stop() {
        if (!started) return
        started = false

        val latch = CountDownLatch(1)
        detectorHandler?.post {
            try {
                stopTickerLocked()
                sensorHub?.unregisterAll()
                live?.reset(SystemClock.elapsedRealtimeNanos() / 1e9)
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

    // ---- Manual override entry points (called from UI via ViewModel) ----

    /** Publishes a forced takeoff event and advances the detector FSM. */
    fun forceTakeoff(confidence: Double = 1.0) {
        scope.launch {
            if (!initJob.await()) return@launch
            val atSec = SystemClock.elapsedRealtimeNanos() / 1e9
            detectorHandler?.post {
                live?.forceFlightStarted(
                    atSec = atSec,
                    confidence = confidence,
                    mode = EventMode.FORCED,
                    publishEvent = true
                )
            }
        }
    }

    /** Publishes a forced landing event and advances the detector FSM. */
    fun forceLanding(confidence: Double = 1.0) {
        scope.launch {
            if (!initJob.await()) return@launch
            val atSec = SystemClock.elapsedRealtimeNanos() / 1e9
            detectorHandler?.post {
                live?.forceFlightEnded(
                    atSec = atSec,
                    confidence = confidence,
                    mode = EventMode.FORCED,
                    publishEvent = true
                )
            }
        }
    }

    // ---- Private helpers ----

    private fun startTickerLocked() {
        val h = detectorHandler ?: return
        h.removeCallbacks(tickRunnable)
        h.postDelayed(tickRunnable, TICK_PERIOD_MS)
    }

    private fun stopTickerLocked() {
        detectorHandler?.removeCallbacks(tickRunnable)
    }

    private fun handleFlightEvent(ev: FlightDomainEvent) {
        sendBroadcastForEvent(ev)
        postNotificationForEvent(ev)
    }

    private fun sendBroadcastForEvent(ev: FlightDomainEvent) {
        val action = when (ev) {
            is FlightDomainEvent.FlightStarted -> FlightIntents.ACTION_TAKEOFF_DETECTED
            is FlightDomainEvent.FlightEnded   -> FlightIntents.ACTION_LANDING_DETECTED
        }
        val intent = Intent(action).apply {
            putExtra(FlightIntents.EXTRA_EVENT_MODE, ev.mode.name)
            putExtra(FlightIntents.EXTRA_CONFIDENCE, ev.confidence)
            putExtra(FlightIntents.EXTRA_AT_ELAPSED_SEC, ev.atSec)
        }
        appContext.sendBroadcast(intent)
        if (debugLogs) {
            Log.i(TAG, "Broadcast: $action mode=${ev.mode.name} atSec=${"%.1f".format(ev.atSec)}")
        }
    }

    /**
     * Posts a heads-up notification for each detected or forced event.
     * Note: notification logic lives here because DetectionEngine runs as a
     * background service with no direct View layer.
     */
    private fun postNotificationForEvent(ev: FlightDomainEvent) {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // NotificationChannel is required on API 26+; minSdk satisfies this.
        val channel = NotificationChannel(
            EVENT_CHANNEL_ID,
            EVENT_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(channel)

        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        val (title, text) = when (ev) {
            is FlightDomainEvent.FlightStarted ->
                (if (ev.mode == EventMode.AUTO) "Takeoff detected" else "Takeoff marked (manual)") to
                        "Takeoff at $timeStr"
            is FlightDomainEvent.FlightEnded ->
                (if (ev.mode == EventMode.AUTO) "Landing detected" else "Landing marked (manual)") to
                        "Landing at $timeStr"
        }

        val tapIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        // FLAG_IMMUTABLE is required on API 23+; minSdk satisfies this.
        val pending = PendingIntent.getActivity(
            appContext, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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

        private const val EVENT_CHANNEL_ID   = "flightcue_events"
        private const val EVENT_CHANNEL_NAME = "Flight events"
        private const val EVENT_NOTIFICATION_ID = 2001

        /** How often [LiveDetection.tick] is called (milliseconds). */
        private const val TICK_PERIOD_MS = 1000L
    }
}