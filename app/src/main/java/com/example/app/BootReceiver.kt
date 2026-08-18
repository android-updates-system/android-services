package com.example.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * مستقبل البث لحدث إقلاع الجهاز.
 * يقوم بتشغيل الخدمة الأمامية تلقائياً بعد إعادة تشغيل الهاتف.
 * 
 * ✅ يعمل بصمت (بدون إشعارات أو واجهة مستخدم).
 * ✅ يدعم جميع إصدارات أندرويد (مع مراعاة القيود).
 * ✅ تم إزالة ACTION_QUICKBOOT_POWERON غير المدعوم.
 * ✅ إضافة آلية إعادة محاولة بديلة (fallback) في حال فشل startForegroundService.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        // التحقق من أن النية هي حدث إقلاع الجهاز
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "📱 Device boot completed, starting foreground service...")

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
                Log.e(TAG, "❌ Failed to start service using primary method: ${e.message}")

                // ✅ محاولة بديلة (fallback) باستخدام startService مباشرة
                // قد تكون مفيدة في بعض الأجهزة أو الإصدارات حيث startForegroundService يفشل
                try {
                    val serviceIntent = Intent(context, ForegroundService::class.java)
                    context.startService(serviceIntent)
                    Log.i(TAG, "✅ ForegroundService started successfully via fallback (startService)")
                } catch (e2: Exception) {
                    Log.e(TAG, "❌ Fallback startService also failed: ${e2.message}")
                }
            }
        }
    }
}
