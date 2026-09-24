package com.yagay.YBrowser

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomFiltersSheet(
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hosts by remember {
        mutableStateOf(CustomFilterRepository.listManual(context))
    }
    var subscriptions by remember {
        mutableStateOf(CustomFilterRepository.subscriptions(context))
    }
    var input by remember { mutableStateOf("") }
    var subscriptionName by remember { mutableStateOf("") }
    var subscriptionUrl by remember { mutableStateOf("") }
    var updating by remember { mutableStateOf(false) }

    fun refresh() {
        hosts = CustomFilterRepository.listManual(context)
        subscriptions = CustomFilterRepository.subscriptions(context)
        onChanged()
    }

    fun updateAll() {
        if (updating) return
        scope.launch {
            updating = true
            val result = withContext(Dispatchers.IO) {
                CustomFilterRepository.updateAll(context)
            }
            updating = false
            refresh()
            Toast.makeText(
                context,
                result.message,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

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
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "自定义过滤",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "支持手动域名与 EasyList/ABP 的 ||domain^ 网络规则。WebView 在请求层阻断；Gecko 同步用于页面资源清理，完整高级规则仍可叠加 uBlock Origin。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        enabled = !updating && subscriptions.any { it.enabled },
                        onClick = ::updateAll,
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "更新过滤订阅",
                        )
                    }
                }
            }

            item {
                Text(
                    "过滤订阅",
                    modifier = Modifier.padding(
                        horizontal = 20.dp,
                        vertical = 6.dp,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (subscriptions.isEmpty()) {
                item {
                    Text(
                        "还没有订阅。可一键加入 EasyList + EasyPrivacy，或添加任意兼容列表。",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(subscriptions, key = { it.id }) { item ->
                    ListItem(
                        headlineContent = {
                            Text(
                                item.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                item.ruleCount.toString() +
                                    " 条网络规则 · " +
                                    if (item.updatedAt > 0L) "已更新" else "尚未更新",
                                maxLines = 1,
                            )
                        },
                        leadingContent = {
                            Switch(
                                checked = item.enabled,
                                onCheckedChange = { enabled ->
                                    CustomFilterRepository.setSubscriptionEnabled(
                                        context,
                                        item.id,
                                        enabled,
                                    )
                                    refresh()
                                },
                            )
                        },
                        trailingContent = {
                            IconButton(
                                onClick = {
                                    CustomFilterRepository.removeSubscription(
                                        context,
                                        item.id,
                                    )
                                    refresh()
                                },
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "删除订阅",
                                )
                            }
                        },
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    TextButton(
                        onClick = {
                            CustomFilterRepository.addEasyListPresets(context)
                            refresh()
                            updateAll()
                        },
                        enabled = !updating,
                    ) {
                        Text("加入 EasyList + EasyPrivacy")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = ::updateAll,
                        enabled = !updating && subscriptions.any { it.enabled },
                    ) {
                        Text(if (updating) "更新中…" else "全部更新")
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = subscriptionName,
                    onValueChange = { subscriptionName = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 3.dp),
                    singleLine = true,
                    label = { Text("订阅名称（可选）") },
                )
                OutlinedTextField(
                    value = subscriptionUrl,
                    onValueChange = { subscriptionUrl = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 3.dp),
                    singleLine = true,
                    label = { Text("订阅地址") },
                    placeholder = { Text("https://example.com/filter.txt") },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = subscriptionUrl.isNotBlank(),
                        onClick = {
                            val item = CustomFilterRepository.addSubscription(
                                context,
                                subscriptionName,
                                subscriptionUrl,
                            )
                            if (item == null) {
                                Toast.makeText(
                                    context,
                                    "订阅地址无效或已达到数量上限",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                subscriptionName = ""
                                subscriptionUrl = ""
                                refresh()
                                scope.launch {
                                    updating = true
                                    val result = withContext(Dispatchers.IO) {
                                        CustomFilterRepository.updateSubscription(
                                            context,
                                            item.id,
                                        )
                                    }
                                    updating = false
                                    refresh()
                                    Toast.makeText(
                                        context,
                                        result.message,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                    ) {
                        Text("添加并更新")
                    }
                }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "手动域名",
                    modifier = Modifier.padding(
                        horizontal = 20.dp,
                        vertical = 4.dp,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            item {
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
            }

            if (hosts.isEmpty()) {
                item {
                    Text(
                        "当前没有手动过滤域名",
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
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
