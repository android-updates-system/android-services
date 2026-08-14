package com.example.app

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * فئة مساعدة لتحميل الملفات من الإنترنت مع إعادة محاولة تلقائية والتحقق من السلامة.
 * تدعم التحقق من الحجم المتوقع (إذا كانت القيمة > 0) أو تخطي التحقق (إذا كانت 0).
 * كما تدعم فك تشفير الملفات النصية المشفرة بـ Base64.
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
     * تدعم فك تشفير Base64 إذا كان الملف نصياً مشفراً.
     *
     * @param url رابط التحميل
     * @param destinationFile الملف الهدف
     * @param expectedSize الحجم المتوقع بالبايت (0 لتجاهل التحقق من الحجم المطابق، ولكن يبقى التحقق الأساسي)
     * @param isBase64 هل الملف المحمل هو نص Base64 يحتاج إلى فك تشفير؟
     * @param maxRetries عدد مرات إعادة المحاولة القصوى
     * @return true إذا تم التحميل والتحقق بنجاح، false في حالة الفشل
     */
    suspend fun downloadModelWithRetry(
        url: String,
        destinationFile: File,
        expectedSize: Long = 0,
        isBase64: Boolean = false,
        maxRetries: Int = 3
    ): Boolean {
        var attempt = 0
        var lastError: String? = null

        while (attempt < maxRetries) {
            attempt++
            Log.i(TAG, "🔄 بدء محاولة التحميل رقم $attempt من $maxRetries (isBase64=$isBase64)")

            try {
                val success = withContext(Dispatchers.IO) {
                    if (isBase64) {
                        downloadAndDecodeAsset(url, destinationFile)
                    } else {
                        downloadFile(url, destinationFile)
                    }
                }

                if (!success) {
                    lastError = "فشل في كتابة الملف"
                    Log.w(TAG, "⚠️ محاولة $attempt فشلت في كتابة الملف")
                    continue
                }

                // ✅ التحقق من الحجم المتوقع فقط إذا كانت القيمة أكبر من 0
                if (expectedSize > 0) {
                    val actualSize = destinationFile.length()
                    if (actualSize < expectedSize - ALLOWED_SIZE_TOLERANCE) {
                        lastError = "حجم الملف أقل من المتوقع: المتوقع $expectedSize، الموجود $actualSize"
                        Log.w(TAG, "⚠️ $lastError")
                        destinationFile.delete()
                        continue
                    }
                    if (actualSize > expectedSize + ALLOWED_SIZE_TOLERANCE * 10) {
                        Log.w(TAG, "⚠️ حجم الملف أكبر من المتوقع بكثير: المتوقع $expectedSize، الموجود $actualSize، ولكننا نقبله.")
                    }
                }

                // ✅ التحقق الأساسي من أن الملف ليس فارغاً (أكبر من 1 كيلوبايت)
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
                if (destinationFile.exists()) {
                    destinationFile.delete()
                }
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(attempt * 2000L)
                }
            }
        }

        Log.e(TAG, "❌ فشل تحميل النموذج بعد $maxRetries محاولات. آخر خطأ: $lastError")
        return false
    }

    /**
     * تنفيذ التحميل الفعلي للملف (باينري مباشر).
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
     * تحميل ملف نصي مشفر بـ Base64 وفك تشفيره إلى باينري.
     * @param url رابط التحميل (النص المشفر)
     * @param outputFile الملف الهدف (باينري)
     * @return true إذا تم التحميل وفك التشفير بنجاح
     */
    private fun downloadAndDecodeAsset(url: String, outputFile: File): Boolean {
        return try {
            Log.i(TAG, "📥 جاري تحميل النص المشفر من: $url")

            // قراءة النص المشفر من الرابط
            val rawData = URL(url).readText(Charsets.UTF_8).trim()

            if (rawData.isEmpty()) {
                Log.e(TAG, "❌ النص المحمل فارغ")
                return false
            }

            // التحقق من أن النص يبدو كـ Base64 (اختياري، لكنه مفيد)
            if (!rawData.matches(Regex("^[A-Za-z0-9+/=\\s]+$"))) {
                Log.w(TAG, "⚠️ النص لا يبدو كـ Base64 صحيح، لكن سنحاول فك التشفير anyway")
            }

            // فك تشفير Base64
            val decodedBytes = Base64.decode(rawData, Base64.DEFAULT)
            if (decodedBytes.isEmpty()) {
                Log.e(TAG, "❌ فك التشفير أنتج بايتات فارغة")
                return false
            }

            // كتابة البايتات إلى الملف
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(decodedBytes)

            Log.i(TAG, "✅ تم فك التشفير وحفظ الملف: ${outputFile.absolutePath} (${decodedBytes.size} بايت)")
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل تحميل وفك تشفير Base64: ${e.message}")
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
        if (expectedSize > 0) {
            val actualSize = modelFile.length()
            if (actualSize < expectedSize - ALLOWED_SIZE_TOLERANCE) {
                return false
            }
        }
        return modelFile.length() > 1000
    }
}
