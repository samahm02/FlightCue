// file: app/src/main/java/com/example/flightcue/service/FlightDetectionService.kt
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

/**
 * Foreground service that keeps DetectionEngine alive in the background.
 *
 * - Runs by default when SettingsStore.detectionEnabled == true
 * - Can be stopped fully (engine + sensors + ticker + notification removed)
 *
 * NOTE:
 * On Android 13+ if user denies POST_NOTIFICATIONS, the service can still run,
 * but the user may not see the notification/event notifications.
 */
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
            else -> {
                if (!started) {
                    started = true
                    // Must go foreground quickly on API 26+
                    startForegroundInternal()
                    engine?.start()
                    Log.i(TAG, "Started (foreground + engine)")
                } else {
                    // idempotent
                    Log.i(TAG, "Already started")
                }
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopEverything("onDestroy")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopEverything(reason: String) {
        if (!started && engine == null) return

        Log.i(TAG, "Stopping everything ($reason)")

        runCatching { engine?.stop() }
        engine = null
        started = false

        // Remove foreground notification
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
    }

    private fun startForegroundInternal() {
        val notif = buildOngoingNotification()

        // Match manifest android:foregroundServiceType="health"
        val fgsType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0

        ServiceCompat.startForeground(
            /* service = */ this,
            /* id = */ ONGOING_NOTIFICATION_ID,
            /* notification = */ notif,
            /* foregroundServiceType = */ fgsType
        )
    }

    private fun buildOngoingNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingFlags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

        val pending = PendingIntent.getActivity(this, 0, tapIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // TODO replace with app icon
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

        /** Start (or keep running). Safe to call repeatedly. */
        fun start(context: Context) {
            val i = Intent(context, FlightDetectionService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        /** Hard stop (engine + sensors + ticker + notification removed). */
        fun stop(context: Context) {
            // stopService does not require us to "startForegroundService" first.
            context.stopService(Intent(context, FlightDetectionService::class.java))
        }

        /** Optional: stop via explicit action (only safe if service is already running). */
        fun requestStopAction(context: Context) {
            val i = Intent(context, FlightDetectionService::class.java).apply { action = ACTION_STOP }
            // Use startService so we don't trigger the "must call startForeground" rule if service isn't running.
            context.startService(i)
        }
    }
}
