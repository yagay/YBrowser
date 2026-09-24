package com.yagay.YBrowser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavSyncSheet(
    config: BrowserWebDavConfig,
    busy: Boolean,
    status: String?,
    onSave: (String, String, String?) -> Unit,
    onClearPassword: () -> Unit,
    onTest: () -> Unit,
    onUpload: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    var endpoint by remember(config.endpoint) { mutableStateOf(config.endpoint) }
    var username by remember(config.username) { mutableStateOf(config.username) }
    var password by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("WebDAV 同步", style = MaterialTheme.typography.headlineSmall)
            Text(
                "使用 YBrowser 完整 JSON 备份作为同步文件。地址应直接指向远端备份文件，例如 https://server/path/ybrowser.json。",
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("WebDAV 文件地址") },
                singleLine = true,
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                label = { Text("用户名") },
                singleLine = true,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                label = {
                    Text(
                        if (config.hasPassword) "密码（留空保持已保存密码）"
                        else "密码"
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )

            Row(modifier = Modifier.padding(top = 8.dp)) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        onSave(
                            endpoint,
                            username,
                            password.takeIf { it.isNotBlank() },
                        )
                        password = ""
                    },
                ) {
                    Text("保存")
                }
                if (config.hasPassword) {
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        enabled = !busy,
                        onClick = onClearPassword,
                    ) {
                        Text("清除密码")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = onTest) {
                    Text("测试连接")
                }
            }

            Row(modifier = Modifier.padding(top = 8.dp)) {
                TextButton(enabled = !busy, onClick = onUpload) {
                    Text("上传本机备份")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(enabled = !busy, onClick = onDownload) {
                    Text("下载并恢复")
                }
            }

            if (!status.isNullOrBlank()) {
                Text(
                    status,
                    modifier = Modifier.padding(top = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
