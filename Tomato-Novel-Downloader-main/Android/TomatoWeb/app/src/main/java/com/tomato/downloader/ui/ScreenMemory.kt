package com.tomato.downloader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tomato.downloader.core.ApiBook
import com.tomato.downloader.core.ApiLibraryPage
import com.tomato.downloader.core.LocalApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 页面级记忆：切换底部页签时 Compose 会销毁旧页面，局部 remember 会跟着丢。
 * 搜书结果、书库路径这类「用户正在做的事」放进单例，切回来还在。
 */
object SearchMemory {

    var keyword by mutableStateOf("")
    var results by mutableStateOf<List<ApiBook>>(emptyList())
    var searched by mutableStateOf(false)
    var searching by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var creatingBookId by mutableStateOf<String?>(null)

    // 搜索不绑定页面生命周期：切到别的页签也继续跑
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun search() {
        val q = keyword.trim()
        if (q.isEmpty() || searching) return
        searching = true
        error = null
        scope.launch {
            try {
                results = LocalApi.search(q)
                searched = true
            } catch (e: Exception) {
                error = e.message ?: "搜索失败"
                results = emptyList()
                searched = true
            } finally {
                searching = false
            }
        }
    }

    fun createJob(book: ApiBook, onCreated: (Long) -> Unit, onError: (String) -> Unit) {
        if (creatingBookId != null) return
        creatingBookId = book.bookId
        scope.launch {
            try {
                val id = LocalApi.createJob(book.bookId)
                onCreated(id)
            } catch (e: Exception) {
                onError(e.message ?: "创建任务失败")
            } finally {
                creatingBookId = null
            }
        }
    }
}

/** 书库页记住当前目录，切页签回来不会跳回根目录。 */
object LibraryMemory {
    var path by mutableStateOf("")
    var page by mutableStateOf<ApiLibraryPage?>(null)
}
