package com.tomato.downloader.core

import android.content.Context
import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 把上游 Web UI 改造成手机布局，但不改内核。
 *
 * 为什么是注入而不是改模板：上游的 index.html / app.css 是用 Rust 的 include_str! 编译进二进制里的，
 * 改模板就必须重编内核；注入只作用于 App 内的 WebView，内核可随意升级。
 *
 * 两条路一起用：
 *  1) interceptCss：拦下 /assets/app.css，把 assets/tomato/mobile.css 追加在后面一起返回，
 *     首帧就是手机布局，不会先闪一下桌面版；
 *  2) injectScript：页面可见后再注入 assets/tomato/mobile.js，负责把宽表格改成竖排卡片
 *     （需要读表头文字，必须等 DOM 就绪）。
 * 两者都只在窄屏（CSS 里的 @media）生效，平板 / 电脑浏览器看到的仍是原版。
 */
class MobileUiInjector(private val context: Context) {

    private val mobileCss: String by lazy {
        runCatching {
            context.assets.open("tomato/mobile.css").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    private val mobileJs: String by lazy {
        runCatching {
            context.assets.open("tomato/mobile.js").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    @Volatile
    private var scriptInjected = false

    /** 新页面开始加载时调用，允许下一次重新注入。 */
    fun onPageStarted() {
        scriptInjected = false
    }

    /**
     * 拦截样式表请求：原样取回内核返回的 app.css，再把手机端样式拼在后面。
     * 取不到时返回 null，交给 WebView 自己加载（injectScript 里还有 CSS 兜底）。
     */
    fun interceptCss(url: String): WebResourceResponse? {
        if (mobileCss.isEmpty()) return null
        if (!url.contains("/assets/app.css")) return null
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 5_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            try {
                if (conn.responseCode !in 200..299) return null
                val original = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val merged = original + "\n\n/* ===== Web For Tomato · 手机端适配 ===== */\n" + mobileCss
                WebResourceResponse(
                    "text/css",
                    "utf-8",
                    ByteArrayInputStream(merged.toByteArray(Charsets.UTF_8))
                )
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** 注入表格卡片化脚本（幂等，同一页面只注入一次）。 */
    fun injectScript(view: WebView) {
        if (scriptInjected) return
        scriptInjected = true
        val payload = buildString {
            append("(function(){")
            append("if(!document.getElementById('tomato-mobile-css')){")
            append("var s=document.createElement('style');s.id='tomato-mobile-css';s.textContent=")
            append(jsString(mobileCss))
            append(";document.head.appendChild(s);}")
            if (mobileJs.isNotEmpty()) {
                append("if(!window.__tomatoMobileReady){")
                append("var run=function(){var el=document.createElement('script');el.textContent=")
                append(jsString(mobileJs))
                append(";document.body.appendChild(el);};")
                append("if(document.body){run();}else{document.addEventListener('DOMContentLoaded',run);}")
                append("}")
            }
            append("})();")
        }
        view.evaluateJavascript(payload, null)
    }

    private fun jsString(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "") + "\""
}
