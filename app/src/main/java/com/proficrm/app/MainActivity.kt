package com.proficrm.app // Оставь свой пакет, если он другой

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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var errorText: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Создаем корневой Layout программно, чтобы не зависеть от XML
        val layout = android.widget.FrameLayout(this)
        layout.setBackgroundColor(Color.WHITE)
        
        // Добавляем WebView
        webView = WebView(this)
        layout.addView(webView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Добавляем текстовое поле для вывода ошибок поверх WebView
        errorText = TextView(this)
        errorText.setTextColor(Color.RED)
        errorText.setBackgroundColor(Color.WHITE)
        errorText.textSize = 16f
        errorText.visibility = TextView.GONE
        layout.addView(errorText, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(layout)

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
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                showError("Ошибка сети: ${error?.description} (Код: ${error?.errorCode})")
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                handler?.proceed()
                showError("Предупреждение SSL: ${error?.primaryError}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                errorText.visibility = TextView.GONE
            }
        }
        
        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")
        webView.loadUrl("https://profi-lead-crm.ru/admin/orders")
    }

    private fun showError(message: String) {
        runOnUiThread {
            errorText.text = message
            errorText.visibility = TextView.VISIBLE
            Log.e("WebViewError", message)
        }
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
            } catch (e: Exception) {
                Log.e("MainActivity", "Не удалось запросить оптимизацию", e)
            }
        }
    }

    inner class WebAppInterface {
        // Защита от null (если у юзера нет SIP-логина)
        @JavascriptInterface
        fun startNativeSip(login: String?, password: String?, server: String?) {
            if (login.isNullOrEmpty() || password.isNullOrEmpty() || login == "null") {
                Log.e("MainActivity", "SIP-логин или пароль пустые. Нативный сервис не запускаем.")
                return
            }
            
            Log.d("MainActivity", "Запуск нативного SIP для $login")
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
        }

        @JavascriptInterface
        fun setSpeakerphoneOn(on: Boolean) { Log.d("MainActivity", "Динамик: $on") }
        @JavascriptInterface
        fun setAudioMode(mode: String) { Log.d("MainActivity", "Аудио режим: $mode") }
        @JavascriptInterface
        fun showIncomingCallNotification(number: String, name: String) {}
    }
}
