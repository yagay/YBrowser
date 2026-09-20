package com.yagay.YBrowser

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserApp(
    store: BrowserStore,
    settings: BrowserSettings,
    onSettingsChanged: (BrowserSettings) -> Unit,
    incomingUrl: String?,
    onIncomingConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val initialSession = remember {
        if (settings.restoreTabs) {
            store.loadTabs(settings.homepage)
        } else {
            defaultTabs(settings.homepage)
        }
    }

    var tabs by remember { mutableStateOf(initialSession.first) }
    var selectedTabId by rememberSaveable { mutableLongStateOf(initialSession.second) }
    var nextId by remember {
        mutableLongStateOf((initialSession.first.maxOfOrNull { it.id } ?: 0L) + 1L)
    }
    var bookmarks by remember { mutableStateOf(store.loadBookmarks()) }
    var history by remember { mutableStateOf(store.loadHistory()) }
    var renderState by remember { mutableStateOf(BrowserRenderState()) }
    var addressInput by rememberSaveable { mutableStateOf("") }

    var showTabs by rememberSaveable { mutableStateOf(false) }
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showFind by rememberSaveable { mutableStateOf(false) }
    var findQuery by rememberSaveable { mutableStateOf("") }
    var confirmClearData by rememberSaveable { mutableStateOf(false) }
    var pendingFilePrompt by remember { mutableStateOf<BrowserFilePromptRequest?>(null) }
    var pendingSitePermission by remember { mutableStateOf<BrowserSitePermissionRequest?>(null) }
    var pendingPermissionHandler by remember {
        mutableStateOf<((Map<String, Boolean>) -> Unit)?>(null)
    }
    var customFullscreenView by remember { mutableStateOf<View?>(null) }
    var customFullscreenExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pageFullscreen by rememberSaveable { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val request = pendingFilePrompt
        pendingFilePrompt = null
        if (request != null) {
            request.complete(
                if (request.allowMultiple) uris
                else uris.firstOrNull()?.let(::listOf),
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val handler = pendingPermissionHandler
        pendingPermissionHandler = null
        handler?.invoke(result)
    }

    val selectedTab = tabs.firstOrNull { it.id == selectedTabId }
        ?: tabs.firstOrNull()
        ?: BrowserTab(1L, settings.homepage, "YBrowser")

    val effectiveEngine = if (selectedTab.privateMode) {
        BrowserEngineKind.GECKO
    } else {
        settings.defaultEngine
    }

    val engineConfig = BrowserEngineConfig(
        privateMode = selectedTab.privateMode,
        javaScriptEnabled = settings.javaScriptEnabled,
        cookiesEnabled = settings.cookiesEnabled,
        desktopMode = selectedTab.desktopMode || settings.desktopModeByDefault,
        textScale = settings.textScale,
        trackingProtection = settings.trackingProtection,
    )

    fun openNewTabFromPage(sourceTabId: Long, url: String) {
        if (url.isBlank()) return
        val source = tabs.firstOrNull { it.id == sourceTabId } ?: selectedTab
        val id = nextId++
        tabs = tabs + BrowserTab(
            id = id,
            url = url,
            title = url,
            privateMode = source.privateMode,
            desktopMode = source.desktopMode,
        )
        selectedTabId = id
    }

    val sessionManager = remember(context) {
        TabSessionManager(
            context = context,
            callbacksFactory = { sourceTabId ->
                BrowserHostCallbacks(
                    onFilePrompt = { request ->
                        pendingFilePrompt?.complete(null)
                        pendingFilePrompt = request
                        val mimeTypes = request.mimeTypes
                            .filter { it.isNotBlank() }
                            .ifEmpty { listOf("*/*") }
                            .toTypedArray()
                        filePicker.launch(mimeTypes)
                    },
                    onSitePermission = { request ->
                        pendingSitePermission = request
                    },
                    onAndroidPermissions = { request ->
                        pendingPermissionHandler = { result ->
                            request.complete(
                                request.permissions.all { permission ->
                                    result[permission] == true
                                },
                            )
                        }
                        permissionLauncher.launch(request.permissions.toTypedArray())
                    },
                    onFullscreenChanged = { fullscreen ->
                        if (sourceTabId == selectedTabId) {
                            pageFullscreen = fullscreen
                        }
                    },
                    onCustomView = { view, exit ->
                        if (sourceTabId == selectedTabId) {
                            customFullscreenView = view
                            customFullscreenExit = exit
                            pageFullscreen = view != null
                        } else if (view != null) {
                            exit?.invoke()
                        }
                    },
                    onOpenNewTab = { url ->
                        openNewTabFromPage(sourceTabId, url)
                    },
                )
            },
            onStateChanged = { tabId, state ->
                tabs = tabs.map { tab ->
                    if (tab.id == tabId) {
                        tab.copy(
                            url = state.url.ifBlank { tab.url },
                            title = state.title.ifBlank { tab.title },
                        )
                    } else {
                        tab
                    }
                }
                if (tabId == selectedTabId) {
                    renderState = state
                    if (state.url.isNotBlank()) {
                        addressInput = state.url
                    }
                }
            },
        )
    }

    val engine = remember(selectedTabId, effectiveEngine) {
        sessionManager.acquire(
            tab = selectedTab,
            kind = effectiveEngine,
            config = engineConfig,
        )
    }

    DisposableEffect(sessionManager) {
        onDispose { sessionManager.destroyAll() }
    }


    LaunchedEffect(pageFullscreen, customFullscreenView) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(
            activity.window,
            activity.window.decorView,
        )
        if (pageFullscreen || customFullscreenView != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(selectedTabId, effectiveEngine) {
        val current = tabs.firstOrNull { it.id == selectedTabId } ?: return@LaunchedEffect
        val liveState = sessionManager.state(selectedTabId)
        renderState = liveState ?: BrowserRenderState(
            url = current.url,
            title = current.title,
        )
        addressInput = liveState?.url?.takeIf { it.isNotBlank() } ?: current.url
    }

    LaunchedEffect(engineConfig, selectedTabId) {
        sessionManager.applyConfig(selectedTabId, engineConfig)
    }

    LaunchedEffect(renderState.url) {
        if (renderState.url.isNotBlank()) {
            addressInput = renderState.url
        }
    }

    LaunchedEffect(renderState.url, renderState.loading, selectedTab.privateMode) {
        val url = renderState.url
        if (!selectedTab.privateMode && !renderState.loading && url.isNotBlank()) {
            store.addHistory(url, renderState.title)
            history = store.loadHistory()
        }
    }

    LaunchedEffect(tabs, selectedTabId, settings.restoreTabs) {
        if (settings.restoreTabs) {
            store.saveTabs(tabs, selectedTabId)
        }
    }

    LaunchedEffect(incomingUrl) {
        val target = incomingUrl?.let { resolveInput(it, settings.searchEngine) }
            ?: return@LaunchedEffect
        tabs = tabs.map { tab ->
            if (tab.id == selectedTabId) tab.copy(url = target, title = target) else tab
        }
        addressInput = target
        engine.load(target)
        onIncomingConsumed()
    }

    fun navigate(raw: String) {
        val target = resolveInput(raw, settings.searchEngine)
        tabs = tabs.map { tab ->
            if (tab.id == selectedTabId) tab.copy(url = target, title = target) else tab
        }
        addressInput = target
        engine.load(target)
    }

    fun addTab(privateMode: Boolean) {
        val id = nextId++
        tabs = tabs + BrowserTab(
            id = id,
            url = settings.homepage,
            title = if (privateMode) "隐私标签页" else "新标签页",
            privateMode = privateMode,
            desktopMode = settings.desktopModeByDefault,
        )
        selectedTabId = id
        showTabs = false
    }

    fun closeTab(tabId: Long) {
        val index = tabs.indexOfFirst { it.id == tabId }
        val wasSelected = tabId == selectedTabId
        sessionManager.close(tabId)
        tabs = tabs.filterNot { it.id == tabId }
        if (tabs.isEmpty()) {
            val id = nextId++
            tabs = listOf(BrowserTab(id, settings.homepage, "新标签页"))
            selectedTabId = id
        } else if (wasSelected) {
            selectedTabId = tabs[index.coerceAtMost(tabs.lastIndex)].id
        }
    }

    fun toggleDesktop() {
        val newMode = !selectedTab.desktopMode
        tabs = tabs.map { tab ->
            if (tab.id == selectedTabId) tab.copy(desktopMode = newMode) else tab
        }
        engine.applyConfig(engineConfig.copy(desktopMode = newMode))
        engine.reload()
    }

    BackHandler {
        when {
            customFullscreenView != null -> {
                customFullscreenExit?.invoke()
                customFullscreenView = null
                customFullscreenExit = null
                pageFullscreen = false
            }
            pageFullscreen -> {
                engine.exitFullscreen()
                pageFullscreen = false
            }
            showSettings -> showSettings = false
            showBookmarks -> showBookmarks = false
            showHistory -> showHistory = false
            showTabs -> showTabs = false
            showFind -> {
                showFind = false
                findQuery = ""
                engine.clearFindInPage()
            }
            renderState.canGoBack -> engine.back()
            else -> (context as? ComponentActivity)?.finish()
        }
    }

    val bookmarkAction = {
        val url = renderState.url.ifBlank { selectedTab.url }
        if (bookmarks.any { it.url == url }) {
            bookmarks = bookmarks.filterNot { it.url == url }
        } else {
            bookmarks = listOf(
                BookmarkEntry(
                    url = url,
                    title = renderState.title.ifBlank { selectedTab.title },
                    createdAt = System.currentTimeMillis(),
                ),
            ) + bookmarks
        }
        store.saveBookmarks(bookmarks)
    }

    val chrome: @Composable () -> Unit = {
        BrowserChrome(
            settings = settings,
            selectedTab = selectedTab,
            tabsCount = tabs.size,
            renderState = renderState,
            addressInput = addressInput,
            onAddressInput = { addressInput = it },
            onNavigate = ::navigate,
            onBack = engine::back,
            onForward = engine::forward,
            onShowTabs = { showTabs = true },
            onShowMenu = { showMenu = true },
            showMenu = showMenu,
            onDismissMenu = { showMenu = false },
            onAddTab = { addTab(false) },
            onAddPrivateTab = { addTab(true) },
            onHome = { navigate(settings.homepage) },
            onReload = engine::reload,
            onBookmark = bookmarkAction,
            isBookmarked = bookmarks.any {
                it.url == renderState.url.ifBlank { selectedTab.url }
            },
            onShowBookmarks = { showBookmarks = true },
            onShowHistory = {
                history = store.loadHistory()
                showHistory = true
            },
            onShowFind = { showFind = true },
            onToggleDesktop = ::toggleDesktop,
            onShare = { shareUrl(context, renderState.url.ifBlank { selectedTab.url }) },
            onCopy = { copyUrl(context, renderState.url.ifBlank { selectedTab.url }) },
            onDownloads = { openDownloads(context) },
            onPrint = {
                if (!engine.printPage()) {
                    Toast.makeText(context, "当前内核无法打印此网页", Toast.LENGTH_SHORT).show()
                }
            },
            onOpenExternal = {
                openExternalUrl(context, renderState.url.ifBlank { selectedTab.url })
            },
            onSettings = { showSettings = true },
        )
    }

    val findBar: @Composable () -> Unit = {
        if (showFind) {
            FindBar(
                query = findQuery,
                onQueryChanged = {
                    findQuery = it
                    if (it.isNotBlank()) engine.findInPage(it, true)
                    else engine.clearFindInPage()
                },
                onPrevious = { engine.findInPage(findQuery, false) },
                onNext = { engine.findInPage(findQuery, true) },
                onClose = {
                    showFind = false
                    findQuery = ""
                    engine.clearFindInPage()
                },
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (pageFullscreen || customFullscreenView != null) {
                    Modifier
                } else {
                    Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                },
            ),
    ) {
        key(effectiveEngine, selectedTabId) {
            AndroidView(
                factory = { engine.view },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (customFullscreenView != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                key(customFullscreenView) {
                    AndroidView(
                        factory = { customFullscreenView!! },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (!pageFullscreen && customFullscreenView == null &&
            settings.toolbarPosition == ToolbarPosition.TOP
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                chrome()
                if (showFind) {
                    Spacer(Modifier.height(5.dp))
                    findBar()
                }
            }
        } else if (!pageFullscreen && customFullscreenView == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (showFind) {
                    findBar()
                    Spacer(Modifier.height(5.dp))
                }
                chrome()
            }
        }
    }

    if (showTabs) {
        ModalBottomSheet(onDismissRequest = { showTabs = false }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "标签页",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    effectiveEngine.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                IconButton(onClick = { addTab(false) }) {
                    Icon(Icons.Outlined.Add, contentDescription = "新标签页")
                }
                IconButton(onClick = { addTab(true) }) {
                    Icon(Icons.Outlined.Lock, contentDescription = "新建隐私标签")
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
            ) {
                items(tabs, key = { it.id }) { tab ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 5.dp)
                            .clickable {
                                selectedTabId = tab.id
                                showTabs = false
                            },
                        shape = RoundedCornerShape(20.dp),
                        color = if (tab.id == selectedTabId) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                start = 16.dp,
                                top = 12.dp,
                                bottom = 12.dp,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (tab.privateMode) {
                                        Icon(
                                            Icons.Outlined.Lock,
                                            contentDescription = null,
                                        )
                                        Spacer(Modifier.padding(horizontal = 2.dp))
                                    }
                                    Text(
                                        tab.title.ifBlank { "新标签页" },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    tab.url,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { closeTab(tab.id) }) {
                                Icon(Icons.Outlined.Close, contentDescription = "关闭标签")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showBookmarks) {
        BookmarkSheet(
            bookmarks = bookmarks,
            onDismiss = { showBookmarks = false },
            onOpen = {
                showBookmarks = false
                navigate(it.url)
            },
            onRemove = {
                bookmarks = bookmarks.filterNot { item -> item.url == it.url }
                store.saveBookmarks(bookmarks)
            },
        )
    }

    if (showHistory) {
        HistorySheet(
            history = history,
            onDismiss = { showHistory = false },
            onOpen = {
                showHistory = false
                navigate(it.url)
            },
            onClear = {
                store.clearHistory()
                history = emptyList()
            },
        )
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            onChange = onSettingsChanged,
            onDismiss = { showSettings = false },
            onClearData = { confirmClearData = true },
            onDefaultBrowser = { requestDefaultBrowser(context) },
        )
    }

    pendingSitePermission?.let { request ->
        AlertDialog(
            onDismissRequest = {
                request.complete(emptySet())
                pendingSitePermission = null
            },
            title = { Text("网站权限") },
            text = {
                val names = request.permissions.joinToString("、") {
                    when (it) {
                        BrowserSitePermission.CAMERA -> "摄像头"
                        BrowserSitePermission.MICROPHONE -> "麦克风"
                        BrowserSitePermission.LOCATION -> "位置"
                    }
                }
                Text((request.origin.ifBlank { "当前网站" }) + " 请求使用：" + names)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingSitePermission = null
                        val androidPermissions = buildList {
                            if (BrowserSitePermission.CAMERA in request.permissions) {
                                add(Manifest.permission.CAMERA)
                            }
                            if (BrowserSitePermission.MICROPHONE in request.permissions) {
                                add(Manifest.permission.RECORD_AUDIO)
                            }
                            if (BrowserSitePermission.LOCATION in request.permissions) {
                                add(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        }
                        if (androidPermissions.isEmpty()) {
                            request.complete(request.permissions)
                        } else {
                            pendingPermissionHandler = { result ->
                                val allowed = request.permissions.filterTo(mutableSetOf()) {
                                    when (it) {
                                        BrowserSitePermission.CAMERA ->
                                            result[Manifest.permission.CAMERA] == true
                                        BrowserSitePermission.MICROPHONE ->
                                            result[Manifest.permission.RECORD_AUDIO] == true
                                        BrowserSitePermission.LOCATION ->
                                            result[Manifest.permission.ACCESS_FINE_LOCATION] == true
                                    }
                                }
                                request.complete(allowed)
                            }
                            permissionLauncher.launch(androidPermissions.toTypedArray())
                        }
                    },
                ) {
                    Text("允许")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        request.complete(emptySet())
                        pendingSitePermission = null
                    },
                ) {
                    Text("拒绝")
                }
            },
        )
    }

    if (confirmClearData) {
        AlertDialog(
            onDismissRequest = { confirmClearData = false },
            title = { Text("清除浏览数据") },
            text = {
                Text("将清除历史记录、Cookie、站点存储和两个内核的浏览数据。收藏夹不会删除。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearData = false
                        store.clearHistory()
                        history = emptyList()
                        clearAllBrowserEngineData(context) {
                            Toast.makeText(
                                context,
                                if (it) "浏览数据已清除" else "部分浏览数据清理失败",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                ) {
                    Text("清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearData = false }) {
                    Text("取消")
                }
            },
        )
    }
}

private fun defaultTabs(homepage: String): Pair<List<BrowserTab>, Long> {
    val tab = BrowserTab(
        id = 1L,
        url = homepage,
        title = "YBrowser",
    )
    return listOf(tab) to tab.id
}

private fun resolveInput(raw: String, searchEngine: SearchEngine): String {
    val input = raw.trim()
    if (input.isBlank()) return "about:blank"
    return when {
        input.startsWith("http://", true) ||
            input.startsWith("https://", true) ||
            input.startsWith("about:", true) -> input
        !input.contains(' ') &&
            (input.contains('.') || input.startsWith("localhost", true)) ->
            "https://" + input
        else -> searchEngine.template.format(Uri.encode(input))
    }
}

private fun shareUrl(context: Context, url: String) {
    if (url.isBlank()) return
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(share, "分享网页"))
}

private fun copyUrl(context: Context, url: String) {
    if (url.isBlank()) return
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
    Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
}

private fun openDownloads(context: Context) {
    runCatching {
        context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
    }.onFailure {
        Toast.makeText(context, "无法打开下载管理", Toast.LENGTH_SHORT).show()
    }
}

private fun openExternalUrl(context: Context, url: String) {
    if (url.isBlank()) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private fun requestDefaultBrowser(context: Context) {
    runCatching {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER) &&
            !roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)
        ) {
            context.startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER))
        } else {
            context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }.onFailure {
        context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }
}
