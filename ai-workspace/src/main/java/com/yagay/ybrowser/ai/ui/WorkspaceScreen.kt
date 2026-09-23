package com.yagay.ybrowser.ai.ui

import android.app.Activity
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    webOnly: Boolean = false,
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
    var deleteActionWindowId by remember { mutableStateOf<String?>(null) }

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
                        "这个聊天还没有绑定项目。可以绑定项目，或直接删除这个聊天。"
                    } else {
                        "可以重新绑定、解除当前项目绑定，或删除这个聊天。"
                    }
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        onClick = {
                            val id = bindingActionWindow.id
                            bindingActionWindowId = null
                            vm.requestBinding(id)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (bindingActionWindow.boundUrl.isNullOrBlank()) {
                                "绑定项目"
                            } else {
                                "重新绑定"
                            }
                        )
                    }

                    if (!bindingActionWindow.boundUrl.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                val id = bindingActionWindow.id
                                bindingActionWindowId = null
                                vm.unbindWindow(id)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("解除绑定")
                        }
                    }

                    TextButton(
                        onClick = {
                            val id = bindingActionWindow.id
                            bindingActionWindowId = null
                            deleteActionWindowId = id
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("删除聊天")
                    }

                    TextButton(
                        onClick = {
                            bindingActionWindowId = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("取消")
                    }
                }
            }
        )
    }

    val deleteActionWindow = vm.windows.firstOrNull {
        it.id == deleteActionWindowId
    }
    if (deleteActionWindow != null) {
        val displayName =
            deleteActionWindow.boundProject.orEmpty()
                .ifBlank { deleteActionWindow.title }
                .ifBlank { "聊天" }
        AlertDialog(
            onDismissRequest = {
                deleteActionWindowId = null
            },
            title = {
                Text("删除聊天")
            },
            text = {
                Text(
                    if (deleteActionWindow.boundUrl.isNullOrBlank()) {
                        "确定删除“$displayName”吗？聊天缓存和本地记录也会一起删除。"
                    } else {
                        "确定删除“$displayName”吗？该项目绑定、聊天缓存和本地记录也会一起删除。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = deleteActionWindow.id
                        deleteActionWindowId = null
                        vm.deleteChat(id, runtime)
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deleteActionWindowId = null
                    }
                ) {
                    Text("取消")
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
            vm.onConversationSnapshot(windowId, provider, snapshot)
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

        // Chat and Web are now completely separate surfaces. The native chat
        // never depends on ChatGPT DOM rendering; Gecko stays in the
        // background for protocol sync and sending only.
        runtime.setChatPresentation(
            windowId = vm.activeWindow.id,
            provider = vm.activeProvider,
            enabled = false,
        )

        if (vm.activeWindow.viewMode == WindowViewMode.CHAT) {
            runtime.detachView(
                windowId = vm.activeWindow.id,
                provider = vm.activeProvider,
            )
            vm.syncPage(runtime, vm.activeWindowId)
        }
    }

    val boundPrewarmKey = vm.boundWindows.map {
        Triple(it.id, it.boundUrl, it.lastActiveAt)
    }
    androidx.compose.runtime.LaunchedEffect(
        boundPrewarmKey,
        vm.activeWindowId,
    ) {
        val standbyRetentionMs =
            24L * 60L * 60L * 1_000L
        val standbyCutoff =
            System.currentTimeMillis() - standbyRetentionMs

        runtime.freezeStaleBoundSessions(
            windows = vm.boundWindows,
            activeWindowId = vm.activeWindowId,
            inactiveMs = standbyRetentionMs,
        )

        // Only pages used in the last 24 hours remain eligible for automatic
        // standby warming. Older bound tabs stay frozen until selected again.
        val targets = vm.boundWindows
            .filter {
                it.id != vm.activeWindowId &&
                    it.providerId == "chatgpt" &&
                    !it.boundUrl.isNullOrBlank() &&
                    (
                        it.lastActiveAt <= 0L ||
                            it.lastActiveAt >= standbyCutoff
                    )
            }
            .sortedByDescending { it.lastActiveAt }

        for (window in targets) {
            if (window.id == vm.activeWindowId) continue
            val provider =
                ProviderCatalog.byId(window.providerId)
            if (runtime.hasLiveSession(window.id, provider)) {
                continue
            }

            delay(450)
            if (window.id == vm.activeWindowId) continue

            runtime.prewarm(
                window = window,
                provider = provider,
            )
            DiagnosticLogger.i(
                "COLD",
                "bound_standby_prewarm window=" +
                    window.id.take(12)
            )

            // Keep background warming serialized. A slow provider gets a
            // bounded window, then the next bound tab may begin warming.
            for (attempt in 0 until 40) {
                val readyUrl =
                    runtime.currentUrl(
                        window.id,
                        provider,
                    ).orEmpty()
                if (
                    runtime.isSessionReady(
                        window.id,
                        provider,
                    ) &&
                    (
                        readyUrl == "https://chatgpt.com" ||
                            readyUrl.startsWith(
                                "https://chatgpt.com/"
                            )
                    )
                ) {
                    break
                }
                delay(200)
            }
        }
    }

    BackHandler(
        enabled =
            webOnly ||
                drawerState.isOpen ||
                vm.activeWindow.viewMode == WindowViewMode.WEB,
    ) {
        if (webOnly) {
            if (
                !runtime.goBack(
                    vm.activeWindow.id,
                    vm.activeProvider,
                )
            ) {
                (context as? Activity)?.finish()
            }
        } else if (drawerState.isOpen) {
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
        gesturesEnabled = !webOnly && drawerState.isOpen,
        drawerContent = {
            if (!webOnly) {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxWidth(0.50f)
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
                        val selected =
                            window.id == vm.activeWindowId
                        Surface(
                            shape = RoundedCornerShape(28.dp),
                            color =
                                if (selected) {
                                    MaterialTheme.colorScheme
                                        .secondaryContainer
                                } else {
                                    androidx.compose.ui.graphics.Color
                                        .Transparent
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
                                    window.boundProject.orEmpty()
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
                            if (webOnly) {
                                IconButton(
                                    onClick = {
                                        (context as? Activity)?.finish()
                                    }
                                ) {
                                    Icon(Icons.Default.Close, "返回 AIHub")
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        scope.launch { drawerState.open() }
                                    }
                                ) {
                                    Icon(Icons.Default.Menu, "窗口列表")
                                }
                            }
                        },
                        actions = {
                            if (!webOnly) {
                            if (vm.activeWindow.viewMode == WindowViewMode.CHAT) {
                                IconButton(
                                    onClick = {
                                        vm.syncPage(
                                            runtime,
                                            vm.activeWindowId,
                                        )
                                    },
                                    enabled = !vm.activeWindow.generating
                                ) {
                                    Icon(Icons.Default.Refresh, "刷新聊天")
                                }
                            }

                            if (
                                vm.activeWindow.viewMode ==
                                    WindowViewMode.WEB
                            ) {
                                TextButton(
                                    onClick = {
                                        vm.requestBinding(
                                            vm.activeWindowId
                                        )
                                    }
                                ) {
                                    Text(
                                        if (
                                            vm.activeWindow.boundUrl
                                                .isNullOrBlank()
                                        ) {
                                            "绑定当前页"
                                        } else {
                                            "更换绑定"
                                        }
                                    )
                                }
                            }

                            TextButton(
                                onClick = {
                                    vm.setViewMode(
                                        if (
                                            vm.activeWindow.viewMode ==
                                                WindowViewMode.CHAT
                                        ) {
                                            WindowViewMode.WEB
                                        } else {
                                            WindowViewMode.CHAT
                                        }
                                    )
                                }
                            ) {
                                Text(
                                    if (
                                        vm.activeWindow.viewMode ==
                                            WindowViewMode.CHAT
                                    ) {
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

                    if (!webOnly) {
                        WindowTabStrip(
                            windows = vm.tabWindows,
                            activeWindowId = vm.activeWindowId,
                            focusRevision = launchRevision,
                            onSelect = vm::switchWindow,
                            onLongPress = { bindingActionWindowId = it },
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (
                    webOnly ||
                    vm.activeWindow.viewMode ==
                        WindowViewMode.WEB
                ) {
                    WorkspaceWebHost(
                        runtime = runtime,
                        window = vm.activeWindow,
                        visible = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    NativeChatPane(
                        messages = vm.messages,
                        status = vm.activeStatus,
                        draft = vm.activeDraft,
                        onDraftChange = vm::updateDraft,
                        generating = vm.activeWindow.generating,
                        attachments = vm.activePendingAttachments,
                        onAttach = {
                            nativePickerTarget =
                                vm.activeWindow.id
                            nativeAttachmentPicker.launch(
                                arrayOf("*/*")
                            )
                        },
                        onSend = { vm.send(runtime) },
                        onStop = { vm.stop(runtime) },
                        visible = true,
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
                horizontal = 0.dp,
                vertical = 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp)
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
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 10.dp,
                            vertical = 8.dp,
                        )
                ) {
                    if (attachments.isNotEmpty()) {
                        Text(
                            "📎 " + attachments
                                .joinToString(", ") {
                                    it.name
                                }
                                .take(160),
                            style =
                                MaterialTheme.typography
                                    .labelMedium,
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
                        verticalAlignment =
                            Alignment.Bottom,
                    ) {
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
                            placeholder = {
                                Text("询问任何问题")
                            },
                            minLines = 1,
                            maxLines = 7,
                            shape =
                                RoundedCornerShape(24.dp),
                            colors =
                                TextFieldDefaults.colors(
                                    focusedIndicatorColor =
                                        androidx.compose.ui
                                            .graphics.Color
                                            .Transparent,
                                    unfocusedIndicatorColor =
                                        androidx.compose.ui
                                            .graphics.Color
                                            .Transparent,
                                    disabledIndicatorColor =
                                        androidx.compose.ui
                                            .graphics.Color
                                            .Transparent,
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
private fun MessageBubble(message: ChatMessage) {
    val mine = message.role == MessageRole.USER

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
                        ChatMarkdownContent(
                            text = message.text,
                            userMessage = true,
                            modifier = Modifier.padding(
                                horizontal = 16.dp,
                                vertical = 11.dp,
                            ),
                        )
                    }
                }
            } else {
                SelectionContainer {
                    ChatMarkdownContent(
                        text = message.text,
                        userMessage = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private enum class ChatBlockType {
    PARAGRAPH,
    HEADING_1,
    HEADING_2,
    HEADING_3,
    BULLET,
    NUMBERED,
    QUOTE,
    CODE,
}

private data class ChatTextBlock(
    val type: ChatBlockType,
    val text: String,
    val marker: String = "",
)

private fun parseChatTextBlocks(
    raw: String,
): List<ChatTextBlock> {
    val lines =
        raw.replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
    val blocks = mutableListOf<ChatTextBlock>()
    var index = 0

    fun isSpecial(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("```") ||
            trimmed.startsWith("# ") ||
            trimmed.startsWith("## ") ||
            trimmed.startsWith("### ") ||
            trimmed.startsWith("> ") ||
            trimmed.startsWith("- ") ||
            trimmed.startsWith("* ") ||
            Regex("""^\d+\.\s+.+""")
                .matches(trimmed)
    }

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()

        if (trimmed.isBlank()) {
            index += 1
            continue
        }

        if (trimmed.startsWith("```")) {
            val language =
                trimmed.removePrefix("```").trim()
            index += 1
            val code = mutableListOf<String>()
            while (
                index < lines.size &&
                !lines[index]
                    .trim()
                    .startsWith("```")
            ) {
                code += lines[index]
                index += 1
            }
            if (index < lines.size) {
                index += 1
            }
            blocks += ChatTextBlock(
                type = ChatBlockType.CODE,
                text = code.joinToString("\n"),
                marker = language,
            )
            continue
        }

        when {
            trimmed.startsWith("### ") -> {
                blocks += ChatTextBlock(
                    ChatBlockType.HEADING_3,
                    trimmed.removePrefix("### "),
                )
                index += 1
            }

            trimmed.startsWith("## ") -> {
                blocks += ChatTextBlock(
                    ChatBlockType.HEADING_2,
                    trimmed.removePrefix("## "),
                )
                index += 1
            }

            trimmed.startsWith("# ") -> {
                blocks += ChatTextBlock(
                    ChatBlockType.HEADING_1,
                    trimmed.removePrefix("# "),
                )
                index += 1
            }

            trimmed.startsWith("> ") -> {
                blocks += ChatTextBlock(
                    ChatBlockType.QUOTE,
                    trimmed.removePrefix("> "),
                )
                index += 1
            }

            trimmed.startsWith("- ") ||
                trimmed.startsWith("* ") -> {
                blocks += ChatTextBlock(
                    ChatBlockType.BULLET,
                    trimmed.drop(2),
                    marker = "•",
                )
                index += 1
            }

            Regex("""^\d+\.\s+.+""")
                .matches(trimmed) -> {
                val marker =
                    trimmed.substringBefore(".") + "."
                blocks += ChatTextBlock(
                    ChatBlockType.NUMBERED,
                    trimmed.substringAfter(".").trim(),
                    marker = marker,
                )
                index += 1
            }

            else -> {
                val paragraph = mutableListOf<String>()
                while (
                    index < lines.size &&
                    lines[index].isNotBlank() &&
                    !isSpecial(lines[index])
                ) {
                    paragraph += lines[index].trim()
                    index += 1
                }
                if (paragraph.isNotEmpty()) {
                    blocks += ChatTextBlock(
                        ChatBlockType.PARAGRAPH,
                        paragraph.joinToString("\n"),
                    )
                } else {
                    index += 1
                }
            }
        }
    }

    return blocks
}

@Composable
private fun ChatMarkdownContent(
    text: String,
    userMessage: Boolean,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) {
        parseChatTextBlocks(text)
    }

    Column(
        modifier = modifier,
        verticalArrangement =
            Arrangement.spacedBy(
                if (userMessage) 6.dp else 10.dp
            ),
    ) {
        blocks.forEach { block ->
            when (block.type) {
                ChatBlockType.HEADING_1,
                ChatBlockType.HEADING_2,
                ChatBlockType.HEADING_3 -> {
                    val style = when (block.type) {
                        ChatBlockType.HEADING_1 ->
                            MaterialTheme.typography
                                .headlineSmall
                        ChatBlockType.HEADING_2 ->
                            MaterialTheme.typography
                                .titleLarge
                        else ->
                            MaterialTheme.typography
                                .titleMedium
                    }
                    ChatInlineMarkdown(
                        text = block.text,
                        style = style.copy(
                            lineHeight =
                                when (block.type) {
                                    ChatBlockType.HEADING_1 ->
                                        30.sp
                                    ChatBlockType.HEADING_2 ->
                                        27.sp
                                    else -> 24.sp
                                },
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                ChatBlockType.CODE -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color =
                            MaterialTheme.colorScheme
                                .surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = 13.dp,
                                vertical = 11.dp,
                            ),
                        ) {
                            if (block.marker.isNotBlank()) {
                                Text(
                                    block.marker,
                                    style =
                                        MaterialTheme.typography
                                            .labelSmall,
                                    color =
                                        MaterialTheme.colorScheme
                                            .onSurfaceVariant,
                                    modifier =
                                        Modifier.padding(
                                            bottom = 7.dp,
                                        ),
                                )
                            }
                            Text(
                                block.text,
                                style =
                                    MaterialTheme.typography
                                        .bodyMedium
                                        .copy(
                                            fontFamily =
                                                FontFamily.Monospace,
                                            lineHeight = 20.sp,
                                        ),
                            )
                        }
                    }
                }

                ChatBlockType.BULLET,
                ChatBlockType.NUMBERED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            block.marker,
                            style =
                                MaterialTheme.typography
                                    .bodyLarge
                                    .copy(lineHeight = 26.sp),
                            modifier = Modifier.width(28.dp),
                        )
                        ChatInlineMarkdown(
                            text = block.text,
                            style =
                                MaterialTheme.typography
                                    .bodyLarge
                                    .copy(lineHeight = 26.sp),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                ChatBlockType.QUOTE -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Surface(
                            color =
                                MaterialTheme.colorScheme
                                    .outlineVariant,
                            modifier = Modifier
                                .width(3.dp)
                                .height(24.dp),
                        ) {}
                        ChatInlineMarkdown(
                            text = block.text,
                            style =
                                MaterialTheme.typography
                                    .bodyLarge
                                    .copy(lineHeight = 26.sp),
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        )
                    }
                }

                ChatBlockType.PARAGRAPH -> {
                    ChatInlineMarkdown(
                        text = block.text,
                        style =
                            MaterialTheme.typography
                                .bodyLarge
                                .copy(lineHeight = 26.sp),
                    )
                }
            }
        }

        if (blocks.isEmpty() && text.isNotBlank()) {
            ChatInlineMarkdown(
                text = text,
                style =
                    MaterialTheme.typography
                        .bodyLarge
                        .copy(lineHeight = 26.sp),
            )
        }
    }
}

@Composable
private fun ChatInlineMarkdown(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color =
        MaterialTheme.colorScheme.onSurface,
    fontWeight: FontWeight? = null,
) {
    val inlineCodeColor =
        MaterialTheme.colorScheme.surfaceContainerHighest

    Text(
        text = remember(text, inlineCodeColor) {
            buildChatInlineText(
                text = text,
                inlineCodeColor = inlineCodeColor,
            )
        },
        style = style,
        color = color,
        fontWeight = fontWeight,
        modifier = modifier,
    )
}

private fun buildChatInlineText(
    text: String,
    inlineCodeColor:
        androidx.compose.ui.graphics.Color,
): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0

        while (cursor < text.length) {
            when {
                text.startsWith("**", cursor) -> {
                    val end =
                        text.indexOf("**", cursor + 2)
                    if (end > cursor + 2) {
                        withStyle(
                            SpanStyle(
                                fontWeight =
                                    FontWeight.SemiBold,
                            )
                        ) {
                            append(
                                text.substring(
                                    cursor + 2,
                                    end,
                                )
                            )
                        }
                        cursor = end + 2
                    } else {
                        append(text[cursor])
                        cursor += 1
                    }
                }

                text[cursor] == '`' -> {
                    val end =
                        text.indexOf('`', cursor + 1)
                    if (end > cursor + 1) {
                        withStyle(
                            SpanStyle(
                                fontFamily =
                                    FontFamily.Monospace,
                                background =
                                    inlineCodeColor,
                            )
                        ) {
                            append(
                                text.substring(
                                    cursor + 1,
                                    end,
                                )
                            )
                        }
                        cursor = end + 1
                    } else {
                        append(text[cursor])
                        cursor += 1
                    }
                }

                else -> {
                    val nextBold =
                        text.indexOf("**", cursor)
                            .takeIf { it >= 0 }
                            ?: text.length
                    val nextCode =
                        text.indexOf('`', cursor)
                            .takeIf { it >= 0 }
                            ?: text.length
                    val next =
                        minOf(nextBold, nextCode)
                    append(
                        text.substring(
                            cursor,
                            next,
                        )
                    )
                    cursor = next
                }
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
    val liveRenderReady = remember(
        window.id,
        window.boundUrl,
        window.url,
    ) {
        runtime.isConversationRenderReady(
            window = window,
            provider = provider,
        )
    }
    val coldCandidate =
        provider.id == "chatgpt" &&
            !window.boundUrl.isNullOrBlank() &&
            !liveRenderReady

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
    var handoffInFlight by remember(
        window.id,
        window.boundUrl,
    ) {
        mutableStateOf(false)
    }
    var snapshotVisualReady by remember(
        window.id,
        window.boundUrl,
    ) {
        mutableStateOf(false)
    }
    var holdLiveReveal by remember(
        window.id,
        window.boundUrl,
    ) {
        mutableStateOf(false)
    }
    var recoveryReloaded by remember(
        window.id,
        window.boundUrl,
    ) {
        mutableStateOf(false)
    }

    fun beginLiveHandoff(reason: String) {
        if (handoffInFlight) return
        handoffInFlight = true
        onlineRequested = true

        DiagnosticLogger.i(
            "COLD",
            "live_handoff_begin window=" +
                window.id.take(12) +
                " reason=" + reason
        )

        runtime.requestLiveHandoff(
            window = window,
            provider = provider,
            timeoutMs = 4_000L,
        ) { ready, detail ->
            handoffInFlight = false

            DiagnosticLogger.i(
                "COLD",
                "live_handoff_finish window=" +
                    window.id.take(12) +
                    " ready=" + ready +
                    " detail=" + detail
            )

            holdLiveReveal = false
            if (ready) {
                recoveryReloaded = false
                showSnapshot = false
            } else if (
                showSnapshot &&
                !cachedSnapshot.isNullOrBlank()
            ) {
                // Never replace a usable archive with a live Gecko page that
                // still has no rendered conversation turns.
                showSnapshot = true

                if (!recoveryReloaded) {
                    recoveryReloaded = true
                    runtime.reloadPage(
                        window = window,
                        provider = provider,
                    )
                    beginLiveHandoff(
                        "snapshot-reload-retry"
                    )
                }
            } else {
                showSnapshot = false
            }
        }
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
            snapshotVisualReady = false
            holdLiveReveal = false
            handoffInFlight = false
            return@LaunchedEffect
        }

        if (window.viewMode == WindowViewMode.WEB) {
            archiveChecked = true
            onlineRequested = true
            showSnapshot = false
            snapshotVisualReady = false
            holdLiveReveal = false
            handoffInFlight = false
            return@LaunchedEffect
        }

        archiveChecked = false
        snapshotVisualReady = false
        holdLiveReveal = false
        handoffInFlight = false
        recoveryReloaded = false
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
        val usable =
            status.hasUsableArchive &&
                !localHtml.isNullOrBlank()
        cachedSnapshot = if (usable) localHtml else null
        archiveChecked = true

        if (!usable) {
            DiagnosticLogger.i(
                "COLD",
                "archive_miss window=" +
                    window.id.take(12) +
                    " kind=" + status.kind +
                    " turns=" + status.turnCount +
                    " archiveBytes=" +
                    status.archiveBytes +
                    " legacyBytes=" +
                    status.legacySnapshotBytes +
                    " -> online"
            )
            showSnapshot = false
            // No local archive to cover Gecko. Keep an app-owned surface while
            // the page prepares itself, but only until the one-shot live
            // handoff completes or its bounded fallback fires.
            holdLiveReveal = true
            beginLiveHandoff("archive-miss")
        } else {
            DiagnosticLogger.i(
                "COLD",
                "archive_hit window=" +
                    window.id.take(12) +
                    " kind=" + status.kind +
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
            snapshotVisualReady = false
            holdLiveReveal = false
            onlineRequested =
                runtime.hasLiveSession(window.id, provider)
        }
    }



    // A GeckoSession is not considered visually usable until real
    // conversation turns exist in its DOM. Keep the archive on top while a
    // half-awake standby session resumes. If it already exists, ask the
    // event-driven live handoff to confirm turns; otherwise warm it quietly.
    androidx.compose.runtime.LaunchedEffect(
        window.id,
        provider.id,
        visible,
        showSnapshot,
        snapshotVisualReady,
        onlineRequested,
    ) {
        if (
            !visible ||
            provider.id != "chatgpt" ||
            !showSnapshot ||
            !snapshotVisualReady
        ) {
            return@LaunchedEffect
        }

        if (
            runtime.isConversationRenderReady(
                window = window,
                provider = provider,
            )
        ) {
            showSnapshot = false
            return@LaunchedEffect
        }

        if (
            runtime.hasLiveSession(
                window.id,
                provider,
            )
        ) {
            delay(120)
            if (
                visible &&
                showSnapshot &&
                !handoffInFlight
            ) {
                beginLiveHandoff(
                    "snapshot-live-not-ready"
                )
            }
            return@LaunchedEffect
        }

        if (!onlineRequested) {
            delay(650)
            if (
                visible &&
                showSnapshot &&
                !onlineRequested
            ) {
                runtime.prewarm(
                    window = window,
                    provider = provider,
                )
                DiagnosticLogger.i(
                    "COLD",
                    "snapshot_prewarm window=" +
                        window.id.take(12)
                )
            }
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
                onVisualReady = {
                    if (!snapshotVisualReady) {
                        snapshotVisualReady = true
                        DiagnosticLogger.i(
                            "COLD",
                            "snapshot_visual_ready window=" +
                                window.id.take(12)
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(4f),
            )

            if (
                snapshotVisualReady &&
                !handoffInFlight &&
                (
                    !onlineRequested ||
                        !runtime.isConversationRenderReady(
                            window = window,
                            provider = provider,
                        )
                )
            ) {
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
                                    " reason=continue-chat mode=event"
                            )
                            if (onlineRequested) {
                                recoveryReloaded = true
                                runtime.reloadPage(
                                    window = window,
                                    provider = provider,
                                )
                                beginLiveHandoff(
                                    "manual-reconnect"
                                )
                            } else {
                                beginLiveHandoff(
                                    "continue-chat"
                                )
                            }
                        },
                        modifier = Modifier.padding(
                            horizontal = 8.dp,
                        ),
                    ) {
                        Text(
                            if (onlineRequested) {
                                "重新连接聊天"
                            } else {
                                "继续聊天（联网）"
                            }
                        )
                    }
                }
            }
        }

        if (
            visible &&
            showSnapshot &&
            !snapshotVisualReady
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .zIndex(5f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "正在读取本地历史…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (
            visible &&
            holdLiveReveal &&
            !showSnapshot
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .zIndex(5f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "正在加载最新聊天内容…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StaticSnapshotWebView(
    html: String,
    baseUrl: String?,
    onVisualReady: () -> Unit,
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
                setBackgroundColor(android.graphics.Color.TRANSPARENT)

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

                                    // Cached chat always opens at the newest
                                    // archived content. Do not restore an old
                                    // top/middle scroll position on cold entry.
                                    const moveToBottom = () => {
                                        const turns = Array.from(
                                            document.querySelectorAll(
                                                '[data-aihub-archive-key]'
                                            )
                                        );
                                        const latest =
                                            turns[turns.length - 1] ||
                                            document.querySelector(
                                                '#aihub-frozen-thread'
                                            );
                                        try {
                                            latest?.scrollIntoView?.({
                                                block: 'end',
                                                inline: 'nearest',
                                                behavior: 'auto'
                                            });
                                        } catch (_) {}

                                        try {
                                            const root =
                                                document.scrollingElement ||
                                                document.documentElement ||
                                                document.body;
                                            if (root) {
                                                root.scrollTop =
                                                    root.scrollHeight;
                                            }
                                            window.scrollTo(
                                                0,
                                                Math.max(
                                                    document.body?.scrollHeight || 0,
                                                    document.documentElement?.scrollHeight || 0
                                                )
                                            );
                                        } catch (_) {}
                                    };

                                    moveToBottom();
                                    requestAnimationFrame(() => {
                                        moveToBottom();
                                        requestAnimationFrame(
                                            moveToBottom
                                        );
                                    });
                                    setTimeout(moveToBottom, 120);
                                })();
                            """.trimIndent(),
                        ) {
                            view.postVisualStateCallback(
                                1L,
                                object : WebView.VisualStateCallback() {
                                    override fun onComplete(
                                        requestId: Long,
                                    ) {
                                        onVisualReady()
                                    }
                                },
                            )
                        }
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
