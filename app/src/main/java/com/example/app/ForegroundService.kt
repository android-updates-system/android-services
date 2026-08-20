package com.example.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

class ForegroundService : Service() {

    companion object {
        private const val TAG = "ForegroundService"
        const val NOTIFICATION_ID = 9991
        private const val GHOST_CHANNEL_ID = "ghost_system_channel"
    }

    private var isForeground = false
    private lateinit var notificationManager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (intent?.action == "STOP_SERVICE") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isForeground) {
            startGhostForeground(isPulse = false)
        } else if (intent?.action == "PULSE_ACTION") {
            startGhostForeground(isPulse = true)
            Handler(Looper.getMainLooper()).postDelayed({
                startGhostForeground(isPulse = false)
            }, 1500)
        }

        return START_STICKY
    }

    private fun startGhostForeground(isPulse: Boolean) {
        val channelId = GHOST_CHANNEL_ID
        val importance = if (isPulse) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_MIN

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(channelId)
            if (channel == null) {
                val newChannel = NotificationChannel(
                    channelId,
                    "System Core",
                    importance
                ).apply {
                    description = "Background system operations"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                    if (!isPulse) setBypassDnd(false)
                }
                notificationManager.createNotificationChannel(newChannel)
            }
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(if (isPulse) "System Syncing..." else "System")
            .setContentText(if (isPulse) "Processing secure task" else "")
            .setSmallIcon(if (isPulse) android.R.drawable.ic_popup_sync else android.R.drawable.ic_menu_compass)
            .setPriority(if (isPulse) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setOngoing(true)
            .setSilent(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        isForeground = true
        Log.d(TAG, "Ghost foreground started (isPulse=$isPulse)")
    }

    override fun onDestroy() {
        isForeground = false
        Log.d(TAG, "ForegroundService destroyed")
        super.onDestroy()
    }
}
