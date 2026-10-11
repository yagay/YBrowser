package com.yagay.YBrowser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderLibrarySheet(
    onDismiss: () -> Unit,
    onOpen: (ReaderDocument) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var savedDocuments by remember {
        mutableStateOf(emptyList<SavedReaderDocument>())
    }
    var loading by remember { mutableStateOf(true) }
    var deletingUrl by remember { mutableStateOf<String?>(null) }
    var refreshGeneration by remember { mutableStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(context, refreshGeneration) {
        loading = true
        try {
            savedDocuments = withContext(Dispatchers.IO) {
                ReaderOfflineRepository.list(context)
            }
            loadFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        } finally {
            loading = false
        }
    }

    fun refresh() {
        refreshGeneration++
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 30.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    "离线阅读",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(
                    onClick = { refresh() },
                ) {
                    Text("刷新")
                }
            }

            if (loading && savedDocuments.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            } else if (loadFailed && savedDocuments.isEmpty()) {
                Text(
                    "读取离线文章失败，请点击刷新重试。",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (savedDocuments.isEmpty()) {
                Text(
                    "还没有离线文章，可从网页菜单直接选择「保存当前网页离线阅读」。",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column {
                    if (loadFailed) {
                        Text(
                            "刷新失败，以下显示上次成功读取的文章。",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    LazyColumn {
                    items(
                        savedDocuments,
                        key = { it.document.sourceUrl },
                    ) { item ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    item.document.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    buildString {
                                        if (item.document.siteName.isNotBlank()) {
                                            append(item.document.siteName)
                                            append(" · ")
                                        }
                                        append(
                                            DateFormat.getDateTimeInstance(
                                                DateFormat.SHORT,
                                                DateFormat.SHORT,
                                            ).format(Date(item.savedAt)),
                                        )
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = {
                                Icon(Icons.Outlined.Article, contentDescription = null)
                            },
                            trailingContent = {
                                IconButton(
                                    enabled = deletingUrl == null,
                                    onClick = {
                                        val url = item.document.sourceUrl
                                        deletingUrl = url
                                        scope.launch {
                                            try {
                                                val removed = withContext(Dispatchers.IO) {
                                                    ReaderOfflineRepository.remove(context, url)
                                                }
                                                if (removed) refresh()
                                                else android.widget.Toast.makeText(
                                                    context, "删除失败，请重试",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            } finally {
                                                deletingUrl = null
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "删除离线文章",
                                    )
                                }
                            },
                            modifier = Modifier.clickable(enabled = deletingUrl == null) {
                                onDismiss()
                                onOpen(item.document)
                            },
                        )
                    }
                    }
                }
            }
        }
    }
}
