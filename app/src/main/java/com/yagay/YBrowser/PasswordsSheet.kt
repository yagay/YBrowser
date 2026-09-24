package com.yagay.YBrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class BrowserLoginPromptOption(
    val origin: String,
    val username: String,
    val password: String,
    val confirm: () -> Unit,
)

data class BrowserLoginSaveUiRequest(
    val options: List<BrowserLoginPromptOption>,
    val dismiss: () -> Unit,
)

data class BrowserLoginSelectUiRequest(
    val options: List<BrowserLoginPromptOption>,
    val dismiss: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordsSheet(
    credentials: List<BrowserCredential>,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val revealed = remember { mutableStateMapOf<String, Boolean>() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
        ) {
            ListItem(
                headlineContent = { Text("密码与凭据") },
                supportingContent = {
                    Text(
                        "GeckoView 使用 YBrowser 加密凭据库；System WebView 继续使用 Android Autofill。"
                    )
                },
                trailingContent = {
                    TextButton(
                        enabled = credentials.isNotEmpty(),
                        onClick = onClearAll,
                    ) {
                        Text("全部清除")
                    }
                },
            )
            HorizontalDivider()

            if (credentials.isEmpty()) {
                Text(
                    "还没有保存的登录凭据。",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn {
                    items(credentials, key = { it.guid }) { entry ->
                        val show = revealed[entry.guid] == true
                        ListItem(
                            headlineContent = {
                                Text(
                                    entry.username.ifBlank { "无用户名" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Column {
                                    Text(
                                        entry.origin,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (show) entry.password
                                        else "•".repeat(entry.password.length.coerceIn(6, 16)),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            trailingContent = {
                                Row {
                                    IconButton(
                                        onClick = {
                                            revealed[entry.guid] = !show
                                        }
                                    ) {
                                        Icon(
                                            if (show) Icons.Outlined.VisibilityOff
                                            else Icons.Outlined.Visibility,
                                            contentDescription =
                                                if (show) "隐藏密码" else "显示密码",
                                        )
                                    }
                                    IconButton(onClick = { onDelete(entry.guid) }) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = "删除凭据",
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.clickable {
                                val clipboard =
                                    context.getSystemService(ClipboardManager::class.java)
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText(
                                        "Password",
                                        entry.password,
                                    )
                                )
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginSavePromptSheet(
    request: BrowserLoginSaveUiRequest,
    onDismissUi: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = {
            request.dismiss()
            onDismissUi()
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
        ) {
            Text(
                "保存登录密码？",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                style = MaterialTheme.typography.headlineSmall,
            )
            request.options.forEach { option ->
                ListItem(
                    headlineContent = {
                        Text(option.username.ifBlank { "无用户名" })
                    },
                    supportingContent = {
                        Column {
                            Text(option.origin)
                            Text("密码：" + "•".repeat(option.password.length.coerceIn(6, 16)))
                        }
                    },
                    modifier = Modifier.clickable {
                        option.confirm()
                        onDismissUi()
                    },
                )
            }
            TextButton(
                modifier = Modifier.padding(horizontal = 12.dp),
                onClick = {
                    request.dismiss()
                    onDismissUi()
                },
            ) {
                Text("不保存")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginSelectPromptSheet(
    request: BrowserLoginSelectUiRequest,
    onDismissUi: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = {
            request.dismiss()
            onDismissUi()
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
        ) {
            Text(
                "选择登录账号",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                style = MaterialTheme.typography.headlineSmall,
            )
            request.options.forEach { option ->
                ListItem(
                    headlineContent = {
                        Text(option.username.ifBlank { "无用户名" })
                    },
                    supportingContent = { Text(option.origin) },
                    modifier = Modifier.clickable {
                        option.confirm()
                        onDismissUi()
                    },
                )
            }
            TextButton(
                modifier = Modifier.padding(horizontal = 12.dp),
                onClick = {
                    request.dismiss()
                    onDismissUi()
                },
            ) {
                Text("取消")
            }
        }
    }
}
