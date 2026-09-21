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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import org.json.JSONArray

private data class PopupChatTarget(
    val repoKey: String,
    val project: String,
    val url: String,
    val title: String,
    val addedAt: Long,
)

class PopupBrowserActivity : ComponentActivity() {
    private var incomingUrl by mutableStateOf<String?>(null)
    private var chatBindingRepo by mutableStateOf<String?>(null)
    private var chatBindingProject by mutableStateOf<String?>(null)
    private var compactMode by mutableStateOf(false)
    private var hubBindingMode by mutableStateOf(false)
    private var transientPreview by mutableStateOf(false)
    private var chatTargets by mutableStateOf<List<PopupChatTarget>>(emptyList())
    private var selectedTarget by mutableStateOf<PopupChatTarget?>(null)
    private var currentPageUrl by mutableStateOf("")
    private var currentPageTitle by mutableStateOf("AI")
    private var reloadSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0.42f)
        window.setGravity(Gravity.CENTER)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
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
                        .imePadding()
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
                            CompactChatNavigation(
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
                                    requestAiBinding(
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
                                    hubBindingMode = hubBindingMode,
                                    retainedSessionKey = RETAINED_AI_SESSION_POOL_KEY,
                                    persistentPageUrls = chatTargets.map { it.url },
                                    onCurrentPageChanged = { url, title ->
                                        currentPageUrl = url
                                        currentPageTitle = title.ifBlank { "AI" }
                                    },
                                    chatBindingRepo = chatBindingRepo,
                                    chatBindingProject = chatBindingProject,
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
                                    hubBindingMode = false,
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
                                hubBindingMode = hubBindingMode,
                                chatBindingRepo = chatBindingRepo,
                                chatBindingProject = chatBindingProject,
                                onChatBindingComplete = { url, title ->
                                    returnChatBinding(
                                        repo = chatBindingRepo.orEmpty(),
                                        project = chatBindingProject.orEmpty(),
                                        url = url,
                                        title = title,
                                    )
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
                intent?.getBooleanExtra(EXTRA_YAGAYHUB_BINDING_MODE, false) == true
        compactMode =
            !transientPreview &&
                hubBindingMode &&
                intent?.getBooleanExtra(EXTRA_YAGAYHUB_COMPACT_MODE, false) == true

        val requestedUrl = intent?.getStringExtra(MainActivity.EXTRA_URL)
            ?.takeIf { it.isNotBlank() }
        chatBindingRepo = intent?.getStringExtra(MainActivity.EXTRA_BIND_REPO)
            ?.takeIf { it.isNotBlank() }
        chatBindingProject = intent?.getStringExtra(MainActivity.EXTRA_BIND_PROJECT)
            ?.takeIf { it.isNotBlank() }

        if (compactMode) {
            val parsed = parseChatTargets(
                intent?.getStringExtra(EXTRA_CHAT_TARGETS_JSON),
            ).toMutableList()

            if (
                parsed.isEmpty() &&
                !requestedUrl.isNullOrBlank() &&
                !chatBindingRepo.isNullOrBlank()
            ) {
                parsed += PopupChatTarget(
                    repoKey = chatBindingRepo.orEmpty(),
                    project = chatBindingProject.orEmpty()
                        .ifBlank { chatBindingRepo.orEmpty().substringAfterLast('/') },
                    url = requestedUrl,
                    title = intent?.getStringExtra(MainActivity.EXTRA_BIND_TITLE)
                        .orEmpty()
                        .ifBlank { "AI" },
                    addedAt = 0L,
                )
            }

            chatTargets = parsed.sortedByDescending { it.addedAt }
            if (chatTargets.isNotEmpty()) {
                AiSessionKeepAliveService.start(
                    this,
                    chatTargets.size,
                )
            }
            val target = chatTargets.firstOrNull {
                samePopupUrl(it.url, requestedUrl.orEmpty())
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

    private fun returnChatBinding(
        repo: String,
        project: String,
        url: String,
        title: String,
    ) {
        if (repo.isBlank() || url.isBlank()) return

        val result = Intent(MainActivity.ACTION_CHATGPT_BOUND).apply {
            setPackage(MainActivity.YAGAYHUB_PACKAGE)
            putExtra(MainActivity.EXTRA_BIND_REPO, repo)
            putExtra(MainActivity.EXTRA_BIND_PROJECT, project)
            putExtra(MainActivity.EXTRA_BIND_URL, url)
            putExtra(MainActivity.EXTRA_BIND_TITLE, title)
        }
        sendBroadcast(result)
        finish()
    }

    private fun requestAiBinding(
        url: String,
        title: String,
    ) {
        if (url.isBlank()) return
        val intent = Intent(ACTION_REQUEST_AI_BINDING).apply {
            setPackage(MainActivity.YAGAYHUB_PACKAGE)
            putExtra(MainActivity.EXTRA_BIND_URL, url)
            putExtra(MainActivity.EXTRA_BIND_TITLE, title.ifBlank { "AI" })
            addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        runCatching { startActivity(intent) }
    }

    companion object {
        const val EXTRA_TRANSIENT_PREVIEW =
            "com.yagay.YBrowser.extra.TRANSIENT_PREVIEW"

        private const val ACTION_SELECT_CHATGPT_CHAT_POPUP =
            "com.yagay.YBrowser.action.SELECT_CHATGPT_CHAT_POPUP"
        private const val ACTION_REQUEST_AI_BINDING =
            "com.yagay.YagaYHub.action.REQUEST_CHATGPT_BINDING"
        private const val EXTRA_CHAT_TARGETS_JSON =
            "com.yagay.YBrowser.extra.CHAT_TARGETS_JSON"
        private const val EXTRA_YAGAYHUB_BINDING_MODE =
            "com.yagay.YBrowser.extra.YAGAYHUB_BINDING_MODE"
        private const val EXTRA_YAGAYHUB_COMPACT_MODE =
            "com.yagay.YBrowser.extra.YAGAYHUB_COMPACT_MODE"
    }
}

@Composable
private fun CompactChatNavigation(
    current: PopupChatTarget?,
    targets: List<PopupChatTarget>,
    onSelect: (PopupChatTarget) -> Unit,
    onRefresh: () -> Unit,
    onBind: () -> Unit,
    onClose: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 7.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                TextButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = current?.project ?: "AI",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (current != null) {
                            Text(
                                text = current.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "切换 AI 绑定项目",
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    targets.forEach { target ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        target.project,
                                        fontWeight = if (target == current) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Normal
                                        },
                                        maxLines = 1,
                                    )
                                    Text(
                                        target.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            onClick = {
                                expanded = false
                                onSelect(target)
                            },
                        )
                    }
                }
            }

            TextButton(onClick = onBind) {
                Text(
                    "绑定",
                    fontWeight = FontWeight.SemiBold,
                )
            }

            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "刷新",
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "关闭",
                )
            }
        }
    }
}

private fun parseChatTargets(raw: String?): List<PopupChatTarget> {
    if (raw.isNullOrBlank()) return emptyList()
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = item.optString("url")
            if (url.isBlank()) continue
            val repoKey = item.optString("repoKey")
            add(
                PopupChatTarget(
                    repoKey = repoKey,
                    project = item.optString("project")
                        .ifBlank { repoKey.substringAfterLast('/') },
                    url = url,
                    title = item.optString("title").ifBlank { "AI" },
                    addedAt = item.optLong("addedAt", 0L),
                ),
            )
        }
    }
}

private fun samePopupUrl(left: String, right: String): Boolean =
    left.substringBefore('#').trimEnd('/') ==
        right.substringBefore('#').trimEnd('/')
