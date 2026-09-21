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

class MainActivity : ComponentActivity() {
    private var incomingUrl by mutableStateOf<String?>(null)
    private var reuseIncomingTab by mutableStateOf(false)
    private var bindingRevision by mutableStateOf(0)
    private var hubBindingMode by mutableStateOf(false)
    private var chatBindingRepo by mutableStateOf<String?>(null)
    private var chatBindingProject by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            val store = remember { BrowserStore(this) }
            var settings by remember { mutableStateOf(store.loadSettings()) }

            YBrowserTheme(settings.themeMode) {
                BrowserApp(
                    store = store,
                    settings = settings,
                    onSettingsChanged = {
                        settings = it
                        store.saveSettings(it)
                    },
                    incomingUrl = incomingUrl,
                    incomingReuseExisting = reuseIncomingTab,
                    onIncomingConsumed = {
                        incomingUrl = null
                        reuseIncomingTab = false
                    },
                    bindingController = if (hubBindingMode) {
                        YagaYHubBridge.bindingController(
                            context = this,
                            revision = bindingRevision,
                            targetRepo = chatBindingRepo,
                            targetProject = chatBindingProject,
                            onBound = { url, title ->
                                val repo = chatBindingRepo.orEmpty()
                                if (repo.isNotBlank()) {
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
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        bindingRevision++
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            YagaYHubContract.ACTION_SELECT_BINDING -> {
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
                reuseIncomingTab = intent?.getBooleanExtra(
                    EXTRA_REUSE_EXISTING,
                    false,
                ) == true
                incomingUrl = resolveIncomingUrl(intent)

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

    private fun clearBindingLaunchState() {
        chatBindingRepo = null
        chatBindingProject = null
        reuseIncomingTab = false
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
    }}

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
