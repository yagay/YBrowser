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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderLibrarySheet(
    onDismiss: () -> Unit,
    onOpen: (ReaderDocument) -> Unit,
) {
    val context = LocalContext.current
    var savedDocuments by remember {
        mutableStateOf(ReaderOfflineRepository.list(context))
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
                    onClick = {
                        savedDocuments = ReaderOfflineRepository.list(context)
                    },
                ) {
                    Text("刷新")
                }
            }

            if (savedDocuments.isEmpty()) {
                Text(
                    "还没有离线文章。打开网页的阅读模式后，点击书签按钮即可保存。",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
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
                                    onClick = {
                                        ReaderOfflineRepository.remove(
                                            context,
                                            item.document.sourceUrl,
                                        )
                                        savedDocuments = ReaderOfflineRepository.list(context)
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "删除离线文章",
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
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
