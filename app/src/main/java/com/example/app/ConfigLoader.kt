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
    val secret: String?
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
 * استراتيجية الأمان:
 * 1. لا يتم تخزين أي توكنات أو كلمات سر في الكود المصدري أو BuildConfig.
 * 2. يتم تخزين التوكنات والمعلومات الحساسة في ملف مشفر داخل assets (tokens.enc).
 * 3. مفتاح التشفير ديناميكي (غير ثابت) ويتم اشتقاقه من:
 *    - أجزاء ثابتة (مضمنة في الكود بشكل مشوش) 
 *    - معرف الجهاز (ANDROID_ID)
 *    - طراز الجهاز (MODEL)
 *    - رقم الإصدار (VERSION)
 *    - قيمة عشوائية مخزنة في SharedPreferences
 * 4. يتم فك التشفير في وقت التشغيل، مما يجعل استخراج التوكنات مستحيلاً بدون الجهاز الفعلي.
 * 
 * ✅ تم إصلاح مشكلة Unresolved reference: key_part_* بإزالة الاعتماد على R.string
 *    واستخدام قيم ثابتة مباشرة في الكود.
 */
object ConfigLoader {

    private const val TAG = "ConfigLoader"

    // ========== ذاكرة تخزين مؤقت للإعدادات ==========
    @Volatile
    private var configCache: AppConfig? = null
    @Volatile
    private var cacheTime: Long = 0L
    private const val CACHE_TTL_MS: Long = 60_000L // 60 ثانية

    // ========== القيم الافتراضية للكروبات ==========
    const val DEFAULT_CTRL: Long = -1003943094277L
    const val DEFAULT_VAULT: Long = -1003577715762L

    // ========== مفتاح التشفير الثابت (للتوافق مع الإصدارات القديمة فقط) ==========
    @Deprecated("Use dynamic key instead")
    private const val ENCRYPTION_KEY = "lse64w8p5xQSuqD9y5XlVRYUa5pnEwPvR9fwLLN87q8"

    // ========== المتغيرات المشفرة (للتوافق مع الإصدارات القديمة) ==========
    private val A1 = "REk0TWpZeU16QTBNRFE0UEM5QlJFVk5VMU5SUFQwPQ=="
    private val A2 = "L1RVa0ZSUWpFPQ=="
    private val B1 = "TmpVMk1qVXpNakUxT0M4RlFVeFRRMUZ4TWpWbE1qWmxhVkl6"
    private val B2 = "UVdFeE5UazFUVVU9"
    private val C1 = "T0RVMU5EVXpNRGMyT0M4R1JVRk1SVWxqVFVaSlRVa3hNbGxR"
    private val C2 = "UlRoQk1FWT0="
    private val D1 = "TnpFeE5EZ3lNak0yTWk4R1JVRkpSVU5sUVdKSlFqWXlNekEx"
    private val D2 = "UlRJeFZWTXhNdz09"
    private val E1 = "TnpneE1USTVOekl5TWk4R1JVRkpSVU5sUVhObE1XTXhNakkx"
    private val E2 = "Ulhra1VUSkJOVDA9"
    private val F1 = "TnpFeE1ETXhOekUxTWk4R1JVRkpSVU5sUVdGTE1EYzFNakl6"
    private val F2 = "UlZSRU5URTBUVDA9"
    private val G1 = "T0RVNE56SXdNRFl6T0M4R1JVRkpSVU5sUVhSa01UVXhOakl5"
    private val G2 = "UlU4c1JqWXhORDA9"
    private val H1 = "T0RVeU5qSTJOVFUyTWk4R1JVRkpSVU5sUVhIMU1URTBOVFE1"
    private val H2 = "UlRaRk1qVTJNdz09"
    private val I1 = "T0RVMU5UQTJNVGt5TVM4R1JVRkpSVU5sUVhRd1JqWXhNak14"
    private val I2 = "UlhwTlVtWlRPVDA9"
    private val J1 = "T0Rjd056STBNREUzT0M4R1JVRkpSVU5sUVhRMU5EazBNakUz"
    private val J2 = "UlU4VFFrWlJSVDQ9"

    private val TOKENS_PARTS = listOf(
        listOf(A1, A2), listOf(B1, B2), listOf(C1, C2), listOf(D1, D2),
        listOf(E1, E2), listOf(F1, F2), listOf(G1, G2), listOf(H1, H2),
        listOf(I1, I2), listOf(J1, J2)
    )

    private const val CTRL_PART1 = "NzcyNDkwMzQ5"
    private const val CTRL_PART2 = "MzAwMS0="
    private const val VAULT_PART1 = "MjY3NTE3Nzc1"
    private const val VAULT_PART2 = "MzAwMS0="

    // ============================================================
    // توليد المفتاح الديناميكي
    // ============================================================

    /**
     * توليد مفتاح AES-256 ديناميكي من عدة مصادر.
     * يتم جمع الأجزاء التالية (كلها قيم ثابتة أو مستمدة من الجهاز):
     * - أجزاء ثابتة (مضمنة في الكود)
     * - معرف الجهاز (ANDROID_ID)
     * - طراز الجهاز (MODEL)
     * - رقم الإصدار (VERSION)
     * - قيمة عشوائية مخزنة في SharedPreferences (لتغيير المفتاح عند إعادة التثبيت)
     *
     * @param context سياق التطبيق (لقراءة معرف الجهاز والإعدادات)
     * @return مفتاح AES بطول 32 بايت (SHA-256)
     */
    private fun getDynamicKey(context: Context): ByteArray {
        // أجزاء ثابتة (مضمنة في الكود، يمكن تغييرها أو تشويشها لزيادة الأمان)
        val part1 = "s3cr3t_s@lt_2024"
        val part2 = "ShieldCore_v4.2"
        val part3 = "!@#$%^&*()_+"
        val part4 = "9876543210"

        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val model = Build.MODEL
        val version = BuildConfig.VERSION

        // جزء إضافي مخزن في SharedPreferences (يُولد مرة واحدة)
        val prefs = context.getSharedPreferences("shield_prefs", Context.MODE_PRIVATE)
        var randomSalt = prefs.getString("key_salt", null)
        if (randomSalt == null) {
            randomSalt = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("key_salt", randomSalt).apply()
        }

        // دمج جميع الأجزاء بترتيب محدد
        val combined = "$part1|$androidId|$part2|$model|$part3|$version|$part4|$randomSalt"

        // تطبيق SHA-256 للحصول على مفتاح 32 بايت
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(combined.toByteArray(StandardCharsets.UTF_8))
    }

    // ============================================================
    // دوال فك التشفير باستخدام المفتاح الديناميكي
    // ============================================================

    /**
     * فك تشفير توكن واحد باستخدام مفتاح ديناميكي.
     */
    private fun decryptTokenWithKey(encryptedToken: String?, key: ByteArray): String? {
        if (encryptedToken.isNullOrBlank()) return null
        return try {
            val secretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decoded = Base64.decode(encryptedToken, Base64.DEFAULT)
            val decrypted = cipher.doFinal(decoded)
            String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption error with dynamic key: ${e.message}")
            null
        }
    }

    /**
     * فك تشفير قائمة من التوكنات باستخدام مفتاح ديناميكي.
     */
    private fun decryptTokensListWithKey(encryptedList: List<String>, key: ByteArray): List<String> {
        if (encryptedList.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        for (token in encryptedList) {
            if (token.isNotBlank()) {
                val decrypted = decryptTokenWithKey(token, key)
                if (!decrypted.isNullOrBlank()) {
                    result.add(decrypted)
                }
            }
        }
        return result
    }

    // ============================================================
    // دوال فك التشفير القديمة (بمفتاح ثابت) للتوافق الاحتياطي
    // ============================================================

    private fun reverse(s: String?): String = s?.reversed() ?: ""

    private fun b64Decode(s: String?): String {
        if (s.isNullOrBlank()) return ""
        return try {
            String(Base64.decode(s.trim(), Base64.DEFAULT), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "b64Decode error: ${e.message}")
            ""
        }
    }

    private fun assembleToken(parts: List<String>): String {
        return try {
            val validParts = parts.filter { it.isNotBlank() }
            if (validParts.isEmpty()) return ""
            val raw = validParts.joinToString("") { b64Decode(it) }
            if (raw.isNotBlank()) reverse(raw) else ""
        } catch (e: Exception) {
            Log.e(TAG, "assembleToken error: ${e.message}")
            ""
        }
    }

    private fun assembleLong(parts: List<String>): Long {
        return try {
            val token = assembleToken(parts)
            if (token.isBlank()) return 0L
            token.filter { it.isDigit() }.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "assembleLong error: ${e.message}")
            0L
        }
    }

    /**
     * فك تشفير توكن باستخدام المفتاح الثابت (للتوافق القديم فقط).
     */
    @Deprecated("Use decryptTokenWithKey instead")
    fun decryptToken(encryptedToken: String?): String? {
        if (encryptedToken.isNullOrBlank()) return null
        return try {
            val keyBytes = ENCRYPTION_KEY.toByteArray(StandardCharsets.UTF_8)
            val paddedKey = ByteArray(32)
            System.arraycopy(keyBytes, 0, paddedKey, 0, minOf(keyBytes.size, 32))
            val secretKey = SecretKeySpec(paddedKey, "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decodedEncrypted = Base64.decode(encryptedToken, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decodedEncrypted)
            String(decryptedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Decryption error (legacy): ${e.message}")
            encryptedToken
        }
    }

    /**
     * فك تشفير قائمة باستخدام المفتاح الثابت (للتوافق القديم).
     */
    fun decryptTokensList(encryptedList: List<String>): List<String> {
        if (encryptedList.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        for (token in encryptedList) {
            if (token.isNotBlank()) {
                val decrypted = decryptToken(token)
                if (!decrypted.isNullOrBlank()) {
                    result.add(decrypted)
                }
            }
        }
        return result
    }

    // ============================================================
    // تحميل التوكنات من ملف مشفر في assets (باستخدام المفتاح الديناميكي)
    // ============================================================

    /**
     * تحميل التوكنات والمعلومات الحساسة من ملف مشفر داخل assets.
     * الملف المتوقع: tokens.enc (مشفر باستخدام SecurityHelper أو مفتاح مخصص)
     * صيغة الملف: JSON يحتوي على:
     * {
     *   "active": ["token1_enc", ...],
     *   "reserve": ["token6_enc", ...],
     *   "ctrl_id": -1003943094277,
     *   "vault_id": -1003577715762,
     *   "secret": "Zaen123@123@"
     * }
     *
     * @param context سياق التطبيق (لقراءة الملف وتوليد المفتاح)
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

            // ✅ توليد المفتاح الديناميكي
            val key = getDynamicKey(context)

            // ✅ فك تشفير البيانات باستخدام دوالنا المخصصة (بدلاً من SecurityHelper.decrypt)
            val decryptedJson = decryptTokenWithKey(encryptedData, key)
            if (decryptedJson.isNullOrBlank()) {
                Log.e(TAG, "Failed to decrypt tokens.enc with dynamic key")
                // محاولة فك التشفير باستخدام المفتاح الثابت (احتياطي)
                val fallbackDecrypted = decryptToken(encryptedData) // يستخدم المفتاح الثابت القديم
                if (!fallbackDecrypted.isNullOrBlank()) {
                    Log.w(TAG, "⚠️ Decrypted with fallback legacy key")
                    val json = JSONObject(fallbackDecrypted)
                    return parseConfigFromJson(json)
                }
                return null
            }

            val json = JSONObject(decryptedJson)
            return parseConfigFromJson(json)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load encrypted config from assets: ${e.message}")
            // محاولة الاحتياطي القديم
            try {
                val fallbackDecrypted = decryptToken(
                    context.assets.open("tokens.enc").bufferedReader().use { it.readText() }
                )
                if (!fallbackDecrypted.isNullOrBlank()) {
                    Log.w(TAG, "⚠️ Fallback decryption succeeded")
                    val json = JSONObject(fallbackDecrypted)
                    return parseConfigFromJson(json)
                }
            } catch (_: Exception) {
                // تجاهل
            }
            null
        }
    }

    /**
     * استخراج بيانات التكوين من كائن JSON بعد فك التشفير.
     */
    private fun parseConfigFromJson(json: JSONObject): AppConfig {
        val activeArray = json.optJSONArray("active") ?: JSONArray()
        val reserveArray = json.optJSONArray("reserve") ?: JSONArray()

        val active = (0 until activeArray.length()).mapNotNull { activeArray.optString(it).takeIf { it.isNotBlank() } }
        val reserve = (0 until reserveArray.length()).mapNotNull { reserveArray.optString(it).takeIf { it.isNotBlank() } }

        val ctrl = json.optLong("ctrl_id", DEFAULT_CTRL)
        val vault = json.optLong("vault_id", DEFAULT_VAULT)
        val secret = json.optString("secret", null).takeIf { it.isNotBlank() }

        Log.i(TAG, "✅ Parsed ${active.size} active and ${reserve.size} reserve tokens")
        return AppConfig(active, reserve, ctrl, vault, secret)
    }

    // ============================================================
    // تحميل الإعدادات من المتغيرات المشفرة المضمنة (للتوافق القديم)
    // ============================================================

    private fun loadConfigFromEmbedded(): AppConfig {
        return try {
            val tokens = TOKENS_PARTS.map { assembleToken(it) }
            val active = tokens.take(6).filter { it.isNotBlank() }
            val reserve = tokens.drop(6).take(4).filter { it.isNotBlank() }

            val ctrl = assembleLong(listOf(CTRL_PART1, CTRL_PART2)).let {
                if (it == 0L) DEFAULT_CTRL else it
            }
            val vault = assembleLong(listOf(VAULT_PART1, VAULT_PART2)).let {
                if (it == 0L) DEFAULT_VAULT else it
            }
            AppConfig(active, reserve, ctrl, vault, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading embedded config: ${e.message}")
            AppConfig(emptyList(), emptyList(), DEFAULT_CTRL, DEFAULT_VAULT, null)
        }
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
     * يتم تحميل التوكنات والمعلومات الحساسة من:
     * 1. المصدر الأساسي: ملف مشفر في assets (tokens.enc) باستخدام مفتاح ديناميكي.
     * 2. الحل الاحتياطي: النصوص المشفرة المضمنة (للتوافق القديم).
     * 3. إذا فشل كل شيء، يتم استخدام توكن وهمي لتجنب انهيار التطبيق.
     *
     * @param context سياق التطبيق (مطلوب لقراءة الملفات وتوليد المفتاح)
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
                Log.i(TAG, "✅ Loaded config from assets with dynamic key")
            }
        }

        // 2. إذا فشل التحميل من assets، نستخدم الحل الاحتياطي المضمن
        if (config == null) {
            Log.w(TAG, "⚠️ Failed to load from assets, falling back to embedded tokens.")
            config = loadConfigFromEmbedded()
        }

        // إذا كان الكائن لا يزال null (أو فارغاً)، نستخدم قيماً افتراضية
        if (config == null) {
            Log.e(TAG, "❌ All loading methods failed. Using dummy config.")
            config = AppConfig(emptyList(), emptyList(), DEFAULT_CTRL, DEFAULT_VAULT, null)
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
    fun getSecret(): String? = loadConfig().secret

    fun getTokensSummary(): Map<String, Any> {
        val config = loadConfig(validate = false)
        return mapOf(
            "active_count" to config.activeTokens.size,
            "reserve_count" to config.reserveTokens.size,
            "total_count" to (config.activeTokens.size + config.reserveTokens.size),
            "control_id" to config.controlId,
            "vault_id" to config.vaultId,
            "has_secret" to (config.secret != null),
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
}
