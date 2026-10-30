package com.proficrm.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
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

    private val CHANNEL_ID = "LinphoneServiceChannel"
    private val TAG = "LinphoneService"
    private var core: Core? = null
    private var coreListener: CoreListenerStub? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var currentLogin: String? = null
    private var currentPassword: String? = null
    private var currentServer: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Сервис создан")
        createNotificationChannel()
        startForeground(2, buildNotification())
        acquireLocks()
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

            Log.d(TAG, "WakeLock и WifiLock для LinphoneService получены")
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
                            showIncomingCallNotification(callerNumber)
                            MainActivity.instance?.onNativeIncomingCall(callerNumber)
                        }
                        Call.State.Connected -> {
                            MainActivity.instance?.onNativeCallActive()
                        }
                        Call.State.End, Call.State.Error -> {
                            cancelIncomingCallNotification()
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
            serverAddress?.transport = TransportType.Udp
            serverAddress?.port = 5068

            val params = core?.createAccountParams()
            if (params != null) {
                params.identityAddress = identityAddress
                params.serverAddress = serverAddress
                params.isRegisterEnabled = true

                val account = core?.createAccount(params)
                if (account != null) {
                    core?.addAccount(account)
                    core?.defaultAccount = account
                }
            }

            core?.start()
            Log.d(TAG, "Linphone Core запущен для $login")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации Linphone", e)
        }
    }

    private fun stopLinphone() {
        try {
            core?.removeListener(coreListener)
            core?.stop()
            core = null
            Log.d(TAG, "Linphone остановлен")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при остановке Linphone", e)
        }
    }

    private fun showIncomingCallNotification(number: String) {
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPending = PendingIntent.getActivity(
            this, 4, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, "ProfiCrmChannel")
            .setContentTitle("Входящий звонок")
            .setContentText("Номер: $number")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPending, true)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1002, notification)
    }

    private fun cancelIncomingCallNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(1002)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopLinphone()
        wakeLock?.release()
        wifiLock?.release()
        Log.d(TAG, "Сервис уничтожен")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Linphone Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Profi CRM")
            .setContentText("SIP-клиент активен")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pendingIntent)
            .build()
    }
}
