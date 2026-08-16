package com.example.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ForegroundService : Service() {
    companion object {
        private const val TAG = "ForegroundService"
        const val NOTIFICATION_ID = 9991
        private const val CHANNEL_ID = "shield_ghost_channel_v4"
        private const val PULSE_DURATION_MS = 200L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isForeground = false
    private var hideRunnable: Runnable? = null
    
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == "PULSE_ACTION") {
            val actionType = intent.getStringExtra("action_type") ?: "Sync"
            triggerPhantomPulse(actionType)
            return START_STICKY
        }

        createGhostChannel()
        startForeground(NOTIFICATION_ID, buildGhostNotification("System Ready"))
        isForeground = true
        
        scheduler.scheduleAtFixedRate({
            triggerPhantomPulse("Background Sync")
        }, Random.nextLong(4, 9), Random.nextLong(4, 9), TimeUnit.HOURS)

        return START_STICKY
    }

    private fun createGhostChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "System Background Services",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Core system operations"
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            } catch (e: Exception) {
                Log.e(TAG, "Channel error: ${e.message}")
            }
        }
    }

    private fun buildGhostNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("System Service")
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openPending)
            .build()
    }

    private fun triggerPhantomPulse(actionType: String) {
        if (!isForeground) return
        hideRunnable?.let { handler.removeCallbacks(it) }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildGhostNotification("Processing $actionType..."))
        
        hideRunnable = Runnable {
            if (isForeground) {
                nm.notify(NOTIFICATION_ID, buildGhostNotification(""))
            }
        }
        handler.postDelayed(hideRunnable!!, PULSE_DURATION_MS)
    }

    override fun onDestroy() {
        scheduler.shutdownNow()
        hideRunnable?.let { handler.removeCallbacks(it) }
        isForeground = false
        super.onDestroy()
    }
}
