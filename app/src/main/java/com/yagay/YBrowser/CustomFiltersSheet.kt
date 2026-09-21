package com.yagay.YBrowser

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomFiltersSheet(
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    var hosts by remember { mutableStateOf(CustomFilterRepository.list(context)) }
    var input by remember { mutableStateOf("") }

    fun refresh() {
        hosts = CustomFilterRepository.list(context)
        onChanged()
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
                Column(Modifier.weight(1f)) {
                    Text(
                        "自定义过滤",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "WebView 网络级阻断；Gecko 页面资源清理。Gecko 的完整网络过滤建议使用 Firefox 扩展。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("域名") },
                    placeholder = { Text("example.com") },
                )
                TextButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        if (CustomFilterRepository.add(context, input)) {
                            input = ""
                            refresh()
                        } else {
                            Toast.makeText(
                                context,
                                "请输入有效域名",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                ) {
                    Text("添加")
                }
            }

            if (hosts.isEmpty()) {
                Text(
                    "当前没有自定义过滤域名",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn {
                    items(hosts, key = { it }) { host ->
                        ListItem(
                            headlineContent = { Text(host) },
                            leadingContent = {
                                Icon(Icons.Outlined.FilterAlt, contentDescription = null)
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        CustomFilterRepository.remove(context, host)
                                        refresh()
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "删除过滤规则",
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
