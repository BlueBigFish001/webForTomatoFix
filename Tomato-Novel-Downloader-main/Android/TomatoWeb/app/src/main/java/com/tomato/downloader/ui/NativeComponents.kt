package com.tomato.downloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomato.downloader.ui.theme.Green
import com.tomato.downloader.ui.theme.Red
import com.tomato.downloader.ui.theme.TerminalBorder
import com.tomato.downloader.ui.theme.TerminalSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 统一的卡片容器（和「关于」页风格一致）。 */
@Composable
fun NativeCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = TerminalSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, TerminalBorder)
    ) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
fun PageTitle(text: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(
            text,
            color = Color(0xFFE6EDF3),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = Color(0xFF8B949E), fontSize = 11.5.sp)
        }
    }
}

/** 状态徽章：queued / running / done / failed / canceled。 */
@Composable
fun JobStateBadge(state: String) {
    val (label, color) = when (state) {
        "queued" -> "排队中" to Color(0xFF8B949E)
        "running" -> "下载中" to Green
        "done" -> "已完成" to Green
        "failed" -> "失败" to Red
        "canceled" -> "已取消" to Color(0xFFD29922)
        else -> state to Color(0xFF8B949E)
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color(0xFF6B7280), fontSize = 12.5.sp)
    }
}

@Composable
fun LoadingRow(text: String = "加载中…") {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Green, strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color(0xFF8B949E), fontSize = 12.5.sp)
    }
}

@Composable
fun ErrorBar(message: String, onRetry: (() -> Unit)? = null) {
    Surface(
        color = Color(0x22FF6B6B),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0x55FF6B6B)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                message,
                color = Color(0xFFFF8A80),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            if (onRetry != null) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onRetry, modifier = Modifier.height(30.dp)) {
                    Text("重试", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            key,
            color = Color(0xFF8B949E),
            fontSize = 12.5.sp,
            modifier = Modifier.width(76.dp)
        )
        Text(
            value,
            color = Color(0xFFC9D1D9),
            fontSize = 12.5.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun Tag(text: String, color: Color = Color(0xFF8B949E)) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(5.dp)) {
        Text(
            text,
            color = color,
            fontSize = 10.5.sp,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "-"
    bytes < 1024 -> bytes.toString() + " B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

fun formatTime(ms: Long?): String {
    if (ms == null || ms <= 0) return "-"
    return runCatching {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }.getOrDefault("-")
}
