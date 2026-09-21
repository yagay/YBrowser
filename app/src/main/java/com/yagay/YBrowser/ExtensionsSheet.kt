package com.yagay.YBrowser

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsSheet(
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    var extensions by remember { mutableStateOf<List<BrowserExtensionInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var installUrl by remember { mutableStateOf("") }
    var confirmInstall by remember { mutableStateOf(false) }

    fun refresh() {
        loading = true
        GeckoExtensionManager.list(context) {
            extensions = it
            loading = false
        }
    }

    fun toast(result: BrowserExtensionOperationResult) {
        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
        if (result.success) refresh()
    }

    LaunchedEffect(Unit) {
        refresh()
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
                        "Firefox 扩展",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "仅 GeckoView 内核生效；安装包必须由 Mozilla 签名",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { refresh() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Text("刷新")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = installUrl,
                    onValueChange = { installUrl = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("https://…/extension.xpi") },
                )
                TextButton(
                    enabled = installUrl.isNotBlank(),
                    onClick = { confirmInstall = true },
                ) {
                    Text("安装")
                }
            }

            when {
                loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                extensions.isEmpty() -> {
                    Text(
                        "还没有安装扩展",
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    LazyColumn {
                        items(extensions, key = { it.id }) { extension ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        extension.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    Column {
                                        Text("v" + extension.version)
                                        if (extension.description.isNotBlank()) {
                                            Text(
                                                extension.description,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            TextButton(
                                                onClick = {
                                                    GeckoExtensionManager.update(
                                                        context,
                                                        extension.id,
                                                        ::toast,
                                                    )
                                                },
                                            ) {
                                                Text("更新")
                                            }
                                            extension.optionsPageUrl
                                                ?.takeIf { it.isNotBlank() }
                                                ?.let { optionsUrl ->
                                                    TextButton(
                                                        onClick = {
                                                            onDismiss()
                                                            onOpenUrl(optionsUrl)
                                                        },
                                                    ) {
                                                        Icon(
                                                            Icons.Outlined.OpenInNew,
                                                            contentDescription = null,
                                                        )
                                                        Text("选项")
                                                    }
                                                }
                                            TextButton(
                                                onClick = {
                                                    GeckoExtensionManager.uninstall(
                                                        context,
                                                        extension.id,
                                                        ::toast,
                                                    )
                                                },
                                            ) {
                                                Icon(
                                                    Icons.Outlined.Delete,
                                                    contentDescription = null,
                                                )
                                                Text("卸载")
                                            }
                                        }
                                    }
                                },
                                leadingContent = {
                                    Icon(Icons.Outlined.Extension, contentDescription = null)
                                },
                                trailingContent = {
                                    Column {
                                        Row {
                                            Text(
                                                "启用",
                                                modifier = Modifier.padding(top = 10.dp, end = 6.dp),
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                            Switch(
                                                checked = extension.enabled,
                                                onCheckedChange = { enabled ->
                                                    GeckoExtensionManager.setEnabled(
                                                        context,
                                                        extension.id,
                                                        enabled,
                                                        ::toast,
                                                    )
                                                },
                                            )
                                        }
                                        Row {
                                            Text(
                                                "隐私",
                                                modifier = Modifier.padding(top = 10.dp, end = 6.dp),
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                            Switch(
                                                checked = extension.allowedInPrivateBrowsing,
                                                onCheckedChange = { allowed ->
                                                    GeckoExtensionManager
                                                        .setAllowedInPrivateBrowsing(
                                                            context,
                                                            extension.id,
                                                            allowed,
                                                            ::toast,
                                                        )
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmInstall) {
        AlertDialog(
            onDismissRequest = { confirmInstall = false },
            title = { Text("安装 Firefox 扩展？") },
            text = {
                Text(
                    "GeckoView 会校验 Mozilla 签名。继续后，YBrowser 将授予该扩展安装时声明的必需权限；隐私模式权限默认关闭。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmInstall = false
                        loading = true
                        GeckoExtensionManager.install(
                            context,
                            installUrl,
                        ) { result ->
                            toast(result)
                            if (!result.success) loading = false
                            if (result.success) installUrl = ""
                        }
                    },
                ) {
                    Text("安装")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmInstall = false }) {
                    Text("取消")
                }
            },
        )
    }
}
