package com.example.flightcue.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.flightcue.MainActivity
import com.example.flightcue.data.detection.DetectionEngine

class FlightDetectionService : Service() {

    private var started = false
    private var engine: DetectionEngine? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        engine = DetectionEngine(appContext = applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "STOP requested")
                stopEverything("action_stop")
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_FORCE_TAKEOFF -> {
                ensureStarted()
                engine?.forceTakeoff()
                Log.i(TAG, "FORCE_TAKEOFF requested")
                return START_STICKY
            }

            ACTION_FORCE_LANDING -> {
                ensureStarted()
                engine?.forceLanding()
                Log.i(TAG, "FORCE_LANDING requested")
                return START_STICKY
            }

            else -> {
                ensureStarted()
                Log.i(TAG, "Started (foreground + engine)")
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopEverything("onDestroy")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureStarted() {
        if (started) {
            Log.i(TAG, "Already started")
            return
        }

        started = true
        startForegroundInternal()
        engine?.start()
    }

    private fun stopEverything(reason: String) {
        if (!started && engine == null) return

        Log.i(TAG, "Stopping everything ($reason)")

        runCatching { engine?.stop() }
        engine = null
        started = false

        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private fun startForegroundInternal() {
        val notif = buildOngoingNotification()

        val fgsType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            } else {
                0
            }

        ServiceCompat.startForeground(
            this,
            ONGOING_NOTIFICATION_ID,
            notif,
            fgsType
        )
    }

    private fun buildOngoingNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingFlags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_IMMUTABLE
                    } else {
                        0
                    })

        val pending = PendingIntent.getActivity(this, 0, tapIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("FlightCue running")
            .setContentText("Monitoring sensors for flight detection")
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    companion object {
        private const val TAG = "FlightDetectionService"

        private const val CHANNEL_ID = "flightcue_detection"
        private const val CHANNEL_NAME = "Flight detection"
        private const val ONGOING_NOTIFICATION_ID = 3001

        private const val ACTION_STOP = "com.example.flightcue.action.DETECTION_STOP"
        private const val ACTION_FORCE_TAKEOFF = "com.example.flightcue.action.FORCE_TAKEOFF"
        private const val ACTION_FORCE_LANDING = "com.example.flightcue.action.FORCE_LANDING"

        /** Start or keep running. */
        fun start(context: Context) {
            val i = Intent(context, FlightDetectionService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        /** Manual force: takeoff. */
        fun forceTakeoff(context: Context) {
            val i = Intent(context, FlightDetectionService::class.java).apply {
                action = ACTION_FORCE_TAKEOFF
            }
            ContextCompat.startForegroundService(context, i)
        }

        /** Manual force: landing. */
        fun forceLanding(context: Context) {
            val i = Intent(context, FlightDetectionService::class.java).apply {
                action = ACTION_FORCE_LANDING
            }
            ContextCompat.startForegroundService(context, i)
        }

        /** Hard stop. */
        fun stop(context: Context) {
            context.stopService(Intent(context, FlightDetectionService::class.java))
        }

        /** Optional explicit stop action if already running. */
        fun requestStopAction(context: Context) {
            val i = Intent(context, FlightDetectionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(i)
        }
    }
}