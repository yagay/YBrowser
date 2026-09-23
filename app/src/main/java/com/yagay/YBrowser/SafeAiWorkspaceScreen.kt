package com.yagay.YBrowser

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yagay.ybrowser.ai.diagnostics.DiagnosticLogger
import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ChatMessage
import com.yagay.ybrowser.ai.model.MessageRole
import com.yagay.ybrowser.ai.ui.WindowTabStrip
import com.yagay.ybrowser.ai.ui.WorkspaceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Crash-resistant AI entry used by YagaYHub.
 *
 * It deliberately reuses the real AIHub chat/tab UI while keeping Gecko and
 * provider sessions lazy. Opening this screen never creates a WebView or
 * GeckoSession; runtime work starts only from an explicit user action.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
)
@Composable
internal fun SafeAiWorkspaceScreen(
    vm: WorkspaceViewModel,
    runtimeError: String?,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onOpenWeb: (String) -> Unit,
    onAttachFiles: (String, List<Uri>) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var nativePickerTarget by remember { mutableStateOf<String?>(null) }
    var bindingActionWindowId by remember { mutableStateOf<String?>(null) }
    var deleteActionWindowId by remember { mutableStateOf<String?>(null) }

    val nativeAttachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val windowId = nativePickerTarget
        nativePickerTarget = null
        if (windowId != null && uris.isNotEmpty()) {
            onAttachFiles(windowId, uris)
        }
    }

    val exportDiagnostics = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    DiagnosticLogger.export(
                        context.applicationContext,
                        uri,
                    )
                }
                Toast.makeText(
                    context,
                    if (result.isSuccess) {
                        "诊断日志已导出"
                    } else {
                        "导出失败"
                    },
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val bindingActionWindow = vm.windows.firstOrNull {
        it.id == bindingActionWindowId
    }
    if (bindingActionWindow != null) {
        val displayName =
            bindingActionWindow.boundProject.orEmpty()
                .ifBlank { bindingActionWindow.title }
                .ifBlank { "聊天" }

        AlertDialog(
            onDismissRequest = {
                bindingActionWindowId = null
            },
            title = {
                Text(displayName)
            },
            text = {
                Text(
                    if (
                        bindingActionWindow.boundUrl
                            .isNullOrBlank()
                    ) {
                        "这个聊天还没有绑定项目。"
                    } else {
                        "可以重新绑定、解除当前项目绑定，或删除这个聊天。"
                    },
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        onClick = {
                            val id = bindingActionWindow.id
                            bindingActionWindowId = null
                            vm.requestBinding(id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (
                                bindingActionWindow.boundUrl
                                    .isNullOrBlank()
                            ) {
                                "绑定项目"
                            } else {
                                "重新绑定"
                            },
                        )
                    }

                    if (
                        !bindingActionWindow.boundUrl
                            .isNullOrBlank()
                    ) {
                        TextButton(
                            onClick = {
                                val id = bindingActionWindow.id
                                bindingActionWindowId = null
                                vm.unbindWindow(id)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("解除绑定")
                        }
                    }

                    TextButton(
                        onClick = {
                            deleteActionWindowId =
                                bindingActionWindow.id
                            bindingActionWindowId = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("删除聊天")
                    }

                    TextButton(
                        onClick = {
                            bindingActionWindowId = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("取消")
                    }
                }
            },
        )
    }

    val deleteActionWindow = vm.windows.firstOrNull {
        it.id == deleteActionWindowId
    }
    if (deleteActionWindow != null) {
        AlertDialog(
            onDismissRequest = {
                deleteActionWindowId = null
            },
            title = {
                Text("删除聊天")
            },
            text = {
                Text(
                    "确定删除“" +
                        deleteActionWindow.boundProject
                            .orEmpty()
                            .ifBlank {
                                deleteActionWindow.title
                            }
                            .ifBlank { "聊天" } +
                        "”吗？",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = deleteActionWindow.id
                        deleteActionWindowId = null
                        onDelete(id)
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deleteActionWindowId = null
                    },
                ) {
                    Text("取消")
                }
            },
        )
    }

    BackHandler(
        enabled = drawerState.isOpen,
    ) {
        scope.launch {
            drawerState.close()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxWidth(0.50f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 20.dp,
                            end = 8.dp,
                            top = 4.dp,
                            bottom = 4.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "AIHub",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                drawerState.close()
                            }
                        },
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭菜单",
                        )
                    }
                }

                Button(
                    onClick = {
                        vm.newWindow()
                        scope.launch {
                            drawerState.close()
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                    )
                    Text(
                        "新建聊天窗口",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                vm.providers.forEach { provider ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 20.dp,
                                end = 8.dp,
                                top = 16.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            provider.name,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                vm.newWindow(provider.id)
                                scope.launch {
                                    drawerState.close()
                                }
                            },
                        ) {
                            Text("+ 新窗口")
                        }
                    }

                    vm.windowsFor(provider.id)
                        .forEach { window ->
                            val selected =
                                window.id == vm.activeWindowId
                            Surface(
                                shape = RoundedCornerShape(28.dp),
                                color =
                                    if (selected) {
                                        MaterialTheme.colorScheme
                                            .secondaryContainer
                                    } else {
                                        androidx.compose.ui.graphics
                                            .Color.Transparent
                                    },
                                modifier = Modifier
                                    .padding(
                                        horizontal = 8.dp,
                                        vertical = 2.dp,
                                    )
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            vm.switchWindow(window.id)
                                            scope.launch {
                                                drawerState.close()
                                            }
                                        },
                                        onLongClick = {
                                            bindingActionWindowId =
                                                window.id
                                            scope.launch {
                                                drawerState.close()
                                            }
                                        },
                                    ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 13.dp,
                                    ),
                                    verticalAlignment =
                                        Alignment.CenterVertically,
                                ) {
                                    Text(
                                        window.boundProject
                                            .orEmpty()
                                            .ifBlank {
                                                window.title
                                            },
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f),
                                    )
                                    when {
                                        window.generating ->
                                            Text(" ⟳")
                                        window.unread ->
                                            Text(
                                                " ●",
                                                color =
                                                    MaterialTheme
                                                        .colorScheme
                                                        .primary,
                                            )
                                    }
                                }
                            }
                        }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                NavigationDrawerItem(
                    label = {
                        Text("清空诊断日志")
                    },
                    selected = false,
                    onClick = {
                        DiagnosticLogger.clear()
                        Toast.makeText(
                            context,
                            "诊断日志已清空，请复现一次问题后再导出",
                            Toast.LENGTH_LONG,
                        ).show()
                        scope.launch {
                            drawerState.close()
                        }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )

                NavigationDrawerItem(
                    label = {
                        Text("导出诊断日志")
                    },
                    selected = false,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                        }
                        exportDiagnostics.launch(
                            DiagnosticLogger.suggestedFileName(),
                        )
                    },
                    icon = {
                        Icon(
                            Icons.Outlined.BugReport,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                Column {
                    CenterAlignedTopAppBar(
                        title = {
                            Column(
                                horizontalAlignment =
                                    Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    vm.activeWindow.title
                                        .ifBlank {
                                            vm.activeWindow
                                                .boundProject
                                                .orEmpty()
                                        },
                                    maxLines = 1,
                                    fontWeight =
                                        FontWeight.SemiBold,
                                )
                                Text(
                                    vm.activeProvider.name,
                                    style =
                                        MaterialTheme.typography
                                            .labelSmall,
                                    color =
                                        MaterialTheme.colorScheme
                                            .onSurfaceVariant,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        drawerState.open()
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = "窗口列表",
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = onRefresh,
                                enabled =
                                    !vm.activeWindow.generating,
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "刷新聊天",
                                )
                            }

                            TextButton(
                                onClick = {
                                    val url =
                                        vm.activeWindow.boundUrl
                                            ?: vm.activeWindow.url
                                            ?: vm.activeProvider
                                                .homeUrl
                                    onOpenWeb(url)
                                },
                            ) {
                                Text("网页")
                            }

                            IconButton(
                                onClick = {
                                    vm.newWindow(
                                        vm.activeWindow.providerId,
                                    )
                                },
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "新窗口",
                                )
                            }
                        },
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 8.dp,
                                vertical = 2.dp,
                            ),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                DiagnosticLogger.clear()
                                Toast.makeText(
                                    context,
                                    "诊断日志已清空，请复现一次问题后再导出",
                                    Toast.LENGTH_LONG,
                                ).show()
                            },
                        ) {
                            Text("清空日志")
                        }

                        TextButton(
                            onClick = {
                                exportDiagnostics.launch(
                                    DiagnosticLogger
                                        .suggestedFileName(),
                                )
                            },
                        ) {
                            Icon(
                                Icons.Outlined.BugReport,
                                contentDescription = null,
                            )
                            Text(
                                "导出日志",
                                modifier = Modifier.padding(
                                    start = 4.dp,
                                ),
                            )
                        }
                    }

                    WindowTabStrip(
                        windows = vm.tabWindows,
                        activeWindowId = vm.activeWindowId,
                        focusRevision = 0,
                        onSelect = vm::switchWindow,
                        onLongPress = {
                            bindingActionWindowId = it
                        },
                    )
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                SafeNativeChatPane(
                    messages = vm.messages,
                    status =
                        listOfNotNull(
                            vm.activeStatus,
                            runtimeError,
                        )
                            .filter {
                                it.isNotBlank()
                            }
                            .joinToString("\n")
                            .takeIf {
                                it.isNotBlank()
                            },
                    draft = vm.activeDraft,
                    onDraftChange = vm::updateDraft,
                    generating = vm.activeWindow.generating,
                    attachments =
                        vm.activePendingAttachments,
                    onAttach = {
                        nativePickerTarget =
                            vm.activeWindow.id
                        nativeAttachmentPicker.launch(
                            arrayOf("*/*"),
                        )
                    },
                    onSend = onSend,
                    onStop = onStop,
                )
            }
        }
    }
}


@Composable
private fun SafeNativeChatPane(
    messages: List<ChatMessage>,
    status: String?,
    draft: String,
    onDraftChange: (String) -> Unit,
    generating: Boolean,
    attachments: List<AttachmentMeta>,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = 0.dp,
                vertical = 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color =
                                MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Box(
                                modifier = Modifier.size(68.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "AI",
                                    style =
                                        MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        Text(
                            "新聊天窗口",
                            style = MaterialTheme.typography.titleMedium,
                        )

                        Text(
                            "每个窗口保持自己的聊天状态。",
                            style = MaterialTheme.typography.bodyMedium,
                            color =
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            items(
                items = messages,
                key = { it.id },
            ) { message ->
                SafeMessageBubble(message)
            }

            if (!status.isNullOrBlank()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .widthIn(max = 760.dp)
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp),
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 12.dp,
                    vertical = 8.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                tonalElevation = 1.dp,
                shadowElevation = 2.dp,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 10.dp,
                            vertical = 8.dp,
                        ),
                ) {
                    if (attachments.isNotEmpty()) {
                        Text(
                            "📎 " +
                                attachments
                                    .joinToString(", ") {
                                        it.name
                                    }
                                    .take(160),
                            style =
                                MaterialTheme.typography.labelMedium,
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                            modifier = Modifier.padding(
                                horizontal = 10.dp,
                                vertical = 5.dp,
                            ),
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        IconButton(
                            onClick = onAttach,
                            enabled = !generating,
                        ) {
                            Icon(
                                Icons.Outlined.AttachFile,
                                contentDescription = "添加附件",
                            )
                        }

                        TextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text("询问任何问题")
                            },
                            minLines = 1,
                            maxLines = 7,
                            shape = RoundedCornerShape(24.dp),
                            colors =
                                TextFieldDefaults.colors(
                                    focusedIndicatorColor =
                                        androidx.compose.ui.graphics
                                            .Color.Transparent,
                                    unfocusedIndicatorColor =
                                        androidx.compose.ui.graphics
                                            .Color.Transparent,
                                    disabledIndicatorColor =
                                        androidx.compose.ui.graphics
                                            .Color.Transparent,
                                ),
                        )

                        Spacer(Modifier.size(6.dp))

                        FilledIconButton(
                            onClick =
                                if (generating) {
                                    onStop
                                } else {
                                    onSend
                                },
                            enabled =
                                generating ||
                                    draft.isNotBlank() ||
                                    attachments.isNotEmpty(),
                        ) {
                            Icon(
                                if (generating) {
                                    Icons.Default.Stop
                                } else {
                                    Icons.Default.Send
                                },
                                contentDescription =
                                    if (generating) {
                                        "停止"
                                    } else {
                                        "发送"
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafeMessageBubble(
    message: ChatMessage,
) {
    val mine =
        message.role == MessageRole.USER

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalArrangement =
                if (mine) {
                    Arrangement.End
                } else {
                    Arrangement.Start
                },
        ) {
            if (mine) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color =
                        MaterialTheme.colorScheme
                            .surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(0.86f),
                ) {
                    SelectionContainer {
                        Text(
                            message.text,
                            style =
                                MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(
                                horizontal = 16.dp,
                                vertical = 11.dp,
                            ),
                        )
                    }
                }
            } else {
                SelectionContainer {
                    Text(
                        message.text,
                        style =
                            MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SafeAiWorkspaceFailureScreen(
    message: String,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "AI Work 无法初始化",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
            )
            AssistChip(
                onClick = onClose,
                label = {
                    Text("返回 YagaYHub")
                },
            )
        }
    }
}
