package com.proficrm.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.linphone.core.*

class LinphoneService : Service() {

    private var core: Core? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val CHANNEL_ID = "SipServiceChannel"
    private val NOTIF_ID = 101

    override fun onCreate() {
        super.onCreate()
        Log.d("LinphoneService", "Сервис создан")
        createNotificationChannel()
        acquireWakeLock()
        
        val notification = createServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, 
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val login = intent?.getStringExtra("sip_login")
        val password = intent?.getStringExtra("sip_password")
        val server = intent?.getStringExtra("sip_server") ?: "sipproxy.telphin.ru"

        if (login != null && password != null && core == null) {
            initLinphone(login, password, server)
        }

        return START_STICKY
    }

    private fun initLinphone(login: String, password: String, server: String) {
        Log.d("LinphoneService", "Инициализация Linphone для $login")
        
        val factory = Factory.instance()
        factory.setDebugMode(true, "Linphone")
        factory.setCacheDir(filesDir.absolutePath)
        factory.setDataDir(filesDir.absolutePath)

        core = factory.createCore(null, null, this)
        core?.let { c ->
            c.transports.tcpPort = 5060
            c.isIpv6Enabled = false
            
            val authInfo = factory.createAuthInfo(login, null, password, null, null, server)
            
            val params = c.createAccountParams()
            
            val identity = factory.createAddress("sip:$login@$server")
            params.identityAddress = identity
            
            // ИСПРАВЛЕНО: используем безопасный вызов (?.) для nullable Address
            val serverAddress = factory.createAddress("sip:$server")?.apply {
                transport = TransportType.Tcp
            }
            params.serverAddress = serverAddress
            
            // ИСПРАВЛЕНО: правильное имя свойства — isRegisterEnabled
            params.isRegisterEnabled = true
            
            val account = c.createAccount(params)
            c.addAuthInfo(authInfo)
            c.addAccount(account)
            c.defaultAccount = account

            c.addListener(object : CoreListenerStub() {
                override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
                    Log.d("LinphoneService", "Состояние звонка: $state")
                    if (state == Call.State.IncomingReceived) {
                        showIncomingCallNotification(call)
                    }
                }
            })

            c.start()
        }
    }

    private fun showIncomingCallNotification(call: Call) {
        val remoteAddress = call.remoteAddress?.username ?: "Неизвестный"
        
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("incoming_call", true)
            putExtra("caller_number", remoteAddress)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Входящий звонок")
            .setContentText("Звонит: $remoteAddress")
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setOngoing(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIF_ID + 1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "SIP Сервис",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createServiceNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SIP-телефон активен")
            .setContentText("Ожидание звонков...")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProfiCrm::SipWakeLock")
        wakeLock?.acquire(10 * 60 * 1000L)
    }

    override fun onDestroy() {
        Log.d("LinphoneService", "Сервис уничтожен")
        core?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
