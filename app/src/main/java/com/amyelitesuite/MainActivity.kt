package com.amyelitesuite

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.media.RingtoneManager
import android.webkit.JavascriptInterface

import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_REQUEST_CODE = 100

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Define LayoutParams to fix cut-off display
        val matchParentParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Initialize SwipeRefreshLayout
        swipeRefreshLayout = SwipeRefreshLayout(this)
        swipeRefreshLayout.layoutParams = matchParentParams
        
        // Initialize Notification Channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "amy_alerts_v2",
                "Trading Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority trading signals"
                enableLights(true)
                lightColor = Color.GREEN
                enableVibration(true)
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audioAttributes)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        // Check POST_NOTIFICATIONS permission for Android 13+
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
            }
        }
        
        requestDisableBatteryOptimization(false)
        
        // Initialize WebView

        webView = WebView(this)
        webView.layoutParams = matchParentParams
        swipeRefreshLayout.addView(webView)
        setContentView(swipeRefreshLayout)

        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT // Optimize caching
        webSettings.allowFileAccess = true

        // Add Javascript Interface for Blob downloads
        webView.addJavascriptInterface(WebAppInterface(this), "Android")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }

                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                } catch (e: ActivityNotFoundException) {
                    fileUploadCallback = null
                    Toast.makeText(this@MainActivity, "Cannot Open File Chooser", Toast.LENGTH_LONG).show()
                    return false
                }
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                swipeRefreshLayout.isRefreshing = false
            }
        }

        // Handle pull-to-refresh
        swipeRefreshLayout.setOnRefreshListener {
            webView.reload()
        }

        // Handle Downloads (PDF Reports)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            if (url.startsWith("blob:")) {
                // Fetch blob via JS and pass to Android interface
                webView.evaluateJavascript("""
                    fetch('$url')
                        .then(res => res.blob())
                        .then(blob => {
                            var reader = new FileReader();
                            reader.readAsDataURL(blob);
                            reader.onloadend = function() {
                                Android.saveBlob(reader.result, 'Trading_Report_${System.currentTimeMillis()}.pdf');
                            }
                        });
                """.trimIndent(), null)
                Toast.makeText(this, "Generating PDF...", Toast.LENGTH_SHORT).show()
            } else if (url.startsWith("http")) {
                // Normal HTTP download
                try {
                    val request = DownloadManager.Request(Uri.parse(url))
                    request.setMimeType(mimetype)
                    request.addRequestHeader("User-Agent", userAgent)
                    request.setDescription("Downloading file...")
                    request.setTitle("Amy FX Download")
                    request.allowScanningByMediaScanner()
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Amy_Elite_Download_${System.currentTimeMillis()}")
                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(request)
                    Toast.makeText(this, "Downloading File...", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Check Storage Permission for older Android versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            }
        }

        // Handle incoming intent if started from notification
        val targetUrl = intent.getStringExtra("target_url")
        if (targetUrl != null) {
            webView.loadUrl(targetUrl)
        } else {
            webView.loadUrl("file:///android_asset/index.html")
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isBatteryOptimizationDisabled()) {
            Toast.makeText(this, "Amy FX belum Unrestricted. Background scanner bisa mati.", Toast.LENGTH_LONG).show()
        }
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestDisableBatteryOptimization(blockScanner: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (isBatteryOptimizationDisabled()) return

        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Wajib matikan Battery Optimization")
                .setMessage("Amy FX perlu mode Unrestricted / Jangan dibatasi agar scanner dan notifikasi tetap hidup saat aplikasi diminimize. Scanner tidak akan dijalankan sebelum izin ini diaktifkan.")
                .setCancelable(!blockScanner)
                .setPositiveButton("Buka Pengaturan") { _, _ -> openBatteryOptimizationRequest() }
                .setNegativeButton("Nanti", null)
                .show()
        }
    }

    private fun openBatteryOptimizationRequest() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (ex: Exception) {
                Toast.makeText(this, "Buka Settings > Battery > Amy FX > Unrestricted", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra("target_url")?.let {
            if (webView.url != it) {
                webView.loadUrl(it)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (fileUploadCallback == null) return
            val result = if (data == null || resultCode != Activity.RESULT_OK) null else arrayOf(data.data!!)
            fileUploadCallback?.onReceiveValue(result)
            fileUploadCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // JS Interface to save base64 blobs
    inner class WebAppInterface(private val mContext: Context) {
        
        @JavascriptInterface
        fun showAppToast(message: String) {
            (mContext as Activity).runOnUiThread {
                Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun triggerHaptic(pattern: Int) {
            try {
                val vibrator = mContext.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(pattern.toLong(), android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern.toLong())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun startBackgroundScanner(apiKey: String?, bsl: String?, ssl: String?) {
            try {
                if (!this@MainActivity.isBatteryOptimizationDisabled()) {
                    (mContext as Activity).runOnUiThread {
                        Toast.makeText(mContext, "Matikan Battery Optimization dulu agar scanner tidak mati.", Toast.LENGTH_LONG).show()
                        this@MainActivity.requestDisableBatteryOptimization(true)
                    }
                    return
                }

                if (!apiKey.isNullOrEmpty() && apiKey != "undefined") {
                    val prefs = mContext.getSharedPreferences("AmyFXPrefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("api_key", apiKey).apply()
                }
                val intent = Intent(mContext, ScannerService::class.java).apply {
                    putExtra("bsl", bsl)
                    putExtra("ssl", ssl)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mContext.startForegroundService(intent)
                } else {
                    mContext.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun stopBackgroundScanner() {
            try {
                val intent = Intent(mContext, ScannerService::class.java)
                intent.action = "STOP_SCANNER"
                mContext.startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }


        @JavascriptInterface
        fun showNotification(title: String, message: String) {
            showNotificationWithUrl(title, message, null)
        }

        @JavascriptInterface
        fun showNotificationWithUrl(title: String, message: String, url: String?) {
            try {
                val intent = Intent(mContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    if (url != null) {
                        putExtra("target_url", url)
                    }
                }
                // Use a unique request code so intents aren't merged
                val requestCode = System.currentTimeMillis().toInt()
                val pendingIntent = PendingIntent.getActivity(
                    mContext, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(mContext, "amy_alerts_v2")
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(mContext)
                        .setPriority(Notification.PRIORITY_HIGH)
                }

                val notification = builder
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                    .setVibrate(longArrayOf(0, 500, 250, 500))
                    .build()

                val nm = mContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(System.currentTimeMillis().toInt(), notification)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private var currentFileOutputStream: FileOutputStream? = null

        @JavascriptInterface
        fun startFile(filename: String) {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = File(downloadsDir, filename)
                currentFileOutputStream = FileOutputStream(file, false)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun appendFileChunk(base64Chunk: String) {
            try {
                val cleanBase64 = base64Chunk.replaceFirst("^data:.*?;base64,".toRegex(), "")
                val fileAsBytes = Base64.decode(cleanBase64, 0)
                currentFileOutputStream?.write(fileAsBytes)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        @JavascriptInterface
        fun finishFile() {
            try {
                currentFileOutputStream?.flush()
                currentFileOutputStream?.close()
                currentFileOutputStream = null
                (mContext as Activity).runOnUiThread {
                    Toast.makeText(mContext, "File tersimpan di folder Download", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                (mContext as Activity).runOnUiThread {
                    Toast.makeText(mContext, "Gagal menyimpan file", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun saveBlob(base64Data: String, filename: String) {
            try {
                val cleanBase64 = base64Data.replaceFirst("^data:.*?;base64,".toRegex(), "")
                val fileAsBytes = Base64.decode(cleanBase64, 0)
                
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                
                val file = File(downloadsDir, filename)
                val os = FileOutputStream(file, false)
                os.write(fileAsBytes)
                os.flush()
                os.close()
                
                (mContext as Activity).runOnUiThread {
                    Toast.makeText(mContext, "File tersimpan di folder Download", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                (mContext as Activity).runOnUiThread {
                    Toast.makeText(mContext, "Gagal menyimpan file", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
