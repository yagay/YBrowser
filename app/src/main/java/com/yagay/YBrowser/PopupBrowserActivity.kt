package com.yagay.YBrowser

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yagay.YBrowser.integration.yagayhub.YagaYHubBridge
import com.yagay.YBrowser.integration.yagayhub.YagaYHubCompactNavigation
import com.yagay.YBrowser.integration.yagayhub.YagaYHubContract
import com.yagay.YBrowser.integration.yagayhub.YagaYHubKeepAliveService
import com.yagay.YBrowser.integration.yagayhub.YagaYHubPopupTarget
import com.yagay.YBrowser.integration.yagayhub.parseYagaYHubPopupTargets
import com.yagay.YBrowser.integration.yagayhub.sameYagaYHubPopupUrl


class PopupBrowserActivity : ComponentActivity() {
    private var incomingUrl by mutableStateOf<String?>(null)
    private var chatBindingRepo by mutableStateOf<String?>(null)
    private var chatBindingProject by mutableStateOf<String?>(null)
    private var compactMode by mutableStateOf(false)
    private var hubBindingMode by mutableStateOf(false)
    private var transientPreview by mutableStateOf(false)
    private var chatTargets by mutableStateOf<List<YagaYHubPopupTarget>>(emptyList())
    private var selectedTarget by mutableStateOf<YagaYHubPopupTarget?>(null)
    private var currentPageUrl by mutableStateOf("")
    private var currentPageTitle by mutableStateOf("AI")
    private var reloadSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0.42f)
        window.setGravity(Gravity.CENTER)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN,
        )
        setFinishOnTouchOutside(true)

        handleIntent(intent)

        setContent {
            val store = remember { BrowserStore(this) }
            var settings by remember { mutableStateOf(store.loadSettings()) }

            YBrowserTheme(settings.themeMode) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp)
                        .clip(RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 12.dp,
                ) {
                    if (compactMode) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            YagaYHubCompactNavigation(
                                current = selectedTarget,
                                targets = chatTargets,
                                onSelect = { target ->
                                    selectedTarget = target
                                    chatBindingRepo = target.repoKey
                                    chatBindingProject = target.project
                                    incomingUrl = target.url
                                },
                                onRefresh = { reloadSignal++ },
                                onBind = {
                                    YagaYHubBridge.requestBindingPicker(
                                        context = this@PopupBrowserActivity,
                                        url = currentPageUrl,
                                        title = currentPageTitle,
                                    )
                                },
                                onClose = ::finish,
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            ) {
                                BrowserApp(
                                    store = store,
                                    settings = settings,
                                    onSettingsChanged = {
                                        settings = it
                                        store.saveSettings(it)
                                    },
                                    incomingUrl = incomingUrl,
                                    incomingReuseExisting = true,
                                    onIncomingConsumed = { incomingUrl = null },
                                    showBrowserChrome = false,
                                    externalReloadSignal = reloadSignal,
                                    bindingController = if (hubBindingMode) {
                                        YagaYHubBridge.bindingController(
                                            context = this@PopupBrowserActivity,
                                            revision = 0,
                                            targetRepo = chatBindingRepo,
                                            targetProject = chatBindingProject,
                                        )
                                    } else {
                                        null
                                    },
                                    retainedSessionKey =
                                        YagaYHubContract.RETAINED_SESSION_POOL_KEY,
                                    persistentPageUrls = chatTargets.map { it.url },
                                    onCurrentPageChanged = { url, title ->
                                        currentPageUrl = url
                                        currentPageTitle = title.ifBlank { "AI" }
                                    },
                                )
                            }
                        }
                    } else if (transientPreview) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "链接预览",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        currentPageTitle.ifBlank { currentPageUrl },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = ::finish) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "关闭预览",
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            ) {
                                BrowserApp(
                                    store = store,
                                    settings = settings.copy(restoreTabs = false),
                                    onSettingsChanged = { updated ->
                                        val persisted = updated.copy(
                                            homepage = settings.homepage,
                                            restoreTabs = settings.restoreTabs,
                                        )
                                        settings = persisted
                                        store.saveSettings(persisted)
                                    },
                                    incomingUrl = incomingUrl,
                                    incomingReuseExisting = false,
                                    onIncomingConsumed = { incomingUrl = null },
                                    showBrowserChrome = true,
                                    recordHistory = false,
                                    onCurrentPageChanged = { url, title ->
                                        currentPageUrl = url
                                        currentPageTitle = title.ifBlank { url }
                                    },
                                )
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            BrowserApp(
                                store = store,
                                settings = settings,
                                onSettingsChanged = {
                                    settings = it
                                    store.saveSettings(it)
                                },
                                incomingUrl = incomingUrl,
                                incomingReuseExisting = true,
                                onIncomingConsumed = { incomingUrl = null },
                                showBrowserChrome = true,
                                bindingController = if (hubBindingMode) {
                                    YagaYHubBridge.bindingController(
                                        context = this@PopupBrowserActivity,
                                        revision = 0,
                                        targetRepo = chatBindingRepo,
                                        targetProject = chatBindingProject,
                                        onBound = { url, title ->
                                            YagaYHubBridge.sendBindingResult(
                                                context = this@PopupBrowserActivity,
                                                repo = chatBindingRepo.orEmpty(),
                                                project = chatBindingProject.orEmpty(),
                                                url = url,
                                                title = title,
                                            )
                                            finish()
                                        },
                                    )
                                } else {
                                    null
                                },
                            )

                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 4.dp,
                            ) {
                                IconButton(onClick = ::finish) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "关闭弹窗",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val bounds = windowManager.currentWindowMetrics.bounds
        val width = (bounds.width() * 0.96f).toInt()
        val height = (bounds.height() * 0.88f).toInt()
        window.setLayout(width, height)
        window.setGravity(Gravity.CENTER)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        transientPreview =
            intent?.getBooleanExtra(EXTRA_TRANSIENT_PREVIEW, false) == true
        hubBindingMode =
            !transientPreview &&
                intent?.getBooleanExtra(YagaYHubContract.EXTRA_BINDING_MODE, false) == true
        compactMode =
            !transientPreview &&
                hubBindingMode &&
                intent?.getBooleanExtra(YagaYHubContract.EXTRA_COMPACT_MODE, false) == true

        val requestedUrl = intent?.getStringExtra(MainActivity.EXTRA_URL)
            ?.takeIf { it.isNotBlank() }
        chatBindingRepo = intent?.getStringExtra(YagaYHubContract.EXTRA_BIND_REPO)
            ?.takeIf { it.isNotBlank() }
        chatBindingProject = intent?.getStringExtra(YagaYHubContract.EXTRA_BIND_PROJECT)
            ?.takeIf { it.isNotBlank() }

        if (compactMode) {
            val parsed = parseYagaYHubPopupTargets(
                intent?.getStringExtra(YagaYHubContract.EXTRA_TARGETS_JSON),
            ).toMutableList()

            if (
                parsed.isEmpty() &&
                !requestedUrl.isNullOrBlank() &&
                !chatBindingRepo.isNullOrBlank()
            ) {
                parsed += YagaYHubPopupTarget(
                    repoKey = chatBindingRepo.orEmpty(),
                    project = chatBindingProject.orEmpty()
                        .ifBlank { chatBindingRepo.orEmpty().substringAfterLast('/') },
                    url = requestedUrl,
                    title = intent?.getStringExtra(YagaYHubContract.EXTRA_BIND_TITLE)
                        .orEmpty()
                        .ifBlank { "AI" },
                    addedAt = 0L,
                )
            }

            chatTargets = parsed.sortedByDescending { it.addedAt }
            if (chatTargets.isNotEmpty()) {
                YagaYHubKeepAliveService.start(
                    this,
                    chatTargets.size,
                )
            }
            val target = chatTargets.firstOrNull {
                sameYagaYHubPopupUrl(it.url, requestedUrl.orEmpty())
            } ?: chatTargets.firstOrNull()

            selectedTarget = target
            if (target != null) {
                chatBindingRepo = target.repoKey
                chatBindingProject = target.project
                incomingUrl = target.url
            } else {
                incomingUrl = requestedUrl
            }
        } else {
            chatTargets = emptyList()
            selectedTarget = null
            incomingUrl = requestedUrl
        }
    }

    companion object {
        const val EXTRA_TRANSIENT_PREVIEW =
            "com.yagay.YBrowser.extra.TRANSIENT_PREVIEW"
    }
}
