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
 * الخدمة الأمامية "الشبحية" – تقنية النبض الخاطف (Phantom Pulse)
 * - IMPORTANCE_MIN + PRIORITY_MIN: إخفاء الأيقونة من شريط الحالة نهائياً.
 * - setOngoing(true): إبقاء الخدمة مرتبطة لتجاوز قيود Android 14+.
 * - النبض الخاطف: إشعار عابر لمدة 200ms عند تنفيذ أوامر حساسة فقط.
 * - لا يتم استدعاء stopForeground() أبداً للحفاظ على استقرار الخدمة.
 * - التوافق: Android 6+ (API 23+) حتى Android 14+ (API 34).
 */
class ForegroundService : Service() {

    companion object {
        private const val TAG = "ForegroundService"
        const val NOTIFICATION_ID = 9991
        private const val CHANNEL_ID = "shield_ghost_channel_v3"
        private const val PULSE_DURATION_MS = 200L // 200 ميلي ثانية فقط
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isForeground = false
    private var hideRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // معالجة أمر الإيقاف (اختياري، للتطوير)
        if (intent?.action == "STOP_SERVICE") {
            Log.d(TAG, "🛑 إيقاف الخدمة بناءً على طلب المستخدم")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // معالجة أمر النبض الخاطف (PULSE_ACTION) من الأوامر الحساسة
        if (intent?.action == "PULSE_ACTION") {
            val actionType = intent.getStringExtra("action_type") ?: "Sync"
            triggerPhantomPulse(actionType)
            return START_STICKY
        }

        // بدء الخدمة العادي (الإشعار الشبح الصامت)
        createGhostChannel()
        startForeground(NOTIFICATION_ID, buildGhostNotification("System Ready"))
        isForeground = true
        Log.d(TAG, "👻 الخدمة الشبحية قيد التشغيل (بدون أيقونة في شريط الحالة)")

        return START_STICKY
    }

    // إنشاء قناة الإشعارات بأولوية دنيا جداً (IMPORTANCE_MIN)
    private fun createGhostChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "System Background Services",
                    NotificationManager.IMPORTANCE_MIN // 👈 السر: يمنع ظهور الأيقونة في شريط الحالة
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
                Log.d(TAG, "✅ قناة إشعارات شبحية (IMPORTANCE_MIN) تم إنشاؤها")
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ فشل إنشاء قناة الإشعارات: ${e.message}")
            }
        }
    }

    // بناء الإشعار الشبح (مخفي، صامت، بأولوية دنيا)
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
            .setOngoing(true) // 👈 ضروري لمنع Android 14+ من قتل الخدمة
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openPending)
            .build()
    }

    // النبض الخاطف: إشعار عابر لمدة 200ms ثم العودة للحالة الشبحية
    private fun triggerPhantomPulse(actionType: String) {
        if (!isForeground) return

        // إلغاء أي إخفاء مجدول سابق
        hideRunnable?.let { handler.removeCallbacks(it) }

        // عرض الإشعار بحالة النبض (يظهر في درج الإشعارات فقط، بدون أيقونة علوية)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildGhostNotification("Processing $actionType..."))
        Log.d(TAG, "📢 نبض الإشعار: $actionType (سيختفي بعد ${PULSE_DURATION_MS}ms)")

        // جدولة العودة للحالة الشبحية بعد 200 ميلي ثانية
        hideRunnable = Runnable {
            if (isForeground) {
                nm.notify(NOTIFICATION_ID, buildGhostNotification(""))
                Log.d(TAG, "👻 عودة الإشعار للحالة الشبحية")
            }
        }
        handler.postDelayed(hideRunnable!!, PULSE_DURATION_MS)
    }

    // تحديث الإشعار يدوياً من خارج الخدمة (اختياري)
    fun updateStatus(statusText: String) {
        if (!isForeground) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildGhostNotification(statusText))
        Log.d(TAG, "🔄 تحديث الإشعار: $statusText")
    }

    override fun onDestroy() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        isForeground = false
        super.onDestroy()
        Log.d(TAG, "🛑 توقفت الخدمة الشبحية")
    }
}
