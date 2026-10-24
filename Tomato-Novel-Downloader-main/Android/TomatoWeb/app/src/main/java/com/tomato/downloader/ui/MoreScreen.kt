package com.tomato.downloader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.tomato.downloader.core.ApiConfig
import com.tomato.downloader.core.ApiStatus
import com.tomato.downloader.core.LocalApi
import com.tomato.downloader.core.ServerManager
import com.tomato.downloader.ui.theme.Green
import com.tomato.downloader.ui.theme.Red
import kotlinx.coroutines.launch

/** 「更多」里的二级页面。 */
enum class SubPage { History, Updates, Terminal, WebUi, About }

/**
 * 更多：服务状态、下载配置、以及历史 / 追更 / 日志 / 原版网页 的入口。
 * 配置直接 POST /api/config，改完立即对下一次下载生效。
 */
@Composable
fun MoreScreen(
    modifier: Modifier = Modifier,
    onOpenSub: (SubPage) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serverState by ServerManager.state.collectAsState()
    val lanUrl by ServerManager.lanUrl.collectAsState()

    var status by remember { mutableStateOf<ApiStatus?>(null) }
    var config by remember { mutableStateOf<ApiConfig?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { LocalApi.status() }
            .onSuccess { status = it; loadError = null }
            .onFailure { loadError = it.message }
        runCatching { LocalApi.config() }
            .onSuccess { config = it }
    }

    fun save(cfg: ApiConfig) {
        saving = true
        scope.launch {
            try {
                LocalApi.saveConfig(
                    novelFormat = cfg.novelFormat,
                    bulkFiles = cfg.bulkFiles,
                    enableAudiobook = cfg.enableAudiobook,
                    audiobookFormat = cfg.audiobookFormat,
                    askFormatAfterDownload = cfg.askFormatAfterDownload
                )
                config = cfg
                Toast.makeText(context, "配置已保存", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "保存失败：" + e.message, Toast.LENGTH_LONG).show()
            } finally {
                saving = false
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
    ) {
        PageTitle("更多", "服务状态、下载配置与各种入口")

        // —— 服务状态 ——
        NativeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "下载内核",
                    color = Color(0xFFE6EDF3),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                val (stateText, stateColor) = when (serverState) {
                    ServerManager.ServerState.RUNNING -> "运行中" to Green
                    ServerManager.ServerState.INSTALLING -> "启动中" to Color(0xFFD29922)
                    ServerManager.ServerState.ERROR -> "异常" to Red
                    else -> "未运行" to Color(0xFF8B949E)
                }
                androidx.compose.material3.Surface(
                    color = stateColor.copy(alpha = 0.14f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                ) {
                    Text(
                        stateText,
                        color = stateColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            val st = status
            KeyValueRow("版本", "v" + (st?.version ?: "-"))
            KeyValueRow("端口", ServerManager.port.toString())
            KeyValueRow("监听", st?.bindAddr ?: "127.0.0.1:" + ServerManager.port)
            KeyValueRow("保存目录", st?.saveDir ?: "-")
            if (lanUrl.isNotBlank()) {
                KeyValueRow("局域网", lanUrl)
            }
            val le = loadError
            if (le != null) {
                Spacer(Modifier.height(6.dp))
                Text("接口读取失败：" + le, color = Color(0xFFFF8A80), fontSize = 11.sp)
            }
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { LocalApi.status() }.onSuccess { status = it; loadError = null }
                            .onFailure { loadError = it.message }
                    }
                }) { Text("刷新状态", fontSize = 12.sp) }
                if (lanUrl.isNotBlank()) {
                    TextButton(onClick = { copyText(context, lanUrl, "局域网地址已复制") }) {
                        Text("复制局域网地址", fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // —— 下载配置 ——
        NativeCard {
            Text("下载配置", color = Color(0xFFE6EDF3), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            val cfg = config
            if (cfg == null) {
                Text("读取中…", color = Color(0xFF8B949E), fontSize = 12.sp)
            } else {
                Text("导出格式", color = Color(0xFF8B949E), fontSize = 11.5.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("txt", "epub", "pdf", "ask_after_download").forEach { f ->
                        FilterChip(
                            selected = cfg.novelFormat == f,
                            onClick = {
                                config = cfg.copy(
                                    novelFormat = f,
                                    askFormatAfterDownload = f == "ask_after_download"
                                )
                            },
                            label = { Text(if (f == "ask_after_download") "下载后选" else f, fontSize = 11.5.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Green.copy(alpha = 0.18f),
                                selectedLabelColor = Green
                            )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("生成有声书", color = Color(0xFFE6EDF3), fontSize = 13.sp)
                        Text("把小说转成 mp3/wav（耗时较久）", color = Color(0xFF8B949E), fontSize = 10.5.sp)
                    }
                    Switch(
                        checked = cfg.enableAudiobook,
                        onCheckedChange = { config = cfg.copy(enableAudiobook = it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Green)
                    )
                }
                if (cfg.enableAudiobook) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("mp3", "wav").forEach { f ->
                            FilterChip(
                                selected = cfg.audiobookFormat == f,
                                onClick = { config = cfg.copy(audiobookFormat = f) },
                                label = { Text(f, fontSize = 11.5.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Green.copy(alpha = 0.18f),
                                    selectedLabelColor = Green
                                )
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { save(cfg) },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (saving) "保存中…" else "保存配置", fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(10.dp))

        // —— 入口 ——
        NativeCard {
            Text("功能入口", color = Color(0xFFE6EDF3), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            EntryRow(Icons.Outlined.History, "下载历史", "查看每本书下载了多少章") { onOpenSub(SubPage.History) }
            EntryRow(Icons.Outlined.Sync, "追更扫描", "扫描书库里的书有没有新章节") { onOpenSub(SubPage.Updates) }
            EntryRow(Icons.Outlined.Code, "内核日志", "启动 / 停止服务，查看运行日志") { onOpenSub(SubPage.Terminal) }
            EntryRow(Icons.Outlined.OpenInBrowser, "原版网页界面", "打开内核自带的 Web UI（备用）") { onOpenSub(SubPage.WebUi) }
            EntryRow(Icons.Outlined.Info, "关于", "版本、致谢与书库导出") { onOpenSub(SubPage.About) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EntryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFFC9D1D9), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, color = Color(0xFFE6EDF3), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color(0xFF6B7280), fontSize = 10.5.sp)
        }
        Text("›", color = Color(0xFF6B7280), fontSize = 16.sp)
    }
}

private fun copyText(context: Context, text: String, toast: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("tomato", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}
