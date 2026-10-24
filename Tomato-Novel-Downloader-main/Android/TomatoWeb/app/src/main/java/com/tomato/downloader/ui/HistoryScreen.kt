package com.tomato.downloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomato.downloader.core.ApiHistoryItem
import com.tomato.downloader.core.LocalApi
import com.tomato.downloader.ui.theme.Green
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<ApiHistoryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var keyword by remember { mutableStateOf("") }

    fun load() {
        loading = true
        scope.launch {
            try {
                items = LocalApi.history(200, keyword.takeIf { it.isNotBlank() })
                error = null
            } catch (e: Exception) {
                error = e.message ?: "读取历史失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        PageTitle("下载历史", "内核记录在本地，共 " + items.size + " 条")
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("按书名 / 作者 / Book ID 过滤", fontSize = 12.5.sp) },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Green,
                unfocusedBorderColor = Color(0xFF30363D),
                focusedContainerColor = Color(0xFF161B22),
                unfocusedContainerColor = Color(0xFF161B22),
                focusedTextColor = Color(0xFFE6EDF3),
                unfocusedTextColor = Color(0xFFE6EDF3)
            ),
            trailingIcon = {
                Text("过滤", color = Green, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
            }
        )
        Spacer(Modifier.height(8.dp))

        val err = error
        if (err != null) {
            ErrorBar(err) { load() }
            Spacer(Modifier.height(8.dp))
        }

        when {
            loading -> LoadingRow()
            items.isEmpty() -> EmptyHint("暂无下载记录")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items) { h ->
                    NativeCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    h.bookName.ifBlank { h.bookId },
                                    color = Color(0xFFE6EDF3),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                Text(
                                    (if (h.author.isBlank()) "" else h.author + " · ") + h.timestamp.take(19).replace('T', ' '),
                                    color = Color(0xFF8B949E),
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(h.progress, color = Color(0xFF6B7280), fontSize = 10.5.sp)
                            }
                            Spacer(Modifier.width(8.dp))
                            JobStateBadge(
                                when {
                                    h.failedChapters > 0 && h.successChapters == 0 -> "failed"
                                    h.successChapters < h.selectedChapters -> "canceled"
                                    else -> "done"
                                }
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}
