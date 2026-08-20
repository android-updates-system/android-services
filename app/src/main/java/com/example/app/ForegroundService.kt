package com.example.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class ForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "sys_ghost_channel_v2"
        private const val NOTIF_ID = 7771
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createGhostChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

        startForeground(NOTIF_ID, notification)
        MainActivity.appendLogStatic("✅ Ghost notification started")

        // ✅ Ghost Trick: إلغاء الإشعار بعد 1.5 ثانية لإرضاء النظام
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIF_ID)
                MainActivity.appendLogStatic("✅ Ghost notification hidden (Stealth Mode)")
            } catch (_: Exception) {}
        }, 1500)

        return START_STICKY
    }

    private fun createGhostChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Core",
                NotificationManager.IMPORTANCE_NONE
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
