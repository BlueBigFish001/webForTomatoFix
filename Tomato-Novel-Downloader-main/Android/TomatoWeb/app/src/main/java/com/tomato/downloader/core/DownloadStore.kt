package com.tomato.downloader.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.util.Locale

/**
 * 把下载 / 导出的文件落到「手机公共存储」，保证用户用文件管理器、微信、阅读器都能直接找到。
 *
 * - Android 10（API 29）+：写入 MediaStore.Downloads，RELATIVE_PATH = Download/Tomato
 *   免存储权限，卸载 App 也不会被清掉。
 * - Android 9 及以下：写入 /sdcard/Download/Tomato（需要 WRITE_EXTERNAL_STORAGE）。
 */
object DownloadStore {

    /** 公共存储下的子目录名：Download/Tomato */
    const val SUB_DIR = "Tomato"

    data class Saved(
        val uri: Uri,
        val displayName: String,
        val location: String,
        val size: Long
    )

    /** 根据扩展名猜 MIME。 */
    fun mimeFor(name: String, fallback: String? = null): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "txt" -> "text/plain"
            "epub" -> "application/epub+zip"
            "pdf" -> "application/pdf"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "zip" -> "application/zip"
            "mobi" -> "application/x-mobipocket-ebook"
            "azw3" -> "application/vnd.amazon.ebook"
            "md" -> "text/markdown"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> fallback ?: "application/octet-stream"
        }
    }

    /** 清掉文件名里的非法字符，避免某些 ROM / FAT 分区写入失败。 */
    fun sanitize(name: String, fallback: String = "download"): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .trim()
            .trim('.')
        return cleaned.ifBlank { fallback }.take(180)
    }

    /** 把一段流写入公共 Download/Tomato 目录。 */
    fun saveStream(
        context: Context,
        displayName: String,
        mime: String,
        writer: (OutputStream) -> Unit
    ): Saved? {
        val safe = sanitize(displayName)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return saveToLegacyDir(context, safe, writer)
        }
        // MediaStore 是 Android 10+ 的免权限路径；个别魔改 ROM 会失败，再退回公共目录直写。
        return saveToMediaStore(context, uniqueName(context, safe), mime, writer)
            ?: saveToLegacyDir(context, safe, writer)
    }

    fun saveBytes(context: Context, displayName: String, mime: String, bytes: ByteArray): Saved? =
        saveStream(context, displayName, mime) { it.write(bytes) }

    private fun saveToMediaStore(
        context: Context,
        name: String,
        mime: String,
        writer: (OutputStream) -> Unit
    ): Saved? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }
            .getOrNull() ?: return null
        var size = 0L
        try {
            resolver.openOutputStream(uri)?.use { out ->
                val counting = CountingOutputStream(out)
                writer(counting)
                size = counting.count
            } ?: throw IllegalStateException("无法打开输出流")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        runCatching { resolver.update(uri, values, null, null) }
        return Saved(uri, name, "Download/" + SUB_DIR + "/" + name, size)
    }

    private fun saveToLegacyDir(context: Context, name: String, writer: (OutputStream) -> Unit): Saved? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUB_DIR
        )
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = uniqueLegacyFile(dir, name)
        val size = runCatching {
            target.outputStream().use { out ->
                val counting = CountingOutputStream(out)
                writer(counting)
                counting.count
            }
        }.getOrNull() ?: return null
        return Saved(Uri.fromFile(target), target.name, target.absolutePath, size)
    }

    private fun uniqueLegacyFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "${base}_$i" else "${base}_$i.$ext")
            i++
        }
        return f
    }

    /** MediaStore 里同名文件已存在时自动加 _1/_2，避免覆盖用户已有文件。 */
    private fun uniqueName(context: Context, name: String): String {
        if (!existsInMediaStore(context, name)) return name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (existsInMediaStore(context, if (ext.isEmpty()) "${base}_$i" else "${base}_$i.$ext")) i++
        return if (ext.isEmpty()) "${base}_$i" else "${base}_$i.$ext"
    }

    private fun existsInMediaStore(context: Context, name: String): Boolean = runCatching {
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            MediaStore.Downloads.DISPLAY_NAME + " = ?",
            arrayOf(name),
            null
        )?.use { c -> c.count > 0 } ?: false
    }.getOrDefault(false)

    private class CountingOutputStream(private val inner: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            inner.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            inner.write(b, off, len)
            count += len
        }

        override fun flush() = inner.flush()
        override fun close() = inner.close()
    }
}
