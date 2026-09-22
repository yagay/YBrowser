package com.yagay.ybrowser.ai

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yagay.browsercore.GeckoCoreCallbacks
import com.yagay.browsercore.GeckoCoreFilePromptRequest
import com.yagay.browsercore.GeckoCoreState

class AiWorkspaceActivity : ComponentActivity() {
    private var launchRevision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launchRevision++

        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
            ) {
                Surface(Modifier.fillMaxSize()) {
                    AiWorkspaceScreen(
                        activity = this,
                        launchRevision = launchRevision,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRevision++
    }
}

@Composable
private fun AiWorkspaceScreen(
    activity: AiWorkspaceActivity,
    launchRevision: Int,
) {
    val context = LocalContext.current
    val store = remember { AiWorkspaceStore(context) }
    val windows = remember {
        mutableStateListOf<AiWorkspaceWindow>().apply {
            addAll(store.loadWindows())
        }
    }
    var activeId by remember {
        mutableStateOf(store.loadActiveId())
    }
    var showProviderPicker by remember { mutableStateOf(false) }
    val states = remember {
        mutableStateMapOf<String, GeckoCoreState>()
    }
    var pendingFilePrompt by remember {
        mutableStateOf<GeckoCoreFilePromptRequest?>(null)
    }

    fun persist() {
        store.save(windows.toList(), activeId)
    }

    fun selectWindow(id: String) {
        val index = windows.indexOfFirst { it.id == id }
        if (index < 0) return
        val now = System.currentTimeMillis()
        windows[index] = windows[index].copy(lastActiveAt = now)
        activeId = id
        persist()
    }

    fun addWindow(window: AiWorkspaceWindow, select: Boolean = true) {
        val sameIndex = windows.indexOfFirst {
            it.id == window.id ||
                AiWorkspaceStore.sameUrl(it.entryUrl, window.entryUrl)
        }
        val id = if (sameIndex >= 0) {
            val previous = windows[sameIndex]
            windows[sameIndex] = previous.copy(
                providerId = window.providerId,
                title = window.title.ifBlank { previous.title },
                entryUrl = window.entryUrl,
                project = window.project ?: previous.project,
                repoKey = window.repoKey ?: previous.repoKey,
                lastActiveAt = if (select) {
                    System.currentTimeMillis()
                } else {
                    previous.lastActiveAt
                },
            )
            previous.id
        } else {
            windows += window
            window.id
        }
        if (select) activeId = id
        persist()
    }

    fun closeWindow(id: String) {
        val index = windows.indexOfFirst { it.id == id }
        if (index < 0) return
        AiSessionRegistry.close(context, id)
        states.remove(id)
        windows.removeAt(index)
        if (activeId == id) {
            activeId = windows.getOrNull(index.coerceAtMost(windows.lastIndex))?.id
                ?: windows.lastOrNull()?.id
        }
        persist()
    }

    fun updateWindowLocation(id: String, state: GeckoCoreState) {
        states[id] = state
        val nextUrl = AiWorkspaceStore.normalizeWebUrl(state.url) ?: return
        val index = windows.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = windows[index]
        if (current.currentUrl != nextUrl) {
            windows[index] = current.copy(
                currentUrl = nextUrl,
                lastActiveAt = System.currentTimeMillis(),
            )
            persist()
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val request = pendingFilePrompt
        pendingFilePrompt = null
        request?.complete(
            if (request.allowMultiple) {
                uris
            } else {
                uris.take(1)
            },
        )
    }

    fun requestFiles(request: GeckoCoreFilePromptRequest) {
        pendingFilePrompt?.complete(null)
        pendingFilePrompt = request
        val types = request.mimeTypes
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf("*/*") }
            .toTypedArray()
        runCatching { fileLauncher.launch(types) }
            .onFailure {
                pendingFilePrompt = null
                request.complete(null)
            }
    }

    fun openNormalBrowser(url: String) {
        val normalized = AiWorkspaceStore.normalizeWebUrl(url) ?: return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
            setPackage(context.packageName)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(launchRevision) {
        val incoming = activity.intent
        val merged = store.mergeTargets(
            windows.toList(),
            incoming?.getStringExtra(AiWorkspaceContract.EXTRA_TARGETS_JSON),
        )
        if (merged != windows.toList()) {
            windows.clear()
            windows.addAll(merged)
        }

        val requestedWindowId =
            incoming?.getStringExtra(AiWorkspaceContract.EXTRA_WINDOW_ID)
        val requestedUrl =
            AiWorkspaceStore.normalizeWebUrl(
                incoming?.getStringExtra(AiWorkspaceContract.EXTRA_URL),
            )
        val requestedProvider =
            AiProviderCatalog.byId(
                incoming?.getStringExtra(AiWorkspaceContract.EXTRA_PROVIDER_ID),
            )
        val project =
            incoming?.getStringExtra(AiWorkspaceContract.EXTRA_BIND_PROJECT)
                ?.takeIf { it.isNotBlank() }
        val repoKey =
            incoming?.getStringExtra(AiWorkspaceContract.EXTRA_BIND_REPO)
                ?.takeIf { it.isNotBlank() }
        val title =
            incoming?.getStringExtra(AiWorkspaceContract.EXTRA_BIND_TITLE)
                ?.takeIf { it.isNotBlank() }

        when {
            !requestedWindowId.isNullOrBlank() &&
                windows.any { it.id == requestedWindowId } -> {
                selectWindow(requestedWindowId)
            }

            requestedUrl != null -> {
                val existing = windows.firstOrNull {
                    AiWorkspaceStore.sameUrl(it.entryUrl, requestedUrl) ||
                        AiWorkspaceStore.sameUrl(it.currentUrl, requestedUrl)
                }
                if (existing != null) {
                    selectWindow(existing.id)
                } else {
                    AiWorkspaceStore.newWebWindow(
                        url = requestedUrl,
                        title = title,
                        project = project,
                        repoKey = repoKey,
                        stable = !repoKey.isNullOrBlank(),
                    )?.let { addWindow(it) }
                }
            }

            requestedProvider != null -> {
                addWindow(
                    AiWorkspaceStore.newProviderWindow(requestedProvider),
                )
            }

            activeId != null && windows.any { it.id == activeId } -> Unit

            windows.isNotEmpty() -> {
                activeId = windows.maxByOrNull { it.lastActiveAt }?.id
                    ?: windows.first().id
                persist()
            }
        }
    }

    val activeWindow = windows.firstOrNull { it.id == activeId }
    val activeState = activeWindow?.let {
        states[it.id] ?: GeckoCoreState(
            url = it.currentUrl,
            title = it.title,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.asPaddingValues()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val session = activeWindow?.let {
                AiSessionRegistry.get(context, it.id)
            }

            IconButton(
                enabled = activeState?.canGoBack == true,
                onClick = { session?.goBack() },
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "后退",
                )
            }
            IconButton(
                enabled = activeState?.canGoForward == true,
                onClick = { session?.goForward() },
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = "前进",
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    text = activeState?.title
                        ?.takeIf { it.isNotBlank() }
                        ?: activeWindow?.title
                        ?: "AI Workspace",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                activeWindow?.let {
                    Text(
                        text = it.project
                            ?: AiProviderCatalog.byId(it.providerId)?.name
                            ?: Uri.parse(it.currentUrl).host.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            IconButton(
                enabled = activeWindow != null,
                onClick = {
                    activeWindow?.let {
                        AiSessionRegistry.get(context, it.id)?.reload()
                    }
                },
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
            }
            IconButton(
                enabled = activeWindow != null,
                onClick = {
                    val url = activeState?.url
                        ?.takeIf { it.isNotBlank() }
                        ?: activeWindow?.currentUrl
                    if (!url.isNullOrBlank()) openNormalBrowser(url)
                },
            ) {
                Icon(
                    Icons.Outlined.OpenInBrowser,
                    contentDescription = "在普通浏览器中打开",
                )
            }
            IconButton(
                enabled = activeWindow != null,
                onClick = {
                    activeWindow?.let { closeWindow(it.id) }
                },
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭会话")
            }
            IconButton(onClick = { showProviderPicker = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "新建 AI 会话")
            }
        }

        if (windows.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                windows.forEach { window ->
                    val label = window.project
                        ?: window.title.takeIf { it.isNotBlank() }
                        ?: AiProviderCatalog.byId(window.providerId)?.name
                        ?: "AI"
                    FilterChip(
                        selected = window.id == activeId,
                        onClick = { selectWindow(window.id) },
                        label = {
                            Text(
                                text = label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }

        if (activeState?.loading == true) {
            LinearProgressIndicator(
                progress = { (activeState.progress.coerceIn(0, 100)) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (activeWindow == null) {
                EmptyWorkspace(
                    onOpenProvider = { provider ->
                        addWindow(
                            AiWorkspaceStore.newProviderWindow(provider),
                        )
                    },
                )
            } else {
                key(activeWindow.id) {
                    val callbacks = GeckoCoreCallbacks(
                        onState = { state ->
                            updateWindowLocation(activeWindow.id, state)
                        },
                        onFilePrompt = ::requestFiles,
                        onNewWindow = { url ->
                            AiWorkspaceStore.newWebWindow(
                                url = url,
                                title = activeWindow.title,
                                project = activeWindow.project,
                                repoKey = activeWindow.repoKey,
                            )?.let(::addWindow)
                        },
                        onExternalUri = { uri ->
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(uri)),
                                )
                            }
                        },
                        onCrash = {
                            Toast.makeText(
                                context,
                                "网页进程已结束，可点击刷新恢复",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                    val session = remember(activeWindow.id) {
                        AiSessionRegistry.acquire(
                            context = context,
                            window = activeWindow,
                            callbacks = callbacks,
                        )
                    }

                    SideEffect {
                        session.attachHostContext(context)
                        session.updateCallbacks(callbacks)
                    }

                    DisposableEffect(activeWindow.id) {
                        onDispose {
                            AiSessionRegistry.detach(context, activeWindow.id)
                        }
                    }

                    AndroidView(
                        factory = {
                            session.attachHostContext(context)
                            val view = session.androidView
                            (view.parent as? ViewGroup)?.removeView(view)
                            view
                        },
                        update = {
                            session.attachHostContext(context)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    if (showProviderPicker) {
        AlertDialog(
            onDismissRequest = { showProviderPicker = false },
            title = { Text("新建 AI 会话") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    AiProviderCatalog.all.forEach { provider ->
                        TextButton(
                            onClick = {
                                showProviderPicker = false
                                addWindow(
                                    AiWorkspaceStore.newProviderWindow(provider),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(provider.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showProviderPicker = false },
                ) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun EmptyWorkspace(
    onOpenProvider: (AiProvider) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "AI Workspace",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "AI 页面使用 YBrowser 的 Gecko Runtime 与默认浏览 Profile。登录一次后，新建和切换会话无需重新登录。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        AiProviderCatalog.all.forEach { provider ->
            TextButton(
                onClick = { onOpenProvider(provider) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(provider.name)
            }
        }
    }
}
