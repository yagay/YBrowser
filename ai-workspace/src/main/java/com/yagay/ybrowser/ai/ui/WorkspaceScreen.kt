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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.platform.LocalDensity
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
import com.yagay.browsercore.GeckoCoreFilePromptKind
import com.yagay.browsercore.GeckoCoreFilePromptRequest
import com.yagay.ybrowser.ai.diagnostics.DiagnosticLogger
import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ChatMessage
import com.yagay.ybrowser.ai.model.ChatWindow
import com.yagay.ybrowser.ai.model.MessageRole
import com.yagay.ybrowser.ai.model.WindowViewMode
import com.yagay.ybrowser.ai.provider.ProviderCatalog
import com.yagay.ybrowser.ai.web.AiWorkspaceRuntime
import com.yagay.ybrowser.ai.web.WindowWebRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// AIHub is a browser shell. YBrowser/Gecko owns the live page, login state,
// navigation, uploads, downloads and product rendering. Project tabs and
// binding metadata remain native UI, but conversation content is never
// reconstructed into a second native transcript.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceRoot(
    runtime: AiWorkspaceRuntime,
    launchIntent: Intent? = null,
    launchRevision: Int = 0,
    resumeRevision: Int = 0,
    preparedViewModel: WorkspaceViewModel? = null,
    onClose: (() -> Unit)? = null,
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
    androidx.compose.runtime.LaunchedEffect(resumeRevision) {
        if (resumeRevision > 1) {
            vm.refreshBindingsFromSharedStore()
        }
        if (resumeRevision > 0) {
            runtime.freezeStaleBoundSessions(
                windows = vm.windows,
                activeWindowId = vm.activeWindowId,
            )
        }
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var nativePickerTarget by remember { mutableStateOf<String?>(null) }
    var contextMenuWindowId by remember { mutableStateOf<String?>(null) }
    var titleContextMenuExpanded by remember { mutableStateOf(false) }
    var deleteActionWindowId by remember { mutableStateOf<String?>(null) }

    val browserRuntime =
        runtime as? WindowWebRuntime
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeVisible by remember(
        density,
        imeInsets,
    ) {
        derivedStateOf {
            imeInsets.getBottom(density) > 0
        }
    }
    var preloadWindowId by remember {
        mutableStateOf<String?>(null)
    }
    var preloadGeneration by remember {
        mutableStateOf(0)
    }
    var preloadAttemptsForActive by remember(
        vm.activeWindowId
    ) {
        mutableStateOf(0)
    }

    androidx.compose.runtime.LaunchedEffect(
        drawerState.currentValue
    ) {
        if (
            drawerState.currentValue ==
                DrawerValue.Closed
        ) {
            contextMenuWindowId = null
        }
    }

    var pendingWebFilePrompt by remember {
        mutableStateOf<GeckoCoreFilePromptRequest?>(null)
    }

    val singleWebFilePicker =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts
                    .OpenDocument(),
        ) { uri ->
            val request = pendingWebFilePrompt
            pendingWebFilePrompt = null
            request?.complete(
                uri?.let(::listOf)
            )
        }

    val multipleWebFilePicker =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts
                    .OpenMultipleDocuments(),
        ) { uris ->
            val request = pendingWebFilePrompt
            pendingWebFilePrompt = null
            request?.complete(
                uris.takeIf {
                    it.isNotEmpty()
                }
            )
        }

    val webFolderPicker =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts
                    .OpenDocumentTree(),
        ) { uri ->
            val request = pendingWebFilePrompt
            pendingWebFilePrompt = null
            request?.complete(
                uri?.let(::listOf)
            )
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

    val deleteActionWindow = vm.windows.firstOrNull {
        it.id == deleteActionWindowId
    }
    if (deleteActionWindow != null) {
        val projectBound =
            !deleteActionWindow.boundRepo.isNullOrBlank() ||
                !deleteActionWindow.boundProject.isNullOrBlank()
        val displayName =
            deleteActionWindow.boundProject.orEmpty()
                .ifBlank { deleteActionWindow.title }
                .ifBlank { "聊天" }
        AlertDialog(
            onDismissRequest = {
                deleteActionWindowId = null
            },
            title = {
                Text(
                    if (projectBound) {
                        "删除项目标签"
                    } else {
                        "删除聊天"
                    }
                )
            },
            text = {
                Text(
                    if (!projectBound) {
                        "确定删除“$displayName”吗？聊天缓存和本地记录也会一起删除。"
                    } else {
                        "确定删除项目标签“$displayName”吗？当前网页绑定和该项目的本地聊天历史也会一起删除。仅想换网页请使用“解除网页绑定”。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = deleteActionWindow.id
                        titleContextMenuExpanded = false
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
        runtime.setFilePromptLauncher { request ->
            pendingWebFilePrompt
                ?.takeIf {
                    it !== request
                }
                ?.complete(null)
            pendingWebFilePrompt = request

            val mimeTypes =
                normalizeWorkspaceFileMimeTypes(
                    request.mimeTypes
                )

            when {
                request.kind ==
                    GeckoCoreFilePromptKind.FOLDER -> {
                    webFolderPicker.launch(null)
                }
                request.allowMultiple -> {
                    multipleWebFilePicker.launch(
                        mimeTypes
                    )
                }
                else -> {
                    singleWebFilePicker.launch(
                        mimeTypes
                    )
                }
            }
        }
        runtime.setFileSelectionListener { windowId, _, attachments ->
            vm.onAttachments(windowId, attachments)
        }
        runtime.setPageChangeListener { windowId, provider, url ->
            vm.onPageChanged(windowId, provider, url)
        }
        runtime.setPageReadyListener(null)
        // Conversation text belongs to the live provider page. Do not mirror
        // its body into Native Chat; the provider bridge remains available for
        // metadata/diagnostics and page identity only.
        runtime.setConversationListener(null)
        runtime.setResponseChangeListener(null)

        onDispose {
            pendingWebFilePrompt
                ?.complete(null)
            pendingWebFilePrompt = null
            runtime.setFilePromptLauncher(null)
            runtime.setFileSelectionListener(null)
            runtime.setPageChangeListener(null)
            runtime.setPageReadyListener(null)
            runtime.setConversationListener(null)
            runtime.setResponseChangeListener(null)
            vm.onWorkspaceExit()
            runtime.releaseUi()
        }
    }

    androidx.compose.runtime.LaunchedEffect(
        vm.activeWindowId,
    ) {
        // Browser semantics: switching a project tab is presentation-only.
        // Cancel any old hidden preload; the newly visible tab gets exclusive
        // startup priority until its own composer is ready.
        browserRuntime?.detachPreloadView()
        preloadWindowId = null
        preloadGeneration += 1
    }

    androidx.compose.runtime.LaunchedEffect(
        imeVisible,
    ) {
        if (imeVisible) {
            // A hidden GeckoView doubling the IME resize work is expensive.
            // Release only its View attachment; its Session/state remains
            // available for a later real-viewport preload.
            browserRuntime?.detachPreloadView()
            preloadWindowId = null
        } else {
            // Let the main Gecko viewport settle after the IME animation
            // before considering more background work.
            delay(300L)
            preloadGeneration += 1
        }
    }

    androidx.compose.runtime.LaunchedEffect(
        vm.activeWindowId,
        vm.windows.size,
        preloadGeneration,
        preloadAttemptsForActive,
        imeVisible,
    ) {
        // Hidden TextureView GeckoView preloading is disabled after a main
        // process crash was captured immediately after reattaching a warmed
        // ChatGPT session to the preload surface. Keep only retained sessions
        // that the user has actually opened.
        preloadWindowId = null
    }

    val preloadWindow =
        preloadWindowId?.let { id ->
            vm.windows.firstOrNull {
                it.id == id
            }
        }

    // Native transcript hydration is intentionally disabled. The live web
    // page is the only visible conversation source.

    // Bound tabs are restored on demand. Do not prewarm background Gecko
    // sessions merely because project bindings exist; the runtime keeps only
    // the sessions that explicit user actions actually touched.

    BackHandler(enabled = true) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (
            !runtime.goBack(
                vm.activeWindow.id,
                vm.activeProvider,
            )
        ) {
            onClose?.invoke()
                ?: (context as? android.app.Activity)?.finish()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.safeDrawing
            ),
    ) {
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
                            contextMenuWindowId = null
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
                    Text("新建 AI 标签", modifier = Modifier.padding(start = 8.dp))
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
                            Text("+ 新标签")
                        }
                    }

                    vm.windowsFor(provider.id).forEach { window ->
                        val selected =
                            window.id == vm.activeWindowId

                        Box(
                            modifier = Modifier
                                .padding(
                                    horizontal = 8.dp,
                                    vertical = 2.dp,
                                )
                                .fillMaxWidth(),
                        ) {
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
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            contextMenuWindowId = null
                                            vm.switchWindow(window.id)
                                            scope.launch {
                                                drawerState.close()
                                            }
                                        },
                                        onLongClick = {
                                            contextMenuWindowId =
                                                window.id
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
                                        modifier =
                                            Modifier.weight(1f),
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

                            WindowActionDropdownMenu(
                                window = window,
                                expanded =
                                    contextMenuWindowId ==
                                        window.id,
                                onDismiss = {
                                    contextMenuWindowId =
                                        null
                                },
                                onRequestBinding = { id ->
                                    vm.requestBinding(id)
                                },
                                onUnbind = { id ->
                                    vm.unbindWindow(id)
                                },
                                onDeleteRequest = { id ->
                                    deleteActionWindowId =
                                        id
                                },
                            )
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
            contentWindowInsets =
                WindowInsets(0, 0, 0, 0),
            topBar = {
                Column {
                    CenterAlignedTopAppBar(
                        title = {
                            Box {
                                Column(
                                    horizontalAlignment =
                                        Alignment.CenterHorizontally,
                                    modifier =
                                        Modifier.combinedClickable(
                                            onClick = {},
                                            onLongClick = {
                                                titleContextMenuExpanded =
                                                    true
                                            },
                                        ),
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
                                        buildString {
                                            append(vm.activeProvider.name)
                                            if (
                                                !vm.activeWindow.boundRepo
                                                    .isNullOrBlank() ||
                                                !vm.activeWindow.boundProject
                                                    .isNullOrBlank()
                                            ) {
                                                append(
                                                    if (
                                                        vm.activeWindow.boundUrl
                                                            .isNullOrBlank()
                                                    ) {
                                                        " · 网页未绑定"
                                                    } else {
                                                        " · 网页已绑定"
                                                    }
                                                )
                                            }
                                        },
                                        style =
                                            MaterialTheme.typography
                                                .labelSmall,
                                        color =
                                            MaterialTheme.colorScheme
                                                .onSurfaceVariant
                                    )
                                }

                                WindowActionDropdownMenu(
                                    window =
                                        vm.activeWindow,
                                    expanded =
                                        titleContextMenuExpanded,
                                    onDismiss = {
                                        titleContextMenuExpanded =
                                            false
                                    },
                                    onRequestBinding = { id ->
                                        vm.requestBinding(id)
                                    },
                                    onUnbind = { id ->
                                        vm.unbindWindow(id)
                                    },
                                    onDeleteRequest = { id ->
                                        titleContextMenuExpanded =
                                            false
                                        deleteActionWindowId =
                                            id
                                    },
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    titleContextMenuExpanded = false
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
                            IconButton(
                                onClick = {
                                    runtime.reloadPage(
                                        window = vm.activeWindow,
                                        provider = vm.activeProvider,
                                    )
                                },
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    "刷新网页",
                                )
                            }

                            TextButton(
                                onClick = {
                                    vm.requestBinding(
                                        vm.activeWindowId
                                    )
                                }
                            ) {
                                Text(
                                    if (
                                        vm.activeWindow.boundRepo
                                            .isNullOrBlank() &&
                                        vm.activeWindow.boundProject
                                            .isNullOrBlank()
                                    ) {
                                        "绑定当前页到项目"
                                    } else {
                                        "更换绑定网页"
                                    }
                                )
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

                            if (onClose != null) {
                                IconButton(
                                    onClick = onClose,
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        "关闭 AI 界面",
                                    )
                                }
                            }
                        }
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
                    browserRuntime != null &&
                    preloadWindow != null &&
                    !imeVisible
                ) {
                    WorkspacePreloadHost(
                        runtime = browserRuntime,
                        window = preloadWindow,
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(-1f),
                        onComplete = { windowId, ready, detail ->
                            if (
                                preloadWindowId ==
                                    windowId
                            ) {
                                DiagnosticLogger.i(
                                    "PRELOAD",
                                    "complete window=" +
                                        windowId.take(12) +
                                        " ready=" +
                                        ready +
                                        " detail=" +
                                        detail,
                                )
                                preloadWindowId = null
                                preloadAttemptsForActive += 1
                                preloadGeneration += 1
                            }
                        },
                    )
                }

                WorkspaceWebHost(
                    runtime = runtime,
                    window = vm.activeWindow,
                    visible = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(2f),
                )
            }
        }
    }
    }
}

private fun normalizeWorkspaceFileMimeTypes(
    raw: List<String>,
): Array<String> {
    val normalized =
        raw.asSequence()
            .map { it.trim() }
            .filter {
                it.isNotBlank() &&
                    it != "*"
            }
            .map {
                if ('/' in it) {
                    it
                } else {
                    "*/*"
                }
            }
            .distinct()
            .toList()

    return if (normalized.isEmpty()) {
        arrayOf("*/*")
    } else {
        normalized.toTypedArray()
    }
}

@Composable
private fun WorkspacePreloadHost(
    runtime: WindowWebRuntime,
    window: ChatWindow,
    modifier: Modifier = Modifier,
    onComplete: (
        windowId: String,
        ready: Boolean,
        detail: String,
    ) -> Unit,
) {
    val provider =
        ProviderCatalog.byId(window.providerId)

    AndroidView(
        factory = { context ->
            FrameLayout(context).apply {
                // Keep a full real layout/viewport for Gecko and ChatGPT
                // hydration while remaining completely non-interactive.
                alpha = 0f
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
            }
        },
        update = { host ->
            runtime.attachPreload(
                host = host,
                window = window,
                provider = provider,
            ) { ready, detail ->
                onComplete(
                    window.id,
                    ready,
                    detail,
                )
            }
        },
        modifier = modifier,
    )

    DisposableEffect(
        runtime,
        window.id,
    ) {
        onDispose {
            runtime.detachPreloadView(
                window.id,
                provider,
            )
        }
    }
}
