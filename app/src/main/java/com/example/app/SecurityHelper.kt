package com.example.app

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * فئة مساعدة لتشفير وفك تشفير النصوص والتوكنات الحساسة باستخدام Android Keystore.
 * توفر أعلى درجات الأمان من خلال تخزين المفاتيح داخل الشريحة الآمنة للجهاز.
 * تم تحسينها لدعم تشفير القوائم والتوافق مع الإصدارات القديمة.
 * 
 * ✅ تم إصلاح مشكلة Base64: استخدام NO_WRAP بدلاً من DEFAULT لمنع إضافة فواصل أسطر.
 * ✅ تم إضافة تحسينات في معالجة الاستثناءات وتحرير الموارد.
 */
object SecurityHelper {

    private const val TAG = "SecurityHelper"
    private const val KEY_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "ShieldCoreMasterKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_SEPARATOR = "]"

    // ذاكرة مؤقتة للمفتاح لتجنب استدعاء Keystore في كل مرة
    @Volatile
    private var cachedSecretKey: SecretKey? = null

    // ====================  إدارة المفتاح  ====================

    /**
     * الحصول على المفتاح السري من Keystore أو إنشائه إذا لم يكن موجوداً.
     * مع دعم التخزين المؤقت.
     */
    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        // إذا كان المفتاح موجوداً في الذاكرة المؤقتة، أعد استخدامه
        cachedSecretKey?.let { return it }

        val keyStore = KeyStore.getInstance(KEY_PROVIDER).apply { load(null) }

        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            cachedSecretKey = entry.secretKey
            Log.d(TAG, "✅ تم استرجاع المفتاح من Keystore (مؤقت).")
            return entry.secretKey
        }

        // إنشاء مفتاح جديد في Keystore
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_PROVIDER)
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        // تعطيل المصادقة المطلوبة من المستخدم لتشغيل الخدمة في الخلفية (Android 6+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.setUserAuthenticationRequired(false)
        }

        // في Android 11+ (API 30)، يمكن تعطيل StrongBox إذا كان الجهاز يدعمه
        // ولكن هذا ليس ضرورياً في معظم الحالات
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // builder.setIsStrongBoxBacked(false) // اختياري
        }

        keyGenerator.init(builder.build())
        val newKey = keyGenerator.generateKey()
        cachedSecretKey = newKey
        Log.i(TAG, "🔑 تم إنشاء مفتاح تشفير جديد داخل Android Keystore.")
        return newKey
    }

    /**
     * التحقق من وجود المفتاح في Keystore.
     */
    fun isKeyAvailable(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(KEY_PROVIDER).apply { load(null) }
            keyStore.containsAlias(KEY_ALIAS)
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ فشل التحقق من وجود المفتاح: ${e.message}")
            false
        }
    }

    /**
     * مسح المفتاح من الذاكرة المؤقتة لتقليل استهلاك الذاكرة.
     * يمكن استدعاؤها عند انتهاء استخدام التشفير لفترة طويلة.
     */
    fun clearCachedKey() {
        cachedSecretKey = null
        Log.d(TAG, "🧹 تم مسح المفتاح المؤقت من الذاكرة.")
    }

    // ====================  تشفير/فك تشفير النصوص  ====================

    /**
     * تشفير نص مفرد.
     * @param plainText النص المراد تشفيره (مثل توكن)
     * @return النص المشفر مع IV (مدمجين) أو null في حالة الفشل
     */
    fun encrypt(plainText: String?): String? {
        if (plainText.isNullOrEmpty()) return null
        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))

            // ✅ استخدام Base64.NO_WRAP لمنع إضافة فواصل أسطر
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val encryptedBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)

            "$ivBase64$IV_SEPARATOR$encryptedBase64"
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل تشفير النص: ${e.message}", e)
            null
        }
    }

    /**
     * فك تشفير نص مشفر مسبقاً.
     * @param encryptedData النص المشفر (مع IV)
     * @return النص الأصلي أو null في حالة الفشل
     */
    fun decrypt(encryptedData: String?): String? {
        if (encryptedData.isNullOrEmpty() || !encryptedData.contains(IV_SEPARATOR)) {
            return null
        }
        return try {
            val parts = encryptedData.split(IV_SEPARATOR)
            if (parts.size != 2) return null

            // ✅ استخدام Base64.NO_WRAP لفك التشفير دون فواصل أسطر
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encryptedBytes = Base64.decode(parts[1], Base64.NO_WRAP)

            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل فك تشفير النص: ${e.message}", e)
            null
        }
    }

    // ====================  تشفير/فك تشفير القوائم  ====================

    /**
     * تشفير قائمة من النصوص (مثل قائمة التوكنات) دفعة واحدة.
     * @param plainList القائمة المطلوب تشفيرها
     * @return قائمة جديدة تحتوي على النصوص المشفرة
     */
    fun encryptList(plainList: List<String>): List<String> {
        return plainList.mapNotNull { encrypt(it) }
    }

    /**
     * فك تشفير قائمة من النصوص المشفرة.
     * @param encryptedList القائمة المشفرة
     * @return قائمة جديدة تحتوي على النصوص الأصلية (مع إزالة العناصر التالفة)
     */
    fun decryptList(encryptedList: List<String>): List<String> {
        return encryptedList.mapNotNull { decrypt(it) }
    }

    // ====================  إدارة المفتاح  ====================

    /**
     * حذف المفتاح الرئيسي من Keystore (يستخدم عند إعادة ضبط التطبيق).
     */
    fun clearMasterKey() {
        try {
            val keyStore = KeyStore.getInstance(KEY_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
                cachedSecretKey = null // إزالة المفتاح من الذاكرة المؤقتة
                Log.i(TAG, "🧹 تم حذف المفتاح الرئيسي من Keystore.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ فشل حذف المفتاح: ${e.message}")
        }
    }

    /**
     * إعادة تعيين المفتاح (إنشاء مفتاح جديد).
     */
    fun resetKey(): Boolean {
        return try {
            clearMasterKey()
            getOrCreateSecretKey()
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل إعادة تعيين المفتاح: ${e.message}")
            false
        }
    }
}
