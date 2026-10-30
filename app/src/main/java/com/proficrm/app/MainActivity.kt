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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.PowerManager
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var webView: WebView
    private lateinit var audioManager: AudioManager
    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private var orderDialog: Dialog? = null
    private var isProximityEnabled = false
    private var originalBrightness = -1f

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
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        if (hasAudioPermission()) {
            startMyService()
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
        }
    }

    inner class WebAppInterface(private val context: Context) {

        @JavascriptInterface
        fun setSpeakerphoneOn(enabled: Boolean) {
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = audioManager.availableCommunicationDevices
                    val targetType = if (enabled) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    val targetDevice = devices.firstOrNull { it.type == targetType }
                    if (targetDevice != null) {
                        audioManager.setCommunicationDevice(targetDevice)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = enabled
                }
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0)
                println("🔊 Громкая связь: $enabled")
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
                    if (targetDevice != null) {
                        audioManager.setCommunicationDevice(targetDevice)
                    }
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
                if (!isProximityEnabled && proximitySensor != null) {
                    sensorManager.registerListener(this@MainActivity, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
                    isProximityEnabled = true
                    println("📡 Датчик приближения ВКЛЮЧЕН")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun disableProximitySensor() {
            try {
                if (isProximityEnabled) {
                    sensorManager.unregisterListener(this@MainActivity)
                    isProximityEnabled = false
                    // Восстанавливаем яркость, если экран был погашен
                    val window = this@MainActivity.window
                    val params = window.attributes
                    params.screenBrightness = originalBrightness
                    window.attributes = params
                    window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                    println("📡 Датчик приближения ВЫКЛЮЧЕН")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun showIncomingCallNotification(number: String, name: String) {
            try {
                val acceptIntent = Intent(this@MainActivity, MainActivity::class.java).apply {
                    action = "ACCEPT_CALL"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val acceptPending = PendingIntent.getActivity(
                    this@MainActivity, 1, acceptIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val declineIntent = Intent(this@MainActivity, MainActivity::class.java).apply {
                    action = "DECLINE_CALL"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val declinePending = PendingIntent.getActivity(
                    this@MainActivity, 2, declineIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val fullScreenIntent = Intent(this@MainActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val fullScreenPending = PendingIntent.getActivity(
                    this@MainActivity, 3, fullScreenIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(this@MainActivity, "ProfiCrmChannel")
                    .setContentTitle("Входящий звонок")
                    .setContentText("$name ($number)")
                    .setSmallIcon(android.R.drawable.ic_menu_call)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setFullScreenIntent(fullScreenPending, true)
                    .setAutoCancel(true)
                    .addAction(android.R.drawable.ic_menu_call, "Принять", acceptPending)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Сбросить", declinePending)
                    .build()

                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(1001, notification)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun cancelIncomingCallNotification() {
            cancelCallNotification()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY && isProximityEnabled) {
            val distance = event.values[0]
            val window = window
            val params = window.attributes

            if (distance < proximitySensor?.maximumRange ?: 0f) {
                // Объект близко (ухо) — гасим экран и блокируем касания
                if (originalBrightness == -1f) {
                    originalBrightness = params.screenBrightness
                }
                params.screenBrightness = 0f
                window.attributes = params
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            } else {
                // Объект далеко — восстанавливаем яркость
                params.screenBrightness = originalBrightness
                window.attributes = params
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Не используется
    }

    private fun cancelCallNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(1001)
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
            startMyService()
        }
    }

    private fun startMyService() {
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

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        orderDialog?.dismiss()
        sensorManager.unregisterListener(this)
        webView.destroy()
    }
}
