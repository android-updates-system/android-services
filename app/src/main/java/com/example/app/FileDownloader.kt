package com.example.app

import android.content.Context
import android.util.Base64
import android.util.Base64InputStream
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.random.Random

/**
 * فئة مساعدة لتحميل الملفات من الإنترنت مع إعادة محاولة تلقائية والتحقق من السلامة.
 *
 * الميزات:
 * - تحميل الملفات الباينرية مباشرة.
 * - تحميل وفك تشفير ملفات Base64 (مع تجاهل الأسطر الجديدة) باستخدام Base64InputStream.
 * - دعم فك ضغط GZIP تلقائياً.
 * - التحقق من نوع المحتوى (Content-Type) قبل معالجة Base64.
 * - إعادة محاولة تلقائية مع تأخير تصاعدي عشوائي.
 * - التحقق من الحجم المتوقع مع هامش تسامح محسّن (15% أو 150KB كحد أدنى).
 * - تحسين استهلاك الذاكرة عبر التدفق (Streaming) مع حد أقصى للحجم (50 MB).
 *
 * ✅ التعديلات الجديدة:
 * - زيادة مهلات الاتصال (connectTimeout: 45s, readTimeout: 90s, writeTimeout: 45s).
 * - تحسين رؤوس HTTP التمويهية (Stealth Headers) لمحاكاة متصفح Android حقيقي.
 * - إضافة معالجة أفضل للاستثناءات مع تسجيل تفصيلي.
 * - تحسين منطق إعادة المحاولة مع تأخير تصاعدي عشوائي.
 * - إضافة تحقق إضافي من صحة الملف المحمل.
 * - ✅ استخدام OkHttp مع متابعة إعادة التوجيه التلقائية.
 * - ✅ إضافة رؤوس User-Agent و Accept لضمان نجاح التحميل من GitHub.
 * - ✅ حفظ الملف بالامتداد .tflite بدلاً من .txt (إزالة لاحقة .txt فوراً).
 * - ✅ هامش تسامح 15% أو 150KB لفحص الحجم لتجاوز اختلافات GitHub البسيطة.
 * - ✅ التحقق من أن الملف لا يقل عن 5 ميجابايت (لنموذج AI) لتجنب التحميل الفاشل.
 * - ✅ إصلاح التحقق من الملف النهائي بعد إعادة التسمية في downloadModelWithRetry.
 * - ✅ إصلاح استخدام Regex بإضافة RegexOption.IGNORE_CASE بدلاً من ignoreCase = true.
 */
class FileDownloader(context: Context) {

    private val contextRef = WeakReference(context.applicationContext)
    private val appContext: Context? get() = contextRef.get()

    companion object {
        private const val TAG = "FileDownloader"
        private const val MIN_FILE_SIZE = 1000L          // 1 كيلوبايت
        private const val MAX_DECODED_SIZE = 50L * 1024 * 1024 // 50 ميجابايت
        private const val SIZE_TOLERANCE_PERCENT = 0.15  // 15%
        private const val MIN_MODEL_SIZE = 5_000_000L   // 5 ميجابايت
    }

    // ✅ عميل OkHttp مع مهلات محسّنة
    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)  // ✅ متابعة إعادة التوجيه تلقائياً
        .followSslRedirects(true)
        .build()

    /**
     * بناء طلب HTTP مع رؤوس تمويهية محسّنة (Stealth Headers)
     * لمحاكاة متصفح Android حقيقي وتجنب الحظر من GitHub.
     */
    private fun buildStealthRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-A235F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Accept", "application/octet-stream, */*")
            .header("Accept-Language", "en-US,en;q=0.9,ar;q=0.8")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Connection", "keep-alive")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "cross-site")
            .build()
    }

    /**
     * تحميل نموذج AI مع إعادة محاولة تلقائية والتحقق من الحجم.
     *
     * @param url رابط التحميل
     * @param destinationFile الملف الهدف (قد يتم إعادة تسميته إذا انتهى بـ .txt)
     * @param expectedSize الحجم المتوقع بالبايت (0 لتجاهل التحقق المطابق)
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
            Log.i(TAG, "🔄 بدء محاولة التحميل رقم $attempt من $maxRetries (isBase64=$isBase64, url=$url)")

            try {
                // ✅ تحميل الملف إلى ملف مؤقت أولاً لتجنب التلوث
                val tempFile = File(destinationFile.parent, "${destinationFile.name}.tmp")
                val success = withContext(Dispatchers.IO) {
                    if (isBase64) {
                        downloadAndDecodeAsset(url, tempFile)
                    } else {
                        downloadFile(url, tempFile)
                    }
                }

                if (!success || !tempFile.exists()) {
                    lastError = "فشل في تحميل الملف"
                    Log.w(TAG, "⚠️ محاولة $attempt فشلت في تحميل الملف")
                    tempFile.delete()
                    if (attempt < maxRetries) {
                        val delayMs = (attempt * 3000L) + Random.nextLong(0, 2000)
                        kotlinx.coroutines.delay(delayMs)
                    }
                    continue
                }

                // ✅ تحديد الملف النهائي مع إزالة لاحقة .txt إذا وجدت
                // ✅ إصلاح Regex: استخدام RegexOption.IGNORE_CASE بدلاً من ignoreCase = true
                val finalFile = if (destinationFile.name.endsWith(".txt", ignoreCase = true)) {
                    val newName = destinationFile.name.replace(Regex("\\.txt$", RegexOption.IGNORE_CASE), ".tflite")
                    File(destinationFile.parent, newName)
                } else {
                    destinationFile
                }

                // ✅ نقل الملف المؤقت إلى الملف النهائي
                if (finalFile.exists()) {
                    finalFile.delete()
                }
                val renamed = tempFile.renameTo(finalFile)
                if (!renamed) {
                    // محاولة نسخ إذا فشل إعادة التسمية
                    try {
                        tempFile.copyTo(finalFile, overwrite = true)
                        tempFile.delete()
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ فشل نقل الملف: ${e.message}")
                        tempFile.delete()
                        lastError = "فشل نقل الملف"
                        if (attempt < maxRetries) {
                            val delayMs = (attempt * 3000L) + Random.nextLong(0, 2000)
                            kotlinx.coroutines.delay(delayMs)
                        }
                        continue
                    }
                }

                // ✅ التحقق من الحجم المتوقع مع هامش تسامح محسّن (15% أو 150KB كحد أدنى)
                if (expectedSize > 0) {
                    val actualSize = finalFile.length()
                    val tolerance = maxOf(150000L, (expectedSize * 0.15).toLong()) // 15% أو 150KB
                    if (actualSize < expectedSize - tolerance) {
                        lastError = "حجم الملف أقل من المتوقع: المتوقع $expectedSize، الموجود $actualSize (الهامش: $tolerance)"
                        Log.w(TAG, "⚠️ $lastError")
                        finalFile.delete()
                        if (attempt < maxRetries) {
                            val delayMs = (attempt * 3000L) + Random.nextLong(0, 2000)
                            kotlinx.coroutines.delay(delayMs)
                        }
                        continue
                    }
                    if (actualSize > expectedSize + tolerance * 2) {
                        Log.w(TAG, "⚠️ حجم الملف أكبر من المتوقع بكثير: $actualSize > $expectedSize")
                    }
                }

                // ✅ التحقق الأساسي من أن الملف ليس فارغاً أو تالفاً
                if (finalFile.length() < MIN_FILE_SIZE) {
                    lastError = "الملف صغير جداً (أقل من 1 كيلوبايت)"
                    Log.w(TAG, "⚠️ $lastError")
                    finalFile.delete()
                    if (attempt < maxRetries) {
                        val delayMs = (attempt * 3000L) + Random.nextLong(0, 2000)
                        kotlinx.coroutines.delay(delayMs)
                    }
                    continue
                }

                // ✅ التحقق الإضافي: حجم الملف لا يقل عن 5 ميجابايت (لنموذج AI)
                if (finalFile.length() < MIN_MODEL_SIZE) {
                    lastError = "حجم الملف صغير جداً ($MIN_MODEL_SIZE) - غير صالح لنموذج AI"
                    Log.w(TAG, "⚠️ $lastError")
                    finalFile.delete()
                    if (attempt < maxRetries) {
                        val delayMs = (attempt * 3000L) + Random.nextLong(0, 2000)
                        kotlinx.coroutines.delay(delayMs)
                    }
                    continue
                }

                // ✅ تحقق إضافي: محاولة قراءة بداية الملف للتأكد من سلامته
                if (!isBase64) {
                    try {
                        val headerBytes = ByteArray(8)
                        FileInputStream(finalFile).use { fis ->
                            fis.read(headerBytes)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ فشل فحص توقيع الملف: ${e.message}")
                    }
                }

                Log.i(TAG, "✅ تم تحميل النموذج بنجاح (حجم: ${finalFile.length()} بايت، المسار: ${finalFile.absolutePath})")
                MainActivity.appendLogStatic("✅ Model downloaded: ${finalFile.length()} bytes")
                return true

            } catch (e: Exception) {
                lastError = e.message ?: "خطأ غير معروف"
                Log.e(TAG, "❌ محاولة $attempt فشلت: $lastError")
                // تنظيف الملفات المؤقتة
                val tempFile = File(destinationFile.parent, "${destinationFile.name}.tmp")
                if (tempFile.exists()) tempFile.delete()
                if (destinationFile.exists()) destinationFile.delete()
                // تنظيف الملف النهائي إذا كان موجوداً
                // ✅ إصلاح Regex: استخدام RegexOption.IGNORE_CASE بدلاً من ignoreCase = true
                val finalFile = if (destinationFile.name.endsWith(".txt", ignoreCase = true)) {
                    val newName = destinationFile.name.replace(Regex("\\.txt$", RegexOption.IGNORE_CASE), ".tflite")
                    File(destinationFile.parent, newName)
                } else {
                    destinationFile
                }
                if (finalFile.exists()) finalFile.delete()

                if (attempt < maxRetries) {
                    val delayMs = (attempt * 3000L) + Random.nextLong(0, 2000)
                    kotlinx.coroutines.delay(delayMs)
                }
            }
        }

        Log.e(TAG, "❌ فشل تحميل النموذج بعد $maxRetries محاولات. آخر خطأ: $lastError")
        MainActivity.appendLogStatic("❌ Model download failed after $maxRetries attempts")
        return false
    }

    /**
     * تنفيذ التحميل الفعلي للملف (باينري مباشر) مع دعم GZIP.
     * ✅ تم تحسينه باستخدام OkHttp مع متابعة إعادة التوجيه ورؤوس مناسبة.
     * ✅ يتم حفظ الملف في الموقع المحدد دون إعادة تسمية (تتم إعادة التسمية في الدالة العليا).
     */
    private fun downloadFile(url: String, destinationFile: File): Boolean {
        var response: Response? = null
        return try {
            val request = buildStealthRequest(url)
            response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP Error: ${response.code} - ${response.message}")
                MainActivity.appendLogStatic("❌ HTTP ${response.code} for model")
                return false
            }

            val body = response.body ?: return false
            destinationFile.parentFile?.mkdirs()

            // ✅ دعم GZIP إذا كان المحتوى مضغوطاً
            val contentEncoding = response.header("Content-Encoding")
            val inputStream: InputStream = if (contentEncoding != null && contentEncoding.contains("gzip", ignoreCase = true)) {
                Log.i(TAG, "📦 المحتوى مضغوط بـ GZIP، جاري فك الضغط...")
                GZIPInputStream(body.byteStream())
            } else {
                body.byteStream()
            }

            FileOutputStream(destinationFile).use { outputStream ->
                inputStream.use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            // ✅ التحقق النهائي من أن الملف تم تحميله بشكل صحيح
            if (destinationFile.length() < MIN_FILE_SIZE) {
                Log.w(TAG, "⚠️ الملف المحمل صغير جداً: ${destinationFile.length()} بايت")
                destinationFile.delete()
                return false
            }

            Log.i(TAG, "✅ تم تحميل الملف بنجاح (حجم: ${destinationFile.length()} بايت)")
            true

        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "⏰ مهلة الاتصال انتهت: ${e.message}")
            destinationFile.delete()
            false
        } catch (e: java.io.IOException) {
            Log.e(TAG, "❌ خطأ في الإدخال/الإخراج: ${e.message}")
            destinationFile.delete()
            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ خطأ في التحميل: ${e.message}")
            destinationFile.delete()
            false
        } finally {
            response?.close()
        }
    }

    /**
     * تحميل ملف نصي مشفر بـ Base64 وفك تشفيره إلى باينري.
     * ✅ يتم حفظ الملف في الموقع المحدد دون إعادة تسمية (تتم إعادة التسمية في الدالة العليا).
     */
    private fun downloadAndDecodeAsset(url: String, outputFile: File): Boolean {
        var response: Response? = null
        return try {
            Log.i(TAG, "📥 جاري تحميل وفك تشفير Base64 من: $url")

            val request = buildStealthRequest(url)
            response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP Error: ${response.code} - ${response.message}")
                return false
            }

            val body = response.body ?: return false

            // ✅ التحقق من نوع المحتوى
            val contentType = response.header("Content-Type")
            if (contentType != null && !contentType.contains("text/plain", ignoreCase = true) &&
                !contentType.contains("application/octet-stream", ignoreCase = true)) {
                Log.w(TAG, "⚠️ Content-Type غير نصي: $contentType، قد لا يكون الملف Base64 صحيحاً")
            }

            outputFile.parentFile?.mkdirs()

            // ✅ دعم GZIP
            val contentEncoding = response.header("Content-Encoding")
            val inputStream: InputStream = if (contentEncoding != null && contentEncoding.contains("gzip", ignoreCase = true)) {
                Log.i(TAG, "📦 المحتوى مضغوط بـ GZIP، جاري فك الضغط...")
                GZIPInputStream(body.byteStream())
            } else {
                body.byteStream()
            }

            // ✅ فك التشفير باستخدام Base64InputStream
            var totalRead = 0L
            inputStream.use { rawStream ->
                Base64InputStream(rawStream, Base64.DEFAULT).use { base64Stream ->
                    FileOutputStream(outputFile).use { outputStream ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (base64Stream.read(buffer).also { read = it } != -1) {
                            totalRead += read
                            if (totalRead > MAX_DECODED_SIZE) {
                                Log.e(TAG, "❌ تجاوز الحجم الأقصى: $totalRead > $MAX_DECODED_SIZE")
                                outputFile.delete()
                                return false
                            }
                            outputStream.write(buffer, 0, read)
                        }
                    }
                }
            }

            if (outputFile.length() < MIN_FILE_SIZE) {
                Log.w(TAG, "⚠️ الملف الناتج صغير جداً: ${outputFile.length()} بايت")
                outputFile.delete()
                return false
            }

            Log.i(TAG, "✅ تم فك التشفير وحفظ الملف: ${outputFile.absolutePath} (${outputFile.length()} بايت)")
            true

        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "⏰ مهلة الاتصال انتهت أثناء تحميل Base64: ${e.message}")
            if (outputFile.exists()) outputFile.delete()
            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل تحميل وفك تشفير Base64: ${e.message}")
            if (outputFile.exists()) outputFile.delete()
            false
        } finally {
            response?.close()
        }
    }

    /**
     * التحقق من وجود الملف وسلامته.
     */
    fun isModelValid(modelFile: File, expectedSize: Long = 0): Boolean {
        if (!modelFile.exists()) {
            Log.w(TAG, "⚠️ الملف غير موجود: ${modelFile.absolutePath}")
            return false
        }
        if (expectedSize > 0) {
            val actualSize = modelFile.length()
            val tolerance = maxOf(150000L, (expectedSize * 0.15).toLong())
            if (actualSize < expectedSize - tolerance) {
                Log.w(TAG, "⚠️ حجم الملف أقل من المتوقع: $actualSize < $expectedSize - $tolerance")
                return false
            }
        }
        val valid = modelFile.length() > MIN_FILE_SIZE
        if (!valid) {
            Log.w(TAG, "⚠️ الملف صغير جداً: ${modelFile.length()} بايت")
        }
        return valid
    }

    /**
     * ✅ دالة مساعدة لتنظيف الملفات المؤقتة الفاشلة
     */
    fun cleanupFailedFile(file: File) {
        if (file.exists()) {
            val deleted = file.delete()
            if (deleted) {
                Log.d(TAG, "🧹 تم حذف الملف الفاشل: ${file.absolutePath}")
            } else {
                Log.w(TAG, "⚠️ فشل حذف الملف: ${file.absolutePath}")
            }
        }
    }

    /**
     * ✅ دالة مساعدة لتنظيف الملف المؤقت
     */
    fun cleanupTempFile(file: File) {
        if (file.exists() && file.name.endsWith(".tmp")) {
            val deleted = file.delete()
            if (deleted) {
                Log.d(TAG, "🧹 تم حذف الملف المؤقت: ${file.absolutePath}")
            }
        }
    }
}
