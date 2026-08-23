package com.example.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * مستقبل البث لحدث إقلاع الجهاز.
 * يقوم بتشغيل الخدمة الأمامية تلقائياً بعد إعادة تشغيل الهاتف.
 * 
 * استراتيجية التشغيل الآمن:
 * - محاولة بدء الخدمة باستخدام startForegroundService (Android 8+)
 * - في حالة الفشل، محاولة بديلة باستخدام startService (لجميع الإصدارات)
 * - تسجيل جميع الأخطاء لمساعدة التصحيح
 * - يعمل بصمت (بدون إشعارات أو واجهة مستخدم)
 * - يدعم جميع إصدارات أندرويد (مع مراعاة القيود)
 * - ✅ إضافة تأخير 3 ثوانٍ لتجنب قيود الإقلاع في أجهزة شاومي وهواوي
 * - ✅ تحسين معالجة الاستثناءات مع تسجيل تفصيلي
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        // التحقق من أن النية هي حدث إقلاع الجهاز
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "📱 Device boot completed, starting foreground service...")

            // ✅ تأخير 3 ثوانٍ لتجنب قيود الإقلاع في بعض الأجهزة (شاومي، هواوي، إلخ)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val serviceIntent = Intent(context, ForegroundService::class.java)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        // أندرويد 8+ (Oreo) يتطلب startForegroundService للخدمات الأمامية
                        context.startForegroundService(serviceIntent)
                        Log.i(TAG, "✅ ForegroundService started using startForegroundService (Android 8+)")
                    } else {
                        // الإصدارات الأقدم (Android 7 والأقل)
                        context.startService(serviceIntent)
                        Log.i(TAG, "✅ ForegroundService started using startService (Android < 8)")
                    }

                } catch (e: SecurityException) {
                    // ✅ خطأ أمان: قد يحدث في بعض الأجهزة المخصصة أو إذا كانت الخدمة غير مصرح بها
                    Log.e(TAG, "❌ SecurityException: ${e.message}")
                    tryFallbackStart(context)
                } catch (e: IllegalStateException) {
                    // ✅ خطأ حالة غير قانونية: قد يحدث إذا كان السياق غير مناسب
                    Log.e(TAG, "❌ IllegalStateException: ${e.message}")
                    tryFallbackStart(context)
                } catch (e: Exception) {
                    // ✅ أي خطأ آخر غير متوقع
                    Log.e(TAG, "❌ Unexpected error: ${e.message}")
                    tryFallbackStart(context)
                }
            }, 3000) // 3 ثوانٍ تأخير
        }
    }

    /**
     * محاولة بديلة لبدء الخدمة باستخدام startService مباشرة.
     * هذه الطريقة تعمل على جميع إصدارات أندرويد وتعتبر حل احتياطي آمن.
     */
    private fun tryFallbackStart(context: Context) {
        try {
            Log.w(TAG, "🔄 Attempting fallback: startService...")
            val serviceIntent = Intent(context, ForegroundService::class.java)
            context.startService(serviceIntent)
            Log.i(TAG, "✅ ForegroundService started successfully via fallback (startService)")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Fallback SecurityException: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "❌ Fallback IllegalStateException: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fallback failed completely: ${e.message}")
        }
    }
}
