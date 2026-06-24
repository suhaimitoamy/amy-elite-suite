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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

class ScannerService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchdogJob: Job? = null
    private lateinit var dataAgent: MarketDataSyncAgent
    private var apiKey: String? = null
    private var bslTarget: Double = 0.0
    private var sslTarget: Double = 0.0
    private var finalBias: String = "NEUTRAL"
    private var htfBias: String = "NEUTRAL"
    private var htfBiasScore: Int = 0
    private var htfBiasReason: String = "HTF menunggu data"
    private var marketPhase: String = "WAIT"
    private var setupScore: Int = 0
    private var setupGrade: String = "WAIT"
    private var hasAlertedBsl = false
    private var hasAlertedSsl = false
    private val lastAlertAt = mutableMapOf<String, Long>()
    @Volatile private var lastTickAt = System.currentTimeMillis()
    @Volatile private var lastReconnectAt = 0L
    @Volatile private var lastHtfScanAt = 0L

    private var recentHigh = 0.0
    private var recentLow = 0.0
    private var eq = 0.0
    private var activeFvg: Zone? = null
    private var discountOb: Zone? = null
    private var premiumOb: Zone? = null

    data class Zone(
        val type: String,
        val low: Double,
        val high: Double
    )

    data class FrameContext(
        val tf: String,
        val bias: String,
        val phase: String,
        val pd: String,
        val score: Int,
        val high: Double,
        val low: Double,
        val eq: Double
    )

    data class NativeEvent(
        val key: String,
        val title: String,
        val message: String
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

        if (!::dataAgent.isInitialized) {
            dataAgent = MarketDataSyncAgent(applicationContext, client)
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
        bootstrapContext()
        startWebSocket()
        startWatchdog()

        return START_STICKY
    }

    private fun bootstrapContext() {
        serviceScope.launch {
            try {
                val rows = dataAgent.bootstrap("XAU/USD", "M5", 300)
                if (rows.size >= 20) {
                    buildContext(rows, rows.last().close)
                    updateHtfBias(rows, rows.last().close, force = true)
                    updateSetupScore(rows.last().close)
                    startForegroundServiceNotification()
                }
            } catch (e: Exception) {
                Log.e("AmyFX", "Bootstrap context failed: ${e.message}")
            }
        }
    }

    private fun startForegroundServiceNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val text = "HTF:$htfBias | Bias:$finalBias | Score:$setupScore | $marketPhase"
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "scanner_channel")
                .setContentTitle("Amy FX Scanner Active")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText("$text\nBSL: ${fmt(bslTarget)} | SSL: ${fmt(sslTarget)}"))
                .setSmallIcon(R.drawable.ic_stat_amy_fx)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Amy FX Scanner Active")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText("$text\nBSL: ${fmt(bslTarget)} | SSL: ${fmt(sslTarget)}"))
                .setSmallIcon(R.drawable.ic_stat_amy_fx)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
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
                lastTickAt = System.currentTimeMillis()
                val subscribeMsg = """{"action": "subscribe", "params": {"symbols": "XAU/USD"}}"""
                webSocket.send(subscribeMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.has("price")) {
                        lastTickAt = System.currentTimeMillis()
                        val currentPrice = json.getDouble("price")
                        val timestamp = json.optLong("timestamp", System.currentTimeMillis() / 1000)
                        checkTargets(currentPrice, timestamp)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("AmyFX", "WebSocket Error: ${t.message}")
                scheduleReconnect("failure")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("AmyFX", "WebSocket Closed: $reason")
                scheduleReconnect("closed")
            }
        })
    }

    private fun scheduleReconnect(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastReconnectAt < 5000L) return
        lastReconnectAt = now
        serviceScope.launch {
            delay(5000)
            Log.d("AmyFX", "Reconnecting WebSocket: $reason")
            startWebSocket()
        }
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = serviceScope.launch {
            while (true) {
                delay(60_000L)
                val now = System.currentTimeMillis()
                val silentTooLong = now - lastTickAt > 120_000L
                if (silentTooLong) {
                    Log.w("AmyFX", "Watchdog restarting silent WebSocket")
                    sendDedupedAlert(
                        "WATCHDOG-${now / 600000L}",
                        "Scanner Watchdog Aktif",
                        "Amy FX mendeteksi WebSocket diam lebih dari 2 menit. Koneksi direstart otomatis. Background test masih hidup."
                    )
                    startForegroundServiceNotification()
                    startWebSocket()
                    lastTickAt = now
                }
            }
        }
    }

    private fun checkTargets(currentPrice: Double, timestamp: Long) {
        val rows = dataAgent.onTick("XAU/USD", "M5", currentPrice, timestamp)
        if (rows.size >= 20) {
            buildContext(rows, currentPrice)
            updateHtfBias(rows, currentPrice)
            updateSetupScore(currentPrice)
            scanNativeEvents(rows, currentPrice).forEach { event ->
                sendDedupedAlert(event.key, event.title, event.message)
            }
            startForegroundServiceNotification()
        }

        val fvg = activeFvg
        val inFvg = fvg != null && currentPrice >= fvg.low && currentPrice <= fvg.high

        if (inFvg && fvg != null) {
            val q = poiQuality("FVG", fvg.type, currentPrice, fvg)
            sendDedupedAlert(
                "FVG-${fvg.type}-${fmt(fvg.low)}-${fmt(fvg.high)}",
                "FVG ${fvg.type} Aktif",
                detailedMessage(
                    currentPrice,
                    "${fvg.type} FVG touched",
                    "${fmt(fvg.low)} - ${fmt(fvg.high)}",
                    "Quality ${q.first} ${q.second}/100. OB ditahan karena FVG aktif."
                )
            )
            return
        }

        val ob = when {
            discountOb?.let { currentPrice >= it.low && currentPrice <= it.high } == true -> discountOb
            premiumOb?.let { currentPrice >= it.low && currentPrice <= it.high } == true -> premiumOb
            else -> null
        }

        if (ob != null) {
            val q = poiQuality("OB", ob.type, currentPrice, ob)
            sendDedupedAlert(
                "OB-${ob.type}-${fmt(ob.low)}-${fmt(ob.high)}",
                "OB ${ob.type} Tersentuh",
                detailedMessage(
                    currentPrice,
                    "${ob.type} OB touched",
                    "${fmt(ob.low)} - ${fmt(ob.high)}",
                    "Quality ${q.first} ${q.second}/100. Final Bias: $finalBias."
                )
            )
        }

        if (bslTarget > 0 && currentPrice >= bslTarget && !hasAlertedBsl) {
            hasAlertedBsl = true
            sendDedupedAlert(
                "BSL-${fmt(bslTarget)}",
                "BSL Tertembus",
                detailedMessage(currentPrice, "BSL tertembus", fmt(bslTarget), "Target atas sudah diambil. Final Bias: $finalBias.")
            )
        }

        if (sslTarget > 0 && currentPrice <= sslTarget && !hasAlertedSsl) {
            hasAlertedSsl = true
            sendDedupedAlert(
                "SSL-${fmt(sslTarget)}",
                "SSL Tertembus",
                detailedMessage(currentPrice, "SSL tertembus", fmt(sslTarget), "Target bawah sudah diambil. Final Bias: $finalBias.")
            )
        }
    }

    private fun scanNativeEvents(rows: List<CandleStore.Candle>, currentPrice: Double): List<NativeEvent> {
        val events = mutableListOf<NativeEvent>()
        if (rows.size < 20) return events

        val latest = rows.last()
        val a = max(atr(rows), 0.05)
        val body = abs(latest.close - latest.open)
        val br = bodyRatio(latest)
        val trend = inferTrend(rows)
        val phase = calcMarketPhase(rows, trend, a)
        marketPhase = phase

        events.add(
            NativeEvent(
                "PHASE-$phase-${latest.openTime}",
                "Market Phase $phase",
                detailedMessage(currentPrice, "Market Phase $phase", "Trend ${trend.uppercase()}", "HTF Bias: $htfBias. Setup Score: $setupScore/100 ($setupGrade).")
            )
        )

        if (body >= a * 1.5 && br >= 0.65) {
            val dir = if (latest.close > latest.open) "Bullish" else "Bearish"
            events.add(
                NativeEvent(
                    "DISPLACEMENT-$dir-${latest.openTime}",
                    "$dir Displacement",
                    detailedMessage(currentPrice, "$dir displacement M5", "Close ${fmt(latest.close)}", "Phase EXPANSION. Body kuat. HTF Bias: $htfBias.")
                )
            )
        }

        if (currentPrice > recentHigh) {
            val tag = if (trend == "bearish") "MSS" else "BOS"
            events.add(
                NativeEvent(
                    "STRUCT-$tag-BULL-${fmt(recentHigh)}",
                    "$tag Bullish M5",
                    detailedMessage(currentPrice, "$tag Bullish M5", fmt(recentHigh), "Resistance/BSL dijebol. HTF Bias: $htfBias. Setup Score: $setupScore/100.")
                )
            )
        }

        if (currentPrice < recentLow) {
            val tag = if (trend == "bullish") "MSS" else "BOS"
            events.add(
                NativeEvent(
                    "STRUCT-$tag-BEAR-${fmt(recentLow)}",
                    "$tag Bearish M5",
                    detailedMessage(currentPrice, "$tag Bearish M5", fmt(recentLow), "Support/SSL dijebol. HTF Bias: $htfBias. Setup Score: $setupScore/100.")
                )
            )
        }

        val pivots = pivotLevels(rows)
        val buySide = pivots.first.filter { it > latest.close }.minOrNull() ?: rows.takeLast(60).maxOf { it.high }
        val sellSide = pivots.second.filter { it < latest.close }.maxOrNull() ?: rows.takeLast(60).minOf { it.low }
        val tol = max(a * 0.10, 0.05)
        val range = max(latest.high - latest.low, 0.0001)
        val topWick = (latest.high - max(latest.open, latest.close)) / range
        val botWick = (kotlin.math.min(latest.open, latest.close) - latest.low) / range

        if (latest.high > buySide + tol && latest.close < buySide && topWick >= 0.30) {
            events.add(
                NativeEvent(
                    "SWEEP-BSL-${latest.openTime}",
                    "BSL Swept",
                    detailedMessage(currentPrice, "Buy-side liquidity swept", fmt(buySide), "Harga ambil likuiditas atas lalu close kembali. HTF Bias: $htfBias.")
                )
            )
        }

        if (latest.low < sellSide - tol && latest.close > sellSide && botWick >= 0.30) {
            events.add(
                NativeEvent(
                    "SWEEP-SSL-${latest.openTime}",
                    "SSL Swept",
                    detailedMessage(currentPrice, "Sell-side liquidity swept", fmt(sellSide), "Harga ambil likuiditas bawah lalu close kembali. HTF Bias: $htfBias.")
                )
            )
        }

        if (setupScore >= 71) {
            events.add(
                NativeEvent(
                    "SETUP-STRONG-$setupGrade-${latest.openTime}",
                    "Setup Score $setupScore/100",
                    detailedMessage(currentPrice, "Setup $setupGrade", "$setupScore/100", "HTF Bias: $htfBias. Final Bias: $finalBias. Phase: $marketPhase.")
                )
            )
        }

        events.add(
            NativeEvent(
                "HTF-$htfBias-${htfBiasScore / 10}-${latest.openTime / 900L}",
                "HTF Bias $htfBias",
                detailedMessage(currentPrice, "HTF Bias $htfBias", "Score $htfBiasScore", htfBiasReason)
            )
        )

        return events
    }

    private fun updateHtfBias(m5Rows: List<CandleStore.Candle>, price: Double, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastHtfScanAt < 60_000L) return
        lastHtfScanAt = now

        val frames = mutableListOf<FrameContext>()
        val tfs = listOf("D1", "H4", "H1", "M15", "M5")
        for (tf in tfs) {
            val rows = if (tf == "M5") m5Rows else dataAgent.latest("XAU/USD", tf, 180)
            if (rows.size >= 20) frames.add(analyzeFrame(tf, rows, price))
        }

        if (frames.isEmpty()) return

        val weights = mapOf("D1" to 5, "H4" to 4, "H1" to 3, "M15" to 2, "M5" to 1)
        var total = 0
        var weightSum = 0
        frames.forEach { frame ->
            val w = weights[frame.tf] ?: 1
            total += frame.score * w
            weightSum += w
        }

        val norm = total.toDouble() / max(weightSum, 1).toDouble()
        htfBiasScore = (norm * 100.0).toInt()
        htfBias = when {
            norm >= 0.35 -> "BULLISH"
            norm <= -0.35 -> "BEARISH"
            else -> "NEUTRAL"
        }
        htfBiasReason = frames.joinToString(" | ") { "${it.tf}:${it.bias}/${it.phase}/${it.pd}" }
    }

    private fun updateSetupScore(price: Double) {
        var score = 30
        if (htfBias != "NEUTRAL" && htfBias == finalBias) score += 25
        if (htfBias != "NEUTRAL" && finalBias != "NEUTRAL" && htfBias != finalBias) score -= 20
        if ((finalBias == "BULLISH" && price < eq) || (finalBias == "BEARISH" && price > eq)) score += 15
        if (activeFvg != null) score -= 10
        val obActive = discountOb?.let { price >= it.low && price <= it.high } == true || premiumOb?.let { price >= it.low && price <= it.high } == true
        if (obActive) score += 15
        if (marketPhase == "EXPANSION") score += 10
        if (marketPhase == "RETRACEMENT") score += 8
        setupScore = score.coerceIn(0, 100)
        setupGrade = when {
            setupScore >= 71 -> "KUAT"
            setupScore >= 41 -> "HATI-HATI"
            else -> "LEMAH"
        }
    }

    private fun analyzeFrame(tf: String, rows: List<CandleStore.Candle>, price: Double): FrameContext {
        val pivots = pivotLevels(rows)
        val highs = pivots.first
        val lows = pivots.second
        val recentH = if (highs.isNotEmpty()) highs.takeLast(3).maxOrNull() ?: rows.takeLast(60).maxOf { it.high } else rows.takeLast(60).maxOf { it.high }
        val recentL = if (lows.isNotEmpty()) lows.takeLast(3).minOrNull() ?: rows.takeLast(60).minOf { it.low } else rows.takeLast(60).minOf { it.low }
        val range = max(recentH - recentL, 0.01)
        val frameEq = recentL + range / 2.0
        val pd = when {
            price > frameEq + range * 0.08 -> "PREMIUM"
            price < frameEq - range * 0.08 -> "DISCOUNT"
            else -> "EQ"
        }
        val trend = inferTrend(rows)
        val bias = when (trend) {
            "bullish" -> "BULLISH"
            "bearish" -> "BEARISH"
            else -> "NEUTRAL"
        }
        val phase = calcMarketPhase(rows, trend, max(atr(rows), 0.05))
        val score = when (bias) {
            "BULLISH" -> 1
            "BEARISH" -> -1
            else -> 0
        }
        return FrameContext(tf, bias, phase, pd, score, recentH, recentL, frameEq)
    }

    private fun buildContext(rows: List<CandleStore.Candle>, price: Double) {
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

    private fun findNearestFvg(rows: List<CandleStore.Candle>, price: Double): Zone? {
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
        if (htfBias == "BULLISH") score += 2
        if (htfBias == "BEARISH") score -= 2
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

    private fun pivotLevels(rows: List<CandleStore.Candle>): Pair<List<Double>, List<Double>> {
        val highs = mutableListOf<Double>()
        val lows = mutableListOf<Double>()
        if (rows.size < 10) return Pair(highs, lows)
        for (i in 3 until rows.size - 2) {
            var isHigh = true
            var isLow = true
            for (j in 1..3) {
                if (rows[i - j].high >= rows[i].high) isHigh = false
                if (rows[i - j].low <= rows[i].low) isLow = false
            }
            for (j in 1..2) {
                if (rows[i + j].high >= rows[i].high) isHigh = false
                if (rows[i + j].low <= rows[i].low) isLow = false
            }
            if (isHigh) highs.add(rows[i].high)
            if (isLow) lows.add(rows[i].low)
        }
        return Pair(highs, lows)
    }

    private fun inferTrend(rows: List<CandleStore.Candle>): String {
        val pivots = pivotLevels(rows)
        val highs = pivots.first
        val lows = pivots.second
        val hh = highs.size >= 2 && highs[highs.size - 1] > highs[highs.size - 2]
        val hl = lows.size >= 2 && lows[lows.size - 1] > lows[lows.size - 2]
        val lh = highs.size >= 2 && highs[highs.size - 1] < highs[highs.size - 2]
        val ll = lows.size >= 2 && lows[lows.size - 1] < lows[lows.size - 2]
        return when {
            hh && hl -> "bullish"
            lh && ll -> "bearish"
            else -> "range"
        }
    }

    private fun calcMarketPhase(rows: List<CandleStore.Candle>, trend: String, a: Double): String {
        val latest = rows.lastOrNull() ?: return "WAIT"
        val body = abs(latest.close - latest.open)
        if (body >= a * 1.5 && bodyRatio(latest) >= 0.60) return "EXPANSION"
        val last = rows.takeLast(60)
        val hi = last.maxOf { it.high }
        val lo = last.minOf { it.low }
        val mid = lo + (hi - lo) / 2.0
        if (trend == "bullish" && latest.close < mid) return "RETRACEMENT"
        if (trend == "bearish" && latest.close > mid) return "RETRACEMENT"
        return if (trend == "range") "RANGE" else "CONTINUATION"
    }

    private fun atr(rows: List<CandleStore.Candle>): Double {
        val ranges = rows.takeLast(14).map { it.high - it.low }.filter { it > 0 }
        return if (ranges.isEmpty()) 0.50 else ranges.sum() / ranges.size
    }

    private fun bodyRatio(c: CandleStore.Candle): Double {
        val range = max(c.high - c.low, 0.0001)
        return abs(c.close - c.open) / range
    }

    private fun poiQuality(kind: String, type: String, price: Double, zone: Zone): Pair<String, Int> {
        var score = if (kind == "OB") 58 else 54
        val bullish = type.contains("Bullish", ignoreCase = true) || type.contains("Discount", ignoreCase = true)
        val bearish = type.contains("Bearish", ignoreCase = true) || type.contains("Premium", ignoreCase = true)
        if (bullish && htfBias == "BULLISH") score += 15
        if (bearish && htfBias == "BEARISH") score += 15
        if (bullish && price < eq) score += 12
        if (bearish && price > eq) score += 12
        if (kind == "OB" && activeFvg != null) score -= 18
        if (marketPhase == "RETRACEMENT") score += 8
        val mid = (zone.low + zone.high) / 2.0
        val width = max(zone.high - zone.low, 0.01)
        if (abs(price - mid) <= width * 2) score += 6
        score = score.coerceIn(0, 100)
        val grade = when {
            score >= 75 -> "KUAT"
            score >= 55 -> "SEDANG"
            else -> "LEMAH"
        }
        return Pair(grade, score)
    }

    private fun detailedMessage(price: Double, event: String, area: String, status: String): String {
        return """
XAU/USD ${fmt(price)}

Event: $event
Area: $area
HTF Bias: $htfBias ($htfBiasScore)
Final Bias: $finalBias
Phase: $marketPhase
Setup Score: $setupScore/100 ($setupGrade)
Target: BSL ${fmt(bslTarget)} | SSL ${fmt(sslTarget)}
Status: $status
        """.trimIndent()
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
                .setContentText(message.lines().firstOrNull() ?: message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setSmallIcon(R.drawable.ic_stat_amy_fx)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(message.lines().firstOrNull() ?: message)
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
