package com.tomato.downloader.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * APK 内置的下载内核（番茄下载器 Rust 二进制）。
 *
 * 背景：旧版客户端必须先在手机联网下载 8MB 的二进制、再用浏览器打开 Web UI 才能用。
 * 这里把内核直接打进 APK 的 assets，首次启动时释放到可执行目录，
 * 做到「装上就能下载」：不需要浏览器、不需要先联网拉二进制。
 *
 * 内核版本随 APK 发布；用户仍可在「Web 服务」页手动联网获取更新版本（走 ServerManager.install(force = true)）。
 */
object BundledCore {

    /** 内置内核版本（与上游 release tag 一致）。 */
    const val VERSION = "v2.4.16"

    private const val ASSET_DIR = "tomato"

    fun assetName(arch: String): String = "TomatoNovelDownloader-Android_$arch"

    /** 当前设备架构是否有内置内核。 */
    fun isBundled(context: Context, arch: String): Boolean = runCatching {
        context.assets.open("$ASSET_DIR/${assetName(arch)}").use { }
        true
    }.getOrDefault(false)

    /**
     * 把内置内核释放到 [dest] 并赋予可执行权限。
     * 返回 true 表示释放成功（文件可执行）。
     */
    suspend fun release(
        context: Context,
        arch: String,
        dest: File,
        log: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val asset = "$ASSET_DIR/${assetName(arch)}"
        if (!isBundled(context, arch)) {
            log("[WARN] APK 内没有 Android_$arch 内核")
            return@withContext false
        }
        runCatching {
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            if (tmp.exists()) tmp.delete()
            context.assets.open(asset).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            dest.setReadable(true, false)
            dest.setExecutable(true, false)
            log("[INFO] 内置内核已释放：${dest.absolutePath}（${dest.length() / 1024 / 1024} MB）")
            true
        }.getOrElse { e ->
            log("[ERR ] 释放内置内核失败：${e.message}")
            false
        }
    }
}
