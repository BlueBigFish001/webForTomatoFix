package com.tomato.downloader.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 本地内核接口的错误（HTTP 非 2xx 或解析失败）。 */
class ApiException(val code: Int, message: String) : Exception(message)

data class ApiBook(val bookId: String, val title: String, val author: String)

data class ApiOption(val label: String, val value: String)

data class ApiJobProgress(
    val groupDone: Int,
    val groupTotal: Int,
    val savedChapters: Int,
    val chapterTotal: Int,
    val commentSaved: Int,
    val commentTotal: Int,
    val audiobookGenerated: Int,
    val audiobookFailed: Int
) {
    /** 章节完成百分比（0..100）。 */
    val percent: Int
        get() = when {
            chapterTotal > 0 -> ((savedChapters.toLong() * 100) / chapterTotal).toInt().coerceIn(0, 100)
            groupTotal > 0 -> ((groupDone.toLong() * 100) / groupTotal).toInt().coerceIn(0, 100)
            else -> 0
        }
}

data class ApiJob(
    val id: Long,
    val bookId: String,
    val title: String,
    val author: String,
    val state: String,
    val progress: ApiJobProgress?,
    val message: String?,
    val formatOptions: List<ApiOption>,
    val nameOptions: List<ApiOption>
) {
    val isActive: Boolean get() = state == "queued" || state == "running"
}

data class ApiLibraryItem(
    val kind: String,
    val name: String,
    val relPath: String,
    val ext: String,
    val size: Long,
    val fileCount: Long?,
    val modifiedMs: Long?
) {
    val isDir: Boolean get() = kind == "dir"
}

data class ApiLibraryPage(
    val path: String,
    val items: List<ApiLibraryItem>,
    val running: Boolean,
    val scanned: Int,
    val error: String?
)

data class ApiHistoryItem(
    val timestamp: String,
    val bookId: String,
    val bookName: String,
    val author: String,
    val selectedChapters: Int,
    val successChapters: Int,
    val failedChapters: Int,
    val progress: String,
    val status: String
)

data class ApiUpdateItem(
    val bookId: String,
    val bookName: String,
    val folder: String,
    val localTotal: Int,
    val localFailed: Int,
    val remoteTotal: Int,
    val newCount: Int,
    val hasUpdate: Boolean
)

data class ApiUpdates(
    val running: Boolean,
    val scanned: Int,
    val total: Int,
    val saveDir: String,
    val updates: List<ApiUpdateItem>,
    val noUpdates: List<ApiUpdateItem>,
    val error: String?
)

data class ApiConfig(
    val novelFormat: String,
    val bulkFiles: Boolean,
    val enableAudiobook: Boolean,
    val audiobookFormat: String,
    val askFormatAfterDownload: Boolean
)

data class ApiStatus(
    val version: String,
    val saveDir: String,
    val bindAddr: String,
    val locked: Boolean,
    val prewarmInProgress: Boolean,
    val prewarmError: String?
)

/**
 * 直接和「本机内核」说话：内核仍以 --server 跑在 127.0.0.1，但界面完全由 Compose 原生绘制，
 * 不再加载 Web 页面。所有端点都来自内核自带的 Web API（见上游 src/ui/web/router.rs）。
 */
object LocalApi {

    val baseUrl: String get() = "http://127.0.0.1:" + PortStore.currentPort

    fun fileUrl(relPath: String): String =
        baseUrl + "/download/" + relPath.split("/").joinToString("/") { enc(it) }

    // ---------------- 基础请求 ----------------

    /**
     * 带重试的请求：App 刚启动时内核可能还没监听端口（释放内置内核要 1~2 秒），
     * 连接被拒时退避重试几次，避免用户一进页面就看到「Connection refused」。
     */
    private suspend fun request(method: String, path: String, body: String? = null): String {
        var lastError: Exception? = null
        for (attempt in 0 until 4) {
            try {
                return requestOnce(method, path, body)
            } catch (e: java.net.ConnectException) {
                lastError = e
            } catch (e: java.net.SocketException) {
                lastError = e
            } catch (e: java.io.IOException) {
                lastError = e
            }
            if (attempt < 3) kotlinx.coroutines.delay(700)
        }
        throw ApiException(-1, "连不上本地下载服务，请到「更多 → 内核日志」确认服务已启动（" + (lastError?.message ?: "连接失败") + "）")
    }

    private suspend fun requestOnce(method: String, path: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                // 若用户开了访问密码，内核也接受请求头密码（见 router.rs 的 auth 中间件）
                PasswordStore.password.value?.let { setRequestProperty("x-tomato-password", it) }
            }
            try {
                if (body != null) {
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    throw ApiException(code, text.ifBlank { "HTTP " + code })
                }
                text
            } finally {
                conn.disconnect()
            }
        }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    // ---------------- 各端点 ----------------

    suspend fun status(): ApiStatus {
        val o = JSONObject(request("GET", "/api/status"))
        return ApiStatus(
            version = o.optString("version"),
            saveDir = o.optString("save_dir"),
            bindAddr = o.optString("bind_addr"),
            locked = o.optBoolean("locked"),
            prewarmInProgress = o.optBoolean("prewarm_in_progress"),
            prewarmError = o.optString("prewarm_error").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    suspend fun search(keyword: String): List<ApiBook> {
        val o = JSONObject(request("GET", "/api/search?q=" + enc(keyword)))
        o.optJSONArray("items")?.let { arr ->
            val list = ArrayList<ApiBook>(arr.length())
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                list.add(
                    ApiBook(
                        bookId = b.optString("book_id"),
                        title = b.optString("title").ifBlank { "(无标题)" },
                        author = b.optString("author")
                    )
                )
            }
            return list
        }
        o.optString("error").takeIf { it.isNotBlank() }?.let { throw ApiException(400, it) }
        return emptyList()
    }

    suspend fun jobs(): List<ApiJob> {
        val o = JSONObject(request("GET", "/api/jobs"))
        val arr = o.optJSONArray("items") ?: return emptyList()
        val list = ArrayList<ApiJob>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { list.add(parseJob(it)) }
        }
        return list
    }

    suspend fun createJob(bookId: String): Long {
        val o = JSONObject(request("POST", "/api/jobs", JSONObject().put("book_id", bookId).toString()))
        return o.optLong("id")
    }

    suspend fun cancelJob(id: Long) {
        request("POST", "/api/jobs/" + id + "/cancel")
    }

    suspend fun deleteJob(id: Long) {
        request("DELETE", "/api/jobs/" + id)
    }

    suspend fun submitFormatChoice(id: Long, value: String?) {
        val body = JSONObject()
        if (value != null) body.put("value", value) else body.put("value", JSONObject.NULL)
        request("POST", "/api/jobs/" + id + "/format", body.toString())
    }

    suspend fun submitBookNameChoice(id: Long, value: String?) {
        val body = JSONObject()
        if (value != null) body.put("value", value) else body.put("value", JSONObject.NULL)
        request("POST", "/api/jobs/" + id + "/book_name", body.toString())
    }

    suspend fun library(path: String, start: Boolean): ApiLibraryPage {
        val o = JSONObject(request("GET", "/api/library?path=" + enc(path) + "&start=" + start))
        val items = ArrayList<ApiLibraryItem>()
        o.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                items.add(
                    ApiLibraryItem(
                        kind = it.optString("kind"),
                        name = it.optString("name"),
                        relPath = it.optString("rel_path"),
                        ext = it.optString("ext"),
                        size = it.optLong("size"),
                        fileCount = if (it.has("file_count")) it.optLong("file_count") else null,
                        modifiedMs = if (it.has("modified_ms")) it.optLong("modified_ms") else null
                    )
                )
            }
        }
        return ApiLibraryPage(
            path = o.optString("path"),
            items = items,
            running = o.optBoolean("running"),
            scanned = o.optInt("scanned"),
            error = o.optString("error").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    suspend fun history(limit: Int = 100, keyword: String? = null): List<ApiHistoryItem> {
        val q = StringBuilder("/api/history?limit=").append(limit)
        if (!keyword.isNullOrBlank()) q.append("&q=").append(enc(keyword))
        val o = JSONObject(request("GET", q.toString()))
        val arr = o.optJSONArray("items") ?: return emptyList()
        val list = ArrayList<ApiHistoryItem>(arr.length())
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            list.add(
                ApiHistoryItem(
                    timestamp = it.optString("timestamp"),
                    bookId = it.optString("book_id"),
                    bookName = it.optString("book_name"),
                    author = it.optString("author"),
                    selectedChapters = it.optInt("selected_chapters"),
                    successChapters = it.optInt("success_chapters"),
                    failedChapters = it.optInt("failed_chapters"),
                    progress = it.optString("progress"),
                    status = it.optString("status")
                )
            )
        }
        return list
    }

    suspend fun updates(start: Boolean): ApiUpdates {
        val o = JSONObject(request("GET", "/api/updates?start=" + start))
        return ApiUpdates(
            running = o.optBoolean("running"),
            scanned = o.optInt("scanned"),
            total = o.optInt("total"),
            saveDir = o.optString("save_dir"),
            updates = parseUpdateList(o.optJSONArray("updates")),
            noUpdates = parseUpdateList(o.optJSONArray("no_updates")),
            error = o.optString("error").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    suspend fun config(): ApiConfig {
        val o = JSONObject(request("GET", "/api/config"))
        return ApiConfig(
            novelFormat = o.optString("novel_format", "epub"),
            bulkFiles = o.optBoolean("bulk_files"),
            enableAudiobook = o.optBoolean("enable_audiobook"),
            audiobookFormat = o.optString("audiobook_format", "mp3"),
            askFormatAfterDownload = o.optBoolean("ask_format_after_download")
        )
    }

    suspend fun saveConfig(
        novelFormat: String?,
        bulkFiles: Boolean?,
        enableAudiobook: Boolean?,
        audiobookFormat: String?,
        askFormatAfterDownload: Boolean?
    ) {
        val body = JSONObject()
        if (novelFormat != null) body.put("novel_format", novelFormat)
        if (bulkFiles != null) body.put("bulk_files", bulkFiles)
        if (enableAudiobook != null) body.put("enable_audiobook", enableAudiobook)
        if (audiobookFormat != null) body.put("audiobook_format", audiobookFormat)
        if (askFormatAfterDownload != null) body.put("ask_format_after_download", askFormatAfterDownload)
        request("POST", "/api/config", body.toString())
    }

    // ---------------- 解析辅助 ----------------

    private fun parseJob(o: JSONObject): ApiJob {
        val progress = o.optJSONObject("progress")?.let { p ->
            ApiJobProgress(
                groupDone = p.optInt("group_done"),
                groupTotal = p.optInt("group_total"),
                savedChapters = p.optInt("saved_chapters"),
                chapterTotal = p.optInt("chapter_total"),
                commentSaved = p.optInt("comment_saved"),
                commentTotal = p.optInt("comment_total"),
                audiobookGenerated = p.optInt("audiobook_generated"),
                audiobookFailed = p.optInt("audiobook_failed")
            )
        }
        return ApiJob(
            id = o.optLong("id"),
            bookId = o.optString("book_id"),
            // 注意：JSONObject.optString 对 JSON null 会返回字面量 "null"，
            // 任务刚创建、内核还没解析出书名时就会出现这个坑，必须清掉。
            title = o.optString("title").cleanJsonText(),
            author = o.optString("author").cleanJsonText(),
            state = o.optString("state", "queued"),
            progress = progress,
            message = o.optString("message").takeIf { it.isNotBlank() && it != "null" },
            formatOptions = parseOptions(o.optJSONArray("format_options")),
            nameOptions = parseOptions(o.optJSONArray("book_name_options"))
        )
    }

    /** 把 JSON null 变成空串（optString 对 null 会给出 "null" 字面量）。 */
    private fun String.cleanJsonText(): String =
        if (isBlank() || this == "null") "" else trim()

    private fun parseOptions(arr: JSONArray?): List<ApiOption> {
        if (arr == null) return emptyList()
        val out = ArrayList<ApiOption>(arr.length())
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            out.add(ApiOption(it.optString("label"), it.optString("value")))
        }
        return out
    }

    private fun parseUpdateList(arr: JSONArray?): List<ApiUpdateItem> {
        if (arr == null) return emptyList()
        val out = ArrayList<ApiUpdateItem>(arr.length())
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            out.add(
                ApiUpdateItem(
                    bookId = it.optString("book_id"),
                    bookName = it.optString("book_name"),
                    folder = it.optString("folder"),
                    localTotal = it.optInt("local_total"),
                    localFailed = it.optInt("local_failed"),
                    remoteTotal = it.optInt("remote_total"),
                    newCount = it.optInt("new_count"),
                    hasUpdate = it.optBoolean("has_update")
                )
            )
        }
        return out
    }
}
