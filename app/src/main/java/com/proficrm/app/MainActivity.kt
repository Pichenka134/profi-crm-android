package com.proficrm.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var audioManager: AudioManager
    private var orderDialog: Dialog? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null

    companion object {
        var instance: MainActivity? = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        proximityWakeLock = powerManager.newWakeLock(
            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
            "ProfiCrm::ProximitySensor"
        )

        if (hasAudioPermission()) {
            startAllServices()
            requestBatteryOptimization()
            requestFullScreenIntentPermission()
        } else {
            requestPermissions()
        }

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
        }

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        webView.webViewClient = WebViewClient()

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d("ProfiCrmWebView", "${consoleMessage.message()} (${consoleMessage.lineNumber()})")
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                orderDialog?.dismiss()

                val newWebView = WebView(this@MainActivity)
                newWebView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }

                newWebView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (url != null && !url.contains("/orders/create")) {
                            orderDialog?.dismiss()
                        }
                    }
                }

                val dialog = Dialog(this@MainActivity)
                orderDialog = dialog

                val layout = LinearLayout(this@MainActivity)
                layout.orientation = LinearLayout.VERTICAL

                val headerLayout = LinearLayout(this@MainActivity)
                headerLayout.orientation = LinearLayout.HORIZONTAL

                val hangupButton = Button(this@MainActivity)
                hangupButton.text = "📞 Сбросить звонок"
                hangupButton.setBackgroundColor(Color.parseColor("#EF4444"))
                hangupButton.setTextColor(Color.WHITE)
                hangupButton.setOnClickListener {
                    webView.evaluateJavascript(
                        "(function(){if(window.phoneComponent){window.phoneComponent.hangup();}})();",
                        null
                    )
                    dialog.dismiss()
                }
                headerLayout.addView(hangupButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                val closeButton = Button(this@MainActivity)
                closeButton.text = "✕ Закрыть форму"
                closeButton.setOnClickListener { dialog.dismiss() }
                headerLayout.addView(closeButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                layout.addView(headerLayout)
                layout.addView(newWebView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

                dialog.setContentView(layout)
                dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                dialog.setOnDismissListener { orderDialog = null }
                dialog.show()

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = newWebView
                resultMsg?.sendToTarget()
                return true
            }
        }

        webView.loadUrl("https://profi-lead-crm.ru/admin")

        handleIntentAction(intent)
    }

    /**
     * Запрос на игнорирование оптимизации батареи
     */
    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e("ProfiCrm", "Ошибка запроса оптимизации батареи", e)
            }
        }
    }

    /**
     * Запрос на полноэкранные уведомления (Android 14+)
     */
    private fun requestFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!notificationManager.canUseFullScreenIntent()) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e("ProfiCrm", "Ошибка запроса полноэкранных уведомлений", e)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntentAction(intent)
    }

    private fun handleIntentAction(intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            "ACCEPT_CALL" -> {
                webView.postDelayed({
                    webView.evaluateJavascript(
                        "(function(){if(window.phoneComponent){window.phoneComponent.answer();}})();",
                        null
                    )
                    cancelCallNotification()
                }, 500)
            }
            "DECLINE_CALL" -> {
                webView.postDelayed({
                    webView.evaluateJavascript(
                        "(function(){if(window.phoneComponent){window.phoneComponent.hangup();}})();",
                        null
                    )
                    cancelCallNotification()
                }, 500)
            }
            "SHOW_INCOMING_CALL" -> {
                // Просто открываем приложение (оно уже на экране)
            }
            "SHOW_MISSED_CALLS" -> {
                // Переходим на страницу журнала звонков
                webView.postDelayed({
                    webView.loadUrl("https://profi-lead-crm.ru/admin/calls")
                }, 300)
            }
        }
    }

    fun onNativeIncomingCall(number: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "(function(){if(window.phoneComponent){window.phoneComponent.onNativeIncomingCall('$number');}})();",
                null
            )
        }
    }

    fun onNativeCallActive() {
        runOnUiThread {
            webView.evaluateJavascript(
                "(function(){if(window.phoneComponent){window.phoneComponent.callState='active';}})();",
                null
            )
        }
    }

    fun onNativeCallEnded() {
        runOnUiThread {
            webView.evaluateJavascript(
                "(function(){if(window.phoneComponent){window.phoneComponent.callState='idle';}})();",
                null
            )
        }
    }

    fun onSipRegistrationState(state: String, message: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "(function(){console.log('SIP-регистрация: $state ($message)');})();",
                null
            )
        }
    }

    /**
     * Нативный диалог набора номера
     */
    private fun showDialerDialog() {
        val dialog = Dialog(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 40, 40, 40)

        val input = EditText(this)
        input.hint = "+7 900 000-00-00"
        input.setText("+7")
        layout.addView(input)

        val callButton = Button(this)
        callButton.text = "📞 Позвонить"
        callButton.setBackgroundColor(Color.parseColor("#22c55e"))
        callButton.setTextColor(Color.WHITE)
        callButton.setOnClickListener {
            val number = input.text.toString().trim()
            if (number.isNotEmpty()) {
                webView.evaluateJavascript(
                    "(function(){if(window.phoneComponent){window.phoneComponent.makeCall('$number');}})();",
                    null
                )
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Введите номер", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(callButton)

        val cancelButton = Button(this)
        cancelButton.text = "Отмена"
        cancelButton.setOnClickListener { dialog.dismiss() }
        layout.addView(cancelButton)

        dialog.setContentView(layout)
        dialog.setTitle("Набор номера")
        dialog.show()
    }

    inner class WebAppInterface(private val context: Context) {

        @JavascriptInterface
        fun startNativeSip(login: String, password: String, server: String) {
            Log.d("ProfiCrm", "🚀 Запуск нативного SIP для $login")
            val intent = Intent(this@MainActivity, LinphoneService::class.java)
            intent.putExtra("sip_login", login)
            intent.putExtra("sip_password", password)
            intent.putExtra("sip_server", server)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                this@MainActivity.startForegroundService(intent)
            } else {
                this@MainActivity.startService(intent)
            }
        }

        @JavascriptInterface
        fun showDialer() {
            runOnUiThread { showDialerDialog() }
        }

        @JavascriptInterface
        fun setSpeakerphoneOn(enabled: Boolean) {
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = audioManager.availableCommunicationDevices
                    val targetType = if (enabled) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    val targetDevice = devices.firstOrNull { it.type == targetType }
                    if (targetDevice != null) audioManager.setCommunicationDevice(targetDevice)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = enabled
                }
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun setAudioMode(mode: String) {
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                val enableSpeaker = (mode == "speaker")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = audioManager.availableCommunicationDevices
                    val targetType = if (enableSpeaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    val targetDevice = devices.firstOrNull { it.type == targetType }
                    if (targetDevice != null) audioManager.setCommunicationDevice(targetDevice)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = enableSpeaker
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun setAudioVolume(volumePercent: Int) {
            try {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val target = (max * volumePercent / 100).coerceIn(0, max)
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun enableProximitySensor() {
            try {
                if (proximityWakeLock?.isHeld == false) {
                    proximityWakeLock?.acquire(60 * 60 * 1000L)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun disableProximitySensor() {
            try {
                if (proximityWakeLock?.isHeld == true) {
                    proximityWakeLock?.release()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun cancelCallNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(1002)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && hasAudioPermission()) {
            startAllServices()
            requestBatteryOptimization()
            requestFullScreenIntentPermission()
        }
    }

    private fun startAllServices() {
        try {
            val serviceIntent = Intent(this, ForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        orderDialog?.dismiss()
        proximityWakeLock?.release()
        webView.destroy()
    }
}
