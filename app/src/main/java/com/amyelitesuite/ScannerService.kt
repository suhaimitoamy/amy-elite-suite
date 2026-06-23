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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ScannerService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var apiKey: String? = null
    private var bslTarget: Double = 0.0
    private var sslTarget: Double = 0.0
    private var finalBias: String = "NEUTRAL"
    private var hasAlertedBsl = false
    private var hasAlertedSsl = false
    private val lastAlertAt = mutableMapOf<String, Long>()

    private var contextReady = false
    private var lastContextFetch = 0L
    private var recentHigh = 0.0
    private var recentLow = 0.0
    private var eq = 0.0
    private var activeFvg: Zone? = null
    private var discountOb: Zone? = null
    private var premiumOb: Zone? = null

    data class Candle(
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val time: String
    )

    data class Zone(
        val type: String,
        val low: Double,
        val high: Double
    )

    override fun onBind(intent: Intent?): IBinder? = null

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
            wakeLock?.acquire(12 * 60 * 60 * 1000L)
        }

        val prefs = getSharedPreferences("AmyFXPrefs", Context.MODE_PRIVATE)
        apiKey = prefs.getString("api_key", null)

        val passedBsl = intent?.getStringExtra("bsl")?.toDoubleOrNull() ?: 0.0
        val passedSsl = intent?.getStringExtra("ssl")?.toDoubleOrNull() ?: 0.0

        if (passedBsl > 0 && abs(passedBsl - bslTarget) > 0.01) {
            bslTarget = passedBsl
            hasAlertedBsl = false
        }
        if (passedSsl > 0 && abs(passedSsl - sslTarget) > 0.01) {
            sslTarget = passedSsl
            hasAlertedSsl = false
        }

        if (apiKey.isNullOrEmpty()) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundServiceNotification()
        refreshMarketContext(force = true)
        startWebSocket()

        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val text = "Bias: $finalBias | BSL: ${fmt(bslTarget)} | SSL: ${fmt(sslTarget)}"
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "scanner_channel")
                .setContentTitle("Amy FX Scanner Active")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_amy_fx)
                .setContentIntent(pendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Amy FX Scanner Active")
                .setContentText(text)
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
        stopWebSocket()
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
        refreshMarketContextIfNeeded(currentPrice)

        val fvg = activeFvg
        val inFvg = fvg != null && currentPrice >= fvg.low && currentPrice <= fvg.high

        if (inFvg && fvg != null) {
            sendDedupedAlert(
                "FVG-${fvg.type}-${fmt(fvg.low)}-${fmt(fvg.high)}",
                "FVG ${fvg.type} Aktif",
                "Harga XAU/USD ${fmt(currentPrice)} menyentuh FVG ${fvg.type} ${fmt(fvg.low)} - ${fmt(fvg.high)}. OB ditahan sampai reaksi FVG jelas."
            )
            return
        }

        val ob = when {
            discountOb?.let { currentPrice >= it.low && currentPrice <= it.high } == true -> discountOb
            premiumOb?.let { currentPrice >= it.low && currentPrice <= it.high } == true -> premiumOb
            else -> null
        }

        if (ob != null) {
            sendDedupedAlert(
                "OB-${ob.type}-${fmt(ob.low)}-${fmt(ob.high)}",
                "OB ${ob.type} Tersentuh",
                "Harga XAU/USD ${fmt(currentPrice)} masuk OB ${ob.type} ${fmt(ob.low)} - ${fmt(ob.high)}. Final Bias: $finalBias."
            )
        }

        if (bslTarget > 0 && currentPrice >= bslTarget && !hasAlertedBsl) {
            hasAlertedBsl = true
            sendDedupedAlert(
                "BSL-${fmt(bslTarget)}",
                "BSL Tertembus",
                "Harga XAU/USD ${fmt(currentPrice)} melewati BSL ${fmt(bslTarget)}. Final Bias: $finalBias."
            )
        }

        if (sslTarget > 0 && currentPrice <= sslTarget && !hasAlertedSsl) {
            hasAlertedSsl = true
            sendDedupedAlert(
                "SSL-${fmt(sslTarget)}",
                "SSL Tertembus",
                "Harga XAU/USD ${fmt(currentPrice)} menembus SSL ${fmt(sslTarget)}. Final Bias: $finalBias."
            )
        }
    }

    private fun refreshMarketContextIfNeeded(currentPrice: Double) {
        val now = System.currentTimeMillis()
        if (!contextReady || now - lastContextFetch > 5 * 60 * 1000L) {
            refreshMarketContext(force = false, currentPrice = currentPrice)
        }
    }

    private fun refreshMarketContext(force: Boolean, currentPrice: Double = 0.0) {
        val now = System.currentTimeMillis()
        if (!force && now - lastContextFetch < 5 * 60 * 1000L) return
        lastContextFetch = now

        serviceScope.launch {
            try {
                val rows = fetchCandles()
                if (rows.size < 20) return@launch
                buildContext(rows, if (currentPrice > 0) currentPrice else rows.last().close)
                contextReady = true
                startForegroundServiceNotification()
            } catch (e: Exception) {
                Log.e("AmyFX", "Context refresh failed: ${e.message}")
            }
        }
    }

    private fun fetchCandles(): List<Candle> {
        val key = apiKey ?: return emptyList()
        val url = "https://api.twelvedata.com/time_series?symbol=XAU/USD&interval=5min&outputsize=120&apikey=$key"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val values = json.optJSONArray("values") ?: return emptyList()
            val rows = mutableListOf<Candle>()
            for (i in values.length() - 1 downTo 0) {
                val item = values.getJSONObject(i)
                rows.add(
                    Candle(
                        open = item.optDouble("open", 0.0),
                        high = item.optDouble("high", 0.0),
                        low = item.optDouble("low", 0.0),
                        close = item.optDouble("close", 0.0),
                        time = item.optString("datetime", "")
                    )
                )
            }
            return rows.filter { it.high > 0 && it.low > 0 }
        }
    }

    private fun buildContext(rows: List<Candle>, price: Double) {
        val highs = mutableListOf<Double>()
        val lows = mutableListOf<Double>()

        for (i in 5 until rows.size - 5) {
            var isHigh = true
            var isLow = true
            for (j in 1..5) {
                if (rows[i].high <= rows[i - j].high || rows[i].high <= rows[i + j].high) isHigh = false
                if (rows[i].low >= rows[i - j].low || rows[i].low >= rows[i + j].low) isLow = false
            }
            if (isHigh) highs.add(rows[i].high)
            if (isLow) lows.add(rows[i].low)
        }

        if (highs.isEmpty() || lows.isEmpty()) return

        recentHigh = highs.takeLast(3).maxOrNull() ?: rows.takeLast(30).maxOf { it.high }
        recentLow = lows.takeLast(3).minOrNull() ?: rows.takeLast(30).minOf { it.low }
        val range = max(recentHigh - recentLow, 0.01)
        eq = recentLow + range / 2.0

        if (bslTarget <= 0) bslTarget = highs.filter { it > price }.minOrNull() ?: rows.takeLast(30).maxOf { it.high }
        if (sslTarget <= 0) sslTarget = lows.filter { it < price }.maxOrNull() ?: rows.takeLast(30).minOf { it.low }

        activeFvg = findNearestFvg(rows, price)
        discountOb = Zone("Discount", recentLow, recentLow + range * 0.2)
        premiumOb = Zone("Premium", recentHigh - range * 0.2, recentHigh)
        finalBias = calculateFinalBias(price, activeFvg)
    }

    private fun findNearestFvg(rows: List<Candle>, price: Double): Zone? {
        for (i in rows.size - 3 downTo max(2, rows.size - 20)) {
            val c1 = rows[i - 2]
            val c3 = rows[i]
            if (c1.high < c3.low) {
                val zone = Zone("Bullish", c1.high, c3.low)
                if (price >= zone.low && price <= zone.high) return zone
            }
            if (c1.low > c3.high) {
                val zone = Zone("Bearish", c3.high, c1.low)
                if (price >= zone.low && price <= zone.high) return zone
            }
        }
        return null
    }

    private fun calculateFinalBias(price: Double, fvg: Zone?): String {
        var score = 0
        if (price > recentHigh) score += 2
        if (price < recentLow) score -= 2
        if (price < eq) score += 1
        if (price > eq) score -= 1
        if (fvg?.type == "Bullish") score += 1
        if (fvg?.type == "Bearish") score -= 1
        return when {
            score >= 2 -> "BULLISH"
            score <= -2 -> "BEARISH"
            else -> "NEUTRAL"
        }
    }

    private fun sendDedupedAlert(key: String, title: String, message: String) {
        val now = System.currentTimeMillis()
        val last = lastAlertAt[key] ?: 0L
        if (now - last < 10 * 60 * 1000L) return
        lastAlertAt[key] = now
        sendAlertNotification(title, message)
    }

    private fun sendAlertNotification(title: String, message: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, System.currentTimeMillis().toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "scanner_channel")
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setSmallIcon(R.drawable.ic_stat_amy_fx)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
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
            ).apply {
                description = "Amy FX background scanner alerts"
                enableVibration(true)
                enableLights(true)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(serviceChannel)
        }
    }

    private fun fmt(value: Double): String {
        if (value <= 0.0) return "-"
        return String.format("%.2f", value)
    }

    override fun onDestroy() {
        stopWebSocket()
        serviceScope.cancel()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        super.onDestroy()
    }
}
