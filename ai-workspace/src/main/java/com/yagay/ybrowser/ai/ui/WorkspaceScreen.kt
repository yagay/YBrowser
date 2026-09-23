package com.yagay.ybrowser.ai.ui
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.horizontalScroll
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData

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

// AIHub presentation/interaction is hosted here, but YBrowser remains the
// sole owner of Gecko sessions, provider execution, persistence, and web mode.
// Do not replace these runtime paths with the standalone AIHub bridge client.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceRoot(
    runtime: WindowWebRuntime,
    launchIntent: Intent? = null,
    launchRevision: Int = 0,
    resumeRevision: Int = 0,
    preparedViewModel: WorkspaceViewModel? = null,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val vm: WorkspaceViewModel =
        preparedViewModel
            ?: viewModel(
                factory =
                    WorkspaceViewModel.Factory(
                        application
                    )
            )
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

            // Opening the native chat must remain UI-only. If Gecko was
            // already started by an explicit action (web/send/attach/refresh),
            // keep the existing background sync behavior. Otherwise render
            // the persisted conversation immediately without booting Gecko.
            if (runtime.isStarted) {
                vm.syncPage(runtime, vm.activeWindowId)
            }
        }
    }

    val boundPrewarmKey = vm.boundWindows.map {
        Triple(it.id, it.boundUrl, it.lastActiveAt)
    }
    androidx.compose.runtime.LaunchedEffect(
        boundPrewarmKey,
        vm.activeWindowId,
    ) {
        // Do not create Gecko merely because the workspace UI was opened.
        // Prewarming is allowed only after an explicit action has already
        // started the runtime in this process.
        if (!runtime.isStarted) {
            return@LaunchedEffect
        }

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
    ) {
        Scaffold(
            topBar = {
                Column {
                    CenterAlignedTopAppBar(
                        title = {
                            Column(
                                horizontalAlignment =
                                    Alignment.CenterHorizontally
                            ) {
                                Text(
                                    vm.activeWindow.boundProject.orEmpty()
                                        .ifBlank {
                                            vm.activeWindow.title
                                        },
                                    maxLines = 1,
                                    fontWeight =
                                        FontWeight.SemiBold
                                )
                                Text(
                                    vm.activeProvider.name,
                                    style =
                                        MaterialTheme.typography
                                            .labelSmall,
                                    color =
                                        MaterialTheme.colorScheme
                                            .onSurfaceVariant
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        drawerState.open()
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.Menu,
                                    "窗口列表"
                                )
                            }
                        },
                        actions = {
                            if (
                                vm.activeWindow.viewMode ==
                                    WindowViewMode.CHAT
                            ) {
                                IconButton(
                                    onClick = {
                                        vm.refreshConversation(
                                            runtime,
                                            vm.activeWindowId,
                                        )
                                    },
                                    enabled =
                                        !vm.activeWindow.generating
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        "刷新聊天"
                                    )
                                }

                                TextButton(
                                    onClick = {
                                        vm.setViewMode(
                                            WindowViewMode.WEB
                                        )
                                    }
                                ) {
                                    Text("网页")
                                }
                            } else {
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

                                TextButton(
                                    onClick = {
                                        vm.setViewMode(
                                            WindowViewMode.CHAT
                                        )
                                    }
                                ) {
                                    Text("聊天")
                                }
                            }

                            IconButton(
                                onClick = {
                                    vm.newWindow(
                                        vm.activeWindow.providerId
                                    )
                                }
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    "新窗口"
                                )
                            }
                        }
                    )

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
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (
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
