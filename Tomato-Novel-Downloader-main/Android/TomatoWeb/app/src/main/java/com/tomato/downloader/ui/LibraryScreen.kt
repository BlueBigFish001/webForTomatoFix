package com.tomato.downloader.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.NorthWest
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomato.downloader.core.ApiLibraryItem
import com.tomato.downloader.core.ApiLibraryPage
import com.tomato.downloader.core.DownloadStore
import com.tomato.downloader.core.LocalApi
import com.tomato.downloader.core.ServerManager
import com.tomato.downloader.ui.theme.Green
import com.tomato.downloader.ui.theme.Red
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 书库：直接读内核 /api/library 列目录。
 * 「导出」把文件复制到手机公共目录 Download/Tomato（免去再走网页下载）；
 * 「删除」直接删应用私有目录里的文件。
 */
@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 路径与结果放在单例里：切到别的页签再回来，还停在原来的文件夹
    val path = LibraryMemory.path
    val page = LibraryMemory.page
    var loading by remember { mutableStateOf(page == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<ApiLibraryItem?>(null) }
    var busyItem by remember { mutableStateOf<String?>(null) }
    // 内核真正的书库根目录（config.default_save_dir，取不到时退回工作目录）
    var rootPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        rootPath = runCatching { LocalApi.status().saveDir }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun rootDir(): File {
        val fromStatus = rootPath
        if (fromStatus != null) return File(fromStatus)
        return ServerManager.execDir.value ?: context.codeCacheDir
    }

    fun reload(start: Boolean) {
        scope.launch {
            loading = true
            try {
                // 内核的目录扫描是异步的：先请求一次，若还在扫就轻量轮询到出结果
                var current = LocalApi.library(path, start)
                var guard = 0
                while (current.running && guard < 15) {
                    delay(300)
                    current = LocalApi.library(path, false)
                    guard++
                }
                LibraryMemory.page = current
                error = null
            } catch (e: Exception) {
                error = e.message ?: "读取书库失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(path) { reload(true) }

    fun export(item: ApiLibraryItem) {
        if (busyItem != null) return
        busyItem = item.relPath
        scope.launch {
            try {
                val src = File(rootDir(), item.relPath.replace('/', File.separatorChar))
                if (!src.exists()) throw IllegalStateException("文件不存在：" + src.absolutePath)
                val saved = DownloadStore.saveStream(
                    context,
                    item.name,
                    DownloadStore.mimeFor(item.name)
                ) { out -> src.inputStream().use { it.copyTo(out) } }
                    ?: throw IllegalStateException("写入公共存储失败")
                Toast.makeText(context, "已导出到 " + saved.location, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "导出失败：" + e.message, Toast.LENGTH_LONG).show()
            } finally {
                busyItem = null
            }
        }
    }

    fun doDelete(item: ApiLibraryItem) {
        scope.launch {
            val f = File(rootDir(), item.relPath.replace('/', File.separatorChar))
            val ok = runCatching { f.delete() }.getOrDefault(false)
            Toast.makeText(context, if (ok) "已删除 " + item.name else "删除失败", Toast.LENGTH_SHORT).show()
            pendingDelete = null
            // 必须 start=true：内核会缓存上一次的目录扫描结果，false 会拿回旧快照（删掉的文件还在列表里）
            LibraryMemory.page = null
            reload(true)
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        PageTitle("书库", "下载好的书都在这里，可直接导出到手机 Download/Tomato")

        NativeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (path.isBlank()) "/" else "/" + path,
                    color = Color(0xFFC9D1D9),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                if (path.isNotBlank()) {
                    TextButton(onClick = {
                        LibraryMemory.path = path.substringBeforeLast('/', "")
                    }) {
                        Icon(Icons.Outlined.NorthWest, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("上级", fontSize = 12.sp)
                    }
                }
                IconButton(onClick = { reload(true) }) {
                    Text("刷新", fontSize = 11.sp, color = Color(0xFF8B949E))
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        val err = error
        if (err != null) {
            ErrorBar(err) { reload(true) }
            Spacer(Modifier.height(8.dp))
        }

        val current = page
        when {
            loading && current == null -> LoadingRow()
            current == null -> EmptyHint("暂无内容")
            current.items.isEmpty() -> EmptyHint(
                if (loadedOnceEmptyHint(path)) "这个目录是空的：先去「搜书」下载一本"
                else "空目录"
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 内核会在工作目录里建 logs/，对用户没意义，隐藏掉
                items(
                    current.items.filter { !(it.isDir && it.name == "logs") },
                    key = { it.relPath }
                ) { item ->
                    LibraryRow(
                        item = item,
                        busy = busyItem == item.relPath,
                        onOpen = { LibraryMemory.path = item.relPath },
                        onExport = { export(item) },
                        onDelete = { pendingDelete = item }
                    )
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除文件", fontSize = 16.sp) },
            text = { Text("确定删除「" + item.name + "」？此操作不可撤销。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { doDelete(item) }) {
                    Text("删除", color = Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

private fun loadedOnceEmptyHint(path: String): Boolean = path.isEmpty()

@Composable
private fun LibraryRow(
    item: ApiLibraryItem,
    busy: Boolean,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    NativeCard(
        modifier = if (item.isDir) Modifier.clickable { onOpen() } else Modifier
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (item.isDir) Icons.Outlined.Folder else Icons.Outlined.MenuBook,
                contentDescription = null,
                tint = if (item.isDir) Color(0xFFD29922) else Green,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    color = Color(0xFFE6EDF3),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.isDir) {
                        Tag("文件夹")
                        if (item.fileCount != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(item.fileCount.toString() + " 个文件", color = Color(0xFF8B949E), fontSize = 10.5.sp)
                        }
                    } else {
                        Text(
                            formatSize(item.size) + " · " + formatTime(item.modifiedMs) +
                                (if (item.ext.isNotBlank()) " · " + item.ext.uppercase() else ""),
                            color = Color(0xFF8B949E),
                            fontSize = 10.5.sp
                        )
                    }
                }
            }
            if (!item.isDir) {
                if (busy) {
                    Text("导出中", color = Color(0xFF8B949E), fontSize = 11.sp)
                } else {
                    IconButton(onClick = onExport) {
                        Icon(Icons.Outlined.SaveAlt, contentDescription = "导出", tint = Green, modifier = Modifier.size(19.dp))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除", tint = Red, modifier = Modifier.size(19.dp))
                    }
                }
            }
        }
    }
}
