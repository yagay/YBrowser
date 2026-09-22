package com.yagay.ybrowser.ai.ui

import android.app.Application
import android.content.Intent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BugReport
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yagay.ybrowser.ai.diagnostics.DiagnosticLogger
import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ChatMessage
import com.yagay.ybrowser.ai.model.ChatWindow
import com.yagay.ybrowser.ai.model.MessageRole
import com.yagay.ybrowser.ai.model.WindowViewMode
import com.yagay.ybrowser.ai.provider.ProviderCatalog
import com.yagay.ybrowser.ai.web.WindowWebRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceRoot(
    runtime: WindowWebRuntime,
    launchIntent: Intent? = null,
    launchRevision: Int = 0,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val vm: WorkspaceViewModel = viewModel(factory = WorkspaceViewModel.Factory(application))
    androidx.compose.runtime.LaunchedEffect(launchRevision) {
        vm.handleLaunchIntent(launchIntent)
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var nativePickerTarget by remember { mutableStateOf<String?>(null) }

    val webFileChooser = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        runtime.handleFileChooserResult(result.resultCode, result.data)
    }

    val nativeAttachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val windowId = nativePickerTarget
        nativePickerTarget = null

        if (windowId != null && uris.isNotEmpty()) {
            val window = vm.windows.firstOrNull { it.id == windowId }
            if (window != null) {
                val provider = ProviderCatalog.byId(window.providerId)
                scope.launch {
                    val result = runCatching {
                        runtime.attachFiles(windowId, provider, uris)
                    }.onFailure {
                        DiagnosticLogger.e(
                            "FILE",
                            "workspace_attachment_failed provider=${provider.id}",
                            it
                        )
                    }.getOrNull()

                    if (result == null || result.attachedCount <= 0) {
                        Toast.makeText(
                            context,
                            "${provider.name} 没有接收文件，已切到网页视图。",
                            Toast.LENGTH_LONG
                        ).show()
                        if (vm.activeWindowId == windowId) {
                            vm.setViewMode(WindowViewMode.WEB)
                        }
                    }
                }
            }
        }
    }

    val exportDiagnostics = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    DiagnosticLogger.export(context.applicationContext, uri)
                }
                Toast.makeText(
                    context,
                    if (result.isSuccess) "诊断日志已导出" else "导出失败",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    DisposableEffect(runtime) {
        runtime.setFileChooserLauncher { intent ->
            webFileChooser.launch(intent)
        }
        runtime.setFileSelectionListener { windowId, _, attachments ->
            vm.onAttachments(windowId, attachments)
        }
        runtime.setPageChangeListener { windowId, provider, url ->
            vm.onPageChanged(windowId, provider, url)
        }
        runtime.setPageReadyListener { windowId, provider, url ->
            vm.onPageChanged(windowId, provider, url)
        }
        runtime.setConversationListener { windowId, provider, snapshot ->
            vm.onConversationSnapshot(windowId, provider, snapshot)
        }

        onDispose {
            runtime.setFileChooserLauncher(null)
            runtime.setFileSelectionListener(null)
            runtime.setPageChangeListener(null)
            runtime.setPageReadyListener(null)
            runtime.setConversationListener(null)
            runtime.destroy()
        }
    }

    BackHandler(enabled = vm.activeWindow.viewMode == WindowViewMode.WEB) {
        if (!runtime.goBack(vm.activeWindow.id, vm.activeProvider)) {
            vm.setViewMode(WindowViewMode.CHAT)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.88f)) {
                Spacer(Modifier.height(16.dp))

                Text(
                    "AIHub",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )

                Button(
                    onClick = {
                        vm.newWindow()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null)
                    Text("新建聊天窗口", modifier = Modifier.padding(start = 8.dp))
                }

                vm.providers.forEach { provider ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            provider.name,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                vm.newWindow(provider.id)
                                scope.launch { drawerState.close() }
                            }
                        ) {
                            Text("+ 新窗口")
                        }
                    }

                    vm.windowsFor(provider.id).forEach { window ->
                        NavigationDrawerItem(
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        window.title,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    when {
                                        window.generating -> Text(" ⟳")
                                        window.unread -> Text(
                                            " ●",
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            selected = window.id == vm.activeWindowId,
                            onClick = {
                                vm.switchWindow(window.id)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                NavigationDrawerItem(
                    label = { Text("清空诊断日志") },
                    selected = false,
                    onClick = {
                        DiagnosticLogger.clear()
                        Toast.makeText(
                            context,
                            "诊断日志已清空，请复现一次问题后再导出",
                            Toast.LENGTH_LONG
                        ).show()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                NavigationDrawerItem(
                    label = { Text("导出诊断日志") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        exportDiagnostics.launch(DiagnosticLogger.suggestedFileName())
                    },
                    icon = { Icon(Icons.Outlined.BugReport, null) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                Column {
                    CenterAlignedTopAppBar(
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    vm.activeWindow.title,
                                    maxLines = 1,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    vm.activeProvider.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    scope.launch { drawerState.open() }
                                }
                            ) {
                                Icon(Icons.Default.Menu, "窗口列表")
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    vm.setViewMode(
                                        if (vm.activeWindow.viewMode == WindowViewMode.CHAT) {
                                            WindowViewMode.WEB
                                        } else {
                                            WindowViewMode.CHAT
                                        }
                                    )
                                }
                            ) {
                                Text(
                                    if (vm.activeWindow.viewMode == WindowViewMode.CHAT) {
                                        "网页"
                                    } else {
                                        "聊天"
                                    }
                                )
                            }

                            IconButton(
                                onClick = {
                                    vm.newWindow(vm.activeWindow.providerId)
                                }
                            ) {
                                Icon(Icons.Default.Add, "新窗口")
                    )

                    if (vm.activeWindow.viewMode == WindowViewMode.CHAT) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    DiagnosticLogger.clear()
                                    Toast.makeText(
                                        context,
                                        "诊断日志已清空，请复现一次问题后再导出",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            ) {
                                Text("清空日志")
                            }

                            TextButton(
                                onClick = {
                                    exportDiagnostics.launch(
                                        DiagnosticLogger.suggestedFileName()
                                    )
                                }
                            ) {
                                Icon(
                                    Icons.Outlined.BugReport,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    "导出日志",
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }

                    WindowTabStrip(
                        windows = vm.windows,
                        activeWindowId = vm.activeWindowId,
                        onSelect = vm::switchWindow
                    )
                }
            }
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                NativeChatPane(
                    messages = vm.messages,
                    status = vm.activeStatus,
                    draft = vm.activeDraft,
                    onDraftChange = vm::updateDraft,
                    generating = vm.activeWindow.generating,
                    attachments = vm.activePendingAttachments,
                    onAttach = {
                        nativePickerTarget = vm.activeWindow.id
                        nativeAttachmentPicker.launch(arrayOf("*/*"))
                    },
                    onSend = {
                        vm.send(runtime)
                    },
                    onStop = {
                        vm.stop(runtime)
                    },
                    visible = vm.activeWindow.viewMode == WindowViewMode.CHAT
                )

                WorkspaceWebHost(
                    runtime = runtime,
                    window = vm.activeWindow,
                    visible = vm.activeWindow.viewMode == WindowViewMode.WEB
                )
            }
        }
    }
}

@Composable
private fun WindowTabStrip(
    windows: List<ChatWindow>,
    activeWindowId: String,
    onSelect: (String) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(windows, key = { it.id }) { window ->
            val selected = window.id == activeWindowId

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ) {
                Row(
                    modifier = Modifier
                        .clickable { onSelect(window.id) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        buildString {
                            append(window.title)
                            when {
                                window.generating -> append(" ⟳")
                                window.unread -> append(" ●")
                            }
                        },
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = 180.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NativeChatPane(
    messages: List<ChatMessage>,
    status: String?,
    draft: String,
    onDraftChange: (String) -> Unit,
    generating: Boolean,
    attachments: List<AttachmentMeta>,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    visible: Boolean
) {
    val listState = rememberLazyListState()

    androidx.compose.runtime.LaunchedEffect(messages.size, visible) {
        if (visible && messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .alpha(if (visible) 1f else 0f)
            .zIndex(if (visible) 1f else -1f)
            .imePadding()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = 16.dp,
                vertical = 18.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(
                                Modifier.size(68.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "AI",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        Text(
                            "新聊天窗口",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Text(
                            "每个窗口保持自己的网页与聊天状态，切换窗口不会重新加载。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            items(messages, key = { it.id }) { message ->
                MessageBubble(message)
            }

            if (status != null) {
                item {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Surface(
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            shape = RoundedCornerShape(
                topStart = 24.dp,
                topEnd = 24.dp
            )
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
            ) {
                if (attachments.isNotEmpty()) {
                    Text(
                        "📎 " + attachments
                            .joinToString(", ") { it.name }
                            .take(120),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(
                            horizontal = 8.dp,
                            vertical = 4.dp
                        )
                    )
                }

                Row(verticalAlignment = Alignment.Bottom) {
                    IconButton(
                        onClick = onAttach,
                        enabled = !generating
                    ) {
                        Icon(
                            Icons.Outlined.AttachFile,
                            "添加附件"
                        )
                    }

                    TextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text("发送消息…")
                        },
                        minLines = 1,
                        maxLines = 6,
                        shape = RoundedCornerShape(22.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor =
                                androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor =
                                androidx.compose.ui.graphics.Color.Transparent
                        )
                    )

                    Spacer(Modifier.size(8.dp))

                    FilledIconButton(
                        onClick = if (generating) {
                            onStop
                        } else {
                            onSend
                        },
                        enabled =
                            generating ||
                                draft.isNotBlank() ||
                                attachments.isNotEmpty()
                    ) {
                        Icon(
                            if (generating) {
                                Icons.Default.Stop
                            } else {
                                Icons.Default.Send
                            },
                            if (generating) "停止" else "发送"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val mine = message.role == MessageRole.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement =
            if (mine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (mine) 20.dp else 6.dp,
                bottomEnd = if (mine) 6.dp else 20.dp
            ),
            color = if (mine) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            modifier = Modifier.fillMaxWidth(
                if (mine) 0.88f else 0.95f
            )
        ) {
            Text(
                message.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 13.dp
                )
            )
        }
    }
}

@Composable
private fun WorkspaceWebHost(
    runtime: WindowWebRuntime,
    window: ChatWindow,
    visible: Boolean
) {
    val provider = ProviderCatalog.byId(window.providerId)

    Box(
        Modifier
            .fillMaxSize()
            .alpha(if (visible) 1f else 0f)
            .zIndex(if (visible) 2f else -1f)
            .then(
                if (visible) {
                    Modifier.background(
                        MaterialTheme.colorScheme.background
                    )
                } else {
                    Modifier
                }
            )
    ) {
        AndroidView(
            factory = { context ->
                FrameLayout(context).also { host ->
                    runtime.attach(
                        host,
                        window,
                        provider
                    )
                }
            },
            update = { host ->
                runtime.attach(
                    host,
                    window,
                    provider
                )
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
