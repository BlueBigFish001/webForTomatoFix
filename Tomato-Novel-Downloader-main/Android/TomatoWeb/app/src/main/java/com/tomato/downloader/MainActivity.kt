package com.tomato.downloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import android.content.Intent
import androidx.core.content.ContextCompat
import com.tomato.downloader.core.ConsentStore
import com.tomato.downloader.core.ServerManager
import com.tomato.downloader.service.WebServerService
import kotlinx.coroutines.delay
import com.tomato.downloader.ui.AboutScreen
import com.tomato.downloader.ui.DownloadWebScreen
import com.tomato.downloader.ui.FloatingGlassNav
import com.tomato.downloader.ui.HistoryScreen
import com.tomato.downloader.ui.JobsScreen
import com.tomato.downloader.ui.LibraryScreen
import com.tomato.downloader.ui.MoreScreen
import com.tomato.downloader.ui.SearchScreen
import com.tomato.downloader.ui.SubPage
import com.tomato.downloader.ui.Tab
import com.tomato.downloader.ui.TerminalScreen
import com.tomato.downloader.ui.UpdatesScreen
import com.tomato.downloader.ui.WebServerScreen
import androidx.compose.material3.MaterialTheme
import com.tomato.downloader.ui.theme.Green
import com.tomato.downloader.ui.theme.TerminalBg
import com.tomato.downloader.ui.theme.TerminalSurface
import com.tomato.downloader.ui.theme.TomatoWebTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 确保 IME（软键盘）弹出时内容区域真的被 resize，
        // 并让 Compose 能消费 WindowInsets(ime)。否则 Compose 页面底部输入框会被键盘盖住。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContent {
            // 同步：要求 window 立即处理 IME inset（部分 ROM 默认行为与 adjustResize 冲突）
            val view = LocalView.current
            androidx.compose.runtime.DisposableEffect(view) {
                ViewCompat.requestApplyInsets(view)
                onDispose {}
            }
            TomatoWebTheme {
                App()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App() {
    val context = LocalContext.current
    val activity = context as? android.app.Activity

    // Android 9 及以下：导出书籍到 Download/Tomato 需要写外部存储权限。
    // Android 10+ 走 MediaStore.Downloads（免权限），这里请求是无害的空操作。
    val storagePermission = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* 用户拒绝时导出会给出失败提示，不阻塞其它功能 */ }
    LaunchedEffect(Unit) {
        // Android 13 起这两个权限已被系统忽略，只在 6..12L 上申请。
        if (android.os.Build.VERSION.SDK_INT in 23..32) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // —— 知情协议对话框：未同意时强制弹出，倒计时 5s 后才能点"同意"；"拒绝"直接退出 ——
    val consentAgreed by ConsentStore.agreed.collectAsState()
    var showDialog by remember { mutableStateOf(!consentAgreed) }
    // 倒计时：从 5 递减到 0，到 0 之前"同意"按钮禁用
    var agreeCountdown by remember { mutableIntStateOf(5) }
    LaunchedEffect(Unit) {
        while (agreeCountdown > 0) {
            delay(1000L)
            agreeCountdown -= 1
        }
    }
    // 状态同步：从磁盘恢复后若用户后来又清空（极端情况）也保持正确
    LaunchedEffect(consentAgreed) {
        if (consentAgreed) showDialog = false
    }

    if (showDialog && !consentAgreed) {
        AlertDialog(
            containerColor = TerminalSurface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            onDismissRequest = { /* 禁止点击外部/返回关闭 */ },
            title = {
                androidx.compose.material3.Text(
                    ConsentStore.DIALOG_TITLE,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                // 协议正文较长，支持滚动 + 长按选中复制
                SelectionContainer {
                    androidx.compose.material3.Text(
                        text = ConsentStore.DIALOG_BODY,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .verticalScroll(rememberScrollState()),
                        fontSize = 12.5.sp,
                        lineHeight = 20.sp
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        // 拒绝：直接关闭应用
                        activity?.finishAffinity() ?: activity?.finish()
                        Runtime.getRuntime().exit(0)
                    }
                ) {
                    androidx.compose.material3.Text("拒绝并退出", color = Color(0xFFFF6B6B))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = agreeCountdown <= 0,
                    onClick = {
                        ConsentStore.markAgreed(context)
                        showDialog = false
                    }
                ) {
                    androidx.compose.material3.Text(
                        text = if (agreeCountdown > 0) "同意并继续 (${agreeCountdown}s)" else "同意并继续",
                        color = if (agreeCountdown > 0) Color(0xFF8B949E) else Green,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        )
    }

    // 默认进入「搜书」页：界面全部原生，不再加载网页
    var tab by remember { mutableStateOf(Tab.Search) }
    // 「更多」里的二级页面（历史 / 追更 / 日志 / 原版网页 / 关于）
    var subPage by remember { mutableStateOf<SubPage?>(null) }

    BackHandler(enabled = subPage != null) { subPage = null }

    // 原生界面依赖内核的本地 HTTP 服务：进 App 就释放内置内核并拉起前台服务
    LaunchedEffect(Unit) {
        if (!ServerManager.isInstalled(context)) {
            ServerManager.install(context, force = false)
        }
        if (ServerManager.state.value != ServerManager.ServerState.RUNNING) {
            val intent = Intent(context, WebServerService::class.java)
                .setAction(WebServerService.ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    // 键盘弹出时的底部留白。
    // 注意：Scaffold 已经用了 imePadding()，键盘弹起时内容会被顶上去；
    // 如果这里再叠加一遍导航栏高度，内容就会被扣两次、塌成一条缝，
    // 露出大片深色背景（看起来就像「黑色遮罩挡住界面」）。
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navReserve = 92.dp
    val bottomInset = if (imeBottom > 0.dp) 0.dp else navReserve

    // 顶层 Box：内容铺满全屏，底栏悬浮叠加在底部（液态玻璃效果 + 浮起）
    Box(Modifier.fillMaxSize().background(TerminalBg)) {

        // 内容：TopAppBar + 页面内容（.imePadding 让键盘弹出时整体往上顶）
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { androidx.compose.material3.Text("Web For Tomato") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color(0xFFE6EDF3)
                    ),
                    windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                )
            }
        ) { innerPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(bottom = bottomInset)
            ) {
                val sub = subPage
                if (sub != null) {
                    SubPageHost(sub) { subPage = null }
                } else {
                    when (tab) {
                        Tab.Search -> SearchScreen(Modifier.fillMaxSize()) { tab = Tab.Jobs }
                        Tab.Jobs -> JobsScreen(Modifier.fillMaxSize())
                        Tab.Library -> LibraryScreen(Modifier.fillMaxSize())
                        Tab.More -> MoreScreen(Modifier.fillMaxSize()) { subPage = it }
                    }
                }
            }
        }

        // 悬浮液态玻璃导航栏（叠加在内容之上，自动适配系统导航条高度）
        FloatingGlassNav(
            current = tab,
            onSelect = { tab = it },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        )
    }
}
/**
 * 二级页面外壳：顶部一条返回栏，下面放原有的整页组件。
 * 这些页面原本是底部 Tab，现在收进「更多」，功能不变。
 */
@Composable
private fun SubPageHost(page: SubPage, onBack: () -> Unit) {
    val title = when (page) {
        SubPage.History -> "下载历史"
        SubPage.Updates -> "追更"
        SubPage.Terminal -> "内核日志"
        SubPage.WebUi -> "原版网页界面"
        SubPage.About -> "关于"
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = Color(0xFFC9D1D9))
            }
            androidx.compose.material3.Text(
                title,
                color = Color(0xFFE6EDF3),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (page) {
                SubPage.History -> HistoryScreen(Modifier.fillMaxSize())
                SubPage.Updates -> UpdatesScreen(Modifier.fillMaxSize())
                SubPage.Terminal -> TerminalScreen(Modifier.fillMaxSize())
                SubPage.WebUi -> DownloadWebScreen(Modifier.fillMaxSize())
                SubPage.About -> AboutScreen(Modifier.fillMaxSize())
            }
        }
    }
}
