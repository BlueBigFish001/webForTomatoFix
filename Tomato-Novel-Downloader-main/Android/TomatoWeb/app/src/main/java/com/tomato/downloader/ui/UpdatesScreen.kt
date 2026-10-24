package com.tomato.downloader.ui

import android.widget.Toast
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
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.tomato.downloader.core.ApiUpdateItem
import com.tomato.downloader.core.ApiUpdates
import com.tomato.downloader.core.LocalApi
import com.tomato.downloader.ui.theme.Green
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 追更：调 /api/updates 扫描书库，列出有新章节的书，可直接补下载。 */
@Composable
fun UpdatesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var data by remember { mutableStateOf<ApiUpdates?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyBook by remember { mutableStateOf<String?>(null) }

    fun load(start: Boolean) {
        scope.launch {
            try {
                data = LocalApi.updates(start)
                error = null
            } catch (e: Exception) {
                error = e.message ?: "扫描失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load(true) }

    // 扫描进行中时轻量轮询
    LaunchedEffect(data?.running) {
        while (data?.running == true) {
            delay(1_200)
            runCatching { data = LocalApi.updates(false) }
        }
    }

    fun updateBook(item: ApiUpdateItem) {
        if (busyBook != null) return
        busyBook = item.bookId
        scope.launch {
            try {
                val id = LocalApi.createJob(item.bookId)
                Toast.makeText(context, "已创建任务 #" + id + "：" + item.bookName, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "创建任务失败：" + e.message, Toast.LENGTH_LONG).show()
            } finally {
                busyBook = null
            }
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        PageTitle("追更", "扫描书库，找出有新章节的书")

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { loading = true; load(true) },
                enabled = data?.running != true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (data?.running == true) "扫描中…" else "重新扫描", fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            val d = data
            if (d != null) {
                Text(
                    "已扫 " + d.scanned + "/" + d.total,
                    color = Color(0xFF8B949E),
                    fontSize = 11.5.sp
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        val err = error ?: data?.error
        if (err != null) {
            ErrorBar(err) { load(true) }
            Spacer(Modifier.height(8.dp))
        }

        val d = data
        when {
            loading && d == null -> LoadingRow("正在扫描书库…")
            d == null -> EmptyHint("暂无数据")
            d.updates.isEmpty() && d.noUpdates.isEmpty() -> EmptyHint("书库为空：先去「搜书」下载一本")
            d.updates.isEmpty() -> EmptyHint("全部 " + d.noUpdates.size + " 本都是最新，没有新章节")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(d.updates, key = { it.bookId }) { item ->
                    UpdateRow(item, busy = busyBook == item.bookId) { updateBook(item) }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun UpdateRow(item: ApiUpdateItem, busy: Boolean, onUpdate: () -> Unit) {
    NativeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.bookName.ifBlank { item.folder },
                    color = Color(0xFFE6EDF3),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "本地 " + item.localTotal + " 章 · 远端 " + item.remoteTotal + " 章" +
                        if (item.localFailed > 0) " · 失败 " + item.localFailed else "",
                    color = Color(0xFF8B949E),
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Tag("新 " + item.newCount + " 章", Green)
                Spacer(Modifier.height(4.dp))
                if (busy) {
                    Text("提交中…", color = Color(0xFF8B949E), fontSize = 11.sp)
                } else {
                    OutlinedButton(onClick = onUpdate, modifier = Modifier.height(30.dp)) {
                        Icon(Icons.Outlined.CloudDownload, null, tint = Green, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("更新", fontSize = 11.5.sp)
                    }
                }
            }
        }
    }
}
