package com.example.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * خدمة أمامية (Foreground Service) تعمل في الخلفية مع إشعار "شبحى" دائم.
 *
 * استراتيجية التخفي المتقدمة (Stealth Ghost Notification):
 * - الإشعار الأساسي بأولوية IMPORTANCE_MIN (مخفي تماماً في شريط الحالة)
 * - setOngoing(true) يبقى مفعلاً طوال الوقت لمنع قتل الخدمة
 * - أيقونة نظامية عامة (stat_sys_data_bluetooth) لتجنب الشك
 * - إخفاء المحتوى من شاشة القفل
 * - لا يتم إلغاء الإشعار نهائياً للحفاظ على حالة "الأمامية" للخدمة
 *   (إلغاء الإشعار قد يؤدي إلى قتل الخدمة على أندرويد 10+)
 *
 * ✅ هذه الاستراتيجية تمنع قتل الخدمة في أجهزة شاومي وهواوي
 * ✅ مع الحفاظ على التخفي المطلق من وجهة نظر المستخدم
 * ✅ تم إصلاح ForegroundServiceDidNotStartInTimeException
 *   باستخدام startForeground مع النوع الصحيح (FOREGROUND_SERVICE_TYPE_DATA_SYNC)
 *   وإبقاء الإشعار حياً بشكل شبحى (غير مرئي)
 */
class ForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "ghost_system_channel_v3"
        private const val NOTIF_ID = 7777
        private const val TAG = "ForegroundService"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // إيقاف الخدمة إذا طُلب ذلك
        if (intent?.action == "STOP_SERVICE") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // ✅ بدء الخدمة كـ Foreground مع إشعار شبحى دائم
        startGhostForeground()

        return START_STICKY
    }

    /**
     * بدء الخدمة الأمامية مع إشعار شبحى دائم.
     * يتم إنشاء قناة إشعار بأدنى أولوية ممكنة (IMPORTANCE_MIN)
     * مما يجعل الإشعار غير مرئي تماماً في شريط الحالة.
     * لا يتم إلغاء الإشعار للحفاظ على حالة "الأمامية" للخدمة.
     */
    private fun startGhostForeground() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // ✅ إنشاء قناة الإشعارات بأدنى أولوية ممكنة
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "System Core",
                    NotificationManager.IMPORTANCE_MIN // أدنى أولوية مرئية
                ).apply {
                    description = "Background system operations"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                    setBypassDnd(false)
                    enableLights(false)
                }
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "✅ Ghost notification channel created")
            }

            // ✅ بناء إشعار شبحى (فارغ، بدون صوت، بدون اهتزاز)
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("")
                .setContentText("")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true) // ✅ ضروري لمنع قتل الخدمة
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()

            // ✅ بدء الخدمة قانونياً مع النوع المناسب لتجنب قيود أندرويد 14
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification)
            }

            MainActivity.appendLogStatic("✅ Ghost notification started (permanent invisible)")
            Log.d(TAG, "✅ Ghost notification started (permanent invisible)")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start foreground service: ${e.message}")
            MainActivity.appendLogStatic("❌ Foreground service start error: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ForegroundService destroyed")
        MainActivity.appendLogStatic("🛑 ForegroundService destroyed")
    }
}
