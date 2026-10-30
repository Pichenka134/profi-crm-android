package com.proficrm.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.linphone.core.AuthInfo
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.ProxyConfig
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType

class LinphoneService : Service() {

    private val TAG = "LinphoneService"
    private var core: Core? = null
    private var coreListener: CoreListenerStub? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null

    private var currentLogin: String? = null
    private var currentPassword: String? = null
    private var currentServer: String? = null

    // Состояния для отслеживания пропущенных
    private var incomingCallReceived = false
    private var callConnected = false
    private var incomingCallerNumber = ""

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Сервис создан")
        createNotificationChannels()
        startForeground(2, buildServiceNotification())
        acquireLocks()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Канал для foreground-сервиса (невидимый)
            val serviceChannel = NotificationChannel(
                "ProfiCrmServiceChannel",
                "Сервис SIP",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(serviceChannel)

            // Канал для входящих звонков (полноэкранный)
            val callChannel = NotificationChannel(
                "ProfiCrmCallChannel",
                "Входящие звонки",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о входящих звонках"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(callChannel)

            // Канал для пропущенных звонков
            val missedChannel = NotificationChannel(
                "ProfiCrmMissedChannel",
                "Пропущенные звонки",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о пропущенных звонках"
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(missedChannel)
        }
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ProfiCrm::LinphoneWakeLock"
            )
            wakeLock?.acquire()

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "ProfiCrm::LinphoneWifiLock"
            )
            wifiLock?.acquire()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при получении блокировок", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val login = intent?.getStringExtra("sip_login")
        val password = intent?.getStringExtra("sip_password")
        val server = intent?.getStringExtra("sip_server") ?: "sipproxy.telphin.ru"

        if (login.isNullOrEmpty() || password.isNullOrEmpty()) {
            return START_STICKY
        }

        if (login != currentLogin || password != currentPassword || server != currentServer) {
            stopLinphone()
            initLinphone(login, password, server)
            currentLogin = login
            currentPassword = password
            currentServer = server
        }

        return START_STICKY
    }

    private fun initLinphone(login: String, password: String, server: String) {
        try {
            val factory = Factory.instance()
            val coreConfig = factory.createConfigWithFactory(
                "${filesDir.absolutePath}/.linphonerc",
                "${filesDir.absolutePath}/linphonerc"
            )
            core = factory.createCoreWithConfig(coreConfig, this)

            core?.isKeepAliveEnabled = true
            core?.isNetworkReachable = true

            coreListener = object : CoreListenerStub() {
                override fun onRegistrationStateChanged(
                    core: Core,
                    proxyConfig: ProxyConfig,
                    state: RegistrationState,
                    message: String
                ) {
                    Log.d(TAG, "Статус регистрации SIP: $state, сообщение: $message")
                    MainActivity.instance?.onSipRegistrationState(state.name, message)
                }

                override fun onCallStateChanged(
                    core: Core,
                    call: Call,
                    state: Call.State,
                    message: String
                ) {
                    Log.d(TAG, "Состояние звонка: $state")
                    val callerNumber = call.remoteAddress?.username ?: "Неизвестный"

                    when (state) {
                        Call.State.IncomingReceived -> {
                            incomingCallReceived = true
                            callConnected = false
                            incomingCallerNumber = callerNumber
                            showFullScreenCallNotification(callerNumber)
                            MainActivity.instance?.onNativeIncomingCall(callerNumber)
                        }
                        Call.State.Connected -> {
                            callConnected = true
                            cancelCallNotification()
                            MainActivity.instance?.onNativeCallActive()
                        }
                        Call.State.End, Call.State.Error -> {
                            if (incomingCallReceived && !callConnected) {
                                showMissedCallNotification(incomingCallerNumber)
                            }
                            incomingCallReceived = false
                            callConnected = false
                            incomingCallerNumber = ""
                            cancelCallNotification()
                            MainActivity.instance?.onNativeCallEnded()
                        }
                        else -> {}
                    }
                }
            }

            core?.addListener(coreListener)

            val authInfo = factory.createAuthInfo(login, null, password, null, null, server, null)
            core?.addAuthInfo(authInfo)

            val identityAddress = factory.createAddress("sip:$login@$server")
            val serverAddress = factory.createAddress("sip:$server")
            serverAddress?.transport = TransportType.Tcp
            serverAddress?.port = 5060

            val params = core?.createAccountParams()
            if (params != null) {
                params.identityAddress = identityAddress
                params.serverAddress = serverAddress
                params.isRegisterEnabled = true
                params.expires = 60

                val account = core?.createAccount(params)
                if (account != null) {
                    core?.addAccount(account)
                    core?.defaultAccount = account
                }
            }

            core?.start()
            startRegistrationRefresh()
            Log.d(TAG, "Linphone Core запущен для $login (TCP)")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации Linphone", e)
        }
    }

    private fun startRegistrationRefresh() {
        refreshRunnable = object : Runnable {
            override fun run() {
                try {
                    core?.refreshRegisters()
                    Log.d(TAG, "Регистрация SIP обновлена принудительно")
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка обновления регистрации", e)
                }
                handler.postDelayed(this, 50000)
            }
        }
        handler.postDelayed(refreshRunnable!!, 50000)
    }

    private fun stopLinphone() {
        try {
            refreshRunnable?.let { handler.removeCallbacks(it) }
            refreshRunnable = null
            core?.removeListener(coreListener)
            core?.stop()
            core = null
            Log.d(TAG, "Linphone остановлен")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при остановке Linphone", e)
        }
    }

    /**
     * Полноэкранное уведомление о входящем звонке
     */
    private fun showFullScreenCallNotification(number: String) {
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = "SHOW_INCOMING_CALL"
            putExtra("caller_number", number)
        }
        val fullScreenPending = PendingIntent.getActivity(
            this, 4, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptIntent = Intent(this, MainActivity::class.java).apply {
            action = "ACCEPT_CALL"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val acceptPending = PendingIntent.getActivity(
            this, 1, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(this, MainActivity::class.java).apply {
            action = "DECLINE_CALL"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val declinePending = PendingIntent.getActivity(
            this, 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "ProfiCrmCallChannel")
            .setContentTitle("Входящий звонок")
            .setContentText("Номер: $number")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPending, true)
            .setAutoCancel(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_menu_call, "Принять", acceptPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Сбросить", declinePending)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1002, notification)
    }

    /**
     * Уведомление о пропущенном звонке
     */
    private fun showMissedCallNotification(number: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = "SHOW_MISSED_CALLS"
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 5, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "ProfiCrmMissedChannel")
            .setContentTitle("Пропущенный звонок")
            .setContentText("Звонок от $number")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setColor(Color.RED)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1003, notification)
    }

    private fun cancelCallNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(1002)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopLinphone()
        wakeLock?.release()
        wifiLock?.release()
    }

    private fun buildServiceNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "ProfiCrmServiceChannel")
            .setContentTitle("Profi CRM")
            .setContentText("SIP-клиент активен")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pendingIntent)
            .build()
    }
}
