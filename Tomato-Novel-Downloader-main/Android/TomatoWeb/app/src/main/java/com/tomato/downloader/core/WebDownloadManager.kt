package com.tomato.downloader.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * 应用内下载器：接管内嵌 WebView 里的导出/下载点击。
 *
 * 为什么要自己接管：WebView 默认不会把文件存到用户能找到的地方（早先只能靠系统浏览器）。
 * 这里把下载流直接落到 Download/Tomato，并在界面上给出进度与结果提示。
 */
object WebDownloadManager {

    data class Progress(
        val active: Boolean = false,
        val displayName: String = "",
        val received: Long = 0L,
        val total: Long = -1L,
        val message: String? = null,
        val failed: Boolean = false
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    /** WebView 的 DownloadListener 回调入口。 */
    fun download(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        val app = context.applicationContext
        // DownloadListener 在主线程回调，CookieManager 也在主线程访问最稳。
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        scope.launch {
            _progress.value = Progress(active = true, displayName = "准备下载…", total = contentLength)
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 20_000
                    readTimeout = 180_000
                    requestMethod = "GET"
                    userAgent?.takeIf { it.isNotBlank() }?.let { setRequestProperty("User-Agent", it) }
                    cookie?.takeIf { it.isNotBlank() }
                        ?.let { setRequestProperty("Cookie", it) }
                    setRequestProperty("Referer", "http://127.0.0.1/")
                }
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val total = if (contentLength > 0) contentLength else conn.contentLengthLong
                val name = guessFileName(
                    contentDisposition ?: conn.getHeaderField("Content-Disposition"),
                    conn.url.toString()
                ) ?: "downloaded"
                _progress.value = Progress(active = true, displayName = name, total = total)

                var received = 0L
                var lastNotify = 0L
                val saved = DownloadStore.saveStream(app, name, DownloadStore.mimeFor(name, mimeType)) { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            received += n
                            // 节流：每 200ms 才推一次进度，避免高频重组
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastNotify >= 200) {
                                lastNotify = now
                                _progress.value = Progress(
                                    active = true, displayName = name, received = received, total = total
                                )
                            }
                        }
                    }
                }
                conn.disconnect()
                if (saved == null) throw IOException("写入公共存储失败")

                _progress.value = Progress(
                    active = false,
                    displayName = saved.displayName,
                    received = saved.size,
                    total = saved.size,
                    message = "已保存到 ${saved.location}"
                )
                toast(app, "已保存到 ${saved.location}")
            } catch (e: Exception) {
                _progress.value = Progress(active = false, displayName = "", message = "下载失败：${e.message}", failed = true)
                toast(app, "下载失败：${e.message}")
            }
        }
    }

    /** blob:/data: 这类前端生成的下载，由 JS 读取后以 data URL 回传，这里解码落盘。 */
    fun saveDataUrl(context: Context, suggestedName: String, mime: String, dataUrl: String) {
        val app = context.applicationContext
        scope.launch {
            try {
                val comma = dataUrl.indexOf(',')
                if (comma < 0) throw IOException("非法 data URL")
                val header = dataUrl.substring(0, comma)
                val payload = dataUrl.substring(comma + 1)
                val bytes = if (header.contains(";base64")) {
                    Base64.decode(payload, Base64.DEFAULT)
                } else {
                    URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
                }
                val name = DownloadStore.sanitize(
                    suggestedName.ifBlank { "download" } + "." + extForMime(mime)
                )
                val saved = DownloadStore.saveBytes(app, name, DownloadStore.mimeFor(name, mime), bytes)
                    ?: throw IOException("写入公共存储失败")
                _progress.value = Progress(
                    active = false,
                    displayName = saved.displayName,
                    received = saved.size,
                    total = saved.size,
                    message = "已保存到 ${saved.location}"
                )
                toast(app, "已保存到 ${saved.location}")
            } catch (e: Exception) {
                _progress.value = Progress(active = false, displayName = "", message = "保存失败：${e.message}", failed = true)
                toast(app, "保存失败：${e.message}")
            }
        }
    }

    fun clearMessage() {
        _progress.value = _progress.value.copy(message = null, failed = false)
    }

    private fun toast(app: Context, text: String) {
        main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
    }

    private fun extForMime(mime: String): String = when {
        mime.contains("epub") -> "epub"
        mime.contains("zip") -> "zip"
        mime.contains("mpeg") -> "mp3"
        mime.contains("wav") -> "wav"
        mime.contains("pdf") -> "pdf"
        mime.startsWith("text/") -> "txt"
        else -> "bin"
    }

    private fun guessFileName(disposition: String?, rawUrl: String): String? {
        if (!disposition.isNullOrBlank()) {
            Regex("filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
                .find(disposition)?.groupValues?.getOrNull(1)?.trim()?.let { enc ->
                    runCatching { URLDecoder.decode(enc, "UTF-8") }.getOrNull()?.let { return it }
                }
            Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                .find(disposition)?.groupValues?.getOrNull(1)?.trim()?.trim('"')?.let { return it }
        }
        val path = runCatching { URL(rawUrl).path }.getOrNull() ?: return null
        val last = path.substringAfterLast('/')
        if (last.isBlank()) return null
        return runCatching { URLDecoder.decode(last, "UTF-8") }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
