package com.yagay.YBrowser

import android.content.Intent
import android.os.Bundle
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
import com.yagay.YBrowser.integration.yagayhub.YagaYHubBindingRecord
import com.yagay.YBrowser.integration.yagayhub.YagaYHubBindingStore
import com.yagay.YBrowser.integration.yagayhub.YagaYHubBridge
import com.yagay.YBrowser.integration.yagayhub.YagaYHubContract
import com.yagay.YBrowser.integration.yagayhub.YagaYHubKeepAliveService
import com.yagay.YBrowser.integration.yagayhub.YagaYHubCompactNavigation
import com.yagay.YBrowser.integration.yagayhub.YagaYHubPopupTarget
import com.yagay.YBrowser.integration.yagayhub.parseYagaYHubPopupTargets
import com.yagay.YBrowser.integration.yagayhub.sameYagaYHubPopupUrl

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

        setContent {
            val store = remember { BrowserStore(this) }
            var settings by remember { mutableStateOf(store.loadSettings()) }

            YBrowserTheme(settings.themeMode) {
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
                    onClose =
                        if (
                            hubBindingMode ||
                            this@MainActivity is
                                YagaYHubEmbeddedActivity ||
                            intent?.getBooleanExtra(
                                YagaYHubContract.EXTRA_EMBEDDED,
                                false,
                            ) == true
                        ) {
                            ::finish
                        } else {
                            null
                        },
                )
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
    }

    override fun onResume() {
        super.onResume()
        bindingRevision++
    }

    override fun onDestroy() {
        if (
            isFinishing &&
            !isChangingConfigurations &&
            this !is YagaYHubEmbeddedActivity
        ) {
            val store = BrowserStore(this)
            val browserSettings = store.loadSettings()
            if (browserSettings.clearHistoryOnExit) {
                store.clearHistory(
                    browserSettings.activeProfileId
                )
            }
            if (browserSettings.clearSiteDataOnExit) {
                clearAllBrowserEngineData(this)
            }
        }
        super.onDestroy()
    }

    private fun handleIncomingIntent(intent: Intent?) {
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

    val baseColors = when {
        android.os.Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> if (dark) darkColorScheme() else lightColorScheme()
    }
    val colors = if (dark) {
        baseColors.withReadableDarkContrast(
            amoled = mode == ThemeMode.AMOLED,
        )
    } else {
        baseColors
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}


private fun androidx.compose.material3.ColorScheme.withReadableDarkContrast(
    amoled: Boolean,
): androidx.compose.material3.ColorScheme = copy(
    background = if (amoled) Color.Black else background,
    onBackground = Color(0xFFF5F5F7),
    surface = if (amoled) Color.Black else surface,
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = if (amoled) Color(0xFF1C1C21) else surfaceVariant,
    onSurfaceVariant = Color(0xFFD6D6DE),
    surfaceContainerLowest =
        if (amoled) Color.Black else surfaceContainerLowest,
    surfaceContainerLow =
        if (amoled) Color(0xFF09090C) else surfaceContainerLow,
    surfaceContainer =
        if (amoled) Color(0xFF101014) else surfaceContainer,
    surfaceContainerHigh =
        if (amoled) Color(0xFF18181D) else surfaceContainerHigh,
    surfaceContainerHighest =
        if (amoled) Color(0xFF24242B) else surfaceContainerHighest,
    outline = Color(0xFFA8A8B2),
    outlineVariant = Color(0xFF5E5E68),
)
