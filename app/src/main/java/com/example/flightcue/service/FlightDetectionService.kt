@file:Suppress("MissingPermission")

package com.example.flightcue.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.flightcue.data.detection.DetectionEngine
import com.example.flightcue.data.modelspec.AppPaths

class FlightDetectionService : Service() {

    private lateinit var engine: DetectionEngine

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            AppPaths.FGS_CHANNEL_ID,
            AppPaths.FGS_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(ch)
        engine = DetectionEngine(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = NotificationCompat.Builder(this, AppPaths.FGS_CHANNEL_ID)
            .setContentTitle("FlightCue")
            .setContentText("Monitoring in background")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done) // TODO: app icon
            .setOngoing(true)
            .build()

        ServiceCompat.startForeground(
            this,
            AppPaths.FGS_NOTIFICATION_ID,
            notif,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        )

        engine.start()
        return START_STICKY
    }

    override fun onDestroy() {
        engine.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun ensureRunning(context: Context) {
            context.startForegroundService(Intent(context, FlightDetectionService::class.java))
        }
    }
}
