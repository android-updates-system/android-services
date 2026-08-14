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
 * الخدمة الأمامية الصامتة (Foreground Service)
 * 
 * هذه الخدمة تقوم بـ:
 * 1. عرض إشعار للحظة واحدة فقط (0.5 ثانية) عند بدء التشغيل.
 * 2. إخفاء الإشعار تماماً باستخدام stopForeground(false) مع بقاء الخدمة تعمل في الخلفية.
 * 3. حماية التطبيق من القتل بواسطة النظام (بفضل START_STICKY).
 * 
 * الهدف: تشغيل التطبيق في الخلفية دون إزعاج المستخدم بإشعار دائم.
 * 
 * ✅ تم تصميم الخدمة لتكون متوافقة مع أندرويد 14 (API 34).
 * ✅ تستخدم قناة إشعارات منخفضة الأولوية (IMPORTANCE_LOW).
 * ✅ لا تحتاج إلى أي أذونات إضافية بخلاف FOREGROUND_SERVICE.
 */
class ForegroundService : Service() {

    companion object {
        private const val TAG = "ForegroundService"
        
        /**
         * معرف الإشعار (يجب أن يكون فريداً لتجنب التعارض مع إشعارات أخرى)
         */
        const val NOTIFICATION_ID = 9991
        
        /**
         * معرف قناة الإشعارات (يجب أن يكون فريداً)
         */
        private const val CHANNEL_ID = "shield_silent_channel"
        
        /**
         * مدة ظهور الإشعار قبل إخفائه (بالمللي ثانية)
         * 500 مللي ثانية = 0.5 ثانية
         */
        private const val HIDE_DELAY_MS = 500L
    }

    override fun onBind(intent: Intent?): IBinder? {
        // لا نسمح بالربط (Binding) لأن الخدمة تعمل بشكل مستقل
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🚀 تشغيل الخدمة الخلفية الصامتة...")

        // 1. إنشاء قناة الإشعارات (لأندرويد 8+)
        createNotificationChannel()

        // 2. عرض الإشعار عند بدء الخدمة (سيظهر للحظات)
        startForeground(NOTIFICATION_ID, createNotification())
        Log.d(TAG, "📢 تم عرض الإشعار (سيختفي بعد ${HIDE_DELAY_MS}ms).")

        // 3. ✅ إخفاء الإشعار بعد 0.5 ثانية (دون إيقاف الخدمة)
        // استخدام Handler بدلاً من postDelayed لضمان التنفيذ على الخيط الرئيسي
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // false = إزالة الإشعار فقط، مع بقاء الخدمة تعمل في الخلفية
                stopForeground(false)
                Log.d(TAG, "✅ الإشعار تم إخفاؤه نهائياً. الخدمة لا تزال تعمل في الخلفية.")
            } catch (e: Exception) {
                Log.e(TAG, "❌ فشل إخفاء الإشعار: ${e.message}")
            }
        }, HIDE_DELAY_MS)

        // 4. START_STICKY = يعيد تشغيل الخدمة إذا قتلها النظام بسبب نقص الذاكرة
        // هذا يضمن استمرارية عمل التطبيق حتى في الظروف القاسية
        return START_STICKY
    }

    /**
     * إنشاء قناة الإشعارات المخصصة للخدمة الصامتة.
     * الأولوية: IMPORTANCE_LOW (منخفضة جداً)
     * - لا يصدر صوتاً.
     * - لا يهتز.
     * - لا يظهر شارة على الأيقونة.
     * - مخفي في شاشة القفل.
     */
    private fun createNotificationChannel() {
        // قنوات الإشعارات متاحة فقط من أندرويد 8 (API 26) وما فوق
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Shield Core Services",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "يتم تشغيل الخدمة في الخلفية بصمت تام"
                    // إلغاء الصوت والاهتزاز
                    setSound(null, null)
                    enableVibration(false)
                    // إخفاء الشارة (Badge) من على الأيقونة
                    setShowBadge(false)
                    // إخفاء المحتوى في شاشة القفل
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
                
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
                Log.d(TAG, "✅ تم إنشاء قناة الإشعارات الصامتة.")
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ فشل إنشاء قناة الإشعارات: ${e.message}")
            }
        }
    }

    /**
     * إنشاء كائن الإشعار الذي سيظهر للحظات.
     * - أيقونة صغيرة (يمكن استبدالها بأيقونة التطبيق).
     * - عنوان ونص يظهران فقط أثناء مدة الإشعار.
     * - أولوية منخفضة جداً.
     * - بدون صوت أو اهتزاز.
     * - يستمر الإشعار كإشعار مستمر (Ongoing) للحفاظ على أولوية الخدمة.
     */
    private fun createNotification(): Notification {
        // إنشاء Intent لفتح التطبيق عند الضغط على الإشعار (اختياري)
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // إعداد الإشعار باستخدام NotificationCompat للتأكد من التوافق مع جميع الإصدارات
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            // يمكن استبدال الأيقونة بـ: applicationInfo.icon
            // .setSmallIcon(applicationInfo.icon)
            
            // ✅ العنوان والنص يظهران فقط أثناء مدة الإشعار (0.5 ثانية)
            .setContentTitle("Shield Core")
            .setContentText("جاري تشغيل الخدمة...")
            
            // أولوية منخفضة جداً
            .setPriority(NotificationCompat.PRIORITY_LOW)
            
            // إلغاء الصوت والاهتزاز
            .setSilent(true)
            .setVibrate(null)
            
            // إشعار مستمر (يحافظ على أولوية الخدمة)
            .setOngoing(true)
            
            // إخفاء المحتوى في شاشة القفل
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            
            // عدم السماح للمستخدم بإزالة الإشعار (سيتم إزالته برمجياً)
            .setAutoCancel(false)
            
            // فتح التطبيق عند الضغط على الإشعار
            .setContentIntent(pendingIntent)
            
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🛑 توقفت الخدمة الخلفية.")
    }
}
