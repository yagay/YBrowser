package com.yagay.YBrowser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun BrowserChrome(
    settings: BrowserSettings,
    selectedTab: BrowserTab,
    tabsCount: Int,
    renderState: BrowserRenderState,
    addressInput: String,
    onAddressInput: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onShowTabs: () -> Unit,
    onShowMenu: () -> Unit,
    showMenu: Boolean,
    onDismissMenu: () -> Unit,
    onAddTab: () -> Unit,
    onAddPrivateTab: () -> Unit,
    onHome: () -> Unit,
    onReload: () -> Unit,
    onBookmark: () -> Unit,
    isBookmarked: Boolean,
    onShowBookmarks: () -> Unit,
    onShowHistory: () -> Unit,
    onShowFind: () -> Unit,
    onToggleDesktop: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onDownloads: () -> Unit,
    onOpenExternal: () -> Unit,
    onSettings: () -> Unit,
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
        color = if (selectedTab.privateMode) {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.97f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(enabled = renderState.canGoBack, onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "后退")
            }

            IconButton(enabled = renderState.canGoForward, onClick = onForward) {
                Icon(Icons.Outlined.ArrowForward, contentDescription = "前进")
            }

            OutlinedTextField(
                value = addressInput,
                onValueChange = onAddressInput,
                modifier = Modifier.weight(1f),
                singleLine = true,
                leadingIcon = if (selectedTab.privateMode) {
                    { Icon(Icons.Outlined.Lock, contentDescription = null) }
                } else {
                    { Icon(Icons.Outlined.Language, contentDescription = null) }
                },
                placeholder = { Text("搜索或输入网址") },
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = { onNavigate(addressInput) },
                ),
            )

            Surface(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(42.dp)
                    .clickable(onClick = onShowTabs),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(tabsCount.toString())
                }
            }

            Box {
                IconButton(onClick = onShowMenu) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "菜单")
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = onDismissMenu,
                ) {
                    DropdownMenuItem(
                        text = { Text("新标签页") },
                        leadingIcon = { Icon(Icons.Outlined.Add, null) },
                        onClick = {
                            onDismissMenu()
                            onAddTab()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("新隐私标签") },
                        leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                        onClick = {
                            onDismissMenu()
                            onAddPrivateTab()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("主页") },
                        leadingIcon = { Icon(Icons.Outlined.Home, null) },
                        onClick = {
                            onDismissMenu()
                            onHome()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("刷新") },
                        leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                        onClick = {
                            onDismissMenu()
                            onReload()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (isBookmarked) "取消收藏" else "添加收藏") },
                        leadingIcon = {
                            Icon(
                                if (isBookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                                null,
                            )
                        },
                        onClick = {
                            onDismissMenu()
                            onBookmark()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("收藏夹") },
                        leadingIcon = { Icon(Icons.Outlined.Bookmark, null) },
                        onClick = {
                            onDismissMenu()
                            onShowBookmarks()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("历史记录") },
                        leadingIcon = { Icon(Icons.Outlined.History, null) },
                        onClick = {
                            onDismissMenu()
                            onShowHistory()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("页面内查找") },
                        leadingIcon = { Icon(Icons.Outlined.FindInPage, null) },
                        onClick = {
                            onDismissMenu()
                            onShowFind()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (selectedTab.desktopMode || settings.desktopModeByDefault) {
                                    "切换为手机版网站"
                                } else {
                                    "桌面版网站"
                                },
                            )
                        },
                        leadingIcon = { Icon(Icons.Outlined.Visibility, null) },
                        onClick = {
                            onDismissMenu()
                            onToggleDesktop()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("复制链接") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = {
                            onDismissMenu()
                            onCopy()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("分享") },
                        leadingIcon = { Icon(Icons.Outlined.Share, null) },
                        onClick = {
                            onDismissMenu()
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("下载") },
                        leadingIcon = { Icon(Icons.Outlined.Download, null) },
                        onClick = {
                            onDismissMenu()
                            onDownloads()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("外部打开") },
                        leadingIcon = { Icon(Icons.Outlined.OpenInNew, null) },
                        onClick = {
                            onDismissMenu()
                            onOpenExternal()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("设置") },
                        leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                        onClick = {
                            onDismissMenu()
                            onSettings()
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun FindBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.weight(1f),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text("在网页中查找") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = onNext),
            )
            IconButton(onClick = onPrevious, enabled = query.isNotBlank()) {
                Icon(Icons.Outlined.ArrowUpward, contentDescription = "上一个")
            }
            IconButton(onClick = onNext, enabled = query.isNotBlank()) {
                Icon(Icons.Outlined.ArrowDownward, contentDescription = "下一个")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭查找")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: BrowserSettings,
    onChange: (BrowserSettings) -> Unit,
    onDismiss: () -> Unit,
    onClearData: () -> Unit,
    onDefaultBrowser: () -> Unit,
) {
    var homeInput by remember(settings.homepage) { mutableStateOf(settings.homepage) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 36.dp),
        ) {
            item {
                Text(
                    "设置",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            item { SectionTitle("浏览器") }
            item {
                ChoiceSetting(
                    title = "默认内核",
                    subtitle = "隐私标签固定使用 GeckoView",
                    values = BrowserEngineKind.entries,
                    selected = settings.defaultEngine,
                    label = { it.label },
                    onSelected = { onChange(settings.copy(defaultEngine = it)) },
                )
            }
            item {
                ChoiceSetting(
                    title = "搜索引擎",
                    values = SearchEngine.entries,
                    selected = settings.searchEngine,
                    label = { it.label },
                    onSelected = { onChange(settings.copy(searchEngine = it)) },
                )
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("主页", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = homeInput,
                        onValueChange = { homeInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                val value = normalizeHome(homeInput)
                                homeInput = value
                                onChange(settings.copy(homepage = value))
                            },
                        ),
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    val value = normalizeHome(homeInput)
                                    homeInput = value
                                    onChange(settings.copy(homepage = value))
                                },
                            ) {
                                Text("保存")
                            }
                        },
                    )
                }
            }

            item { SectionTitle("外观") }
            item {
                ChoiceSetting(
                    title = "主题",
                    values = ThemeMode.entries,
                    selected = settings.themeMode,
                    label = { it.label },
                    onSelected = { onChange(settings.copy(themeMode = it)) },
                )
            }
            item {
                ChoiceSetting(
                    title = "地址栏位置",
                    values = ToolbarPosition.entries,
                    selected = settings.toolbarPosition,
                    label = { it.label },
                    onSelected = { onChange(settings.copy(toolbarPosition = it)) },
                )
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("网页字体缩放：${settings.textScale}%")
                    Slider(
                        value = settings.textScale.toFloat(),
                        onValueChange = {
                            onChange(settings.copy(textScale = it.toInt().coerceIn(50, 200)))
                        },
                        valueRange = 50f..200f,
                    )
                }
            }

            item { SectionTitle("网页") }
            item {
                ToggleSetting(
                    title = "JavaScript",
                    subtitle = "关闭后部分网站无法正常工作",
                    checked = settings.javaScriptEnabled,
                    onChecked = { onChange(settings.copy(javaScriptEnabled = it)) },
                )
            }
            item {
                ToggleSetting(
                    title = "Cookie",
                    subtitle = "同时应用到 WebView 和 GeckoView",
                    checked = settings.cookiesEnabled,
                    onChecked = { onChange(settings.copy(cookiesEnabled = it)) },
                )
            }
            item {
                ToggleSetting(
                    title = "默认使用桌面版网站",
                    checked = settings.desktopModeByDefault,
                    onChecked = { onChange(settings.copy(desktopModeByDefault = it)) },
                )
            }
            item {
                ToggleSetting(
                    title = "恢复上次标签页",
                    checked = settings.restoreTabs,
                    onChecked = { onChange(settings.copy(restoreTabs = it)) },
                )
            }

            item { SectionTitle("系统与隐私") }
            item {
                ListItem(
                    headlineContent = { Text("设为默认浏览器") },
                    supportingContent = { Text("打开 Android 默认浏览器选择界面") },
                    leadingContent = { Icon(Icons.Outlined.Language, null) },
                    modifier = Modifier.clickable(onClick = onDefaultBrowser),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("清除浏览数据") },
                    supportingContent = { Text("历史、Cookie、站点数据和两个内核缓存") },
                    leadingContent = { Icon(Icons.Outlined.Delete, null) },
                    modifier = Modifier.clickable(onClick = onClearData),
                )
            }
            item {
                Text(
                    "YBrowser 0.2 · WebView + GeckoView 双内核",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun <T> ChoiceSetting(
    title: String,
    values: List<T>,
    selected: T,
    label: (T) -> String,
    subtitle: String? = null,
    onSelected: (T) -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(7.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelected(value) },
                    label = { Text(label(value)) },
                )
            }
        }
    }
}

@Composable
private fun ToggleSetting(
    title: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { text -> { Text(text) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onChecked,
            )
        },
        modifier = Modifier.clickable { onChecked(!checked) },
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkSheet(
    bookmarks: List<BookmarkEntry>,
    onDismiss: () -> Unit,
    onOpen: (BookmarkEntry) -> Unit,
    onRemove: (BookmarkEntry) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "收藏夹",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        if (bookmarks.isEmpty()) {
            Text(
                "还没有收藏",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.padding(bottom = 30.dp)) {
                items(bookmarks, key = { it.url }) { item ->
                    ListItem(
                        headlineContent = {
                            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(item.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = { Icon(Icons.Outlined.Bookmark, null) },
                        trailingContent = {
                            IconButton(onClick = { onRemove(item) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "删除收藏")
                            }
                        },
                        modifier = Modifier.clickable { onOpen(item) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    history: List<HistoryEntry>,
    onDismiss: () -> Unit,
    onOpen: (HistoryEntry) -> Unit,
    onClear: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "历史记录",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = onClear, enabled = history.isNotEmpty()) {
                Text("清空")
            }
        }
        if (history.isEmpty()) {
            Text(
                "没有历史记录",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.padding(bottom = 30.dp)) {
                items(history, key = { it.url + it.visitedAt }) { item ->
                    ListItem(
                        headlineContent = {
                            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(item.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = { Icon(Icons.Outlined.History, null) },
                        modifier = Modifier.clickable { onOpen(item) },
                    )
                }
            }
        }
    }
}

fun normalizeHome(raw: String): String {
    val input = raw.trim()
    if (input.isBlank()) return "https://www.google.com/"
    return when {
        input.startsWith("http://", true) ||
            input.startsWith("https://", true) ||
            input.startsWith("about:", true) -> input
        else -> "https://" + input
    }
}
