package com.yagay.YBrowser

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
fun ProfilesSheet(
    activeProfileId: String,
    onSwitch: (BrowserProfile) -> Unit,
    onDelete: (BrowserProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf(BrowserProfileRepository.list(context)) }
    var newName by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<BrowserProfile?>(null) }

    fun refresh() {
        profiles = BrowserProfileRepository.list(context)
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
                        "浏览器 Profile",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (BrowserProfileStorage.webViewMultiProfileSupported()) {
                            "GeckoView 与 System WebView 都支持独立 Cookie / localStorage"
                        } else {
                            "GeckoView 完整隔离；当前 System WebView 不支持 MULTI_PROFILE，将使用默认 WebView Profile"
                        },
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
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("新 Profile 名称") },
                )
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        val created = BrowserProfileRepository.create(context, newName)
                        if (created == null) {
                            Toast.makeText(
                                context,
                                "名称为空或已存在",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            newName = ""
                            refresh()
                        }
                    },
                ) {
                    Text("新增")
                }
            }

            LazyColumn {
                items(profiles, key = { it.id }) { profile ->
                    ListItem(
                        headlineContent = { Text(profile.name) },
                        supportingContent = {
                            Text(
                                when {
                                    profile.id == DEFAULT_BROWSER_PROFILE_ID ->
                                        "兼容旧数据的默认 Profile"
                                    BrowserProfileStorage.webViewMultiProfileSupported() ->
                                        "Gecko + WebView 独立存储"
                                    else -> "Gecko 独立存储；WebView 回退默认 Profile"
                                },
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Outlined.AccountCircle, contentDescription = null)
                        },
                        trailingContent = {
                            Row {
                                RadioButton(
                                    selected = activeProfileId == profile.id,
                                    onClick = {
                                        if (activeProfileId != profile.id) onSwitch(profile)
                                    },
                                )
                                if (profile.id != DEFAULT_BROWSER_PROFILE_ID) {
                                    IconButton(onClick = { editing = profile }) {
                                        Icon(
                                            Icons.Outlined.Edit,
                                            contentDescription = "重命名 Profile",
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            if (activeProfileId == profile.id) {
                                                Toast.makeText(
                                                    context,
                                                    "请先切换到其他 Profile",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            } else {
                                                onDelete(profile)
                                                refresh()
                                            }
                                        },
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = "删除 Profile",
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.clickable {
                            if (activeProfileId != profile.id) onSwitch(profile)
                        },
                    )
                }
            }

            ListItem(
                headlineContent = { Text("Profile 隔离说明") },
                supportingContent = {
                    Text(
                        "YBrowser 标签、收藏、历史、站点设置和权限也按 Profile 分开；Firefox 扩展、用户脚本和全局浏览器设置仍共享。",
                    )
                },
                leadingContent = {
                    Icon(Icons.Outlined.Storage, contentDescription = null)
                },
            )
        }
    }

    editing?.let { profile ->
        var name by remember(profile.id) { mutableStateOf(profile.name) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("重命名 Profile") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("名称") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (BrowserProfileRepository.rename(context, profile.id, name)) {
                            editing = null
                            refresh()
                        } else {
                            Toast.makeText(
                                context,
                                "名称为空或已存在",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) {
                    Text("取消")
                }
            },
        )
    }
}
