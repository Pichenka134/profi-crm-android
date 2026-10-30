package com.proficrm.app // ОСТАВЬ СВОЮ СТРОКУ PACKAGE, ЕСЛИ ОНА ДРУГАЯ

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Запрашиваем разрешения
        requestAppPermissions()

        // 2. Отключаем оптимизацию батареи (чтобы сервис не убивало)
        requestIgnoreBatteryOptimizations()

        // 3. Настраиваем WebView
        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        
        // ВАЖНО: Разрешаем WebView использовать микрофон
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }
        }
        
        webView.webViewClient = WebViewClient()
        
        // Подключаем мост для вызова из JS
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

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    // --- МОСТ МЕЖДУ WEBVIEW И ANDROID ---
    inner class WebAppInterface {
        
        @JavascriptInterface
        fun startNativeSip(login: String, password: String, server: String) {
            Log.d("MainActivity", "Запуск нативного SIP для $login")
            val intent = Intent(this@MainActivity, LinphoneService::class.java).apply {
                putExtra("sip_login", login)
                putExtra("sip_password", password)
                putExtra("sip_server", server)
            }
            // КРИТИЧЕСКИ ВАЖНО: Для Android 8+ используем startForegroundService
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        @JavascriptInterface
        fun setSpeakerphoneOn(on: Boolean) {
            Log.d("MainActivity", "Динамик: $on")
        }
        
        @JavascriptInterface
        fun setAudioMode(mode: String) {
            Log.d("MainActivity", "Аудио режим: $mode")
        }

        @JavascriptInterface
        fun showIncomingCallNotification(number: String, name: String) {
            // Обрабатывается в LinphoneService
        }
    }
}
