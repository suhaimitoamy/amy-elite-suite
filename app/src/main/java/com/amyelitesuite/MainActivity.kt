package com.amyelitesuite

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

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

        webView.webChromeClient = WebChromeClient() // Enables JS alerts
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
                    request.setTitle("Amy Elite Suite Download")
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

        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // JS Interface to save base64 blobs
    inner class WebAppInterface(private val mContext: Context) {
        @JavascriptInterface
        fun saveBlob(base64Data: String, filename: String) {
            try {
                val cleanBase64 = base64Data.replaceFirst("^data:.*?;base64,".toRegex(), "")
                val pdfAsBytes = Base64.decode(cleanBase64, 0)
                
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                
                val file = File(downloadsDir, filename)
                val os = FileOutputStream(file, false)
                os.write(pdfAsBytes)
                os.flush()
                os.close()
                
                (mContext as Activity).runOnUiThread {
                    Toast.makeText(mContext, "PDF tersimpan di folder Download", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                (mContext as Activity).runOnUiThread {
                    Toast.makeText(mContext, "Gagal menyimpan PDF", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
