package com.gamingworld.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.ClipData
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Environment
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: TopProgressBar
    private lateinit var overlay: View

    private val handler = Handler(Looper.getMainLooper())
    private var overlayVisible = false
    private var isFirstLoad = true
    private var pageVisibleCommitted = false

    private val timeoutRunnable = Runnable { hideOverlay() }
    private val renderTimeoutRunnable = Runnable {
        if (!pageVisibleCommitted && !isShowingError) {
            showWebErrorPage(webView.url ?: APP_URL, "页面长时间未正常渲染，可能被网站限制或渲染失败")
        }
    }

    private var isShowingError = false
    private var failedUrl: String? = null
    private var lastBlockedHint: String? = null
    private var lastConsoleError: String? = null

    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingGeoCallback: android.webkit.GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String? = null
    private var fileChooserCallbackRef: ValueCallback<Array<Uri>>? = null
    private var pendingFileChooserParams: WebChromeClient.FileChooserParams? = null
    private var cameraImageUri: Uri? = null
    private var cameraVideoUri: Uri? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results: Array<Uri>? = when {
            result.resultCode != RESULT_OK -> null
            result.data != null -> parseSelectedUris(result.data!!)
            cameraVideoUri != null -> arrayOf(cameraVideoUri!!)
            cameraImageUri != null -> arrayOf(cameraImageUri!!)
            else -> null
        }
        revokeCapturedUriPermissions()
        fileChooserCallbackRef?.onReceiveValue(results)
        fileChooserCallbackRef = null
        pendingFileChooserParams = null
        cameraImageUri = null
        cameraVideoUri = null
    }

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            pendingFileChooserParams?.let { launchFileChooser(it) }
        } else {
            revokeCapturedUriPermissions()
            fileChooserCallbackRef?.onReceiveValue(null)
            fileChooserCallbackRef = null
            pendingFileChooserParams = null
            cameraImageUri = null
            cameraVideoUri = null
            android.widget.Toast.makeText(this, "缺少文件上传所需权限", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // تعيين وضع الشاشة الكاملة
        @Suppress("DEPRECATION")
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            
        // منع تصوير الشاشة (اختياري)
        // window.setFlags(
        //     android.view.WindowManager.LayoutParams.FLAG_SECURE,
        //     android.view.WindowManager.LayoutParams.FLAG_SECURE
        // )
        
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        overlay = findViewById(R.id.overlay)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        
        swipeRefresh.setColorSchemeColors(
            android.graphics.Color.parseColor("#c94d06")
        )
        
        swipeRefresh.setOnRefreshListener {
            isShowingError = false
            failedUrl = null
            webView.reload()
        }
        
        showOverlay()
        setupWebView()
    }

    private fun showBlankPageError(url: String, detail: String) {
        showWebErrorPage(url, "页面未正常显示：${detail}")
    }

    private fun inspectBlankPage(view: WebView, url: String) {
        if (isShowingError) return
        view.postVisualStateCallback(System.currentTimeMillis()) {
            view.evaluateJavascript(
                """
                (function(){
                  try{
                    var body=document.body;
                    var text=(body&&body.innerText?body.innerText:'').trim();
                    var html=(document.documentElement&&document.documentElement.outerHTML?document.documentElement.outerHTML:'');
                    var bg=window.getComputedStyle(document.body||document.documentElement).backgroundColor||'';
                    return JSON.stringify({
                      textLength:text.length,
                      htmlLength:html.length,
                      title:document.title||'',
                      bg:bg
                    });
                  }catch(e){
                    return JSON.stringify({error:String(e)});
                  }
                })();
                """.trimIndent()
            ) { raw ->
                val payload = raw.orEmpty()
                val looksBlank = payload.contains(""textLength":0") && !payload.contains(""htmlLength":0")
                val jsError = lastConsoleError
                if (!isShowingError && (looksBlank || !jsError.isNullOrBlank())) {
                    val detail = jsError ?: "页面内容为空或未渲染完成"
                    showBlankPageError(url, detail)
                }
            }
        }
    }

    private fun showBlockedBySitePage(url: String, reason: String) {
        hideOverlay()
        handler.removeCallbacks(renderTimeoutRunnable)
        pageVisibleCommitted = false
        isShowingError = true
        failedUrl = url
        lastBlockedHint = reason
        swipeRefresh.isRefreshing = false
        val safeUrl = url.replace("'", "&#39;")
        val safeReason = reason.replace("'", "&#39;")
        val html = """
            <!doctype html>
            <html lang="ar" dir="rtl">
            <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width,initial-scale=1" />
                <title>Gaming World - خطأ في التحميل</title>
                <style>
                    body{margin:0;padding:24px;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#000;color:#fff;display:flex;align-items:center;justify-content:center;min-height:100vh}
                    .card{max-width:560px;width:100%;background:#0a0a0a;border:2px solid #c94d06;border-radius:25px;padding:30px;box-shadow:0 10px 30px rgba(0,0,0,.35);text-align:center}
                    h1{margin:0 0 15px;font-size:24px;color:#c94d06}
                    p{margin:0 0 15px;line-height:1.7;color:#ccc}
                    .url{font-size:13px;color:#c94d06;background:rgba(201,77,6,.12);padding:10px;border-radius:12px;word-break:break-all}
                    .actions{display:flex;gap:12px;justify-content:center;margin-top:20px}
                    button{border:0;border-radius:40px;padding:12px 24px;font-size:15px;font-weight:600;cursor:pointer;font-family:sans-serif}
                    .primary{background:#c94d06;color:#fff}
                    .secondary{background:#1a1a1a;color:#fff;border:1px solid #c94d06}
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>⚠️ خطأ في التحميل</h1>
                    <p>حدثت مشكلة أثناء تحميل الصفحة</p>
                    <p class="url">""" + safeUrl + """</p>
                    <div class="actions">
                        <button class="primary" onclick="Android.openExternal('""" + safeUrl + """')">فتح في المتصفح</button>
                        <button class="secondary" onclick="location.reload()">إعادة المحاولة</button>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
        webView.loadDataWithBaseURL(url, html, "text/html", "UTF-8", url)
    }

    private fun showWebErrorPage(url: String, message: String) {
        hideOverlay()
        handler.removeCallbacks(renderTimeoutRunnable)
        pageVisibleCommitted = false
        isShowingError = true
        failedUrl = url
        swipeRefresh.isRefreshing = false
        webView.loadDataWithBaseURL(url, errorHtml(url, message), "text/html", "UTF-8", url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true
        }
        
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url != "about:blank" && url != failedUrl) {
                    isShowingError = false
                    failedUrl = null
                    lastBlockedHint = null
                    lastConsoleError = null
                }
                pageVisibleCommitted = false
                handler.removeCallbacks(renderTimeoutRunnable)
                handler.postDelayed(renderTimeoutRunnable, 12000)
                if (isFirstLoad) showOverlay()
            }

            override fun onPageFinished(view: WebView, url: String) {
                swipeRefresh.isRefreshing = false
                handler.removeCallbacks(renderTimeoutRunnable)
                if (!isShowingError) {
                    fetchThemeColor(view)
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                pageVisibleCommitted = true
                handler.removeCallbacks(renderTimeoutRunnable)
                hideOverlay()
                isFirstLoad = false
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                handler.proceed() // قبول الشهادة (للاختبار فقط، احذف في الإنتاج)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try { 
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) 
                    } catch (e: Exception) {}
                    return true
                }
                return false
            }
            
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    hideOverlay()
                    val errDesc = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        when (error.errorCode) {
                            android.webkit.WebViewClient.ERROR_HOST_LOOKUP -> "فشل في تحليل اسم النطاق، تأكد من اتصال الإنترنت"
                            android.webkit.WebViewClient.ERROR_CONNECT -> "لا يمكن الاتصال بالخادم"
                            android.webkit.WebViewClient.ERROR_TIMEOUT -> "انتهت مهلة الاتصال"
                            else -> "حدث خطأ أثناء التحميل"
                        }
                    } else {
                        "فشل في تحميل الصفحة"
                    }
                    showWebErrorPage(request.url.toString(), errDesc)
                }
            }
        }
        
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    lastConsoleError = consoleMessage.message()
                    Log.e("GamingWorld", consoleMessage.message())
                }
                return super.onConsoleMessage(consoleMessage)
            }
            
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.setProgress(newProgress)
                if (newProgress >= 75 && isFirstLoad) {
                    hideOverlay()
                    isFirstLoad = false
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: android.webkit.GeolocationPermissions.Callback
            ) {
                callback.invoke(origin, true, false)
            }
        }
        
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val uri = Uri.parse(url)
                val filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                val req = DownloadManager.Request(uri).apply {
                    setMimeType(mimetype)
                    addRequestHeader("User-Agent", userAgent)
                    setDescription("جارٍ التحميل...")
                    setTitle(filename)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                }
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(req)
                android.widget.Toast.makeText(this, "بدأ التحميل: $filename", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
            }
        }

        // ربط JavaScript مع Native
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onThemeColor(hex: String) {
                try {
                    val color = android.graphics.Color.parseColor(hex)
                    runOnUiThread { progressBar.setBarColor(color) }
                } catch (e: Exception) {}
            }
        }, "ThemeBridge")

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun openExternal(url: String) {
                runOnUiThread {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {}
                }
            }
            
            @JavascriptInterface
            fun toast(msg: String) {
                runOnUiThread {
                    android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            
            @JavascriptInterface
            fun back() {
                runOnUiThread { if (webView.canGoBack()) webView.goBack() }
            }
            
            @JavascriptInterface
            fun reload() {
                runOnUiThread {
                    isShowingError = false
                    failedUrl = null
                    webView.reload()
                }
            }
        }, "Android")
        
        // رابط موقعك - غير هذا إلى رابط موقعك
        webView.loadUrl("https://world-store.66ghz.com")
    }

    private fun fetchThemeColor(view: WebView) {
        val js = """
            (function() {
                var m = document.querySelector('meta[name="theme-color"]');
                if (m && m.content) { ThemeBridge.onThemeColor(m.content); }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun showOverlay() {
        if (overlayVisible) return
        overlayVisible = true
        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        progressBar.setProgress(0)
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, 10000L)
    }

    private fun hideOverlay() {
        if (!overlayVisible) return
        handler.removeCallbacks(timeoutRunnable)
        overlayVisible = false
        overlay.animate().alpha(0f).setDuration(300).withEndAction {
            overlay.visibility = View.GONE
        }.start()
    }

    private fun errorHtml(url: String?, errDesc: String? = null): String {
        val safeUrl = url?.replace("'", "\'") ?: "about:blank"
        val safeDesc = (errDesc ?: "فشل في الاتصال بالخادم").replace("&", "&amp;").replace("<", "&lt;")
        return """
            <!DOCTYPE html>
            <html lang="ar" dir="rtl">
            <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width,initial-scale=1" />
                <title>Gaming World - خطأ</title>
                <style>
                    body{margin:0;padding:24px;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#000;color:#fff;display:flex;align-items:center;justify-content:center;min-height:100vh}
                    .error-card{text-align:center;max-width:400px;background:#0a0a0a;border:2px solid #c94d06;border-radius:25px;padding:40px 30px}
                    .error-icon{font-size:64px;margin-bottom:20px}
                    .error-title{color:#c94d06;font-size:24px;margin-bottom:15px}
                    .error-message{color:#aaa;margin-bottom:25px;line-height:1.6}
                    .retry-btn{background:#c94d06;color:#fff;border:none;padding:12px 30px;border-radius:40px;font-size:16px;font-weight:bold;cursor:pointer}
                    .retry-btn:hover{background:#ff6a1a}
                </style>
            </head>
            <body>
                <div class="error-card">
                    <div class="error-icon">⚠️</div>
                    <h1 class="error-title">عذراً، حدث خطأ</h1>
                    <p class="error-message">${safeDesc}</p>
                    <button class="retry-btn" onclick="if(window.Android){Android.reload()}else{location.reload()}">إعادة المحاولة</button>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private var backPressedTime = 0L
    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            val now = System.currentTimeMillis()
            if (now - backPressedTime < 2000) {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            } else {
                backPressedTime = now
                android.widget.Toast.makeText(this, "اضغط مرة أخرى للخروج", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun parseSelectedUris(data: Intent): Array<Uri>? {
        val clipData = data.clipData
        if (clipData != null && clipData.itemCount > 0) {
            return Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
        }
        val parsed = WebChromeClient.FileChooserParams.parseResult(RESULT_OK, data)
        if (!parsed.isNullOrEmpty()) return parsed
        data.data?.let { return arrayOf(it) }
        return null
    }

    private fun revokeCapturedUriPermissions() {
        cameraImageUri?.let {
            try { revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
        }
        cameraVideoUri?.let {
            try { revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
        }
    }

    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = File(cacheDir, "webview_uploads").apply { if (!exists()) mkdirs() }
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
    }

    private fun createVideoFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = File(cacheDir, "webview_uploads").apply { if (!exists()) mkdirs() }
        return File.createTempFile("VIDEO_${timeStamp}_", ".mp4", storageDir)
    }

    private fun launchFileChooser(fileChooserParams: WebChromeClient.FileChooserParams) {
        try {
            val contentIntent = fileChooserParams.createIntent().apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            }
            val chooserIntents = mutableListOf<Intent>()
            val acceptTypes = fileChooserParams.acceptTypes.filter { it.isNotBlank() }
            val acceptJoined = acceptTypes.joinToString(",").lowercase()
            val wantsImage = acceptJoined.isBlank() || acceptJoined.contains("image")
            val wantsVideo = acceptJoined.contains("video")

            if (wantsImage && fileChooserParams.isCaptureEnabled) {
                val photoFile = createImageFile()
                cameraImageUri = FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.fileprovider",
                    photoFile
                )
                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (cameraIntent.resolveActivity(packageManager) != null) {
                    chooserIntents.add(cameraIntent)
                    if (fileChooserParams.isCaptureEnabled) {
                        fileChooserLauncher.launch(cameraIntent)
                        return
                    }
                }
            }

            if (wantsVideo && fileChooserParams.isCaptureEnabled) {
                val videoFile = createVideoFile()
                cameraVideoUri = FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.fileprovider",
                    videoFile
                )
                val videoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, cameraVideoUri)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (videoIntent.resolveActivity(packageManager) != null) {
                    chooserIntents.add(videoIntent)
                    if (fileChooserParams.isCaptureEnabled && acceptTypes.all { it.startsWith("video/") }) {
                        fileChooserLauncher.launch(videoIntent)
                        return
                    }
                }
            }

            val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                putExtra(Intent.EXTRA_INTENT, contentIntent)
                putExtra(Intent.EXTRA_INITIAL_INTENTS, chooserIntents.toTypedArray())
                putExtra(Intent.EXTRA_TITLE, "اختر ملف")
            }
            if (contentIntent.resolveActivity(packageManager) != null) {
                fileChooserLauncher.launch(chooser)
            } else {
                throw IllegalStateException("لا يوجد تطبيق لاختيار الملفات")
            }
        } catch (e: Exception) {
            revokeCapturedUriPermissions()
            fileChooserCallbackRef?.onReceiveValue(null)
            fileChooserCallbackRef = null
            pendingFileChooserParams = null
            cameraImageUri = null
            cameraVideoUri = null
            android.widget.Toast.makeText(this, "لا يمكن فتح مستعرض الملفات", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() { 
        super.onPause()
        CookieManager.getInstance().flush() 
    }
    
    override fun onDestroy() { 
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy() 
    }

    companion object {
        const val APP_URL = "https://world-store.66ghz.com"
        const val APP_VERSION = "1.0.0"
        const val PERMISSION_REQUEST_CODE = 1002
    }
}
