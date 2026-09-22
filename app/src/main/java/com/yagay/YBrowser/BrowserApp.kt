package com.yagay.YBrowser

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Rational
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale

private data class PendingSitePermissionUi(
    val request: BrowserSitePermissionRequest,
    val host: String?,
    val privateMode: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserApp(
    store: BrowserStore,
    settings: BrowserSettings,
    onSettingsChanged: (BrowserSettings) -> Unit,
    incomingUrl: String?,
    incomingReuseExisting: Boolean = false,
    incomingRequestRevision: Int = 0,
    onIncomingConsumed: () -> Unit,
    showBrowserChrome: Boolean = true,
    externalReloadSignal: Int = 0,
    bindingController: BrowserBindingController? = null,
    retainedSessionKey: String? = null,
    persistentPageUrls: List<String> = emptyList(),
    onCurrentPageChanged: (String, String) -> Unit = { _, _ -> },
    recordHistory: Boolean = true,
    browserChromeOverride: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val effectiveProfileId = if (retainedSessionKey != null) {
        DEFAULT_BROWSER_PROFILE_ID
    } else {
        settings.activeProfileId
    }
    val profileLabel = BrowserProfileRepository.name(context, effectiveProfileId)
    val initialSession = remember(retainedSessionKey, incomingUrl, effectiveProfileId) {
        if (retainedSessionKey != null && !incomingUrl.isNullOrBlank()) {
            val target = resolveInput(incomingUrl, settings)
            val id = retainedSessionTabId(target)
            listOf(
                BrowserTab(
                    id = id,
                    url = target,
                    title = target,
                    privateMode = false,
                    desktopMode = settings.desktopModeByDefault,
                ),
            ) to id
        } else if (settings.restoreTabs) {
            store.loadTabs(newTabUrl(settings), effectiveProfileId)
        } else {
            defaultTabs(newTabUrl(settings))
        }
    }

    var tabs by remember { mutableStateOf(initialSession.first) }
    var lastClosedTab by remember { mutableStateOf<BrowserTab?>(null) }
    var selectedTabId by rememberSaveable { mutableLongStateOf(initialSession.second) }
    var nextId by remember {
        mutableLongStateOf((initialSession.first.maxOfOrNull { it.id } ?: 0L) + 1L)
    }
    var bookmarks by remember(effectiveProfileId) {
        mutableStateOf(store.loadBookmarks(effectiveProfileId))
    }
    var history by remember(effectiveProfileId) {
        mutableStateOf(store.loadHistory(effectiveProfileId))
    }
    var renderState by remember { mutableStateOf(BrowserRenderState()) }
    var addressInput by rememberSaveable { mutableStateOf("") }

    var showTabs by rememberSaveable { mutableStateOf(false) }
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showExtensions by rememberSaveable { mutableStateOf(false) }
    var showProfiles by rememberSaveable { mutableStateOf(false) }
    var profileRevision by remember { mutableStateOf(0) }
    var showUserScripts by rememberSaveable { mutableStateOf(false) }
    var userScriptsRevision by remember { mutableStateOf(0) }
    var showCustomFilters by rememberSaveable { mutableStateOf(false) }
    var customFiltersRevision by remember { mutableStateOf(0) }
    var showPrivacyReport by rememberSaveable { mutableStateOf(false) }
    var privacyEvents by remember {
        mutableStateOf<Map<Long, List<BrowserPrivacyEvent>>>(emptyMap())
    }
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showDownloads by rememberSaveable { mutableStateOf(false) }
    var downloadStates by remember { mutableStateOf<List<BrowserDownloadState>>(emptyList()) }
    var showFind by rememberSaveable { mutableStateOf(false) }
    var showSiteSettings by rememberSaveable { mutableStateOf(false) }
    var findQuery by rememberSaveable { mutableStateOf("") }
    var confirmClearData by rememberSaveable { mutableStateOf(false) }
    var siteSettingsRevision by remember { mutableStateOf(0) }
    var pendingFilePrompt by remember { mutableStateOf<BrowserFilePromptRequest?>(null) }
    var pendingSitePermission by remember { mutableStateOf<PendingSitePermissionUi?>(null) }
    var pendingPermissionHandler by remember {
        mutableStateOf<((Map<String, Boolean>) -> Unit)?>(null)
    }
    var customFullscreenView by remember { mutableStateOf<View?>(null) }
    var customFullscreenExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pageFullscreen by rememberSaveable { mutableStateOf(false) }
    var pendingContentTarget by remember { mutableStateOf<BrowserContentTarget?>(null) }
    var pendingWebPrompt by remember { mutableStateOf<BrowserWebPromptRequest?>(null) }
    var webPromptInput by remember { mutableStateOf("") }
    var pendingAuthPrompt by remember { mutableStateOf<BrowserAuthPromptRequest?>(null) }
    var authUsername by remember { mutableStateOf("") }
    var authPassword by remember { mutableStateOf("") }
    var tabPreviews by remember { mutableStateOf<Map<Long, Bitmap>>(emptyMap()) }
    var readerDocument by remember { mutableStateOf<ReaderDocument?>(null) }
    var showReaderLibrary by rememberSaveable { mutableStateOf(false) }
    var showSnoozedTabs by rememberSaveable { mutableStateOf(false) }
    var readerLoading by remember { mutableStateOf(false) }
    var mediaStates by remember {
        mutableStateOf<Map<Long, BrowserMediaState>>(emptyMap())
    }
    var activeMediaTabId by remember { mutableStateOf<Long?>(null) }
    var localBindingRevision by remember { mutableStateOf(0) }
    var backupRestoreRevision by remember { mutableStateOf(0) }
    var sessionResetRevision by remember { mutableStateOf(0) }

    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            val result = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(
                        BrowserBackupManager.exportJson(context)
                            .toByteArray(Charsets.UTF_8),
                    )
                } ?: error("无法打开导出文件")
                true
            }.getOrDefault(false)
            Toast.makeText(
                context,
                if (result) "备份已导出" else "备份导出失败",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val raw = runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
            }.getOrNull()
            val result = if (raw.isNullOrBlank()) {
                BrowserBackupResult(false, "无法读取备份文件")
            } else {
                BrowserBackupManager.importJson(context, raw)
            }
            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
            if (result.success) {
                val restoredSettings = store.loadSettings()
                onSettingsChanged(restoredSettings)
                val restoredProfileId = if (retainedSessionKey != null) {
                    DEFAULT_BROWSER_PROFILE_ID
                } else {
                    restoredSettings.activeProfileId
                }
                bookmarks = store.loadBookmarks(restoredProfileId)
                history = store.loadHistory(restoredProfileId)
                userScriptsRevision += 1
                customFiltersRevision += 1
                backupRestoreRevision += 1
            }
        }
    }

    val singleFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val request = pendingFilePrompt
        pendingFilePrompt = null
        request?.complete(uri?.let(::listOf))
    }

    val multipleFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val request = pendingFilePrompt
        pendingFilePrompt = null
        request?.complete(uris.takeIf { it.isNotEmpty() })
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
        ?: BrowserTab(1L, newTabUrl(settings), "YBrowser")

    val effectiveEngine = if (selectedTab.privateMode) {
        BrowserEngineKind.GECKO
    } else {
        settings.defaultEngine
    }

    val currentPageUrl = renderState.url.ifBlank { selectedTab.url }
    val showingNativeHome =
        settings.nativeNewTabPage && currentPageUrl == NATIVE_NEW_TAB_URL
    val currentPageTitle = renderState.title
        .ifBlank { selectedTab.title }
        .ifBlank { "YBrowser" }
    val canBindCurrentPage =
        bindingController != null &&
            isBindableWebPage(currentPageUrl)
    val currentPageBinding = remember(
        currentPageUrl,
        bindingController,
        bindingController?.revision,
        localBindingRevision,
    ) {
        bindingController?.findBinding?.invoke(currentPageUrl)
    }

    LaunchedEffect(currentPageUrl, currentPageTitle) {
        onCurrentPageChanged(currentPageUrl, currentPageTitle)
    }

    val selectedHost = browserHost(currentPageUrl)
    val selectedSiteSettings = remember(
        selectedHost,
        siteSettingsRevision,
        effectiveProfileId,
    ) {
        selectedHost?.let { host ->
            store.loadSiteSettings(host, effectiveProfileId)
        }
    }
    val enabledUserScripts = remember(userScriptsRevision) {
        BrowserUserScriptRepository.enabled(context)
    }
    val customBlockedHosts = remember(customFiltersRevision) {
        CustomFilterRepository.list(context).toSet()
    }

    fun configForSite(site: SiteSettings?): BrowserEngineConfig = BrowserEngineConfig(
        privateMode = selectedTab.privateMode,
        javaScriptEnabled = site?.javaScriptEnabled ?: settings.javaScriptEnabled,
        cookiesEnabled = site?.cookiesEnabled ?: settings.cookiesEnabled,
        desktopMode = selectedTab.desktopMode || settings.desktopModeByDefault,
        textScale = site?.textScale ?: settings.textScale,
        trackingProtection = site?.trackingProtection ?: settings.trackingProtection,
        blockAutoplay = settings.blockAutoplay,
        muted = site?.muted == true,
        userScripts = enabledUserScripts,
        customBlockedHosts = customBlockedHosts,
        profileId = effectiveProfileId,
    )

    val engineConfig = configForSite(selectedSiteSettings)

    fun handleMediaState(tabId: Long, incoming: BrowserMediaState?) {
        val ended = incoming != null &&
            !incoming.playing &&
            incoming.durationMs > 0L &&
            incoming.positionMs >= incoming.durationMs - 750L
        val state = if (ended) null else incoming

        val updated = if (state == null) {
            mediaStates - tabId
        } else {
            mediaStates + (tabId to state)
        }
        mediaStates = updated

        activeMediaTabId = when {
            state?.playing == true -> tabId
            activeMediaTabId == tabId && state == null ->
                updated.entries.lastOrNull { it.value.playing }?.key
            activeMediaTabId == null ->
                updated.entries.lastOrNull { it.value.playing }?.key
            else -> activeMediaTabId
        }

        val active = activeMediaTabId?.let(updated::get)
        if (active == null) {
            BrowserMediaRuntime.clear(context)
        } else {
            BrowserMediaRuntime.update(context, active)
        }
    }

    fun openNewTabFromPage(sourceTabId: Long, url: String, select: Boolean = true) {
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
        if (select) selectedTabId = id
    }

    val sessionCallbacksFactory: (Long) -> BrowserHostCallbacks = { sourceTabId ->
        BrowserHostCallbacks(
            onFilePrompt = { request ->
                pendingFilePrompt?.complete(null)
                pendingFilePrompt = request
                val mimeTypes = normalizeFilePickerMimeTypes(
                    request.mimeTypes,
                ).toTypedArray()
                if (request.allowMultiple) {
                    multipleFilePicker.launch(mimeTypes)
                } else {
                    singleFilePicker.launch(mimeTypes)
                }
            },
            onSitePermission = { request ->
                val source = tabs.firstOrNull { it.id == sourceTabId }
                val host = browserHost(request.origin)
                if (source?.privateMode == true || host == null) {
                    pendingSitePermission = PendingSitePermissionUi(
                        request = request,
                        host = host,
                        privateMode = source?.privateMode == true,
                    )
                } else {
                    val preAllowed = request.permissions.filterTo(mutableSetOf()) { permission ->
                        store.loadSitePermissionDecision(host, permission, effectiveProfileId) ==
                            SitePermissionDecision.ALLOW
                    }
                    val ask = request.permissions.filterTo(mutableSetOf()) { permission ->
                        store.loadSitePermissionDecision(host, permission, effectiveProfileId) ==
                            SitePermissionDecision.ASK
                    }
                    if (ask.isEmpty()) {
                        request.complete(preAllowed)
                    } else {
                        pendingSitePermission = PendingSitePermissionUi(
                            request = BrowserSitePermissionRequest(
                                origin = request.origin,
                                permissions = ask,
                                complete = { granted ->
                                    request.complete(preAllowed + granted)
                                },
                            ),
                            host = host,
                            privateMode = false,
                        )
                    }
                }
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
            onContentLongPress = { target ->
                if (sourceTabId == selectedTabId) {
                    pendingContentTarget = target
                }
            },
            onWebPrompt = { request ->
                if (sourceTabId == selectedTabId) {
                    pendingWebPrompt?.dismiss?.invoke()
                    pendingWebPrompt = request
                    webPromptInput = request.defaultValue.orEmpty()
                } else {
                    request.dismiss()
                }
            },
            onAuthPrompt = { request ->
                if (sourceTabId == selectedTabId) {
                    pendingAuthPrompt?.dismiss?.invoke()
                    pendingAuthPrompt = request
                    authUsername = ""
                    authPassword = ""
                } else {
                    request.dismiss()
                }
            },
            onMediaState = { mediaState ->
                handleMediaState(sourceTabId, mediaState)
            },
            onContentBlocked = { event ->
                val current = privacyEvents[sourceTabId].orEmpty()
                privacyEvents = privacyEvents + (
                    sourceTabId to (current + event).takeLast(500)
                )
            },
        )
    }

    val sessionStateChanged: (Long, BrowserRenderState) -> Unit = { tabId, state ->
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
    }

    val sessionManager = remember(context, retainedSessionKey) {
        if (retainedSessionKey != null) {
            BrowserSessionRegistry.get(context, retainedSessionKey)
        } else {
            TabSessionManager(context)
        }
    }

    SideEffect {
        sessionManager.attachHandlers(
            hostContext = context,
            callbacksFactory = sessionCallbacksFactory,
            onStateChanged = sessionStateChanged,
        )
    }

    fun keepPreview(tabId: Long, bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        val old = tabPreviews[tabId]
        tabPreviews = tabPreviews + (tabId to bitmap)
        if (old != null && old !== bitmap && !old.isRecycled) {
            old.recycle()
        }
    }

    fun refreshTabPreviews() {
        sessionManager.captureAllPreviews { tabId, bitmap ->
            keepPreview(tabId, bitmap)
        }
    }

    val engine = remember(
        selectedTabId,
        effectiveEngine,
        effectiveProfileId,
        sessionResetRevision,
    ) {
        sessionManager.acquire(
            tab = selectedTab,
            kind = effectiveEngine,
            config = engineConfig,
        )
    }

    LaunchedEffect(externalReloadSignal) {
        if (externalReloadSignal > 0) {
            engine.reload()
        }
    }

    LaunchedEffect(backupRestoreRevision) {
        if (backupRestoreRevision <= 0 || retainedSessionKey != null) {
            return@LaunchedEffect
        }
        val restoredSettings = store.loadSettings()
        tabs.forEach { tab ->
            sessionManager.close(tab.id)
        }
        val restored = if (restoredSettings.restoreTabs) {
            store.loadTabs(
                newTabUrl(restoredSettings),
                restoredSettings.activeProfileId,
            )
        } else {
            defaultTabs(newTabUrl(restoredSettings))
        }
        tabs = restored.first
        selectedTabId = restored.second
        nextId = (restored.first.maxOfOrNull { it.id } ?: 0L) + 1L
        tabPreviews.values.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        tabPreviews = emptyMap()
        privacyEvents = emptyMap()
        sessionResetRevision += 1
    }

    val mediaCommandHandler = remember(sessionManager) {
        { command: BrowserMediaCommand ->
            val tabId = activeMediaTabId
            if (tabId != null) {
                if (command == BrowserMediaCommand.STOP) {
                    mediaStates = mediaStates - tabId
                    activeMediaTabId = mediaStates.entries
                        .lastOrNull { it.value.playing }
                        ?.key
                    BrowserMediaRuntime.clear(context)
                }
                sessionManager.mediaCommand(tabId, command)
            }
        }
    }

    val activeMediaState = activeMediaTabId?.let(mediaStates::get)

    DisposableEffect(selectedTabId, sessionManager) {
        val leavingTabId = selectedTabId
        onDispose {
            sessionManager.capturePreview(leavingTabId) { bitmap ->
                keepPreview(leavingTabId, bitmap)
            }
        }
    }

    DisposableEffect(sessionManager, retainedSessionKey) {
        BrowserMediaRuntime.bind(mediaCommandHandler)
        onDispose {
            BrowserMediaRuntime.unbind(mediaCommandHandler)
            BrowserMediaRuntime.clear(context)
            if (retainedSessionKey != null) {
                sessionManager.detachHandlers()
            } else {
                sessionManager.destroyAll()
            }
            tabPreviews.values.forEach { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }


    LaunchedEffect(pageFullscreen, customFullscreenView) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val fullscreenMedia = pageFullscreen || customFullscreenView != null
        val controller = WindowCompat.getInsetsController(
            activity.window,
            activity.window.decorView,
        )
        if (fullscreenMedia) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }

        runCatching {
            activity.setPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setAutoEnterEnabled(fullscreenMedia)
                    .setSeamlessResizeEnabled(true)
                    .build(),
            )
        }
    }

    LaunchedEffect(selectedTabId, effectiveEngine) {
        val now = System.currentTimeMillis()
        tabs = tabs.map { tab ->
            if (tab.id == selectedTabId) {
                tab.copy(lastAccessedAt = now)
            } else {
                tab
            }
        }
        val current = tabs.firstOrNull { it.id == selectedTabId } ?: return@LaunchedEffect
        val liveState = sessionManager.state(selectedTabId)
        renderState = liveState ?: BrowserRenderState(
            url = current.url,
            title = current.title,
        )
        addressInput = (liveState?.url?.takeIf { it.isNotBlank() } ?: current.url)
            .let { if (settings.nativeNewTabPage && it == NATIVE_NEW_TAB_URL) "" else it }
    }

    LaunchedEffect(engineConfig, selectedTabId) {
        sessionManager.applyConfig(selectedTabId, engineConfig)
    }

    LaunchedEffect(renderState.url) {
        if (renderState.url.isNotBlank()) {
            addressInput = if (
                settings.nativeNewTabPage && renderState.url == NATIVE_NEW_TAB_URL
            ) {
                ""
            } else {
                renderState.url
            }
        }
    }

    LaunchedEffect(
        renderState.url,
        renderState.loading,
        selectedTab.privateMode,
        recordHistory,
    ) {
        val url = renderState.url
        if (
            recordHistory &&
            !selectedTab.privateMode &&
            !renderState.loading &&
            url.isNotBlank()
        ) {
            store.addHistory(url, renderState.title, effectiveProfileId)
            history = store.loadHistory(effectiveProfileId)
        }
    }

    LaunchedEffect(
        tabs,
        selectedTabId,
        settings.restoreTabs,
        retainedSessionKey,
    ) {
        if (settings.restoreTabs && retainedSessionKey == null) {
            store.saveTabs(tabs, selectedTabId, effectiveProfileId)
        }
    }

    LaunchedEffect(
        retainedSessionKey,
        persistentPageUrls,
        settings.defaultEngine,
        settings.javaScriptEnabled,
        settings.cookiesEnabled,
        settings.desktopModeByDefault,
        settings.textScale,
        settings.trackingProtection,
        settings.blockAutoplay,
        userScriptsRevision,
        customFiltersRevision,
        siteSettingsRevision,
    ) {
        if (retainedSessionKey == null) return@LaunchedEffect

        val urls = persistentPageUrls
            .map(::normalizeReusableUrl)
            .filter { it.isNotBlank() && isBindableWebPage(it) }
            .distinct()
        if (urls.isEmpty()) return@LaunchedEffect

        val additions = urls.mapNotNull { url ->
            val id = retainedSessionTabId(url)
            if (tabs.any { it.id == id }) {
                null
            } else {
                BrowserTab(
                    id = id,
                    url = url,
                    title = url,
                    privateMode = false,
                    desktopMode = settings.desktopModeByDefault,
                )
            }
        }
        val knownTabs = if (additions.isEmpty()) tabs else tabs + additions
        if (additions.isNotEmpty()) {
            tabs = knownTabs
        }

        urls.forEach { url ->
            val id = retainedSessionTabId(url)
            val tab = knownTabs.firstOrNull { it.id == id }
                ?: BrowserTab(
                    id = id,
                    url = url,
                    title = url,
                    privateMode = false,
                    desktopMode = settings.desktopModeByDefault,
                )
            val host = browserHost(url)
            val site = host?.let { store.loadSiteSettings(it, DEFAULT_BROWSER_PROFILE_ID) }
            val config = BrowserEngineConfig(
                privateMode = false,
                javaScriptEnabled = site?.javaScriptEnabled
                    ?: settings.javaScriptEnabled,
                cookiesEnabled = site?.cookiesEnabled
                    ?: settings.cookiesEnabled,
                desktopMode = tab.desktopMode ||
                    settings.desktopModeByDefault,
                textScale = site?.textScale ?: settings.textScale,
                trackingProtection = site?.trackingProtection
                    ?: settings.trackingProtection,
                blockAutoplay = settings.blockAutoplay,
                muted = site?.muted == true,
                userScripts = enabledUserScripts,
                customBlockedHosts = customBlockedHosts,
                profileId = DEFAULT_BROWSER_PROFILE_ID,
            )
            sessionManager.acquire(
                tab = tab,
                kind = settings.defaultEngine,
                config = config,
            )
        }
    }

    LaunchedEffect(
        incomingUrl,
        incomingReuseExisting,
        retainedSessionKey,
        incomingRequestRevision,
    ) {
        val target = incomingUrl?.let { resolveInput(it, settings) }
            ?: return@LaunchedEffect

        if (
            retainedSessionKey != null &&
            persistentPageUrls.any { sameReusableUrl(it, target) }
        ) {
            val id = retainedSessionTabId(target)
            val liveUrl = sessionManager.state(id)?.url
                ?.takeIf { it.isNotBlank() }
                ?: target
            if (tabs.none { it.id == id }) {
                tabs = tabs + BrowserTab(
                    id = id,
                    url = liveUrl,
                    title = liveUrl,
                    privateMode = false,
                    desktopMode = settings.desktopModeByDefault,
                )
            }
            selectedTabId = id
            addressInput = liveUrl
            showTabs = false
            onIncomingConsumed()
            return@LaunchedEffect
        }

        if (incomingReuseExisting) {
            val currentUrl = sessionManager.state(selectedTabId)?.url
                ?.takeIf { it.isNotBlank() }
                ?: selectedTab.url
            if (sameReusableUrl(currentUrl, target)) {
                addressInput = currentUrl
                onIncomingConsumed()
                return@LaunchedEffect
            }

            val existingTab = tabs.firstOrNull { tab ->
                val liveUrl = sessionManager.state(tab.id)?.url
                    ?.takeIf { it.isNotBlank() }
                    ?: tab.url
                sameReusableUrl(liveUrl, target)
            }
            if (existingTab != null) {
                selectedTabId = existingTab.id
                addressInput = sessionManager.state(existingTab.id)?.url
                    ?.takeIf { it.isNotBlank() }
                    ?: existingTab.url
                showTabs = false
                onIncomingConsumed()
                return@LaunchedEffect
            }
        }

        tabs = tabs.map { tab ->
            if (tab.id == selectedTabId) tab.copy(url = target, title = target) else tab
        }
        addressInput = target
        engine.load(target)
        onIncomingConsumed()
    }

    fun navigate(raw: String) {
        val command = raw.trim()
        if (command.startsWith(">")) {
            when (command.drop(1).trim().lowercase()) {
                "new", "tab", "新标签" -> {
                    val id = nextId++
                    tabs = tabs + BrowserTab(
                        id = id,
                        url = newTabUrl(settings),
                        title = "新标签页",
                        privateMode = false,
                        desktopMode = settings.desktopModeByDefault,
                    )
                    selectedTabId = id
                    showTabs = false
                }
                "private", "incognito", "隐私" -> {
                    val id = nextId++
                    tabs = tabs + BrowserTab(
                        id = id,
                        url = newTabUrl(settings),
                        title = "隐私标签页",
                        privateMode = true,
                        desktopMode = settings.desktopModeByDefault,
                    )
                    selectedTabId = id
                    showTabs = false
                }
                "tabs", "标签" -> {
                    refreshTabPreviews()
                    showTabs = true
                }
                "history", "历史" -> {
                    history = store.loadHistory(effectiveProfileId)
                    showHistory = true
                }
                "downloads", "下载" -> {
                    downloadStates = BrowserDownloadRepository.states(context)
                    showDownloads = true
                }
                "settings", "设置" -> showSettings = true
                "home", "主页" -> {
                    val target = settings.homepage
                    tabs = tabs.map { tab ->
                        if (tab.id == selectedTabId) {
                            tab.copy(url = target, title = target)
                        } else {
                            tab
                        }
                    }
                    addressInput = target
                    engine.load(target)
                }
                "back", "后退" -> engine.back()
                "forward", "前进" -> engine.forward()
                "reload", "refresh", "刷新" -> engine.reload()
                "pin", "固定" -> {
                    tabs = tabs.map { tab ->
                        if (tab.id == selectedTabId) tab.copy(pinned = !tab.pinned) else tab
                    }
                }
                "close", "关闭" -> {
                    // handled by the normal close action from the tab manager;
                    // keep the current page unchanged here to avoid surprising data loss.
                    Toast.makeText(
                        context,
                        "请在标签页管理中关闭当前标签",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                "help", "帮助" -> {
                    Toast.makeText(
                        context,
                        ">new  >private  >tabs  >history  >downloads  >settings  >home  >pin",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                else -> {
                    Toast.makeText(
                        context,
                        "未知命令，输入 >help 查看可用命令",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            return
        }

        val target = resolveInput(raw, settings)
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
            url = newTabUrl(settings),
            title = if (privateMode) "隐私标签页" else "新标签页",
            privateMode = privateMode,
            desktopMode = settings.desktopModeByDefault,
        )
        selectedTabId = id
        showTabs = false
    }

    fun duplicateTab(tabId: Long) {
        val source = tabs.firstOrNull { it.id == tabId } ?: return
        val liveUrl = sessionManager.state(tabId)?.url
            ?.takeIf { it.isNotBlank() }
            ?: source.url
        val id = nextId++
        tabs = tabs + source.copy(
            id = id,
            url = liveUrl,
            title = source.title.ifBlank { liveUrl },
            pinned = false,
        )
        selectedTabId = id
        showTabs = false
    }

    fun togglePin(tabId: Long) {
        tabs = tabs.map { tab ->
            if (tab.id == tabId) tab.copy(pinned = !tab.pinned) else tab
        }
    }

    fun closeTab(tabId: Long) {
        val closing = tabs.firstOrNull { it.id == tabId } ?: return
        val index = tabs.indexOfFirst { it.id == tabId }
        val wasSelected = tabId == selectedTabId
        if (retainedSessionKey == null) {
            lastClosedTab = closing.copy(
                url = sessionManager.state(tabId)?.url
                    ?.takeIf { it.isNotBlank() }
                    ?: closing.url,
            )
        }
        handleMediaState(tabId, null)
        sessionManager.close(tabId)
        tabPreviews[tabId]?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        tabPreviews = tabPreviews - tabId
        tabs = tabs.filterNot { it.id == tabId }
        if (tabs.isEmpty()) {
            val id = nextId++
            tabs = listOf(BrowserTab(id, newTabUrl(settings), "新标签页"))
            selectedTabId = id
        } else if (wasSelected) {
            selectedTabId = tabs[index.coerceAtMost(tabs.lastIndex)].id
        }
    }

    LaunchedEffect(
        effectiveProfileId,
        settings.autoCloseTabsDays,
        retainedSessionKey,
    ) {
        val days = settings.autoCloseTabsDays
        if (retainedSessionKey != null || days <= 0) return@LaunchedEffect
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        val staleIds = tabs
            .filter { tab ->
                !tab.privateMode &&
                    !tab.pinned &&
                    tab.lastAccessedAt < cutoff
            }
            .map { it.id }
        staleIds.forEach(::closeTab)
    }

    fun closeOtherTabs(tabId: Long) {
        val targets = tabs.filter { it.id != tabId && !it.pinned }
        targets.forEach { closeTab(it.id) }
        if (tabs.any { it.id == tabId }) {
            selectedTabId = tabId
        }
    }

    fun closeUnpinnedTabs() {
        val selectedWasPinned = tabs.firstOrNull { it.id == selectedTabId }?.pinned == true
        val targets = tabs.filterNot { it.pinned }
        targets.forEach { closeTab(it.id) }
        if (tabs.isEmpty()) {
            addTab(false)
        } else if (!selectedWasPinned) {
            selectedTabId = tabs.first().id
        }
    }

    fun reopenLastClosedTab() {
        val closed = lastClosedTab ?: return
        val id = nextId++
        val restored = closed.copy(id = id)
        tabs = tabs + restored
        selectedTabId = id
        lastClosedTab = null
        showTabs = false
    }

    fun switchProfile(profile: BrowserProfile) {
        if (retainedSessionKey != null || profile.id == effectiveProfileId) {
            showProfiles = false
            return
        }

        store.saveTabs(tabs, selectedTabId, effectiveProfileId)
        tabs.forEach { tab ->
            handleMediaState(tab.id, null)
            sessionManager.close(tab.id)
        }
        tabPreviews.values.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        tabPreviews = emptyMap()
        privacyEvents = emptyMap()
        lastClosedTab = null

        val nextSettings = settings.copy(activeProfileId = profile.id)
        onSettingsChanged(nextSettings)
        val restored = if (nextSettings.restoreTabs) {
            store.loadTabs(newTabUrl(nextSettings), profile.id)
        } else {
            defaultTabs(newTabUrl(nextSettings))
        }
        tabs = restored.first
        selectedTabId = restored.second
        nextId = (restored.first.maxOfOrNull { it.id } ?: 0L) + 1L
        bookmarks = store.loadBookmarks(profile.id)
        history = store.loadHistory(profile.id)
        siteSettingsRevision += 1
        sessionResetRevision += 1
        profileRevision += 1
        showProfiles = false
    }

    fun deleteProfile(profile: BrowserProfile) {
        if (
            retainedSessionKey != null ||
            profile.id == DEFAULT_BROWSER_PROFILE_ID ||
            profile.id == effectiveProfileId
        ) {
            return
        }
        store.deleteProfileData(profile.id)
        BrowserProfileStorage.clear(context, profile.id)
        BrowserProfileRepository.remove(context, profile.id)
        profileRevision += 1
    }

    fun setTabGroup(tabId: Long, group: String?) {
        val normalized = group?.trim()?.take(40)?.takeIf { it.isNotBlank() }
        tabs = tabs.map { tab ->
            if (tab.id == tabId) tab.copy(groupName = normalized) else tab
        }
    }

    fun moveTab(tabId: Long, delta: Int) {
        val from = tabs.indexOfFirst { it.id == tabId }
        if (from < 0 || tabs.size < 2) return
        val to = (from + delta).coerceIn(0, tabs.lastIndex)
        if (from == to) return
        val mutable = tabs.toMutableList()
        val tab = mutable.removeAt(from)
        mutable.add(to, tab)
        tabs = mutable
    }

    fun snoozeTab(tabId: Long, wakeAt: Long) {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.privateMode) {
            Toast.makeText(
                context,
                "隐私标签不会保存到休眠列表",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val liveUrl = sessionManager.state(tabId)?.url
            ?.takeIf { it.isNotBlank() }
            ?: tab.url
        val saved = TabSnoozeManager.snooze(
            context,
            tab.copy(url = liveUrl),
            wakeAt,
        )
        if (!saved) {
            Toast.makeText(context, "无法休眠此标签", Toast.LENGTH_SHORT).show()
            return
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        closeTab(tabId)
        Toast.makeText(context, "标签已休眠", Toast.LENGTH_SHORT).show()
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
            readerDocument != null -> {
                readerDocument = null
                readerLoading = false
            }
            readerLoading -> readerLoading = false
            showReaderLibrary -> showReaderLibrary = false
            showSnoozedTabs -> showSnoozedTabs = false
            pendingWebPrompt != null -> {
                pendingWebPrompt?.dismiss?.invoke()
                pendingWebPrompt = null
            }
            pendingAuthPrompt != null -> {
                pendingAuthPrompt?.dismiss?.invoke()
                pendingAuthPrompt = null
            }
            pendingContentTarget != null -> pendingContentTarget = null
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
            showSiteSettings -> showSiteSettings = false
            showPrivacyReport -> showPrivacyReport = false
            showProfiles -> showProfiles = false
            showUserScripts -> showUserScripts = false
            showCustomFilters -> showCustomFilters = false
            showExtensions -> showExtensions = false
            showSettings -> showSettings = false
            showBookmarks -> showBookmarks = false
            showHistory -> showHistory = false
            showDownloads -> showDownloads = false
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
        store.saveBookmarks(bookmarks, effectiveProfileId)
    }

    val chrome: @Composable () -> Unit = {
        BrowserChrome(
            settings = settings,
            selectedTab = selectedTab,
            tabsCount = tabs.size,
            renderState = renderState,
            addressInput = addressInput,
            onAddressInput = { addressInput = it },
            addressSuggestions = localAddressSuggestions(
                query = addressInput,
                bookmarks = bookmarks,
                history = history,
                limit = 4,
            ),
            onNavigate = ::navigate,
            onBack = engine::back,
            onForward = engine::forward,
            onPreviousTab = {
                val index = tabs.indexOfFirst { it.id == selectedTabId }
                if (index >= 0 && tabs.size > 1) {
                    selectedTabId = tabs[
                        (index - 1 + tabs.size) % tabs.size
                    ].id
                }
            },
            onNextTab = {
                val index = tabs.indexOfFirst { it.id == selectedTabId }
                if (index >= 0 && tabs.size > 1) {
                    selectedTabId = tabs[
                        (index + 1) % tabs.size
                    ].id
                }
            },
            onShowTabs = {
                refreshTabPreviews()
                showTabs = true
            },
            onShowMenu = { showMenu = true },
            showMenu = showMenu,
            onDismissMenu = { showMenu = false },
            onAddTab = { addTab(false) },
            onAddPrivateTab = { addTab(true) },
            onHome = { navigate(settings.homepage) },
            onQrScan = {
                runCatching {
                    com.google.mlkit.vision.codescanner.GmsBarcodeScanning
                        .getClient(context)
                        .startScan()
                        .addOnSuccessListener { barcode ->
                            barcode.rawValue
                                ?.takeIf { it.isNotBlank() }
                                ?.let(::navigate)
                        }
                        .addOnFailureListener { error ->
                            Toast.makeText(
                                context,
                                error.message ?: "扫码失败",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                }.onFailure { error ->
                    Toast.makeText(
                        context,
                        error.message ?: "无法启动扫码器",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onReload = engine::reload,
            onBookmark = bookmarkAction,
            isBookmarked = bookmarks.any {
                it.url == renderState.url.ifBlank { selectedTab.url }
            },
            onShowBookmarks = { showBookmarks = true },
            onShowHistory = {
                history = store.loadHistory(effectiveProfileId)
                showHistory = true
            },
            onShowFind = { showFind = true },
            onToggleDesktop = ::toggleDesktop,
            onShare = { shareUrl(context, renderState.url.ifBlank { selectedTab.url }) },
            onCopy = { copyUrl(context, renderState.url.ifBlank { selectedTab.url }) },
            onDownloads = {
                downloadStates = BrowserDownloadRepository.states(context)
                showDownloads = true
            },
            onReader = {
                val currentUrl = renderState.url.ifBlank { selectedTab.url }
                if (!currentUrl.startsWith("http://") &&
                    !currentUrl.startsWith("https://")
                ) {
                    Toast.makeText(
                        context,
                        "当前页面不支持阅读模式",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else if (!readerLoading) {
                    val requestTabId = selectedTabId
                    readerLoading = true
                    engine.extractReader { document ->
                        if (selectedTabId != requestTabId) {
                            readerLoading = false
                            return@extractReader
                        }
                        readerLoading = false
                        if (document == null || document.blocks.isEmpty()) {
                            Toast.makeText(
                                context,
                                "没有提取到可阅读的正文",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            readerDocument = document
                        }
                    }
                }
            },
            onOfflineReader = { showReaderLibrary = true },
            onSnoozedTabs = { showSnoozedTabs = true },
            onPrint = {
                if (!engine.printPage()) {
                    Toast.makeText(context, "当前内核无法打印此网页", Toast.LENGTH_SHORT).show()
                }
            },
            onTranslate = {
                val url = renderState.url.ifBlank { selectedTab.url }
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    navigate(translatePageUrl(url))
                } else {
                    Toast.makeText(context, "当前页面无法翻译", Toast.LENGTH_SHORT).show()
                }
            },
            onViewSource = {
                val url = renderState.url.ifBlank { selectedTab.url }
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    navigate("view-source:" + url)
                } else {
                    Toast.makeText(context, "当前页面没有可查看的网页源代码", Toast.LENGTH_SHORT).show()
                }
            },
            onOpenExternal = {
                openExternalUrl(context, renderState.url.ifBlank { selectedTab.url })
            },
            onSiteSettings = {
                if (selectedHost != null) {
                    showSiteSettings = true
                } else {
                    Toast.makeText(context, "当前页面没有可配置的网站域名", Toast.LENGTH_SHORT).show()
                }
            },
            onPrivacyReport = { showPrivacyReport = true },
            blockedCount = privacyEvents[selectedTabId].orEmpty().size,
            onUserScripts = { showUserScripts = true },
            onCustomFilters = { showCustomFilters = true },
            onProfiles = { showProfiles = true },
            profileLabel = profileLabel,
            onExtensions = { showExtensions = true },
            onSettings = { showSettings = true },
            showBindingAction = bindingController != null,
            bindingLabel = when {
                bindingController?.targetLabel != null ->
                    "绑定 · " + bindingController.targetLabel
                currentPageBinding != null ->
                    "已绑 · " + currentPageBinding.label
                canBindCurrentPage -> "绑定"
                else -> "不可绑定"
            },
            bindingActive = currentPageBinding != null,
            bindingEnabled = canBindCurrentPage,
            onBindingClick = {
                val controller = bindingController
                if (controller != null) {
                    when {
                        !canBindCurrentPage -> {
                            Toast.makeText(
                                context,
                                "当前页面不是可绑定的网页",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        controller.targetLabel != null -> {
                            controller.bindToTarget(
                                currentPageUrl,
                                currentPageTitle,
                            )
                            localBindingRevision++
                        }
                        currentPageBinding != null -> {
                            controller.unbind(currentPageUrl)
                            localBindingRevision++
                        }
                        else -> {
                            controller.requestBinding(
                                currentPageUrl,
                                currentPageTitle,
                            )
                        }
                    }
                }
            },
            onMenuShortcutsChanged = { shortcuts ->
                onSettingsChanged(settings.copy(menuShortcuts = shortcuts))
            },
        )
    }

    val activeChrome: @Composable () -> Unit =
        browserChromeOverride ?: chrome

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
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            if (
                showBrowserChrome &&
                !pageFullscreen &&
                customFullscreenView == null &&
                settings.toolbarPosition == ToolbarPosition.TOP
            ) {
                activeChrome()
                activeMediaState?.let { media ->
                    BrowserMediaMiniBar(
                        state = media,
                        onOpenMediaTab = {
                            activeMediaTabId?.let { selectedTabId = it }
                        },
                        onToggle = {
                            mediaCommandHandler(BrowserMediaCommand.TOGGLE)
                        },
                        onStop = {
                            mediaCommandHandler(BrowserMediaCommand.STOP)
                        },
                    )
                }
                if (showFind) {
                    Spacer(Modifier.height(5.dp))
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp),
                    ) {
                        findBar()
                    }
                    Spacer(Modifier.height(5.dp))
                }
            }

            if (
                showingNativeHome &&
                !pageFullscreen &&
                customFullscreenView == null
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    BrowserHomePage(
                        searchEngine = settings.searchEngine,
                        bookmarks = bookmarks,
                        history = history,
                        privateMode = selectedTab.privateMode,
                        onNavigate = ::navigate,
                    )
                }
            } else {
                key(effectiveEngine, selectedTabId) {
                    AndroidView(
                        factory = { engine.view },
                        modifier = if (
                            pageFullscreen || customFullscreenView != null
                        ) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        },
                    )
                }
            }

            if (
                showBrowserChrome &&
                !pageFullscreen &&
                customFullscreenView == null &&
                settings.toolbarPosition == ToolbarPosition.BOTTOM
            ) {
                if (showFind) {
                    Spacer(Modifier.height(5.dp))
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp),
                    ) {
                        findBar()
                    }
                    Spacer(Modifier.height(5.dp))
                }
                activeMediaState?.let { media ->
                    BrowserMediaMiniBar(
                        state = media,
                        onOpenMediaTab = {
                            activeMediaTabId?.let { selectedTabId = it }
                        },
                        onToggle = {
                            mediaCommandHandler(BrowserMediaCommand.TOGGLE)
                        },
                        onStop = {
                            mediaCommandHandler(BrowserMediaCommand.STOP)
                        },
                    )
                }
                activeChrome()
            }
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

        readerDocument?.let { document ->
            ReaderScreen(
                document = document,
                defaultTextScale = settings.textScale,
                onClose = {
                    readerDocument = null
                    readerLoading = false
                },
                onOpenSource = {
                    readerDocument = null
                    readerLoading = false
                },
            )
        }

        if (readerLoading && readerDocument == null) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(14.dp))
                        Text("正在提取正文…")
                    }
                }
            }
        }
    }


    if (showSnoozedTabs) {
        SnoozedTabsSheet(
            onDismiss = { showSnoozedTabs = false },
            onOpen = { item ->
                val id = nextId++
                tabs = tabs + BrowserTab(
                    id = id,
                    url = item.url,
                    title = item.title,
                    privateMode = false,
                    desktopMode = settings.desktopModeByDefault,
                )
                selectedTabId = id
                showSnoozedTabs = false
            },
        )
    }

    if (showReaderLibrary) {
        ReaderLibrarySheet(
            onDismiss = { showReaderLibrary = false },
            onOpen = { document ->
                showReaderLibrary = false
                readerDocument = document
                readerLoading = false
            },
        )
    }

    if (showTabs) {
        TabSwitcherSheet(
            tabs = tabs,
            selectedTabId = selectedTabId,
            previews = tabPreviews,
            engineLabel = effectiveEngine.label,
            canReopenClosed = lastClosedTab != null,
            layout = settings.tabSwitcherLayout,
            onLayoutChanged = { layout ->
                onSettingsChanged(settings.copy(tabSwitcherLayout = layout))
            },
            onDismiss = { showTabs = false },
            onSelect = { tabId ->
                selectedTabId = tabId
            },
            onClose = ::closeTab,
            onTogglePin = ::togglePin,
            onDuplicate = ::duplicateTab,
            onCloseOthers = ::closeOtherTabs,
            onCloseUnpinned = ::closeUnpinnedTabs,
            onReopenClosed = ::reopenLastClosedTab,
            onMove = ::moveTab,
            onSetGroup = ::setTabGroup,
            onSnooze = ::snoozeTab,
            onShowSnoozed = {
                showTabs = false
                showSnoozedTabs = true
            },
            onAddTab = { addTab(false) },
            onAddPrivateTab = { addTab(true) },
        )
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
                store.saveBookmarks(bookmarks, effectiveProfileId)
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
                store.clearHistory(effectiveProfileId)
                history = emptyList()
            },
        )
    }


    if (showDownloads) {
        DownloadsSheet(
            downloads = downloadStates,
            onRefresh = {
                downloadStates = BrowserDownloadRepository.states(context)
            },
            onOpen = { item ->
                if (!BrowserDownloadRepository.open(context, item)) {
                    Toast.makeText(context, "无法打开该文件", Toast.LENGTH_SHORT).show()
                }
            },
            onShare = { item ->
                if (!BrowserDownloadRepository.share(context, item)) {
                    Toast.makeText(context, "无法分享该文件", Toast.LENGTH_SHORT).show()
                }
            },
            onRetry = { item ->
                BrowserDownloadRepository.retry(context, item.record)
                downloadStates = BrowserDownloadRepository.states(context)
            },
            onDelete = { item ->
                BrowserDownloadRepository.cancelAndDelete(context, item.record.id)
                downloadStates = BrowserDownloadRepository.states(context)
            },
            onClearCompleted = {
                BrowserDownloadRepository.clearCompletedRecords(context)
                downloadStates = BrowserDownloadRepository.states(context)
            },
            onDismiss = { showDownloads = false },
        )
    }

    if (showSiteSettings && selectedHost != null) {
        SiteSettingsSheet(
            host = selectedHost,
            current = selectedSiteSettings,
            global = settings,
            permissionDecisions = BrowserSitePermission.entries.associateWith { permission ->
                store.loadSitePermissionDecision(selectedHost, permission, effectiveProfileId)
            },
            onPermissionChanged = { permission, decision ->
                store.saveSitePermissionDecision(
                    selectedHost,
                    permission,
                    decision,
                    effectiveProfileId,
                )
            },
            onSave = { saved ->
                store.saveSiteSettings(saved, effectiveProfileId)
                siteSettingsRevision += 1
                sessionManager.applyConfig(selectedTabId, configForSite(saved))
                engine.reload()
            },
            onReset = {
                store.clearSiteSettings(selectedHost, effectiveProfileId)
                siteSettingsRevision += 1
                sessionManager.applyConfig(selectedTabId, configForSite(null))
                engine.reload()
            },
            onResetPermissions = {
                store.clearSitePermissionDecisions(selectedHost, effectiveProfileId)
                Toast.makeText(context, "已清除此网站的权限决定", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showSiteSettings = false },
        )
    }

    if (showPrivacyReport) {
        PrivacyReportSheet(
            pageUrl = currentPageUrl,
            protection = selectedSiteSettings?.trackingProtection
                ?: settings.trackingProtection,
            events = privacyEvents[selectedTabId].orEmpty(),
            onClear = {
                privacyEvents = privacyEvents + (selectedTabId to emptyList())
            },
            onDismiss = { showPrivacyReport = false },
        )
    }

    if (showProfiles && retainedSessionKey == null) {
        ProfilesSheet(
            activeProfileId = effectiveProfileId,
            onSwitch = ::switchProfile,
            onDelete = ::deleteProfile,
            onDismiss = { showProfiles = false },
        )
    }

    if (showCustomFilters) {
        CustomFiltersSheet(
            onDismiss = { showCustomFilters = false },
            onChanged = { customFiltersRevision += 1 },
        )
    }

    if (showUserScripts) {
        UserScriptsSheet(
            onDismiss = { showUserScripts = false },
            onChanged = { userScriptsRevision += 1 },
        )
    }

    if (showExtensions) {
        ExtensionsSheet(
            onDismiss = { showExtensions = false },
            onOpenUrl = { url ->
                showExtensions = false
                openNewTabFromPage(selectedTabId, url)
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
            onExtensions = {
                showSettings = false
                showExtensions = true
            },
            onUserScripts = {
                showSettings = false
                showUserScripts = true
            },
            onCustomFilters = {
                showSettings = false
                showCustomFilters = true
            },
            onProfiles = {
                showSettings = false
                showProfiles = true
            },
            profileLabel = profileLabel,
            onExportBackup = {
                backupExportLauncher.launch("YBrowser-backup.json")
            },
            onImportBackup = {
                backupImportLauncher.launch(arrayOf("application/json", "text/plain"))
            },
        )
    }

    pendingContentTarget?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { pendingContentTarget = null },
        ) {
            Text(
                when (target.kind) {
                    BrowserContentTargetKind.LINK -> "链接"
                    BrowserContentTargetKind.IMAGE -> "图片"
                    BrowserContentTargetKind.IMAGE_LINK -> "图片链接"
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                target.url,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (
                target.kind == BrowserContentTargetKind.LINK ||
                target.kind == BrowserContentTargetKind.IMAGE_LINK
            ) {
                ListItem(
                    headlineContent = { Text("预览链接") },
                    supportingContent = { Text("在临时浮窗中打开，不加入标签页") },
                    modifier = Modifier.clickable {
                        pendingContentTarget = null
                        val previewIntent = Intent(
                            context,
                            PopupBrowserActivity::class.java,
                        ).apply {
                            putExtra(MainActivity.EXTRA_URL, target.url)
                            putExtra(PopupBrowserActivity.EXTRA_TRANSIENT_PREVIEW, true)
                        }
                        context.startActivity(previewIntent)
                    },
                )
            }
            ListItem(
                headlineContent = { Text("在新标签页打开") },
                modifier = Modifier.clickable {
                    pendingContentTarget = null
                    openNewTabFromPage(selectedTabId, target.url, true)
                },
            )
            ListItem(
                headlineContent = { Text("在后台标签页打开") },
                modifier = Modifier.clickable {
                    pendingContentTarget = null
                    openNewTabFromPage(selectedTabId, target.url, false)
                },
            )
            ListItem(
                headlineContent = { Text("复制链接") },
                modifier = Modifier.clickable {
                    pendingContentTarget = null
                    copyUrl(context, target.url)
                },
            )
            ListItem(
                headlineContent = { Text("分享") },
                modifier = Modifier.clickable {
                    pendingContentTarget = null
                    shareUrl(context, target.url)
                },
            )
            ListItem(
                headlineContent = { Text("下载") },
                modifier = Modifier.clickable {
                    pendingContentTarget = null
                    downloadUrl(context, target.imageUrl ?: target.url)
                },
            )
            if (!target.imageUrl.isNullOrBlank() && target.imageUrl != target.url) {
                ListItem(
                    headlineContent = { Text("复制图片地址") },
                    modifier = Modifier.clickable {
                        pendingContentTarget = null
                        copyUrl(context, target.imageUrl)
                    },
                )
                ListItem(
                    headlineContent = { Text("保存图片") },
                    modifier = Modifier.clickable {
                        pendingContentTarget = null
                        downloadUrl(context, target.imageUrl)
                    },
                )
            }
            ListItem(
                headlineContent = { Text("外部应用打开") },
                modifier = Modifier
                    .clickable {
                        pendingContentTarget = null
                        openExternalUrl(context, target.url)
                    }
                    .padding(bottom = 24.dp),
            )
        }
    }

    pendingWebPrompt?.let { request ->
        AlertDialog(
            onDismissRequest = {
                request.dismiss()
                pendingWebPrompt = null
            },
            title = {
                Text(
                    request.title?.takeIf { it.isNotBlank() } ?: when (request.kind) {
                        BrowserWebPromptKind.ALERT -> "网页提示"
                        BrowserWebPromptKind.CONFIRM -> "网页确认"
                        BrowserWebPromptKind.TEXT -> "网页输入"
                        BrowserWebPromptKind.BEFORE_UNLOAD -> "离开网页？"
                        BrowserWebPromptKind.REPOST -> "重新提交表单？"
                    },
                )
            },
            text = {
                Column {
                    request.message?.takeIf { it.isNotBlank() }?.let { Text(it) }
                    if (request.kind == BrowserWebPromptKind.TEXT) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = webPromptInput,
                            onValueChange = { webPromptInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingWebPrompt = null
                        request.confirm(
                            if (request.kind == BrowserWebPromptKind.TEXT) {
                                webPromptInput
                            } else {
                                null
                            },
                        )
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = if (request.kind == BrowserWebPromptKind.ALERT) {
                null
            } else {
                {
                    TextButton(
                        onClick = {
                            pendingWebPrompt = null
                            request.dismiss()
                        },
                    ) {
                        Text("取消")
                    }
                }
            },
        )
    }

    pendingAuthPrompt?.let { request ->
        AlertDialog(
            onDismissRequest = {
                request.dismiss()
                pendingAuthPrompt = null
            },
            title = { Text("网站身份验证") },
            text = {
                Column {
                    Text(
                        buildString {
                            append(request.uri)
                            request.realm?.takeIf { it.isNotBlank() }?.let {
                                append("\n")
                                append(it)
                            }
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!request.onlyPassword) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = authUsername,
                            onValueChange = { authUsername = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("用户名") },
                            singleLine = true,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = authPassword,
                        onValueChange = { authPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAuthPrompt = null
                        request.confirm(authUsername, authPassword)
                    },
                ) {
                    Text("登录")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingAuthPrompt = null
                        request.dismiss()
                    },
                ) {
                    Text("取消")
                }
            },
        )
    }

    pendingSitePermission?.let { pending ->
        val request = pending.request

        fun grant(save: Boolean) {
            pendingSitePermission = null
            if (save && !pending.privateMode && pending.host != null) {
                request.permissions.forEach { permission ->
                    store.saveSitePermissionDecision(
                        pending.host,
                        permission,
                        SitePermissionDecision.ALLOW,
                        effectiveProfileId,
                    )
                }
            }

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
        }

        fun deny(save: Boolean) {
            pendingSitePermission = null
            if (save && !pending.privateMode && pending.host != null) {
                request.permissions.forEach { permission ->
                    store.saveSitePermissionDecision(
                        pending.host,
                        permission,
                        SitePermissionDecision.BLOCK,
                        effectiveProfileId,
                    )
                }
            }
            request.complete(emptySet())
        }

        AlertDialog(
            onDismissRequest = { deny(false) },
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
                Row {
                    TextButton(onClick = { grant(false) }) {
                        Text("允许一次")
                    }
                    if (!pending.privateMode && pending.host != null) {
                        TextButton(onClick = { grant(true) }) {
                            Text("始终允许")
                        }
                    }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { deny(false) }) {
                        Text("拒绝一次")
                    }
                    if (!pending.privateMode && pending.host != null) {
                        TextButton(onClick = { deny(true) }) {
                            Text("始终拒绝")
                        }
                    }
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
                        store.clearHistory(effectiveProfileId)
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

private fun isBindableWebPage(url: String): Boolean {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
    return (scheme == "http" || scheme == "https") &&
        !uri.host.isNullOrBlank()
}

private const val NATIVE_NEW_TAB_URL = "about:blank"

private fun newTabUrl(settings: BrowserSettings): String =
    if (settings.nativeNewTabPage) NATIVE_NEW_TAB_URL else settings.homepage

private fun defaultTabs(homepage: String): Pair<List<BrowserTab>, Long> {
    val tab = BrowserTab(
        id = 1L,
        url = homepage,
        title = "YBrowser",
    )
    return listOf(tab) to tab.id
}

private fun resolveInput(raw: String, settings: BrowserSettings): String {
    val input = raw.trim()
    if (input.isBlank()) return "about:blank"
    return when {
        input.startsWith("http://", true) ||
            input.startsWith("https://", true) ||
            input.startsWith("about:", true) ||
            input.startsWith("view-source:", true) -> input
        !input.contains(' ') &&
            (input.contains('.') || input.startsWith("localhost", true)) ->
            "https://" + input
        settings.searchEngine == SearchEngine.SEARXNG -> {
            val base = settings.searxngBaseUrl.trim().trimEnd('/')
                .takeIf {
                    it.startsWith("https://", true) ||
                        it.startsWith("http://", true)
                }
                ?: "https://searx.be"
            base + "/search?q=" + Uri.encode(input)
        }
        else -> settings.searchEngine.template.format(Uri.encode(input))
    }
}

private fun translatePageUrl(url: String): String {
    val localeLanguage = Locale.getDefault().language.lowercase()
    val targetLanguage = when (localeLanguage) {
        "zh" -> "zh-CN"
        "iw" -> "he"
        else -> localeLanguage.ifBlank { "en" }
    }
    return "https://translate.google.com/translate?sl=auto&tl=" +
        Uri.encode(targetLanguage) +
        "&u=" +
        Uri.encode(url)
}

private fun browserHost(url: String): String? {
    return runCatching { Uri.parse(url).host }
        .getOrNull()
        ?.lowercase()
        ?.trim()
        ?.trimEnd('.')
        ?.takeIf { it.isNotBlank() }
}

private fun normalizeFilePickerMimeTypes(
    rawTypes: List<String>,
): List<String> {
    val mimeTypeMap = android.webkit.MimeTypeMap.getSingleton()
    val normalized = rawTypes
        .asSequence()
        .flatMap { raw ->
            raw.split(',').asSequence()
        }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { value ->
            val clean = value.substringBefore(';').trim()
            when {
                clean == "*" || clean == "*/*" -> "*/*"
                clean.startsWith(".") -> {
                    val extension = clean
                        .removePrefix(".")
                        .substringAfterLast('.')
                        .lowercase()
                    mimeTypeMap.getMimeTypeFromExtension(extension)
                }
                '/' in clean -> clean.lowercase()
                else -> {
                    val extension = clean
                        .substringAfterLast('.', missingDelimiterValue = "")
                        .lowercase()
                    extension
                        .takeIf { it.isNotBlank() }
                        ?.let(mimeTypeMap::getMimeTypeFromExtension)
                }
            }
        }
        .distinct()
        .toList()

    return normalized.ifEmpty { listOf("*/*") }
}

private fun normalizeReusableUrl(value: String): String =
    value.trim().trimEnd('/')

private fun sameReusableUrl(left: String, right: String): Boolean =
    normalizeReusableUrl(left) == normalizeReusableUrl(right)

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

private fun downloadUrl(context: Context, url: String) {
    val id = BrowserDownloadRepository.enqueue(context, url)
    if (id != null) {
        val fileName = android.webkit.URLUtil.guessFileName(url, null, null)
        Toast.makeText(context, "开始下载：" + fileName, Toast.LENGTH_SHORT).show()
    } else {
        openExternalUrl(context, url)
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
