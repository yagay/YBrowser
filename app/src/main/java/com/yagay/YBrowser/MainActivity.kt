package com.yagay.YBrowser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.yagay.YBrowser.integration.yagayhub.YagaYHubBindingRecord
import com.yagay.YBrowser.integration.yagayhub.YagaYHubBindingStore
import com.yagay.YBrowser.integration.yagayhub.YagaYHubBridge
import com.yagay.YBrowser.integration.yagayhub.YagaYHubContract
import com.yagay.YBrowser.integration.yagayhub.YagaYHubKeepAliveService
import com.yagay.YBrowser.integration.yagayhub.YagaYHubCompactNavigation
import com.yagay.YBrowser.integration.yagayhub.YagaYHubPopupTarget
import com.yagay.YBrowser.integration.yagayhub.parseYagaYHubPopupTargets
import com.yagay.YBrowser.integration.yagayhub.sameYagaYHubPopupUrl
import com.yagay.ybrowser.ai.provider.ProviderCatalog
import com.yagay.ybrowser.ai.ui.WorkspaceViewModel
import com.yagay.ybrowser.ai.web.WindowWebRuntime
import kotlinx.coroutines.launch

open class MainActivity : ComponentActivity() {
    private var incomingUrl by mutableStateOf<String?>(null)
    private var reuseIncomingTab by mutableStateOf(false)
    private var bindingRevision by mutableStateOf(0)
    private var hubBindingMode by mutableStateOf(false)
    private var chatBindingRepo by mutableStateOf<String?>(null)
    private var chatBindingProject by mutableStateOf<String?>(null)
    private var compactMode by mutableStateOf(false)
    private var chatTargets by mutableStateOf<List<YagaYHubPopupTarget>>(emptyList())
    private var selectedTarget by mutableStateOf<YagaYHubPopupTarget?>(null)
    private var currentPageUrl by mutableStateOf("")
    private var currentPageTitle by mutableStateOf("AI")
    private var reloadSignal by mutableIntStateOf(0)
    private var incomingRequestRevision by mutableIntStateOf(0)
    private var incomingOpenInNewTab by mutableStateOf(false)

    private var aiMode by mutableStateOf(false)
    private var aiWorkspaceViewModel by mutableStateOf<WorkspaceViewModel?>(null)
    private var aiBootError by mutableStateOf<String?>(null)
    private var aiRuntimeError by mutableStateOf<String?>(null)
    private var aiRuntime: WindowWebRuntime? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BrowserNavigationLog.log(
            this,
            "ACTIVITY_CREATE",
            "action=" + intent?.action +
                " data=" + intent?.dataString +
                " extraUrl=" + intent?.getStringExtra(EXTRA_URL),
        )
        handleIncomingIntent(intent)
        prepareSafeAiHost(intent)

        setContent {
            val store = remember { BrowserStore(this) }
            var settings by remember { mutableStateOf(store.loadSettings()) }

            YBrowserTheme(settings.themeMode) {
                if (aiMode) {
                    val vm = aiWorkspaceViewModel
                    if (vm != null) {
                        SafeAiWorkspaceScreen(
                            vm = vm,
                            runtimeError = aiRuntimeError,
                            onSend = {
                                requireAiRuntime()?.let(vm::send)
                            },
                            onStop = {
                                requireAiRuntime()?.let(vm::stop)
                            },
                            onRefresh = {
                                requireAiRuntime()?.let {
                                    vm.refreshConversation(it)
                                }
                            },
                            onOpenWeb = ::openAiWebPopup,
                            onAttachFiles = ::attachAiFiles,
                            onDelete = { windowId ->
                                requireAiRuntime()?.let {
                                    vm.deleteChat(windowId, it)
                                }
                            },
                            onClose = ::finish,
                        )
                    } else {
                        SafeAiWorkspaceFailureScreen(
                            message = aiBootError
                                ?: "AI 本地工作区没有完成初始化。",
                            onClose = ::finish,
                        )
                    }
                } else {
                val compactLookupUrl = currentPageUrl
                    .takeIf { it.isNotBlank() }
                    ?: selectedTarget?.url.orEmpty()
                val compactCurrentTarget = when {
                    currentPageUrl.isBlank() -> selectedTarget
                    else -> chatTargets.firstOrNull { target ->
                        sameYagaYHubPopupUrl(
                            target.url,
                            currentPageUrl,
                        )
                    } ?: selectedTarget
                }
                val compactBinding = remember(
                    compactLookupUrl,
                    bindingRevision,
                ) {
                    if (compactLookupUrl.isNotBlank()) {
                        YagaYHubBindingStore(this@MainActivity)
                            .find(compactLookupUrl)
                    } else {
                        null
                    }
                }

                BrowserApp(
                    store = store,
                    settings = settings,
                    onSettingsChanged = {
                        settings = it
                        store.saveSettings(it)
                    },
                    incomingUrl = incomingUrl,
                    incomingReuseExisting = reuseIncomingTab,
                    incomingOpenInNewTab = incomingOpenInNewTab,
                    incomingRequestRevision = incomingRequestRevision,
                    onIncomingConsumed = {
                        incomingUrl = null
                        reuseIncomingTab = false
                        incomingOpenInNewTab = false
                    },
                    showBrowserChrome = true,
                    externalReloadSignal = reloadSignal,
                    bindingController = if (hubBindingMode) {
                        YagaYHubBridge.bindingController(
                            context = this,
                            revision = bindingRevision,
                            targetRepo = chatBindingRepo,
                            targetProject = chatBindingProject,
                            onBound = { url, title ->
                                val repo = chatBindingRepo.orEmpty()
                                if (repo.isNotBlank() && !compactMode) {
                                    YagaYHubBridge.openBindingResultActivity(
                                        context = this,
                                        repo = repo,
                                        project = chatBindingProject.orEmpty(),
                                        url = url,
                                        title = title,
                                    )
                                    chatBindingRepo = null
                                    chatBindingProject = null
                                }
                            },
                        )
                    } else {
                        null
                    },
                    retainedSessionKey = if (hubBindingMode) {
                        YagaYHubContract.RETAINED_SESSION_POOL_KEY
                    } else {
                        null
                    },
                    persistentPageUrls = if (hubBindingMode) {
                        chatTargets.map { it.url }
                    } else {
                        emptyList()
                    },
                    compactPageUrls = if (hubBindingMode) {
                        chatTargets.map { it.url }
                    } else {
                        emptyList()
                    },
                    openCompactLinksInNewTabs =
                        hubBindingMode && chatTargets.isNotEmpty(),
                    onCurrentPageChanged = { url, title ->
                        currentPageUrl = url
                        currentPageTitle = title.ifBlank { url }
                    },
                    browserChromeOverride = if (
                        hubBindingMode && chatTargets.isNotEmpty()
                    ) {
                        {
                            YagaYHubCompactNavigation(
                                current = compactCurrentTarget,
                                targets = chatTargets,
                                currentBindingProject = compactBinding?.project,
                                currentPageUrl = currentPageUrl,
                                currentPageTitle = currentPageTitle,
                                onSelect = { target ->
                                    selectedTarget = target
                                    chatBindingRepo = target.repoKey
                                    chatBindingProject = target.project
                                    YagaYHubBindingStore(this@MainActivity)
                                        .saveLastCompactUrl(target.url)
                                    incomingUrl = target.url
                                },
                                onRefresh = { reloadSignal++ },
                                onBind = {
                                    YagaYHubBridge.requestBindingPicker(
                                        context = this@MainActivity,
                                        url = currentPageUrl,
                                        title = currentPageTitle,
                                    )
                                },
                                onUnbind = {
                                    val url = compactLookupUrl
                                    if (url.isNotBlank()) {
                                        YagaYHubBridge.bindingController(
                                            context = this@MainActivity,
                                            revision = bindingRevision,
                                            targetRepo = chatBindingRepo,
                                            targetProject = chatBindingProject,
                                        ).unbind(url)
                                        bindingRevision++
                                        chatTargets = chatTargets.filterNot { target ->
                                            sameYagaYHubPopupUrl(
                                                target.url,
                                                url,
                                            )
                                        }
                                        if (
                                            selectedTarget?.let { target ->
                                                sameYagaYHubPopupUrl(
                                                    target.url,
                                                    url,
                                                )
                                            } == true
                                        ) {
                                            selectedTarget = null
                                            chatBindingRepo = null
                                            chatBindingProject = null
                                        }
                                    }
                                },
                                onClose = ::finish,
                            )
                        }
                    } else {
                        null
                    },
                )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingRequestRevision++
        BrowserNavigationLog.log(
            this,
            "NEW_INTENT",
            "revision=" + incomingRequestRevision +
                " action=" + intent.action +
                " data=" + intent.dataString +
                " extraUrl=" + intent.getStringExtra(EXTRA_URL),
        )
        handleIncomingIntent(intent)
        prepareSafeAiHost(intent)
    }

    override fun onResume() {
        super.onResume()
        bindingRevision++
        if (aiMode) {
            runCatching {
                aiWorkspaceViewModel
                    ?.refreshBindingsFromSharedStore()
            }.onFailure {
                BrowserNavigationLog.log(
                    this,
                    "AI_SAFE_HOST",
                    "binding_refresh_failed=" +
                        (it.message ?: it.javaClass.simpleName),
                )
            }
        }
    }

    override fun onDestroy() {
        runCatching {
            aiRuntime?.apply {
                setFileChooserLauncher(null)
                setFileSelectionListener(null)
                setPageChangeListener(null)
                setPageReadyListener(null)
                setConversationListener(null)
                releaseUi()
            }
        }
        aiRuntime = null
        super.onDestroy()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_AI) {
            aiMode = false
        }
        BrowserNavigationLog.log(
            this,
            "HANDLE_INTENT",
            "action=" + intent?.action +
                " resolved=" + resolveIncomingUrl(intent) +
                " compactExtra=" +
                intent?.getBooleanExtra(
                    YagaYHubContract.EXTRA_COMPACT_MODE,
                    false,
                ),
        )
        when (intent?.action) {
            ACTION_OPEN_AI -> {
                aiMode = true
                hubBindingMode = false
                compactMode = false
                chatTargets = emptyList()
                selectedTarget = null
                chatBindingRepo = null
                chatBindingProject = null
                incomingUrl = null
                reuseIncomingTab = false
                incomingOpenInNewTab = false
                BrowserNavigationLog.log(
                    this,
                    "AI_SAFE_HOST",
                    "route_open_ai",
                )
            }

            YagaYHubContract.ACTION_OPEN_BROWSER,
            YagaYHubContract.ACTION_SELECT_BINDING_POPUP -> {
                hubBindingMode = true
                compactMode = intent.getBooleanExtra(
                    YagaYHubContract.EXTRA_COMPACT_MODE,
                    intent.action ==
                        YagaYHubContract.ACTION_SELECT_BINDING_POPUP,
                )
                chatBindingRepo = intent.getStringExtra(
                    YagaYHubContract.EXTRA_BIND_REPO,
                )
                chatBindingProject = intent.getStringExtra(
                    YagaYHubContract.EXTRA_BIND_PROJECT,
                )
                reuseIncomingTab = true
                incomingOpenInNewTab = !compactMode &&
                    !resolveIncomingUrl(intent).isNullOrBlank()
                prepareYagaYHubTargets(intent)
            }

            YagaYHubContract.ACTION_SELECT_BINDING -> {
                compactMode = false
                chatTargets = emptyList()
                selectedTarget = null
                hubBindingMode = intent.getBooleanExtra(
                    YagaYHubContract.EXTRA_BINDING_MODE,
                    false,
                )
                chatBindingRepo = intent.getStringExtra(
                    YagaYHubContract.EXTRA_BIND_REPO,
                )
                chatBindingProject = intent.getStringExtra(
                    YagaYHubContract.EXTRA_BIND_PROJECT,
                )
                reuseIncomingTab = false
                incomingOpenInNewTab = false
                incomingUrl = intent.getStringExtra(
                    YagaYHubContract.EXTRA_URL,
                )?.takeIf { it.isNotBlank() }
            }

            YagaYHubContract.ACTION_BINDING_SYNC -> {
                val repo = intent.getStringExtra(
                    YagaYHubContract.EXTRA_BIND_REPO,
                ).orEmpty()
                val project = intent.getStringExtra(
                    YagaYHubContract.EXTRA_BIND_PROJECT,
                ).orEmpty()
                val url = intent.getStringExtra(
                    YagaYHubContract.EXTRA_BIND_URL,
                ).orEmpty()
                val title = intent.getStringExtra(
                    YagaYHubContract.EXTRA_BIND_TITLE,
                ).orEmpty()

                if (repo.isNotBlank() && url.isNotBlank()) {
                    YagaYHubBindingStore(this).save(
                        YagaYHubBindingRecord(
                            repoKey = repo,
                            project = project.ifBlank {
                                repo.substringAfterLast('/')
                            },
                            url = url,
                            title = title.ifBlank { "AI" },
                        ),
                    )
                    bindingRevision++
                }
                clearBindingLaunchState()
            }

            YagaYHubContract.ACTION_BINDING_REMOVE -> {
                val url = intent.getStringExtra(
                    YagaYHubContract.EXTRA_BIND_URL,
                ).orEmpty()
                if (url.isNotBlank()) {
                    YagaYHubBindingStore(this).remove(url)
                    BrowserSessionRegistry.close(
                        YagaYHubContract.RETAINED_SESSION_POOL_KEY,
                        retainedSessionTabId(url),
                    )
                    YagaYHubKeepAliveService.syncWithSessionPool(this)
                    bindingRevision++
                }
                clearBindingLaunchState()
            }

            else -> {
                chatBindingRepo = null
                chatBindingProject = null
                hubBindingMode = intent?.getBooleanExtra(
                    YagaYHubContract.EXTRA_BINDING_MODE,
                    false,
                ) == true
                compactMode = hubBindingMode &&
                    intent?.getBooleanExtra(
                        YagaYHubContract.EXTRA_COMPACT_MODE,
                        false,
                    ) == true
                reuseIncomingTab = intent?.getBooleanExtra(
                    EXTRA_REUSE_EXISTING,
                    false,
                ) == true
                incomingOpenInNewTab = false
                if (compactMode && intent != null) {
                    prepareYagaYHubTargets(intent)
                } else {
                    chatTargets = emptyList()
                    selectedTarget = null
                    incomingUrl = resolveIncomingUrl(intent)
                }

                val syncedRepo = intent?.getStringExtra(
                    YagaYHubContract.EXTRA_BIND_REPO,
                ).orEmpty()
                val syncedProject = intent?.getStringExtra(
                    YagaYHubContract.EXTRA_BIND_PROJECT,
                ).orEmpty()
                val syncedTitle = intent?.getStringExtra(
                    YagaYHubContract.EXTRA_BIND_TITLE,
                ).orEmpty()
                val syncedUrl = incomingUrl.orEmpty()
                if (syncedRepo.isNotBlank() && syncedUrl.isNotBlank()) {
                    YagaYHubBindingStore(this).save(
                        YagaYHubBindingRecord(
                            repoKey = syncedRepo,
                            project = syncedProject.ifBlank {
                                syncedRepo.substringAfterLast('/')
                            },
                            url = syncedUrl,
                            title = syncedTitle.ifBlank { "AI" },
                        ),
                    )
                    bindingRevision++
                }
            }
        }
    }

    private fun prepareSafeAiHost(intent: Intent?) {
        if (!aiMode) return

        if (aiWorkspaceViewModel == null && aiBootError == null) {
            val vm = runCatching {
                ViewModelProvider(
                    this,
                    WorkspaceViewModel.Factory(application),
                )[WorkspaceViewModel::class.java]
            }.onFailure {
                aiBootError =
                    "本地聊天数据初始化失败：" +
                        (it.message ?: it.javaClass.simpleName)
                BrowserNavigationLog.log(
                    this,
                    "AI_SAFE_HOST",
                    "viewmodel_init_failed=" +
                        (it.message ?: it.javaClass.simpleName),
                )
            }.getOrNull()

            if (vm != null) {
                aiWorkspaceViewModel = vm
                BrowserNavigationLog.log(
                    this,
                    "AI_SAFE_HOST",
                    "viewmodel_ready",
                )
            }
        }

        val vm = aiWorkspaceViewModel ?: return
        runCatching {
            vm.handleLaunchIntent(intent)
        }.onFailure {
            aiRuntimeError =
                "启动参数读取失败，已保留本地聊天：" +
                    (it.message ?: it.javaClass.simpleName)
            BrowserNavigationLog.log(
                this,
                "AI_SAFE_HOST",
                "launch_intent_failed=" +
                    (it.message ?: it.javaClass.simpleName),
            )
        }
    }

    private fun requireAiRuntime(): WindowWebRuntime? {
        aiRuntime?.let { return it }

        val runtime = runCatching {
            WindowWebRuntime(this)
        }.onFailure {
            aiRuntimeError =
                "网页运行时启动失败：" +
                    (it.message ?: it.javaClass.simpleName)
            BrowserNavigationLog.log(
                this,
                "AI_SAFE_HOST",
                "runtime_init_failed=" +
                    (it.message ?: it.javaClass.simpleName),
            )
        }.getOrNull()

        if (runtime != null) {
            configureSafeAiRuntime(runtime)
            aiRuntime = runtime
            aiRuntimeError = null
            BrowserNavigationLog.log(
                this,
                "AI_SAFE_HOST",
                "runtime_ready",
            )
        }
        return runtime
    }

    private fun configureSafeAiRuntime(
        runtime: WindowWebRuntime,
    ) {
        val vm = aiWorkspaceViewModel ?: return

        runtime.setFileSelectionListener {
            windowId,
            _,
            attachments,
        ->
            vm.onAttachments(
                windowId,
                attachments,
            )
        }
        runtime.setPageChangeListener {
            windowId,
            provider,
            url,
        ->
            vm.onPageChanged(
                windowId,
                provider,
                url,
            )
        }
        runtime.setPageReadyListener {
            windowId,
            provider,
            url,
        ->
            vm.onPageChanged(
                windowId,
                provider,
                url,
            )
        }
        runtime.setConversationListener {
            windowId,
            provider,
            snapshot,
        ->
            vm.onConversationSnapshot(
                windowId,
                provider,
                snapshot,
            )
        }
    }

    private fun attachAiFiles(
        windowId: String,
        uris: List<Uri>,
    ) {
        val vm = aiWorkspaceViewModel ?: return
        val window =
            vm.windows.firstOrNull {
                it.id == windowId
            } ?: return
        val runtime =
            requireAiRuntime()
                ?: return
        val provider =
            ProviderCatalog.byId(
                window.providerId,
            )

        lifecycleScope.launch {
            val result = runCatching {
                runtime.attachFiles(
                    windowId,
                    provider,
                    uris,
                )
            }.onFailure {
                aiRuntimeError =
                    "附件上传失败：" +
                        (it.message
                            ?: it.javaClass.simpleName)
                BrowserNavigationLog.log(
                    this@MainActivity,
                    "AI_SAFE_HOST",
                    "attachment_failed=" +
                        (it.message
                            ?: it.javaClass.simpleName),
                )
            }.getOrNull()

            if (
                result == null ||
                result.attachedCount <= 0
            ) {
                Toast.makeText(
                    this@MainActivity,
                    provider.name +
                        " 没有接收文件，可打开网页检查。",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun openAiWebPopup(url: String) {
        runCatching {
            startActivity(
                Intent(
                    this,
                    PopupBrowserActivity::class.java,
                ).apply {
                    action = ACTION_OPEN_URL
                    putExtra(EXTRA_URL, url)
                }
            )
        }.onFailure {
            aiRuntimeError =
                "无法打开网页：" +
                    (it.message ?: it.javaClass.simpleName)
        }
    }

    private fun prepareYagaYHubTargets(intent: Intent) {
        val requestedUrl = resolveIncomingUrl(intent)
        val parsed = parseYagaYHubPopupTargets(
            intent.getStringExtra(
                YagaYHubContract.EXTRA_TARGETS_JSON,
            ),
        ).toMutableList()

        if (
            parsed.isEmpty() &&
            !requestedUrl.isNullOrBlank() &&
            !chatBindingRepo.isNullOrBlank()
        ) {
            parsed += YagaYHubPopupTarget(
                repoKey = chatBindingRepo.orEmpty(),
                project = chatBindingProject.orEmpty()
                    .ifBlank {
                        chatBindingRepo.orEmpty().substringAfterLast('/')
                    },
                url = requestedUrl,
                title = intent.getStringExtra(
                    YagaYHubContract.EXTRA_BIND_TITLE,
                ).orEmpty().ifBlank { "AI" },
                addedAt = 0L,
            )
        }

        chatTargets = parsed.sortedByDescending { it.addedAt }
        BrowserNavigationLog.log(
            this,
            "TARGETS",
            "requested=" + requestedUrl +
                " count=" + chatTargets.size +
                " urls=" + chatTargets.joinToString(" | ") { it.url },
        )
        if (chatTargets.isNotEmpty()) {
            YagaYHubKeepAliveService.start(
                this,
                chatTargets.size,
            )
        }

        val bindingStore = YagaYHubBindingStore(this)
        val rememberedUrl = bindingStore.lastCompactUrl()
        val target = when {
            !requestedUrl.isNullOrBlank() ->
                chatTargets.firstOrNull {
                    sameYagaYHubPopupUrl(
                        it.url,
                        requestedUrl,
                    )
                }
            !rememberedUrl.isNullOrBlank() ->
                chatTargets.firstOrNull {
                    sameYagaYHubPopupUrl(
                        it.url,
                        rememberedUrl,
                    )
                } ?: chatTargets.firstOrNull()
            else -> chatTargets.firstOrNull()
        }

        selectedTarget = target
        BrowserNavigationLog.log(
            this,
            "TARGET_SELECT",
            "requested=" + requestedUrl +
                " remembered=" + rememberedUrl +
                " selected=" + target?.url +
                " incomingOpenInNewTab=" + incomingOpenInNewTab,
        )
        if (target != null) {
            chatBindingRepo = target.repoKey
            chatBindingProject = target.project
            bindingStore.saveLastCompactUrl(target.url)
            incomingUrl = target.url
        } else {
            incomingUrl = requestedUrl
        }
    }

    private fun clearBindingLaunchState() {
        compactMode = false
        chatTargets = emptyList()
        selectedTarget = null
        chatBindingRepo = null
        chatBindingProject = null
        reuseIncomingTab = false
        incomingOpenInNewTab = false
        incomingUrl = null
    }

    private fun resolveIncomingUrl(intent: Intent?): String? {
        if (intent == null) return null
        return intent.getStringExtra(EXTRA_URL)
            ?.takeIf { it.isNotBlank() }
            ?: intent.dataString?.takeIf { it.isNotBlank() }
    }

    companion object {
        const val ACTION_OPEN_AI = "com.yagay.YBrowser.action.OPEN_AI"
        const val ACTION_OPEN_URL = "com.yagay.YBrowser.action.OPEN_URL"
        const val EXTRA_URL = "com.yagay.YBrowser.extra.URL"
        const val EXTRA_REUSE_EXISTING =
            "com.yagay.YBrowser.extra.REUSE_EXISTING"
    }
}

@Composable
fun YBrowserTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }

    val colors = when {
        mode == ThemeMode.AMOLED -> darkColorScheme(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainer = Color.Black,
        )
        android.os.Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> if (dark) darkColorScheme() else lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
