package com.yagay.YBrowser.integration.yagayhub

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yagay.YBrowser.BrowserApp
import com.yagay.YBrowser.BrowserStore
import com.yagay.YBrowser.MainActivity
import com.yagay.YBrowser.YBrowserTheme

/**
 * Dedicated full-page browser shell used only by YagaYHub.
 *
 * Browser capabilities still come from BrowserApp/BrowserEngine. YagaYHub
 * binding, retained AI sessions and compact navigation stay in this integration
 * package instead of being mixed into the generic popup browser.
 */
class YagaYHubBrowserActivity : ComponentActivity() {
    private var incomingUrl by mutableStateOf<String?>(null)
    private var bindingRepo by mutableStateOf<String?>(null)
    private var bindingProject by mutableStateOf<String?>(null)
    private var compactMode by mutableStateOf(false)
    private var targets by mutableStateOf<List<YagaYHubPopupTarget>>(emptyList())
    private var selectedTarget by mutableStateOf<YagaYHubPopupTarget?>(null)
    private var currentPageUrl by mutableStateOf("")
    private var currentPageTitle by mutableStateOf("AI")
    private var reloadSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0f)
        window.setGravity(Gravity.FILL)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN,
        )
        setFinishOnTouchOutside(false)

        handleIntent(intent)

        setContent {
            val store = remember { BrowserStore(this) }
            var settings by remember { mutableStateOf(store.loadSettings()) }

            YBrowserTheme(settings.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 0.dp,
                ) {
                    if (compactMode) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            YagaYHubCompactNavigation(
                                current = selectedTarget,
                                targets = targets,
                                onSelect = { target ->
                                    selectedTarget = target
                                    bindingRepo = target.repoKey
                                    bindingProject = target.project
                                    incomingUrl = target.url
                                },
                                onRefresh = { reloadSignal++ },
                                onBind = {
                                    YagaYHubBridge.requestBindingPicker(
                                        context = this@YagaYHubBrowserActivity,
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
                                    bindingController = YagaYHubBridge.bindingController(
                                        context = this@YagaYHubBrowserActivity,
                                        revision = 0,
                                        targetRepo = bindingRepo,
                                        targetProject = bindingProject,
                                    ),
                                    retainedSessionKey =
                                        YagaYHubContract.RETAINED_SESSION_POOL_KEY,
                                    persistentPageUrls = targets.map { it.url },
                                    onCurrentPageChanged = { url, title ->
                                        currentPageUrl = url
                                        currentPageTitle = title.ifBlank { "AI" }
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
                                bindingController = YagaYHubBridge.bindingController(
                                    context = this@YagaYHubBrowserActivity,
                                    revision = 0,
                                    targetRepo = bindingRepo,
                                    targetProject = bindingProject,
                                    onBound = { url, title ->
                                        YagaYHubBridge.sendBindingResult(
                                            context = this@YagaYHubBrowserActivity,
                                            repo = bindingRepo.orEmpty(),
                                            project = bindingProject.orEmpty(),
                                            url = url,
                                            title = title,
                                        )
                                        finish()
                                    },
                                ),
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
                                        contentDescription = "关闭浏览器",
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
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        window.setGravity(Gravity.FILL)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        compactMode =
            intent?.getBooleanExtra(
                YagaYHubContract.EXTRA_COMPACT_MODE,
                false,
            ) == true

        val requestedUrl = intent?.getStringExtra(MainActivity.EXTRA_URL)
            ?.takeIf { it.isNotBlank() }

        bindingRepo = intent?.getStringExtra(
            YagaYHubContract.EXTRA_BIND_REPO,
        )?.takeIf { it.isNotBlank() }

        bindingProject = intent?.getStringExtra(
            YagaYHubContract.EXTRA_BIND_PROJECT,
        )?.takeIf { it.isNotBlank() }

        if (compactMode) {
            val parsed = parseYagaYHubPopupTargets(
                intent?.getStringExtra(
                    YagaYHubContract.EXTRA_TARGETS_JSON,
                ),
            ).toMutableList()

            if (
                parsed.isEmpty() &&
                !requestedUrl.isNullOrBlank() &&
                !bindingRepo.isNullOrBlank()
            ) {
                parsed += YagaYHubPopupTarget(
                    repoKey = bindingRepo.orEmpty(),
                    project = bindingProject.orEmpty()
                        .ifBlank {
                            bindingRepo.orEmpty().substringAfterLast('/')
                        },
                    url = requestedUrl,
                    title = intent?.getStringExtra(
                        YagaYHubContract.EXTRA_BIND_TITLE,
                    ).orEmpty().ifBlank { "AI" },
                    addedAt = 0L,
                )
            }

            targets = parsed.sortedByDescending { it.addedAt }
            if (targets.isNotEmpty()) {
                YagaYHubKeepAliveService.start(
                    this,
                    targets.size,
                )
            }

            val target = targets.firstOrNull {
                sameYagaYHubPopupUrl(
                    it.url,
                    requestedUrl.orEmpty(),
                )
            } ?: targets.firstOrNull()

            selectedTarget = target
            if (target != null) {
                bindingRepo = target.repoKey
                bindingProject = target.project
                incomingUrl = target.url
            } else {
                incomingUrl = requestedUrl
            }
        } else {
            targets = emptyList()
            selectedTarget = null
            incomingUrl = requestedUrl
        }
    }
}
