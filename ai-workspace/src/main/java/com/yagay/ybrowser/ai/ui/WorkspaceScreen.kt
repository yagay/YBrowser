package com.yagay.ybrowser.ai.ui

import android.app.Application
import android.content.Intent
import android.widget.FrameLayout
import android.widget.Toast
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.AlertDialog
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceRoot(
    runtime: WindowWebRuntime,
    launchIntent: Intent? = null,
    launchRevision: Int = 0,
    resumeRevision: Int = 0,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val vm: WorkspaceViewModel = viewModel(factory = WorkspaceViewModel.Factory(application))
    androidx.compose.runtime.LaunchedEffect(launchRevision) {
        vm.handleLaunchIntent(launchIntent)
    }
    androidx.compose.runtime.LaunchedEffect(resumeRevision) {
        if (resumeRevision > 1) {
            vm.refreshBindingsFromSharedStore()
        }
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var nativePickerTarget by remember { mutableStateOf<String?>(null) }
    var bindingActionWindowId by remember { mutableStateOf<String?>(null) }

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

    val bindingActionWindow = vm.windows.firstOrNull {
        it.id == bindingActionWindowId
    }
    if (bindingActionWindow != null) {
        val projectName = bindingActionWindow.boundProject.orEmpty()
            .ifBlank { bindingActionWindow.title }
        AlertDialog(
            onDismissRequest = { bindingActionWindowId = null },
            title = { Text(projectName) },
            text = {
                Text(
                    if (bindingActionWindow.boundUrl.isNullOrBlank()) {
                        "这个新聊天还没有绑定项目。"
                    } else {
                        "可以重新绑定到其他项目，或解除当前绑定。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = bindingActionWindow.id
                        bindingActionWindowId = null
                        vm.requestBinding(id)
                    }
                ) {
                    Text(
                        if (bindingActionWindow.boundUrl.isNullOrBlank()) {
                            "绑定项目"
                        } else {
                            "重新绑定"
                        }
                    )
                }
            },
            dismissButton = {
                Row {
                    if (!bindingActionWindow.boundUrl.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                val id = bindingActionWindow.id
                                bindingActionWindowId = null
                                vm.unbindWindow(id)
                            }
                        ) {
                            Text("解除绑定")
                        }
                    }
                    TextButton(
                        onClick = { bindingActionWindowId = null }
                    ) {
                        Text("取消")
                    }
                }
            }
        )
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
            if (provider.id != "chatgpt") {
                vm.onConversationSnapshot(windowId, provider, snapshot)
            }
        }

        onDispose {
            runtime.setFileChooserLauncher(null)
            runtime.setFileSelectionListener(null)
            runtime.setPageChangeListener(null)
            runtime.setPageReadyListener(null)
            runtime.setConversationListener(null)
            vm.onWorkspaceExit()
            runtime.releaseUi()
        }
    }

    androidx.compose.runtime.LaunchedEffect(
        vm.activeWindowId,
        vm.activeWindow.viewMode,
        vm.activeWindow.boundUrl,
    ) {
        // Tab switching itself must never navigate or reload a live session.
        // URL correction is reserved for the explicit full-web view.
        if (vm.activeWindow.viewMode == WindowViewMode.WEB) {
            runtime.ensurePreferredPage(
                vm.activeWindow,
                vm.activeProvider,
            )
        }

        val chatDomMode =
            vm.activeProvider.id == "chatgpt" &&
                vm.activeWindow.viewMode == WindowViewMode.CHAT

        runtime.setChatPresentation(
            windowId = vm.activeWindow.id,
            provider = vm.activeProvider,
            enabled = chatDomMode,
        )

        if (
            vm.activeWindow.viewMode == WindowViewMode.CHAT &&
            vm.activeProvider.id != "chatgpt"
        ) {
            vm.syncPage(runtime, vm.activeWindowId)
        }
    }

    BackHandler(
        enabled =
            drawerState.isOpen ||
                vm.activeWindow.viewMode == WindowViewMode.WEB,
    ) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (
            !runtime.goBack(
                vm.activeWindow.id,
                vm.activeProvider,
            )
        ) {
            vm.setViewMode(WindowViewMode.CHAT)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Closed: edge swipes cannot open the drawer.
        // Open: gestures are enabled so the drawer can be swiped closed.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .verticalScroll(rememberScrollState())
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
                            scope.launch { drawerState.close() }
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
                            if (vm.activeWindow.viewMode == WindowViewMode.CHAT) {
                                IconButton(
                                    onClick = {
                                        if (vm.activeProvider.id == "chatgpt") {
                                            runtime.reloadPage(
                                                vm.activeWindow,
                                                vm.activeProvider,
                                            )
                                        } else {
                                            vm.refreshConversation(runtime)
                                        }
                                    },
                                    enabled = !vm.activeWindow.generating
                                ) {
                                    Icon(Icons.Default.Refresh, "刷新聊天")
                                }
                            }

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
                            }
                        }
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
                        windows = vm.tabWindows,
                        activeWindowId = vm.activeWindowId,
                        focusRevision = launchRevision,
                        onSelect = vm::switchWindow,
                        onLongPress = { bindingActionWindowId = it },
                    )
                }
            }
        ) { padding ->
            val chatGptDomMode =
                vm.activeProvider.id == "chatgpt" &&
                    vm.activeWindow.viewMode == WindowViewMode.CHAT

            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Column(Modifier.fillMaxSize()) {
                    val showActiveWeb =
                        vm.activeWindow.viewMode ==
                            WindowViewMode.WEB ||
                            chatGptDomMode

                    WorkspaceWebHost(
                        runtime = runtime,
                        window = vm.activeWindow,
                        visible = showActiveWeb,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }

                if (!chatGptDomMode) {
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
                        onSend = { vm.send(runtime) },
                        onStop = { vm.stop(runtime) },
                        visible = vm.activeWindow.viewMode == WindowViewMode.CHAT,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowTabStrip(
    windows: List<ChatWindow>,
    activeWindowId: String,
    focusRevision: Int,
    onSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    val listState = rememberLazyListState()

    androidx.compose.runtime.LaunchedEffect(
        activeWindowId,
        windows.map { it.id },
        focusRevision,
    ) {
        val index = windows.indexOfFirst { it.id == activeWindowId }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(windows, key = { it.id }) { window ->
            val selected = window.id == activeWindowId
            val label = window.boundProject.orEmpty()
                .ifBlank { window.title }

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
                        .combinedClickable(
                            onClick = { onSelect(window.id) },
                            onLongClick = { onLongPress(window.id) },
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        buildString {
                            append(label)
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
private fun NativeChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    generating: Boolean,
    attachments: List<AttachmentMeta>,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        shape = RoundedCornerShape(
            topStart = 24.dp,
            topEnd = 24.dp,
        ),
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
                        vertical = 4.dp,
                    ),
                )
            }

            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(
                    onClick = onAttach,
                    enabled = !generating,
                ) {
                    Icon(
                        Icons.Outlined.AttachFile,
                        "添加附件",
                    )
                }

                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("发送消息…") },
                    minLines = 1,
                    maxLines = 6,
                    shape = RoundedCornerShape(22.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor =
                            androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor =
                            androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )

                Spacer(Modifier.size(8.dp))

                FilledIconButton(
                    onClick = if (generating) onStop else onSend,
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
                        if (generating) "停止" else "发送",
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
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val provider = ProviderCatalog.byId(window.providerId)
    val hadLiveSession = remember(
        window.id,
        window.boundUrl,
    ) {
        runtime.hasLiveSession(window.id, provider)
    }
    val coldCandidate =
        provider.id == "chatgpt" &&
            !window.boundUrl.isNullOrBlank() &&
            !hadLiveSession

    var cachedSnapshot by remember(
        window.id,
        window.boundUrl,
    ) {
        mutableStateOf<String?>(null)
    }
    var archiveChecked by remember(
        window.id,
        window.boundUrl,
    ) {
        mutableStateOf(!coldCandidate)
    }
    var onlineRequested by remember(
        window.id,
        window.boundUrl,
    ) {
        mutableStateOf(
            hadLiveSession ||
                !coldCandidate ||
                window.viewMode == WindowViewMode.WEB
        )
    }
    var showSnapshot by remember(
        window.id,
        window.boundUrl,
    ) {
        mutableStateOf(false)
    }

    // Cold ChatGPT tabs check the compressed local archive on IO first.
    // Until this completes, no GeckoSession is created and no network request
    // is allowed to start.
    androidx.compose.runtime.LaunchedEffect(
        window.id,
        window.boundUrl,
        window.viewMode,
        coldCandidate,
    ) {
        if (!coldCandidate) {
            archiveChecked = true
            onlineRequested = true
            showSnapshot = false
            return@LaunchedEffect
        }

        if (runtime.hasLiveSession(window.id, provider)) {
            archiveChecked = true
            onlineRequested = true
            showSnapshot = false
            return@LaunchedEffect
        }

        if (window.viewMode == WindowViewMode.WEB) {
            archiveChecked = true
            onlineRequested = true
            showSnapshot = false
            return@LaunchedEffect
        }

        archiveChecked = false
        DiagnosticLogger.i(
            "COLD",
            "archive_check_start window=" +
                window.id.take(12)
        )
        val local = withContext(Dispatchers.IO) {
            runtime.archiveStatus(window.id) to
                runtime.cachedSnapshotHtml(window.id)
        }
        val status = local.first
        val localHtml = local.second
        cachedSnapshot = localHtml
        archiveChecked = true

        if (localHtml.isNullOrBlank()) {
            DiagnosticLogger.i(
                "COLD",
                "archive_miss window=" +
                    window.id.take(12) +
                    " turns=" + status.turnCount +
                    " archiveBytes=" +
                    status.archiveBytes +
                    " legacyBytes=" +
                    status.legacySnapshotBytes +
                    " -> online"
            )
            showSnapshot = false
            onlineRequested = true
        } else {
            DiagnosticLogger.i(
                "COLD",
                "archive_hit window=" +
                    window.id.take(12) +
                    " turns=" + status.turnCount +
                    " archiveBytes=" +
                    status.archiveBytes +
                    " stylesBytes=" +
                    status.stylesBytes +
                    " sessionStateBytes=" +
                    status.sessionStateBytes +
                    " htmlChars=" +
                    localHtml.length
            )
            showSnapshot = true
            onlineRequested =
                runtime.hasLiveSession(window.id, provider)
        }
    }

    androidx.compose.runtime.LaunchedEffect(
        window.id,
        provider.id,
        showSnapshot,
        onlineRequested,
    ) {
        if (!showSnapshot) return@LaunchedEffect

        repeat(300) {
            if (
                !onlineRequested &&
                runtime.hasLiveSession(window.id, provider)
            ) {
                onlineRequested = true
            }

            if (
                onlineRequested &&
                runtime.isSessionReady(window.id, provider)
            ) {
                DiagnosticLogger.i(
                    "COLD",
                    "cold_to_hot_ready window=" +
                        window.id.take(12)
                )
                showSnapshot = false
                return@LaunchedEffect
            }

            delay(100)
        }
    }

    Box(
        modifier
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
                FrameLayout(context)
            },
            update = { host ->
                if (visible && onlineRequested) {
                    runtime.attach(
                        host = host,
                        window = window,
                        provider = provider,
                    )
                } else {
                    runtime.detachView(
                        windowId = window.id,
                        provider = provider,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (
            visible &&
            coldCandidate &&
            !archiveChecked
        ) {
            Text(
                "正在读取本地历史…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (
            visible &&
            showSnapshot &&
            !cachedSnapshot.isNullOrBlank()
        ) {
            StaticSnapshotWebView(
                html = cachedSnapshot.orEmpty(),
                baseUrl = window.boundUrl ?: window.url,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(4f),
            )

            if (!onlineRequested) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp)
                        .zIndex(6f),
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 6.dp,
                ) {
                    TextButton(
                        onClick = {
                            DiagnosticLogger.i(
                                "COLD",
                                "cold_to_hot_requested window=" +
                                    window.id.take(12) +
                                    " reason=continue-chat"
                            )
                            onlineRequested = true
                        },
                        modifier = Modifier.padding(
                            horizontal = 8.dp,
                        ),
                    ) {
                        Text("继续聊天（联网）")
                    }
                }
            }
        }
    }
}

@Composable
private fun StaticSnapshotWebView(
    html: String,
    baseUrl: String?,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                isLongClickable = true

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean = true

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        url: String?,
                    ): Boolean = true

                    override fun onPageFinished(
                        view: WebView,
                        url: String?,
                    ) {
                        view.evaluateJavascript(
                            """
                                (() => {
                                    const scrollMeta =
                                        document.querySelector(
                                            'meta[name="aihub-snapshot-scroll"]'
                                        );
                                    const anchorMeta =
                                        document.querySelector(
                                            'meta[name="aihub-archive-anchor"]'
                                        );
                                    const offsetMeta =
                                        document.querySelector(
                                            'meta[name="aihub-archive-offset"]'
                                        );
                                    const y = Number(
                                        scrollMeta?.content || 0
                                    );
                                    const anchor =
                                        anchorMeta?.content || "";
                                    const offset = Number(
                                        offsetMeta?.content || 0
                                    );

                                    document.documentElement.style.setProperty(
                                        'height',
                                        'auto',
                                        'important'
                                    );
                                    document.documentElement.style.setProperty(
                                        'overflow-y',
                                        'auto',
                                        'important'
                                    );
                                    document.body?.style?.setProperty(
                                        'height',
                                        'auto',
                                        'important'
                                    );
                                    document.body?.style?.setProperty(
                                        'overflow-y',
                                        'auto',
                                        'important'
                                    );
                                    document.body?.style?.setProperty(
                                        'touch-action',
                                        'pan-y pinch-zoom',
                                        'important'
                                    );

                                    let restored = false;
                                    if (anchor) {
                                        const node = Array.from(
                                            document.querySelectorAll(
                                                '[data-aihub-archive-key]'
                                            )
                                        ).find(
                                            (item) =>
                                                item.getAttribute(
                                                    'data-aihub-archive-key'
                                                ) === anchor
                                        );
                                        if (node) {
                                            node.scrollIntoView({
                                                block: 'start'
                                            });
                                            if (
                                                Number.isFinite(offset) &&
                                                offset !== 0
                                            ) {
                                                window.scrollBy(
                                                    0,
                                                    -offset
                                                );
                                            }
                                            restored = true;
                                        }
                                    }

                                    if (
                                        !restored &&
                                        Number.isFinite(y) &&
                                        y > 0
                                    ) {
                                        window.scrollTo(0, y);
                                    }
                                })();
                            """.trimIndent(),
                            null,
                        )
                    }
                }

                loadDataWithBaseURL(
                    baseUrl,
                    html,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
        },
        update = { },
        modifier = modifier,
    )
}
