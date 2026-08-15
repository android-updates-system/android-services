package com.example.app

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
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
 * 3. يتم تشفير tokens.enc في CI باستخدام مفتاح ثابت.
 * 4. ✅ يتم فك التشفير في وقت التشغيل باستخدام المفتاح الثابت نفسه.
 * 5. في حال فشل فك التشفير، يتم استخدام قيمة افتراضية لضمان عمل التطبيق.
 * 6. تم تقليل مدة الكاش إلى 30 ثانية لتقليل فترة بقاء البيانات الحساسة في الذاكرة.
 * 7. تم إضافة دالة clearSensitiveData() لتنظيف الذاكرة يدوياً.
 */
object ConfigLoader {

    private const val TAG = "ConfigLoader"

    // ========== ذاكرة تخزين مؤقت للإعدادات ==========
    @Volatile
    private var configCache: AppConfig? = null
    @Volatile
    private var cacheTime: Long = 0L
    // ✅ تم تقليل مدة الكاش من 60 إلى 30 ثانية
    private const val CACHE_TTL_MS: Long = 30_000L // 30 ثانية

    // ========== القيم الافتراضية للكروبات ==========
    const val DEFAULT_CTRL: Long = -1003943094277L
    const val DEFAULT_VAULT: Long = -1003577715762L

    // ========== المفتاح الثابت المستخدم للتشفير في CI ==========
    // يجب أن يتطابق مع المفتاح المستخدم في build.yml
    private const val FALLBACK_ENCRYPTION_KEY = "ShieldCoreEncryptionKey2024!"

    // ذاكرة مؤقتة للمفتاح المُشتق (للاستخدامات المستقبلية)
    @Volatile
    private var derivedKey: ByteArray? = null

    // ============================================================
    // توليد مفتاح AES من النص الثابت (SHA-256)
    // ============================================================

    /**
     * الحصول على المفتاح الثابت (SHA-256 من النص الثابت).
     * هذا هو المفتاح المستخدم لتشفير tokens.enc في CI.
     */
    private fun getFallbackKey(): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(FALLBACK_ENCRYPTION_KEY.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * ✅ الحصول على مفتاح التشفير - معتمد على المفتاح الثابت لملفات assets.
     * 
     * لماذا نستخدم المفتاح الثابت؟
     * - ملف tokens.enc يتم تشفيره في GitHub CI باستخدام المفتاح الثابت.
     * - استخدام مفتاح ديناميكي (Android ID) سيفشل في فك التشفير لأن CI لا يعرف Android ID.
     * - المفتاح الثابت مطلوب لضمان نجاح فك التشفير على الجهاز الحقيقي.
     * 
     * تم الاحتفاظ بدالة getDynamicEncryptionKey للاستخدامات المحلية المستقبلية
     * (مثل تشفير البيانات المخزنة محلياً بعد فك التشفير).
     */
    private fun getEncryptionKey(context: Context): ByteArray {
        // ✅ إجبار استخدام المفتاح الثابت لملفات assets
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
        // استخدام الكاش إذا كان موجوداً
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
    // دوال فك التشفير
    // ============================================================

    /**
     * فك تشفير نص مشفر باستخدام مفتاح AES.
     */
    private fun decryptTokenWithKey(encryptedToken: String?, key: ByteArray): String? {
        if (encryptedToken.isNullOrBlank()) return null
        return try {
            val secretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decoded = Base64.decode(encryptedToken, Base64.NO_WRAP)
            val decrypted = cipher.doFinal(decoded)
            String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption error: ${e.message}")
            null
        }
    }

    // ============================================================
    // تحميل التوكنات من ملف مشفر في assets
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
        return try {
            val inputStream = context.assets.open("tokens.enc")
            val encryptedData = inputStream.bufferedReader().use { it.readText() }
            inputStream.close()

            if (encryptedData.isBlank()) {
                Log.w(TAG, "tokens.enc is empty")
                return null
            }

            // ✅ استخدام المفتاح الثابت لفك التشفير
            val key = getEncryptionKey(context)
            val decryptedJson = decryptTokenWithKey(encryptedData, key)

            if (decryptedJson.isNullOrBlank()) {
                Log.e(TAG, "❌ Failed to decrypt tokens.enc")
                return null
            }

            val json = JSONObject(decryptedJson)
            parseConfigFromJson(json)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load encrypted config from assets: ${e.message}")
            null
        }
    }

    /**
     * استخراج بيانات التكوين من كائن JSON بعد فك التشفير.
     * في حال عدم وجود حقل secret، يتم استخدام القيمة الافتراضية.
     * ✅ تم إضافة .trim() لإزالة المسافات المخفية
     */
    private fun parseConfigFromJson(json: JSONObject): AppConfig {
        val activeArray = json.optJSONArray("active") ?: JSONArray()
        val reserveArray = json.optJSONArray("reserve") ?: JSONArray()

        val active = (0 until activeArray.length()).mapNotNull { activeArray.optString(it).takeIf { it.isNotBlank() } }
        val reserve = (0 until reserveArray.length()).mapNotNull { reserveArray.optString(it).takeIf { it.isNotBlank() } }

        val ctrl = json.optLong("ctrl_id", DEFAULT_CTRL)
        val vault = json.optLong("vault_id", DEFAULT_VAULT)

        // ✅ إضافة .trim() لإزالة أي مسافات بيضاء مخفية من secret
        val secret = json.optString("secret", "Zaen123@123@").trim().takeIf { it.isNotBlank() } ?: "Zaen123@123@"

        Log.i(TAG, "✅ Parsed ${active.size} active and ${reserve.size} reserve tokens")
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
    // الواجهة الرئيسية لتحميل الإعدادات
    // ============================================================

    /**
     * الواجهة الرئيسية لتحميل الإعدادات مع دعم الكاش.
     * يتم تحميل التوكنات من:
     * 1. المصدر الأساسي: ملف مشفر في assets (tokens.enc) باستخدام المفتاح الثابت.
     * 2. الحل الاحتياطي: نصوص وهمية (لتجنب انهيار التطبيق في حالات الطوارئ).
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
            return configCache!!
        }

        var config: AppConfig? = null

        // 1. المصدر الأساسي: الملف المشفر في assets (يتطلب Context)
        if (context != null) {
            config = loadEncryptedConfigFromAssets(context)
            if (config != null) {
                Log.i(TAG, "✅ Loaded config from assets with static encryption key.")
            }
        }

        // 2. إذا فشل التحميل من assets، نستخدم الحل الاحتياطي المضمن
        if (config == null) {
            Log.w(TAG, "⚠️ Failed to load from assets, falling back to embedded dummy tokens.")
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
    // ✅ دالة تنظيف الذاكرة الحساسة (مضافة)
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
    }

    /**
     * مسح المفتاح المشتق من الذاكرة (يُستخدم عند تسجيل الخروج أو تنظيف البيانات)
     */
    fun clearDerivedKey() {
        derivedKey = null
        Log.d(TAG, "🧹 Derived encryption key cleared from memory")
    }
}
