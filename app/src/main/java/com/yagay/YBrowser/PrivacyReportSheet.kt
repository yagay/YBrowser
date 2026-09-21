package com.yagay.YBrowser

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyReportSheet(
    pageUrl: String,
    protection: TrackingProtection,
    events: List<BrowserPrivacyEvent>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val host = runCatching { Uri.parse(pageUrl).host }.getOrNull().orEmpty()
    val categories = events
        .groupingBy { it.category }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
    val domains = events
        .mapNotNull { event ->
            runCatching { Uri.parse(event.url).host?.removePrefix("www.") }.getOrNull()
        }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 30.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "隐私报告",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            host.ifBlank { pageUrl },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        enabled = events.isNotEmpty(),
                        onClick = onClear,
                    ) {
                        Text("清零")
                    }
                }
            }

            item {
                ListItem(
                    headlineContent = {
                        Text("本页已拦截 " + events.size + " 项")
                    },
                    supportingContent = {
                        Text("跟踪保护：" + protection.label)
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.Lock, contentDescription = null)
                    },
                )
            }

            if (categories.isNotEmpty()) {
                item {
                    Text(
                        "类别",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(categories, key = { it.key }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.key) },
                        trailingContent = { Text(entry.value.toString()) },
                    )
                }
                item { HorizontalDivider() }
            }

            if (domains.isEmpty()) {
                item {
                    Text(
                        if (protection == TrackingProtection.OFF) {
                            "跟踪保护已关闭。"
                        } else {
                            "当前页面还没有记录到被拦截的跟踪内容。"
                        },
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    Text(
                        "已拦截域名",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(domains, key = { it.key }) { entry ->
                    ListItem(
                        headlineContent = {
                            Text(
                                entry.key,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = { Text(entry.value.toString()) },
                    )
                }
            }
        }
    }
}
