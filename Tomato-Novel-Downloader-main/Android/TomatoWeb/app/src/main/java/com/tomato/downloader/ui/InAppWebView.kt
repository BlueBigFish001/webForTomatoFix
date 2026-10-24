package com.tomato.downloader.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.tomato.downloader.core.BundledCore
import com.tomato.downloader.core.MobileUiInjector
import com.tomato.downloader.core.ServerManager
import com.tomato.downloader.core.WebDownloadManager
import com.tomato.downloader.service.WebServerService
import com.tomato.downloader.ui.theme.Green
import com.tomato.downloader.ui.theme.TerminalBg
import com.tomato.downloader.ui.theme.TerminalBorder
import com.tomato.downloader.ui.theme.TerminalSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 应用内下载页：把下载内核的 Web UI 嵌进 App（WebView），
 * 不再需要跳系统浏览器；网页里的「导出 / 下载」由 [WebDownloadManager] 接管，
 * 直接落到手机的 Download/Tomato 目录。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DownloadWebScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? Activity
    val state by ServerManager.state.collectAsState()
    val localUrl by ServerManager.localUrl.collectAsState()
    val dl by WebDownloadManager.progress.collectAsState()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf("番茄小说下载器") }
    var loading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    // 手机端适配：CSS 由 shouldInterceptRequest 直接拼进 app.css（首帧即生效），
    // 表格卡片化脚本在页面可见后注入。
    val mobileUi = remember { MobileUiInjector(context.applicationContext) }

    // ① 进入页面即准备：内置内核释放（离线、秒级）+ 启动前台服务
    // reloadTick 变化（用户点「重试启动」）会重跑一次
    LaunchedEffect(reloadTick) {
        if (started && reloadTick == 0) return@LaunchedEffect
        started = true
        if (!ServerManager.isInstalled(context)) {
            ServerManager.install(context, force = false)
        }
        if (ServerManager.state.value != ServerManager.ServerState.RUNNING) {
            val intent = Intent(context, WebServerService::class.java)
                .setAction(WebServerService.ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    // ② 端口真正可连后再 loadUrl，避免首屏 CONNECTION_REFUSED
    LaunchedEffect(state, localUrl, reloadTick, webView) {
        val wv = webView ?: return@LaunchedEffect
        if (state != ServerManager.ServerState.RUNNING) return@LaunchedEffect
        loading = true
        pageError = null
        val ok = waitPort(ServerManager.port, 20_000)
        if (ok) {
            wv.loadUrl(localUrl)
        } else {
            loading = false
            pageError = "下载服务启动超时，请到「Web服务」页查看日志"
        }
    }

    // ③ 下载完成 / 失败提示
    LaunchedEffect(dl.message, dl.failed) {
        val msg = dl.message ?: return@LaunchedEffect
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        delay(3000)
        WebDownloadManager.clearMessage()
    }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else activity?.finish()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webView = null
        }
    }

    Column(modifier = modifier.fillMaxSize().background(TerminalBg)) {
        // —— 顶部工具条 ——
        Surface(color = TerminalSurface) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        webView?.let { wv ->
                            if (wv.canGoBack()) {
                                wv.goBack()
                                wv.post { canGoBack = wv.canGoBack() }
                            }
                        }
                    },
                    enabled = canGoBack
                ) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "后退", tint = Color(0xFFC9D1D9))
                }
                Text(
                    text = pageTitle,
                    color = Color(0xFFE6EDF3),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    val wv = webView ?: return@IconButton
                    if (state == ServerManager.ServerState.RUNNING) {
                        loading = true
                        pageError = null
                        wv.reload()
                    } else {
                        reloadTick++
                    }
                }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新", tint = Color(0xFFC9D1D9))
                }
                IconButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(localUrl))) }
                        .onFailure {
                            Toast.makeText(context, "没有可打开的浏览器", Toast.LENGTH_SHORT).show()
                        }
                }) {
                    Icon(Icons.Outlined.OpenInBrowser, contentDescription = "在浏览器打开", tint = Color(0xFFC9D1D9))
                }
            }
        }
        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Green,
                trackColor = Color.Transparent
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize().background(TerminalBg),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            allowFileAccess = false
                            allowContentAccess = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            builtInZoomControls = false
                            displayZoomControls = false
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            cacheMode = WebSettings.LOAD_DEFAULT
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? {
                                val url = request.url.toString()
                                if (url.contains("/assets/app.css")) {
                                    mobileUi.interceptCss(url)?.let { return it }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageCommitVisible(view: WebView?, url: String?) {
                                super.onPageCommitVisible(view, url)
                                if (view != null) mobileUi.injectScript(view)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                val uri = request.url
                                val host = uri.host.orEmpty()
                                val local = host == "127.0.0.1" || host == "localhost" ||
                                    host == "::1" || host == "0.0.0.0"
                                if (local) return false
                                // 站外链接（致谢、更新日志等）交给系统浏览器
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, uri)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }.onFailure {
                                    Toast.makeText(context, "无法打开外部链接", Toast.LENGTH_SHORT).show()
                                }
                                return true
                            }

                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: android.graphics.Bitmap?
                            ) {
                                loading = true
                                pageError = null
                                canGoBack = view?.canGoBack() == true
                                mobileUi.onPageStarted()
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                view?.title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
                                canGoBack = view?.canGoBack() == true
                                if (view != null) mobileUi.injectScript(view)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    loading = false
                                    pageError = error?.description?.toString() ?: "页面加载失败"
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                if (newProgress >= 100) loading = false
                                view?.title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
                            }

                            override fun onJsAlert(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                result: android.webkit.JsResult?
                            ): Boolean {
                                showJsDialog(activity, "提示", message.orEmpty(), null) { result?.confirm() }
                                return true
                            }

                            override fun onJsConfirm(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                result: android.webkit.JsResult?
                            ): Boolean {
                                showJsDialog(activity, "请确认", message.orEmpty(), result)
                                return true
                            }
                        }
                        addJavascriptInterface(TomatoJsBridge(context.applicationContext), "TomatoBridge")
                        setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                            if (url.startsWith("blob:") || url.startsWith("data:")) {
                                // 前端生成的下载：让页面自己读成 data URL 再交给原生落盘
                                evaluateJavascript(blobFetcherJs(url, mimeType), null)
                            } else {
                                WebDownloadManager.download(
                                    context, url, userAgent, contentDisposition, mimeType, contentLength
                                )
                            }
                        }
                        webView = this
                    }
                }
            )

            // 服务启动中的遮罩
            val waitingMask = state == ServerManager.ServerState.INSTALLING ||
                (state != ServerManager.ServerState.RUNNING && pageError == null)
            if (waitingMask) {
                Box(
                    Modifier.fillMaxSize().background(Color(0xCC0D1117)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (state == ServerManager.ServerState.ERROR) {
                            Text(
                                "下载服务启动失败",
                                color = Color(0xFFFF6B6B),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "请切到「终端」页查看日志，或点下方重试",
                                color = Color(0xFF8B949E),
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { reloadTick++ }) { Text("重试启动") }
                        } else {
                            CircularProgressIndicator(color = Green)
                            Spacer(Modifier.height(14.dp))
                            Text(
                                when (state) {
                                    ServerManager.ServerState.INSTALLING -> "正在准备下载内核…"
                                    else -> "正在启动下载服务…"
                                },
                                color = Color(0xFFC9D1D9),
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "内置内核 " + BundledCore.VERSION,
                                color = Color(0xFF6B7280),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // 加载失败时的重试
            val err = pageError
            if (err != null) {
                Box(Modifier.fillMaxSize().background(Color(0xE60D1117)), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("页面加载失败", color = Color(0xFFFF6B6B), fontWeight = FontWeight.SemiBold)
                        Text(err, color = Color(0xFF8B949E), fontSize = 12.sp)
                        OutlinedButton(onClick = {
                            pageError = null
                            webView?.reload()
                        }) { Text("重试") }
                    }
                }
            }

            // 底部下载进度条
            if (dl.active) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
                    color = TerminalSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, TerminalBorder)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.CloudDownload,
                                contentDescription = null,
                                tint = Green,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                dl.displayName.ifBlank { "下载中…" },
                                color = Color(0xFFE6EDF3),
                                fontSize = 12.sp,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (dl.total > 0) (dl.received * 100 / dl.total).toString() + "%"
                                else formatBytes(dl.received),
                                color = Color(0xFF8B949E),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (dl.total > 0) (dl.received.toFloat() / dl.total).coerceIn(0f, 1f) else 0f
                            },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = Green
                        )
                    }
                }
            }
        }
    }
}

private class TomatoJsBridge(private val appContext: Context) {
    @JavascriptInterface
    fun saveBlob(name: String?, mime: String?, dataUrl: String?) {
        if (dataUrl.isNullOrBlank()) return
        WebDownloadManager.saveDataUrl(
            appContext,
            name?.takeIf { it.isNotBlank() } ?: "tomato-download",
            mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
            dataUrl
        )
    }

    @JavascriptInterface
    fun toast(text: String?) {
        if (text.isNullOrBlank()) return
        Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
    }
}

private fun blobFetcherJs(url: String, mime: String?): String {
    val u = jsString(url)
    val m = jsString(mime ?: "application/octet-stream")
    return """
        (function(){
          try {
            fetch(${u}).then(function(r){ return r.blob(); }).then(function(b){
              var fr = new FileReader();
              fr.onload = function(){ window.TomatoBridge.saveBlob("tomato-download", ${m}, fr.result); };
              fr.onerror = function(){ window.TomatoBridge.toast("读取 blob 失败"); };
              fr.readAsDataURL(b);
            }).catch(function(e){ window.TomatoBridge.toast("blob 下载失败: " + e); });
          } catch (e) { window.TomatoBridge.toast("blob 下载异常: " + e); }
        })();
    """.trimIndent()
}

private fun jsString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

private fun showJsDialog(
    activity: Activity?,
    title: String,
    message: String,
    result: android.webkit.JsResult?,
    onOk: () -> Unit = { result?.confirm() }
) {
    if (activity == null) {
        onOk()
        return
    }
    AlertDialog.Builder(activity)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("确定") { _, _ -> onOk() }
        .setOnCancelListener { result?.cancel() }
        .show()
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> bytes.toString() + " B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}

/** 轮询本机端口，直到服务真正 accept 连接。 */
private suspend fun waitPort(port: Int, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
        val ok = runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400) }
            true
        }.getOrDefault(false)
        if (ok) return@withContext true
        delay(250)
    }
    false
}
