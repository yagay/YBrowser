package com.yagay.YBrowser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {
    private var incomingUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incomingUrl = resolveIncomingUrl(intent)

        setContent {
            YBrowserTheme {
                BrowserScreen(
                    incomingUrl = incomingUrl,
                    onIncomingConsumed = { incomingUrl = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUrl = resolveIncomingUrl(intent)
    }

    private fun resolveIncomingUrl(intent: Intent?): String? {
        if (intent == null) return null
        val explicit = intent.getStringExtra(EXTRA_URL)
        val data = intent.dataString
        return explicit?.takeIf { it.isNotBlank() }
            ?: data?.takeIf { it.isNotBlank() }
    }

    companion object {
        const val ACTION_OPEN_URL = "com.yagay.YBrowser.action.OPEN_URL"
        const val EXTRA_URL = "com.yagay.YBrowser.extra.URL"
    }
}

private data class BrowserTab(
    val id: Long,
    val url: String,
    val title: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(
    incomingUrl: String?,
    onIncomingConsumed: () -> Unit,
) {
    val context = LocalContext.current
    var engineKindName by rememberSaveable { mutableStateOf(BrowserEngineKind.GECKO.name) }
    val engineKind = BrowserEngineKind.valueOf(engineKindName)
    var nextId by remember { mutableLongStateOf(2L) }
    var tabs by remember {
        mutableStateOf(
            listOf(
                BrowserTab(
                    id = 1L,
                    url = HOME_URL,
                    title = "YBrowser",
                ),
            ),
        )
    }
    var selectedTabId by rememberSaveable { mutableLongStateOf(1L) }
    var renderState by remember { mutableStateOf(BrowserRenderState()) }
    var addressInput by rememberSaveable { mutableStateOf(HOME_URL) }
    var showTabs by rememberSaveable { mutableStateOf(false) }
    var showMenu by rememberSaveable { mutableStateOf(false) }

    val selectedTab = tabs.firstOrNull { it.id == selectedTabId } ?: tabs.first()

    val engine = remember(engineKind, selectedTabId) {
        createBrowserEngine(context, engineKind) { state ->
            renderState = state
            tabs = tabs.map { tab ->
                if (tab.id == selectedTabId) {
                    tab.copy(
                        url = state.url.ifBlank { tab.url },
                        title = state.title.ifBlank { tab.title },
                    )
                } else {
                    tab
                }
            }
        }
    }

    DisposableEffect(engine) {
        onDispose { engine.destroy() }
    }

    LaunchedEffect(engine, selectedTabId) {
        renderState = BrowserRenderState(
            url = selectedTab.url,
            title = selectedTab.title,
        )
        addressInput = selectedTab.url
        engine.load(selectedTab.url)
    }

    LaunchedEffect(renderState.url) {
        if (renderState.url.isNotBlank()) addressInput = renderState.url
    }

    LaunchedEffect(incomingUrl) {
        val target = incomingUrl?.let(::resolveInput) ?: return@LaunchedEffect
        tabs = tabs.map { tab ->
            if (tab.id == selectedTabId) {
                tab.copy(url = target, title = target)
            } else {
                tab
            }
        }
        addressInput = target
        engine.load(target)
        onIncomingConsumed()
    }

    BackHandler {
        when {
            showTabs -> showTabs = false
            renderState.canGoBack -> engine.back()
            else -> (context as? ComponentActivity)?.finish()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        key(engineKind, selectedTabId) {
            AndroidView(
                factory = { engine.view },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (renderState.loading && renderState.progress in 1..99) {
                LinearProgressIndicator(
                    progress = { renderState.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                )
                Spacer(Modifier.height(5.dp))
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        enabled = renderState.canGoBack,
                        onClick = engine::back,
                    ) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "后退")
                    }

                    IconButton(
                        enabled = renderState.canGoForward,
                        onClick = engine::forward,
                    ) {
                        Icon(Icons.Outlined.ArrowForward, contentDescription = "前进")
                    }

                    OutlinedTextField(
                        value = addressInput,
                        onValueChange = { addressInput = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("搜索或输入网址") },
                        shape = RoundedCornerShape(22.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                val target = resolveInput(addressInput)
                                tabs = tabs.map { tab ->
                                    if (tab.id == selectedTabId) {
                                        tab.copy(url = target, title = target)
                                    } else {
                                        tab
                                    }
                                }
                                addressInput = target
                                engine.load(target)
                            },
                        ),
                    )

                    Surface(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(42.dp)
                            .clickable { showTabs = true },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(tabs.size.toString())
                        }
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "菜单")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("主页") },
                                leadingIcon = { Icon(Icons.Outlined.Home, null) },
                                onClick = {
                                    showMenu = false
                                    engine.load(HOME_URL)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("刷新") },
                                leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                onClick = {
                                    showMenu = false
                                    engine.reload()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("分享") },
                                leadingIcon = { Icon(Icons.Outlined.Share, null) },
                                onClick = {
                                    showMenu = false
                                    val url = renderState.url.ifBlank { selectedTab.url }
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, url)
                                    }
                                    context.startActivity(Intent.createChooser(share, "分享网页"))
                                },
                            )
                            HorizontalDivider()
                            Text(
                                "浏览器内核",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            BrowserEngineKind.entries.forEach { item ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (item == engineKind) "${item.label}  ✓"
                                            else item.label,
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        if (item != engineKind) {
                                            engineKindName = item.name
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
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
                )
                Text(
                    engineKind.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                IconButton(
                    onClick = {
                        val id = nextId++
                        tabs = tabs + BrowserTab(id, HOME_URL, "新标签页")
                        selectedTabId = id
                        showTabs = false
                    },
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "新标签页")
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
                                Text(
                                    tab.title.ifBlank { "新标签页" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    tab.url,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (tabs.size > 1) {
                                IconButton(
                                    onClick = {
                                        val wasSelected = tab.id == selectedTabId
                                        val index = tabs.indexOfFirst { it.id == tab.id }
                                        tabs = tabs.filterNot { it.id == tab.id }
                                        if (wasSelected) {
                                            selectedTabId = tabs
                                                .getOrNull(index.coerceAtMost(tabs.lastIndex))
                                                ?.id
                                                ?: tabs.first().id
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "关闭标签",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun resolveInput(raw: String): String {
    val input = raw.trim()
    if (input.isBlank()) return HOME_URL
    return when {
        input.startsWith("http://", true) ||
            input.startsWith("https://", true) ||
            input.startsWith("about:", true) -> input
        !input.contains(' ') &&
            (input.contains('.') || input.startsWith("localhost", true)) ->
            "https://" + input
        else -> SEARCH_URL + Uri.encode(input)
    }
}

@Composable
private fun YBrowserTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}

private const val HOME_URL = "https://www.google.com/"
private const val SEARCH_URL = "https://www.google.com/search?q="
