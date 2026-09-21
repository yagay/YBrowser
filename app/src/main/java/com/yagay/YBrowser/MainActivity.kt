package com.yagay.YBrowser

import android.content.BroadcastReceiver
import android.content.Context
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
                    bindingRevision = bindingRevision,
                    hubBindingMode = hubBindingMode,
                    chatBindingRepo = chatBindingRepo,
                    chatBindingProject = chatBindingProject,
                    onChatBindingComplete = { url, title ->
                        val repo = chatBindingRepo
                        if (!repo.isNullOrBlank()) {
                            returnChatBinding(
                                repo = repo,
                                project = chatBindingProject.orEmpty(),
                                url = url,
                                title = title,
                            )
                        }
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
            ACTION_SELECT_CHATGPT_CHAT -> {
                hubBindingMode =
                    intent.getBooleanExtra(EXTRA_YAGAYHUB_BINDING_MODE, false)
                chatBindingRepo = intent.getStringExtra(EXTRA_BIND_REPO)
                chatBindingProject = intent.getStringExtra(EXTRA_BIND_PROJECT)
                reuseIncomingTab = false
                incomingUrl = intent.getStringExtra(EXTRA_URL)
                    ?.takeIf { it.isNotBlank() }
            }
            ACTION_CHATGPT_BINDING_SYNC -> {
                val repo = intent.getStringExtra(EXTRA_BIND_REPO).orEmpty()
                val project = intent.getStringExtra(EXTRA_BIND_PROJECT).orEmpty()
                val url = intent.getStringExtra(EXTRA_BIND_URL).orEmpty()
                val title = intent.getStringExtra(EXTRA_BIND_TITLE).orEmpty()
                if (repo.isNotBlank() && url.isNotBlank()) {
                    BrowserStore(this).saveChatBinding(
                        ChatBindingRecord(
                            repoKey = repo,
                            project = project.ifBlank { repo.substringAfterLast('/') },
                            url = url,
                            title = title.ifBlank { "AI" },
                        ),
                    )
                    bindingRevision++
                }
                chatBindingRepo = null
                chatBindingProject = null
                reuseIncomingTab = false
                incomingUrl = null
            }
            ACTION_CHATGPT_BINDING_REMOVE -> {
                val url = intent.getStringExtra(EXTRA_BIND_URL).orEmpty()
                if (url.isNotBlank()) {
                    BrowserStore(this).removeChatBinding(url)
                    BrowserSessionRegistry.close(
                        RETAINED_AI_SESSION_POOL_KEY,
                        retainedSessionTabId(url),
                    )
                    bindingRevision++
                }
                chatBindingRepo = null
                chatBindingProject = null
                reuseIncomingTab = false
                incomingUrl = null
            }
            else -> {
                chatBindingRepo = null
                chatBindingProject = null
                hubBindingMode = intent?.getBooleanExtra(EXTRA_YAGAYHUB_BINDING_MODE, false) == true
                reuseIncomingTab = intent?.getBooleanExtra(EXTRA_REUSE_EXISTING, false) == true
                incomingUrl = resolveIncomingUrl(intent)

                val syncedRepo = intent?.getStringExtra(EXTRA_BIND_REPO).orEmpty()
                val syncedProject = intent?.getStringExtra(EXTRA_BIND_PROJECT).orEmpty()
                val syncedTitle = intent?.getStringExtra(EXTRA_BIND_TITLE).orEmpty()
                val syncedUrl = incomingUrl.orEmpty()
                if (syncedRepo.isNotBlank() && syncedUrl.isNotBlank()) {
                    BrowserStore(this).saveChatBinding(
                        ChatBindingRecord(
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

    private fun returnChatBinding(
        repo: String,
        project: String,
        url: String,
        title: String,
    ) {
        val result = Intent(ACTION_CHATGPT_BOUND).apply {
            setPackage(YAGAYHUB_PACKAGE)
            putExtra(EXTRA_BIND_REPO, repo)
            putExtra(EXTRA_BIND_PROJECT, project)
            putExtra(EXTRA_BIND_URL, url)
            putExtra(EXTRA_BIND_TITLE, title)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        runCatching { startActivity(result) }
        chatBindingRepo = null
        chatBindingProject = null
    }

    private fun resolveIncomingUrl(intent: Intent?): String? {
        if (intent == null) return null
        return intent.getStringExtra(EXTRA_URL)
            ?.takeIf { it.isNotBlank() }
            ?: intent.dataString?.takeIf { it.isNotBlank() }
    }

    companion object {
        const val ACTION_OPEN_URL = "com.yagay.YBrowser.action.OPEN_URL"
        const val ACTION_SELECT_CHATGPT_CHAT =
            "com.yagay.YBrowser.action.SELECT_CHATGPT_CHAT"
        const val ACTION_CHATGPT_BOUND =
            "com.yagay.YagaYHub.action.CHATGPT_BOUND"
        const val ACTION_CHATGPT_BINDING_SYNC =
            "com.yagay.YBrowser.action.CHATGPT_BINDING_SYNC"
        const val ACTION_CHATGPT_BINDING_REMOVE =
            "com.yagay.YBrowser.action.CHATGPT_BINDING_REMOVE"
        const val EXTRA_URL = "com.yagay.YBrowser.extra.URL"
        const val EXTRA_REUSE_EXISTING = "com.yagay.YBrowser.extra.REUSE_EXISTING"
        const val EXTRA_YAGAYHUB_BINDING_MODE =
            "com.yagay.YBrowser.extra.YAGAYHUB_BINDING_MODE"
        const val EXTRA_BIND_REPO = "com.yagay.YBrowser.extra.BIND_REPO"
        const val EXTRA_BIND_PROJECT = "com.yagay.YBrowser.extra.BIND_PROJECT"
        const val EXTRA_BIND_URL = "com.yagay.YBrowser.extra.BIND_URL"
        const val EXTRA_BIND_TITLE = "com.yagay.YBrowser.extra.BIND_TITLE"
        const val YAGAYHUB_PACKAGE = "com.yagay.YagaYHub"
    }
}

class ChatBindingRemoveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (
            intent?.action !=
            "com.yagay.YBrowser.action.CHATGPT_BINDING_REMOVE"
        ) {
            return
        }
        val url = intent.getStringExtra(
            "com.yagay.YBrowser.extra.BIND_URL"
        ).orEmpty()
        if (url.isBlank()) return
        BrowserStore(context).removeChatBinding(url)
        BrowserSessionRegistry.close(
            RETAINED_AI_SESSION_POOL_KEY,
            retainedSessionTabId(url),
        )
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
