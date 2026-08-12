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

class FileDownloader(context: Context) {

    private val contextRef = WeakReference(context.applicationContext)
    private val appContext: Context? get() = contextRef.get()

    companion object {
        private const val TAG = "FileDownloader"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * تحميل نموذج AI مع إعادة محاولة تلقائية والتحقق من الحجم
     *
     * @param url رابط التحميل
     * @param destinationFile الملف الهدف
     * @param expectedSize الحجم المتوقع بالبايت (0 لتجاهل التحقق)
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

                // التحقق من حجم الملف إذا كان متوقعاً
                if (expectedSize > 0) {
                    val actualSize = destinationFile.length()
                    if (actualSize != expectedSize) {
                        lastError = "حجم الملف غير متطابق: المتوقع $expectedSize، الموجود $actualSize"
                        Log.w(TAG, "⚠️ $lastError")
                        // حذف الملف التالف وإعادة المحاولة
                        destinationFile.delete()
                        continue
                    }
                }

                // التحقق من أن الملف ليس فارغاً (حماية إضافية)
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
     * تنفيذ التحميل الفعلي للملف
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
     * التحقق من وجود الملف وسلامته (حسب الحجم)
     */
    fun isModelValid(modelFile: File, expectedSize: Long = 0): Boolean {
        if (!modelFile.exists()) return false
        if (expectedSize > 0 && modelFile.length() != expectedSize) return false
        return modelFile.length() > 1000
    }
}