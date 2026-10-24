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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomato.downloader.core.ApiBook
import com.tomato.downloader.core.LocalApi
import com.tomato.downloader.ui.theme.Green
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 搜书：输入关键词 → 调内核 /api/search → 列表点「下载」→ 创建下载任务。
 * 全程不走 Web 页面。
 */
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    onJobCreated: () -> Unit
) {
    val context = LocalContext.current

    // 状态放在单例里：切到别的页签再回来，关键词和搜索结果都还在
    var keyword by remember { mutableStateOf(SearchMemory.keyword) }
    val searching = SearchMemory.searching
    val error = SearchMemory.error
    val results = SearchMemory.results
    val searched = SearchMemory.searched
    val creatingId = SearchMemory.creatingBookId

    fun doSearch() {
        SearchMemory.keyword = keyword
        SearchMemory.search()
    }

    fun createJob(book: ApiBook) {
        SearchMemory.createJob(
            book,
            onCreated = { id ->
                Toast.makeText(context, "已创建任务 #" + id + "：" + book.title, Toast.LENGTH_SHORT).show()
                onJobCreated()
            },
            onError = { msg ->
                Toast.makeText(
                    context,
                    when {
                        msg.contains("429") -> "已有任务在下载，请等它结束"
                        msg.contains("400") -> "Book ID 无法识别"
                        else -> "创建任务失败：" + msg
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    Column(modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        PageTitle("搜书", "输入书名 / Book ID / 链接，直接创建下载任务")

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it; SearchMemory.keyword = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("书名、作者或 Book ID", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Color(0xFF8B949E)) },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Green,
                    unfocusedBorderColor = Color(0xFF30363D),
                    focusedContainerColor = Color(0xFF161B22),
                    unfocusedContainerColor = Color(0xFF161B22),
                    focusedTextColor = Color(0xFFE6EDF3),
                    unfocusedTextColor = Color(0xFFE6EDF3)
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch() })
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { doSearch() },
                enabled = !searching && keyword.isNotBlank(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("搜索", fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(10.dp))

        val err = error
        if (err != null) {
            ErrorBar(err) { doSearch() }
            Spacer(Modifier.height(8.dp))
        }

        when {
            searching -> LoadingRow("正在搜索…")
            !searched -> EmptyHint("还没有搜索：输入书名后点「搜索」")
            results.isEmpty() -> EmptyHint("没有找到相关书籍，换个关键词试试")
            else -> {
                Text(
                    "共 " + results.size + " 条结果",
                    color = Color(0xFF8B949E),
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results, key = { it.bookId }) { book ->
                        BookRow(
                            book = book,
                            busy = creatingId == book.bookId,
                            onDownload = { createJob(book) }
                        )
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }
}

@Composable
private fun BookRow(book: ApiBook, busy: Boolean, onDownload: () -> Unit) {
    NativeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    color = Color(0xFFE6EDF3),
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (book.author.isNotBlank()) {
                        Text(book.author, color = Color(0xFF8B949E), fontSize = 11.5.sp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        book.bookId,
                        color = Color(0xFF6B7280),
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Green, strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = "下载", tint = Green)
                }
            }
        }
    }
}
