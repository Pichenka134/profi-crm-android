package com.proficrm.app // Оставь свой пакет

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.webkit.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var errorText: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- ПЕРЕХВАТЧИК ОШИБОК ---
        val crashFile = File(filesDir, "crash_log.txt")
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                crashFile.writeText(throwable.stackTraceToString())
            } catch (e: Exception) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        // --------------------------

        val layout = android.widget.FrameLayout(this)
        layout.setBackgroundColor(Color.WHITE)
        
        webView = WebView(this)
        layout.addView(webView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))

        errorText = TextView(this)
        errorText.setTextColor(Color.RED)
        errorText.setBackgroundColor(Color.YELLOW) // Желтый фон, чтобы сразу заметить
        errorText.textSize = 14f
        errorText.visibility = TextView.GONE
        layout.addView(errorText, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(layout)

        // Если в прошлый раз было падение, показываем лог
        if (crashFile.exists()) {
            errorText.text = "КРАШ:\n" + crashFile.readText().take(1000)
            errorText.visibility = TextView.VISIBLE
        }

        requestAppPermissions()
        requestIgnoreBatteryOptimizationsSafely()

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        WebView.setWebContentsDebuggingEnabled(true)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Очищаем красный текст, если сайт загрузился успешно
                if (errorText.text.startsWith("Ошибка сети")) {
                   errorText.visibility = TextView.GONE
                }
            }
        }
        
        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")
        webView.loadUrl("https://profi-lead-crm.ru/admin/orders")
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    private fun requestIgnoreBatteryOptimizationsSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent().apply {
                        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {}
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun startNativeSip(login: String?, password: String?, server: String?) {
            if (login.isNullOrEmpty() || password.isNullOrEmpty() || login == "null") return
            
            try {
                val intent = Intent(this@MainActivity, LinphoneService::class.java).apply {
                    putExtra("sip_login", login)
                    putExtra("sip_password", password)
                    putExtra("sip_server", server ?: "sipproxy.telphin.ru")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка запуска сервиса", e)
            }
        }

        @JavascriptInterface
        fun setSpeakerphoneOn(on: Boolean) {}
        @JavascriptInterface
        fun setAudioMode(mode: String) {}
        @JavascriptInterface
        fun showIncomingCallNotification(number: String, name: String) {}
    }
}
