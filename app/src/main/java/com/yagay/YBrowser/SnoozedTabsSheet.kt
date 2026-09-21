package com.yagay.YBrowser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
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
fun SnoozedTabsSheet(
    onDismiss: () -> Unit,
    onOpen: (SnoozedTab) -> Unit,
) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(TabSnoozeManager.list(context)) }

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
                    "休眠标签",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(
                    onClick = { items = TabSnoozeManager.list(context) },
                ) {
                    Text("刷新")
                }
            }

            if (items.isEmpty()) {
                Text(
                    "没有休眠标签",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn {
                    items(items, key = { it.id }) { item ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    item.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    "唤醒：" +
                                        DateFormat.getDateTimeInstance(
                                            DateFormat.SHORT,
                                            DateFormat.SHORT,
                                        ).format(Date(item.wakeAt)) +
                                        "\n" +
                                        item.url,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = {
                                Icon(Icons.Outlined.Alarm, contentDescription = null)
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        TabSnoozeManager.remove(context, item.id)
                                        items = TabSnoozeManager.list(context)
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "删除休眠标签",
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
                                TabSnoozeManager.remove(context, item.id)
                                onDismiss()
                                onOpen(item)
                            },
                        )
                    }
                }
            }
        }
    }
}
