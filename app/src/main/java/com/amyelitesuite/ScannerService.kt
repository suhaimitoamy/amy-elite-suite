package com.amyelitesuite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ScannerService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var apiKey: String? = null
    private var bslTarget: Double = 0.0
    private var sslTarget: Double = 0.0
    private var hasAlertedBsl = false
    private var hasAlertedSsl = false

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SCANNER") {
            stopWebSocket()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()

        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "amyfx:scanner")
            wakeLock?.acquire(12 * 60 * 60 * 1000L /*12 hours max*/)
        }

        val prefs = getSharedPreferences("AmyFXPrefs", Context.MODE_PRIVATE)
        apiKey = prefs.getString("api_key", null)
        
        val passedBsl = intent?.getStringExtra("bsl")?.toDoubleOrNull() ?: 0.0
        val passedSsl = intent?.getStringExtra("ssl")?.toDoubleOrNull() ?: 0.0
        
        if (passedBsl > 0) bslTarget = passedBsl
        if (passedSsl > 0) sslTarget = passedSsl
        
        // Reset flags if new targets are set via intent
        hasAlertedBsl = false
        hasAlertedSsl = false

        if (apiKey.isNullOrEmpty()) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundServiceNotification()
        startWebSocket()

        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "scanner_channel")
                .setContentTitle("Amy FX Scanner Active")
                .setContentText("Memantau XAU/USD (BSL: $bslTarget, SSL: $sslTarget)")
                .setSmallIcon(R.drawable.ic_stat_amy_fx)
                .setContentIntent(pendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Amy FX Scanner Active")
                .setContentText("Memantau XAU/USD (BSL: $bslTarget, SSL: $sslTarget)")
                .setSmallIcon(R.drawable.ic_stat_amy_fx)
                .setContentIntent(pendingIntent)
                .build()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    private fun startWebSocket() {
        stopWebSocket() // ensure no duplicates
        val request = Request.Builder()
            .url("wss://ws.twelvedata.com/v1/quotes/price?apikey=$apiKey")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("AmyFX", "WebSocket Connected")
                val subscribeMsg = """{"action": "subscribe", "params": {"symbols": "XAU/USD"}}"""
                webSocket.send(subscribeMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.has("price")) {
                        val currentPrice = json.getDouble("price")
                        checkTargets(currentPrice)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("AmyFX", "WebSocket Error: ${t.message}")
                // Auto-reconnect after 5 seconds
                serviceScope.launch {
                    delay(5000)
                    startWebSocket()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("AmyFX", "WebSocket Closed: $reason")
            }
        })
    }

    private fun checkTargets(currentPrice: Double) {
        if (bslTarget > 0 && currentPrice >= bslTarget && !hasAlertedBsl) {
            hasAlertedBsl = true
            sendAlertNotification("🚨 BSL Tertembus!", "Harga XAU/USD mencapai $currentPrice (Melewati BSL: $bslTarget)")
        }
        if (sslTarget > 0 && currentPrice <= sslTarget && !hasAlertedSsl) {
            hasAlertedSsl = true
            sendAlertNotification("🚨 SSL Tertembus!", "Harga XAU/USD jatuh ke $currentPrice (Menembus SSL: $sslTarget)")
        }
    }

    private fun sendAlertNotification(title: String, message: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val intent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, System.currentTimeMillis().toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "scanner_channel")
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_stat_amy_fx)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_stat_amy_fx)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
        }

        nm.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun stopWebSocket() {
        webSocket?.close(1000, "Service Stopped")
        webSocket = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "scanner_channel",
                "Background Scanner",
                NotificationManager.IMPORTANCE_HIGH
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        stopWebSocket()
        serviceScope.cancel()
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        super.onDestroy()
    }
}
