package com.example.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * مستقبل البث لحدث إقلاع الجهاز.
 * يقوم بتشغيل الخدمة الأمامية تلقائياً بعد إعادة تشغيل الهاتف.
 *
 * ✅ يعمل بصمت (بدون إشعارات أو واجهة مستخدم).
 * ✅ يدعم جميع إصدارات أندرويد (مع مراعاة القيود).
 * ✅ يطلب WakeLock مؤقتاً لضمان بدء الخدمة أثناء الإقلاع.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        // التحقق من أن النية هي حدث إقلاع الجهاز
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == Intent.ACTION_QUICKBOOT_POWERON) {

            Log.i(TAG, "📱 Device boot completed, starting foreground service...")

            // طلب WakeLock مؤقت لضمان استمرار التشغيل أثناء بدء الخدمة
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BootReceiver:ServiceStart"
            )
            wakeLock?.apply {
                try {
                    acquire(5000) // قفل لمدة 5 ثوانٍ كحد أقصى
                } catch (_: Exception) {
                    // تجاهل فشل الحصول على القفل
                }
            }

            try {
                // إنشاء نية لبدء الخدمة الأمامية
                val serviceIntent = Intent(context, ForegroundService::class.java)

                // بدء الخدمة حسب إصدار أندرويد
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // أندرويد 8+ (Oreo) يتطلب startForegroundService
                    context.startForegroundService(serviceIntent)
                } else {
                    // الإصدارات الأقدم
                    context.startService(serviceIntent)
                }

                Log.i(TAG, "✅ ForegroundService started successfully after boot")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start service after boot: ${e.message}")
            } finally {
                // تحرير WakeLock بعد بدء الخدمة أو في حالة الفشل
                try {
                    wakeLock?.release()
                } catch (_: Exception) {
                    // تجاهل
                }
            }
        }
    }
}
