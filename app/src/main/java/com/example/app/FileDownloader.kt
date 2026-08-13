package com.example.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

/**
 * فئة مساعدة لتحميل الملفات من الإنترنت مع إعادة محاولة تلقائية والتحقق من السلامة.
 * تدعم التحقق من الحجم المتوقع (إذا كانت القيمة > 0) أو تخطي التحقق (إذا كانت 0).
 */
class FileDownloader(context: Context) {

    private val contextRef = WeakReference(context.applicationContext)
    private val appContext: Context? get() = contextRef.get()

    companion object {
        private const val TAG = "FileDownloader"
        private const val DEFAULT_CONNECT_TIMEOUT = 60L
        private const val DEFAULT_READ_TIMEOUT = 60L
        private const val ALLOWED_SIZE_TOLERANCE = 1024L // 1 كيلوبايت هامش خطأ
    }

    // عميل OkHttp مع مهلات قابلة للتخصيص
    private val client = OkHttpClient.Builder()
        .connectTimeout(DEFAULT_CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS)
        .build()

    /**
     * تحميل نموذج AI مع إعادة محاولة تلقائية والتحقق من الحجم (اختياري).
     *
     * @param url رابط التحميل
     * @param destinationFile الملف الهدف
     * @param expectedSize الحجم المتوقع بالبايت (0 لتجاهل التحقق من الحجم المطابق، ولكن يبقى التحقق الأساسي)
     * @param maxRetries عدد مرات إعادة المحاولة القصوى
     * @return true إذا تم التحميل والتحقق بنجاح، false في حالة الفشل
     */
    suspend fun downloadModelWithRetry(
        url: String,
        destinationFile: File,
        expectedSize: Long = 0,
        maxRetries: Int = 3
    ): Boolean {
        var attempt = 0
        var lastError: String? = null

        while (attempt < maxRetries) {
            attempt++
            Log.i(TAG, "🔄 بدء محاولة التحميل رقم $attempt من $maxRetries")

            try {
                val success = withContext(Dispatchers.IO) {
                    downloadFile(url, destinationFile)
                }

                if (!success) {
                    lastError = "فشل في كتابة الملف"
                    Log.w(TAG, "⚠️ محاولة $attempt فشلت في كتابة الملف")
                    continue
                }

                // ✅ التحقق من الحجم المتوقع فقط إذا كانت القيمة أكبر من 0
                // تم تعديل التحقق لاستخدام هامش خطأ (1 كيلوبايت) لتجنب فشل التحميل بسبب اختلافات بسيطة
                if (expectedSize > 0) {
                    val actualSize = destinationFile.length()
                    // السماح بفارق بسيط (1 كيلوبايت) لتجاوز مشاكل EOF أو إضافة سطر فارغ
                    if (actualSize < expectedSize - ALLOWED_SIZE_TOLERANCE) {
                        lastError = "حجم الملف أقل من المتوقع بهامش أكبر من المسموح: المتوقع $expectedSize، الموجود $actualSize"
                        Log.w(TAG, "⚠️ $lastError")
                        destinationFile.delete()
                        continue
                    }
                    // إذا كان الحجم أكبر من المتوقع، نقبل الملف طالما أنه ضمن نطاق معقول (لا نرفضه)
                    // لكن يمكننا تسجيل تحذير إذا كان الفرق كبيراً جداً
                    if (actualSize > expectedSize + ALLOWED_SIZE_TOLERANCE * 10) {
                        Log.w(TAG, "⚠️ حجم الملف أكبر من المتوقع بكثير: المتوقع $expectedSize، الموجود $actualSize، ولكننا نقبله.")
                    }
                }

                // ✅ التحقق الأساسي من أن الملف ليس فارغاً (أكبر من 1 كيلوبايت)
                // هذا يضمن عدم قبول ملفات تالفة أو فارغة حتى لو تم تخطي التحقق الصارم
                if (destinationFile.length() < 1000) {
                    lastError = "الملف صغير جداً (أقل من 1 كيلوبايت)، يعتبر تالفاً"
                    Log.w(TAG, "⚠️ $lastError")
                    destinationFile.delete()
                    continue
                }

                Log.i(TAG, "✅ تم تحميل النموذج بنجاح (حجم: ${destinationFile.length()} بايت)")
                return true

            } catch (e: Exception) {
                lastError = e.message ?: "خطأ غير معروف"
                Log.e(TAG, "❌ محاولة $attempt فشلت: $lastError")
                // حذف الملف التالف إن وجد
                if (destinationFile.exists()) {
                    destinationFile.delete()
                }
                // انتظار قبل إعادة المحاولة (تأخير تصاعدي)
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(attempt * 2000L)
                }
            }
        }

        Log.e(TAG, "❌ فشل تحميل النموذج بعد $maxRetries محاولات. آخر خطأ: $lastError")
        return false
    }

    /**
     * تنفيذ التحميل الفعلي للملف (داخل Coroutine).
     */
    private fun downloadFile(url: String, destinationFile: File): Boolean {
        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP Error: ${response.code}")
                return false
            }

            val body = response.body ?: return false

            // إنشاء المجلد إذا لم يكن موجوداً
            destinationFile.parentFile?.mkdirs()

            FileOutputStream(destinationFile).use { outputStream ->
                body.byteStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}")
            false
        }
    }

    /**
     * التحقق من وجود الملف وسلامته.
     * إذا كانت expectedSize == 0، يتم تخطي التحقق من الحجم المطابق.
     * @param modelFile الملف المراد التحقق منه
     * @param expectedSize الحجم المتوقع (0 لتخطي التحقق)
     * @return true إذا كان الملف موجوداً وصالحاً، false وإلا
     */
    fun isModelValid(modelFile: File, expectedSize: Long = 0): Boolean {
        if (!modelFile.exists()) return false
        // التحقق من الحجم مع هامش خطأ إذا كانت القيمة > 0
        if (expectedSize > 0) {
            val actualSize = modelFile.length()
            if (actualSize < expectedSize - ALLOWED_SIZE_TOLERANCE) {
                return false
            }
        }
        // التحقق الأساسي: الملف يجب أن يكون أكبر من 1 كيلوبايت
        return modelFile.length() > 1000
    }
}
