// ... (остальной код без изменений)

    inner class WebAppInterface(private val context: Context) {

        // ⬇️ НОВЫЙ МЕТОД ⬇️
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
        fun setSpeakerphoneOn(enabled: Boolean) { /* ... */ }
        // ... и так далее, остальные методы без изменений
    }

    private fun startAllServices() {
        try {
            // ❌ Убираем запуск LinphoneService отсюда — он теперь запускается из WebView
            // val linphoneIntent = Intent(this, LinphoneService::class.java)
            // startForegroundService(linphoneIntent)

            // ✅ Оставляем только ForegroundService (для поддержания WebView)
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
