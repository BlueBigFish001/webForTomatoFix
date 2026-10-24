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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.tomato.downloader.core.ApiJob
import com.tomato.downloader.core.ApiOption
import com.tomato.downloader.core.LocalApi
import com.tomato.downloader.ui.theme.Green
import com.tomato.downloader.ui.theme.Red
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 任务：直接轮询内核 /api/jobs，原生渲染进度与操作按钮。
 * 如果内核在等「格式 / 书名」选择，会用原生对话框问，不依赖网页。
 */
@Composable
fun JobsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var jobs by remember { mutableStateOf<List<ApiJob>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var pendingFormat by remember { mutableStateOf<ApiJob?>(null) }
    var pendingName by remember { mutableStateOf<ApiJob?>(null) }
    val handled = remember { mutableSetOf<Long>() }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val list = LocalApi.jobs()
                jobs = list
                error = null
                list.firstOrNull { it.formatOptions.isNotEmpty() && !handled.contains(it.id) }
                    ?.let { pendingFormat = it }
                list.firstOrNull { it.nameOptions.isNotEmpty() && !handled.contains(it.id) }
                    ?.let { pendingName = it }
            } catch (e: Exception) {
                error = e.message ?: "读取任务失败"
            }
            loading = false
            delay(1_500)
        }
    }

    fun cancel(job: ApiJob) {
        scope.launch {
            runCatching { LocalApi.cancelJob(job.id) }
                .onFailure { Toast.makeText(context, "取消失败：" + it.message, Toast.LENGTH_SHORT).show() }
        }
    }

    fun remove(job: ApiJob) {
        scope.launch {
            runCatching { LocalApi.deleteJob(job.id) }
                .onFailure { Toast.makeText(context, "删除失败：" + it.message, Toast.LENGTH_SHORT).show() }
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        PageTitle("任务", "同一时间只跑一个下载任务")

        val err = error
        if (err != null) {
            ErrorBar(err)
            Spacer(Modifier.height(8.dp))
        }

        when {
            loading -> LoadingRow()
            jobs.isEmpty() -> EmptyHint("还没有任务：去「搜书」页下载一本")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(jobs, key = { it.id }) { job ->
                    JobCard(
                        job = job,
                        onCancel = { cancel(job) },
                        onDelete = { remove(job) }
                    )
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }

    pendingFormat?.let { job ->
        ChoiceDialog(
            title = "选择导出格式",
            message = job.title.ifBlank { job.bookId },
            options = job.formatOptions,
            allowCancel = true,
            onDismiss = {
                handled.add(job.id)
                pendingFormat = null
                scope.launch { runCatching { LocalApi.submitFormatChoice(job.id, null) } }
            }
        ) { opt ->
            handled.add(job.id)
            pendingFormat = null
            scope.launch {
                runCatching { LocalApi.submitFormatChoice(job.id, opt.value) }
                    .onFailure { Toast.makeText(context, "提交失败：" + it.message, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    pendingName?.let { job ->
        ChoiceDialog(
            title = "选择书名",
            message = "内核解析出多个书名，选一个用于保存",
            options = job.nameOptions,
            allowCancel = false,
            onDismiss = {},
        ) { opt ->
            handled.add(job.id)
            pendingName = null
            scope.launch {
                runCatching { LocalApi.submitBookNameChoice(job.id, opt.value) }
                    .onFailure { Toast.makeText(context, "提交失败：" + it.message, Toast.LENGTH_SHORT).show() }
            }
        }
    }
}

@Composable
private fun JobCard(job: ApiJob, onCancel: () -> Unit, onDelete: () -> Unit) {
    NativeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // 任务刚创建时内核还没解析出书名：先给个占位，别显示 null 或一长串 ID
                Text(
                    job.title.takeIf { it.isNotBlank() } ?: "解析书名中…",
                    color = Color(0xFFE6EDF3),
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
                if (job.author.isNotBlank()) {
                    Text(job.author, color = Color(0xFF8B949E), fontSize = 11.5.sp)
                } else {
                    Text(
                        job.bookId,
                        color = Color(0xFF6B7280),
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            JobStateBadge(job.state)
        }

        val p = job.progress
        if (job.isActive && p != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { p.percent / 100f },
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = Green,
                trackColor = Color(0xFF21262D)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "章节 " + p.savedChapters + " / " + p.chapterTotal +
                    "（" + p.percent + "%）" +
                    if (p.groupTotal > 0) "  分组 " + p.groupDone + "/" + p.groupTotal else "",
                color = Color(0xFF8B949E),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        } else if (job.isActive) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = Green,
                trackColor = Color(0xFF21262D)
            )
        }

        val msg = job.message
        if (!msg.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                msg,
                color = if (job.state == "failed") Color(0xFFFF8A80) else Color(0xFF8B949E),
                fontSize = 11.sp
            )
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#" + job.id + " · " + job.bookId,
                color = Color(0xFF6B7280),
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            if (job.isActive) {
                TextButton(onClick = onCancel) {
                    Icon(Icons.Outlined.Close, null, tint = Color(0xFFD29922), modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("取消", color = Color(0xFFD29922), fontSize = 12.sp)
                }
            } else {
                TextButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, null, tint = Red, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("删除", color = Red, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    message: String,
    options: List<ApiOption>,
    allowCancel: Boolean,
    onDismiss: () -> Unit,
    onPick: (ApiOption) -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (allowCancel) onDismiss() },
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column {
                Text(message, color = Color(0xFF8B949E), fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                options.forEach { opt ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(opt) }
                            .padding(vertical = 10.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(opt.label, color = Color(0xFFE6EDF3), fontSize = 13.5.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (allowCancel) {
                TextButton(onClick = onDismiss) { Text("跳过") }
            }
        }
    )
}
