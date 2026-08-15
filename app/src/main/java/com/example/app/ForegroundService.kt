package com.example.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * الخدمة الأمامية (Foreground Service) - الإصدار المستقر
 * 
 * هذه الخدمة تقوم بـ:
 * 1. عرض إشعار دائم في درج الإشعارات أثناء تشغيل التطبيق في الخلفية.
 * 2. حماية التطبيق من القتل بواسطة النظام (بفضل START_STICKY).
 * 3. توفير واجهة للمستخدم لإيقاف الخدمة أو فتح التطبيق.
 * 
 * ✅ تم تصميم الخدمة لتكون متوافقة مع أندرويد 14 (API 34).
 * ✅ تستخدم قناة إشعارات منخفضة الأولوية (IMPORTANCE_LOW).
 * ✅ لا تحتاج إلى أي أذونات إضافية بخلاف FOREGROUND_SERVICE.
 * ✅ الإشعار دائم ولا يتم إخفاؤه لضمان استمرارية الخدمة (متطلب Android 14+).
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
        private const val CHANNEL_ID = "shield_service_channel"
    }

    override fun onBind(intent: Intent?): IBinder? {
        // لا نسمح بالربط (Binding) لأن الخدمة تعمل بشكل مستقل
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🚀 تشغيل الخدمة الأمامية...")

        // 1. إنشاء قناة الإشعارات (لأندرويد 8+)
        createNotificationChannel()

        // 2. عرض الإشعار الدائم عند بدء الخدمة
        startForeground(NOTIFICATION_ID, createNotification())
        Log.d(TAG, "📢 تم عرض الإشعار الدائم.")

        // 3. START_STICKY = يعيد تشغيل الخدمة إذا قتلها النظام بسبب نقص الذاكرة
        // هذا يضمن استمرارية عمل التطبيق حتى في الظروف القاسية
        return START_STICKY
    }

    /**
     * إنشاء قناة الإشعارات المخصصة للخدمة.
     * الأولوية: IMPORTANCE_LOW (منخفضة) - لا تصدر صوتاً ولا تهتز ولا تظهر شارة.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Shield Core Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "تشغيل الخدمة الخلفية للتطبيق"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
                
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
                Log.d(TAG, "✅ تم إنشاء قناة الإشعارات.")
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ فشل إنشاء قناة الإشعارات: ${e.message}")
            }
        }
    }

    /**
     * إنشاء كائن الإشعار الدائم.
     * - أيقونة صغيرة.
     * - عنوان ونص واضحان.
     * - أولوية منخفضة.
     * - بدون صوت أو اهتزاز.
     * - إشعار مستمر (Ongoing) لا يمكن إزالته من قبل المستخدم بسهولة.
     */
    private fun createNotification(): Notification {
        // Intent لفتح التطبيق عند الضغط على الإشعار
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Intent لإيقاف الخدمة (اختياري - يمكن إضافته كزر في الإشعار)
        val stopIntent = Intent(this, ForegroundService::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            // يمكن استبدال الأيقونة بـ: applicationInfo.icon
            // .setSmallIcon(applicationInfo.icon)
            
            .setContentTitle("🛡️ Shield Core")
            .setContentText("الخدمة تعمل في الخلفية...")
            
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setVibrate(null)
            
            // إشعار مستمر (يحافظ على أولوية الخدمة)
            .setOngoing(true)
            
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setAutoCancel(false)
            
            .setContentIntent(pendingIntent)
            
            // ✅ إضافة زر إيقاف الخدمة (اختياري)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "إيقاف",
                stopPendingIntent
            )
            
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🛑 توقفت الخدمة الأمامية.")
    }
}
