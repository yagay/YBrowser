package com.yagay.YBrowser

import android.content.Intent
import android.content.pm.ShortcutManager
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InstalledWebAppsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(InstalledWebAppRegistry.list(context)) }
    var removeTarget by remember { mutableStateOf<InstalledWebApp?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("网页应用", style = MaterialTheme.typography.headlineSmall)
            Text(
                "管理经桌面确认的网页应用和快捷方式。独立窗口离开网站作用域时会切回正常浏览器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (apps.isEmpty()) {
                Text("还没有已确认的网页应用", modifier = Modifier.padding(vertical = 24.dp))
            }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(bottom = 26.dp)) {
            items(apps, key = { it.id }) { app ->
                ListItem(
                    leadingContent = {
                        Icon(Icons.Outlined.Home, contentDescription = null)
                    },
                    headlineContent = { Text(app.name) },
                    supportingContent = {
                        Column {
                            Text(if (app.isPwa) "Manifest 网页应用" else "普通网页快捷方式")
                            Text(app.startUrl, maxLines = 1)
                        }
                    },
                    trailingContent = {
                        Column {
                            TextButton(onClick = {
                                context.startActivity(
                                    Intent(context, WebAppActivity::class.java).apply {
                                        data = Uri.parse(app.startUrl)
                                        putExtra(WebAppActivity.EXTRA_URL, app.startUrl)
                                        putExtra(WebAppActivity.EXTRA_APP_ID, app.id)
                                    },
                                )
                                onDismiss()
                            }) { Text("打开") }
                            TextButton(onClick = { removeTarget = app }) {
                                Text("移除")
                            }
                        }
                    },
                )
            }
        }
    }
    removeTarget?.let { app ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("移除 " + app.name + "？") },
            text = {
                Text("将删除 YBrowser 的应用记录并尝试停用对应桌面快捷方式。" +
                    "部分桌面仍需手动删除图标；不会删除网页数据或其他标签。")
            },
            confirmButton = {
                TextButton(onClick = {
                    val manager = context.getSystemService(ShortcutManager::class.java)
                    runCatching {
                        manager?.disableShortcuts(listOf(app.id), "网页应用已从 YBrowser 移除")
                    }
                    InstalledWebAppRegistry.remove(context, app.id)
                    apps = InstalledWebAppRegistry.list(context)
                    removeTarget = null
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("取消") }
            },
        )
    }
}
