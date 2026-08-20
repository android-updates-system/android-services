// داخل ConfigLoader.kt، استبدال دالة decryptTokenWithKey بهذه النسخة:
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
        Log.e(TAG, "❌ Decryption error: ${e.message}")
        null
    }
}

// وإضافة تسجيل تشخيصي في loadConfig:
fun loadConfig(
    context: Context? = null,
    validate: Boolean = false,
    forceRefresh: Boolean = false,
    skipInvalid: Boolean = false
): AppConfig {
    // ... (الكود الموجود)
    
    var config: AppConfig? = null
    if (context != null) {
        config = loadEncryptedConfigFromAssets(context)
        if (config != null) {
            Log.i(TAG, "✅ Loaded config from assets successfully.")
            // ✅ تسجيل في MainActivity إن أمكن
            try {
                val clazz = Class.forName("com.example.app.MainActivity")
                val method = clazz.getMethod("appendLog", String::class.java)
                method.invoke(null, "✅ Config decrypted successfully: ${config.activeTokens.size} tokens")
            } catch (_: Exception) {
                // تجاهل
            }
        } else {
            Log.w(TAG, "⚠️ FAILED to load from assets (tokens.enc missing or decryption failed).")
        }
    }
    
    if (config == null) {
        Log.e(TAG, "❌ CRITICAL: Falling back to DUMMY tokens. Telegram will NOT work.")
        config = loadConfigFromEmbedded()
    }
    
    // ... (بقية الدالة)
}
