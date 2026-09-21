package com.yagay.YBrowser

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserScriptsSheet(
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    var scripts by remember { mutableStateOf(BrowserUserScriptRepository.list(context)) }
    var editing by remember { mutableStateOf<BrowserUserScript?>(null) }
    var adding by remember { mutableStateOf(false) }

    fun refresh() {
        scripts = BrowserUserScriptRepository.list(context)
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
                        "用户脚本",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "按域名自动注入 JavaScript；脚本只保存在本机",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { adding = true }) {
                    Text("新增")
                }
            }

            if (scripts.isEmpty()) {
                Text(
                    "还没有用户脚本。匹配规则支持 example.com、*.example.com 或 *。",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn {
                    items(scripts, key = { it.id }) { script ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    script.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    script.match,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = {
                                Icon(Icons.Outlined.Code, contentDescription = null)
                            },
                            trailingContent = {
                                Row {
                                    Switch(
                                        checked = script.enabled,
                                        onCheckedChange = { enabled ->
                                            BrowserUserScriptRepository.setEnabled(
                                                context,
                                                script.id,
                                                enabled,
                                            )
                                            refresh()
                                        },
                                    )
                                    IconButton(onClick = { editing = script }) {
                                        Icon(
                                            Icons.Outlined.Edit,
                                            contentDescription = "编辑脚本",
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            BrowserUserScriptRepository.remove(
                                                context,
                                                script.id,
                                            )
                                            refresh()
                                        },
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = "删除脚本",
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.clickable { editing = script },
                        )
                    }
                }
            }
        }
    }

    val target = when {
        editing != null -> editing
        adding -> BrowserUserScript(
            id = 0L,
            name = "",
            match = "*",
            code = "",
            enabled = true,
        )
        else -> null
    }

    target?.let { initial ->
        UserScriptEditorDialog(
            initial = initial,
            onDismiss = {
                editing = null
                adding = false
            },
            onSave = { value ->
                if (
                    value.name.isBlank() ||
                    value.match.isBlank() ||
                    value.code.isBlank()
                ) {
                    Toast.makeText(
                        context,
                        "名称、匹配域名和脚本内容不能为空",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    BrowserUserScriptRepository.save(context, value)
                    editing = null
                    adding = false
                    refresh()
                }
            },
        )
    }
}

@Composable
private fun UserScriptEditorDialog(
    initial: BrowserUserScript,
    onDismiss: () -> Unit,
    onSave: (BrowserUserScript) -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var match by remember(initial.id) { mutableStateOf(initial.match) }
    var code by remember(initial.id) { mutableStateOf(initial.code) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initial.id > 0L) "编辑用户脚本" else "新增用户脚本")
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("名称") },
                )
                OutlinedTextField(
                    value = match,
                    onValueChange = { match = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    singleLine = true,
                    label = { Text("匹配域名") },
                    supportingText = {
                        Text("例如 example.com、*.example.com 或 *")
                    },
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 360.dp)
                        .padding(top = 8.dp),
                    label = { Text("JavaScript") },
                    minLines = 8,
                    maxLines = 16,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            match = match.trim(),
                            code = code,
                        ),
                    )
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
