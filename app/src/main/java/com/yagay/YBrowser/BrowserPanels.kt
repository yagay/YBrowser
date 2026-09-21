package com.yagay.YBrowser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.outlined.Code
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
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Translate
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
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
    onPreviousTab: () -> Unit,
    onNextTab: () -> Unit,
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
    onReader: () -> Unit,
    onPrint: () -> Unit,
    onTranslate: () -> Unit,
    onViewSource: () -> Unit,
    onOpenExternal: () -> Unit,
    onSiteSettings: () -> Unit,
    onSettings: () -> Unit,
    showBindingAction: Boolean,
    bindingLabel: String,
    bindingActive: Boolean,
    bindingEnabled: Boolean,
    onBindingClick: () -> Unit,
    onMenuShortcutsChanged: (List<BrowserMenuShortcut>) -> Unit,
) {
    var editingShortcuts by remember(showMenu) { mutableStateOf(false) }

    fun shortcutLabel(shortcut: BrowserMenuShortcut): String = when (shortcut) {
        BrowserMenuShortcut.BOOKMARK ->
            if (isBookmarked) "取消收藏" else "收藏"
        BrowserMenuShortcut.DESKTOP_MODE ->
            if (selectedTab.desktopMode || settings.desktopModeByDefault) "手机版" else "桌面版"
        else -> shortcut.label
    }

    fun shortcutIcon(shortcut: BrowserMenuShortcut): ImageVector = when (shortcut) {
        BrowserMenuShortcut.NEW_TAB -> Icons.Outlined.Add
        BrowserMenuShortcut.PRIVATE_TAB -> Icons.Outlined.Lock
        BrowserMenuShortcut.SHARE -> Icons.Outlined.Share
        BrowserMenuShortcut.COPY_LINK -> Icons.Outlined.ContentCopy
        BrowserMenuShortcut.BOOKMARKS -> Icons.Outlined.Bookmark
        BrowserMenuShortcut.HISTORY -> Icons.Outlined.History
        BrowserMenuShortcut.DOWNLOADS -> Icons.Outlined.Download
        BrowserMenuShortcut.FIND_IN_PAGE -> Icons.Outlined.FindInPage
        BrowserMenuShortcut.HOME -> Icons.Outlined.Home
        BrowserMenuShortcut.BOOKMARK ->
            if (isBookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder
        BrowserMenuShortcut.DESKTOP_MODE -> Icons.Outlined.Visibility
        BrowserMenuShortcut.READER -> Icons.Outlined.FindInPage
        BrowserMenuShortcut.TRANSLATE -> Icons.Outlined.Translate
        BrowserMenuShortcut.VIEW_SOURCE -> Icons.Outlined.Code
        BrowserMenuShortcut.PRINT -> Icons.Outlined.Print
        BrowserMenuShortcut.OPEN_EXTERNAL -> Icons.Outlined.OpenInNew
        BrowserMenuShortcut.SITE_SETTINGS -> Icons.Outlined.Language
        BrowserMenuShortcut.SETTINGS -> Icons.Outlined.Settings
    }

    fun runShortcut(shortcut: BrowserMenuShortcut) {
        onDismissMenu()
        when (shortcut) {
            BrowserMenuShortcut.NEW_TAB -> onAddTab()
            BrowserMenuShortcut.PRIVATE_TAB -> onAddPrivateTab()
            BrowserMenuShortcut.SHARE -> onShare()
            BrowserMenuShortcut.COPY_LINK -> onCopy()
            BrowserMenuShortcut.BOOKMARKS -> onShowBookmarks()
            BrowserMenuShortcut.HISTORY -> onShowHistory()
            BrowserMenuShortcut.DOWNLOADS -> onDownloads()
            BrowserMenuShortcut.FIND_IN_PAGE -> onShowFind()
            BrowserMenuShortcut.HOME -> onHome()
            BrowserMenuShortcut.BOOKMARK -> onBookmark()
            BrowserMenuShortcut.DESKTOP_MODE -> onToggleDesktop()
            BrowserMenuShortcut.READER -> onReader()
            BrowserMenuShortcut.TRANSLATE -> onTranslate()
            BrowserMenuShortcut.VIEW_SOURCE -> onViewSource()
            BrowserMenuShortcut.PRINT -> onPrint()
            BrowserMenuShortcut.OPEN_EXTERNAL -> onOpenExternal()
            BrowserMenuShortcut.SITE_SETTINGS -> onSiteSettings()
            BrowserMenuShortcut.SETTINGS -> onSettings()
        }
    }
    Column {
        if (renderState.loading && renderState.progress in 1..99) {
            LinearProgressIndicator(
                progress = { renderState.progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
            )
            Spacer(Modifier.height(4.dp))
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RectangleShape,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            color = if (selectedTab.privateMode) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.98f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.98f)
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    enabled = renderState.canGoBack,
                    onClick = onBack,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "后退")
                }

                IconButton(
                    enabled = renderState.canGoForward,
                    onClick = onForward,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Outlined.ArrowForward, contentDescription = "前进")
                }

                OutlinedTextField(
                    value = addressInput,
                    onValueChange = onAddressInput,
                    modifier = Modifier
                        .weight(1f)
                        .pointerInput(onPreviousTab, onNextTab) {
                            var dragDistance = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { dragDistance = 0f },
                                onHorizontalDrag = { _, amount ->
                                    dragDistance += amount
                                },
                                onDragEnd = {
                                    if (abs(dragDistance) >= 72f) {
                                        if (dragDistance > 0f) {
                                            onPreviousTab()
                                        } else {
                                            onNextTab()
                                        }
                                    }
                                    dragDistance = 0f
                                },
                                onDragCancel = { dragDistance = 0f },
                            )
                        },
                    singleLine = true,
                    placeholder = { Text("搜索或输入网址") },
                    shape = RoundedCornerShape(20.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = { onNavigate(addressInput) },
                    ),
                )

                if (showBindingAction) {
                    Surface(
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .clickable(
                                enabled = bindingEnabled || bindingActive,
                                onClick = onBindingClick,
                            ),
                        shape = RoundedCornerShape(12.dp),
                        color = if (bindingActive) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ) {
                        Text(
                            text = bindingLabel,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (bindingActive) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else if (bindingEnabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                        )
                    }
                }

                IconButton(
                    onClick = onReload,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                }

                Surface(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .size(38.dp)
                        .clickable(onClick = onShowTabs),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            tabsCount.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                IconButton(
                    onClick = onShowMenu,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "菜单")
                }
            }
        }
    }

    if (showMenu) {
        ModalBottomSheet(onDismissRequest = onDismissMenu) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 28.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "YBrowser",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            renderState.title.ifBlank {
                                selectedTab.title.ifBlank { "当前网页" }
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            if (selectedTab.privateMode) "隐私" else settings.defaultEngine.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = { editingShortcuts = !editingShortcuts }) {
                            Text(if (editingShortcuts) "完成" else "编辑快捷功能")
                        }
                    }
                }

                if (settings.menuShortcuts.isEmpty()) {
                    Text(
                        if (editingShortcuts) "当前没有快捷功能，可从下方添加" else "暂无快捷功能",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    settings.menuShortcuts.chunked(6).forEach { shortcutRow ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                        ) {
                            shortcutRow.forEach { shortcut ->
                                BrowserMenuAction(
                                    modifier = Modifier.weight(1f),
                                    icon = shortcutIcon(shortcut),
                                    label = shortcutLabel(shortcut),
                                    editing = editingShortcuts,
                                    onRemove = {
                                        onMenuShortcutsChanged(
                                            settings.menuShortcuts.filterNot { it == shortcut },
                                        )
                                    },
                                    onClick = { runShortcut(shortcut) },
                                )
                            }
                            repeat(6 - shortcutRow.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }

                if (editingShortcuts) {
                    val availableShortcuts = BrowserMenuShortcut.entries.filterNot {
                        it in settings.menuShortcuts
                    }

                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        "可添加功能 · " +
                            settings.menuShortcuts.size.toString() +
                            "/" +
                            BrowserMenuShortcut.MAX_COUNT.toString(),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        availableShortcuts.forEach { shortcut ->
                            FilterChip(
                                selected = false,
                                enabled = settings.menuShortcuts.size <
                                    BrowserMenuShortcut.MAX_COUNT,
                                onClick = {
                                    if (settings.menuShortcuts.size <
                                        BrowserMenuShortcut.MAX_COUNT
                                    ) {
                                        onMenuShortcutsChanged(
                                            settings.menuShortcuts + shortcut,
                                        )
                                    }
                                },
                                label = { Text("+ " + shortcut.label) },
                            )
                        }
                    }
                    if (settings.menuShortcuts.size >= BrowserMenuShortcut.MAX_COUNT) {
                        Text(
                            "快捷区最多 12 个功能；先删除一个后即可新增。",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                ListItem(
                    headlineContent = { Text("主页") },
                    leadingContent = { Icon(Icons.Outlined.Home, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onHome()
                    },
                )
                ListItem(
                    headlineContent = {
                        Text(if (isBookmarked) "取消收藏" else "添加收藏")
                    },
                    leadingContent = {
                        Icon(
                            if (isBookmarked) Icons.Outlined.Bookmark
                            else Icons.Outlined.BookmarkBorder,
                            null,
                        )
                    },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onBookmark()
                    },
                )
                ListItem(
                    headlineContent = {
                        Text(
                            if (selectedTab.desktopMode || settings.desktopModeByDefault) {
                                "切换为手机版网站"
                            } else {
                                "桌面版网站"
                            },
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.Visibility, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onToggleDesktop()
                    },
                )
                ListItem(
                    headlineContent = { Text("阅读模式") },
                    leadingContent = { Icon(Icons.Outlined.FindInPage, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onReader()
                    },
                )
                ListItem(
                    headlineContent = { Text("翻译网页") },
                    leadingContent = { Icon(Icons.Outlined.Translate, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onTranslate()
                    },
                )
                ListItem(
                    headlineContent = { Text("查看源代码") },
                    leadingContent = { Icon(Icons.Outlined.Code, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onViewSource()
                    },
                )
                ListItem(
                    headlineContent = { Text("打印 / 保存 PDF") },
                    leadingContent = { Icon(Icons.Outlined.Print, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onPrint()
                    },
                )
                ListItem(
                    headlineContent = { Text("外部打开") },
                    leadingContent = { Icon(Icons.Outlined.OpenInNew, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onOpenExternal()
                    },
                )

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                ListItem(
                    headlineContent = { Text("网站设置") },
                    leadingContent = { Icon(Icons.Outlined.Language, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onSiteSettings()
                    },
                )
                ListItem(
                    headlineContent = { Text("设置") },
                    leadingContent = { Icon(Icons.Outlined.Settings, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onSettings()
                    },
                )
            }
        }
    }
}

@Composable
private fun BrowserMenuAction(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    editing: Boolean = false,
    onRemove: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .padding(horizontal = 4.dp)
            .clickable(enabled = !editing, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Surface(
                modifier = Modifier.size(46.dp),
                shape = RoundedCornerShape(15.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = label)
                }
            }

            if (editing && onRemove != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(22.dp)
                        .clickable(onClick = onRemove),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "删除快捷功能",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
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
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
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

private enum class SettingsSection(val title: String) {
    GENERAL("常规"),
    APPEARANCE("外观"),
    HOME_SEARCH("主页与搜索"),
    WEB("网页"),
    PRIVACY("隐私与安全"),
    SYSTEM("系统"),
    ABOUT("关于"),
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
    var section by remember { mutableStateOf<SettingsSection?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 36.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (section != null) {
                        IconButton(onClick = { section = null }) {
                            Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    } else {
                        Spacer(Modifier.size(48.dp))
                    }
                    Text(
                        section?.title ?: "设置",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.size(48.dp))
                }
            }

            when (section) {
                null -> {
                    item {
                        SettingsCategory(
                            title = "常规",
                            subtitle = "默认内核、标签页恢复",
                            icon = Icons.Outlined.Settings,
                        ) { section = SettingsSection.GENERAL }
                    }
                    item {
                        SettingsCategory(
                            title = "外观",
                            subtitle = "主题、地址栏位置、网页缩放",
                            icon = Icons.Outlined.Visibility,
                        ) { section = SettingsSection.APPEARANCE }
                    }
                    item {
                        SettingsCategory(
                            title = "主页与搜索",
                            subtitle = "主页和默认搜索引擎",
                            icon = Icons.Outlined.Search,
                        ) { section = SettingsSection.HOME_SEARCH }
                    }
                    item {
                        SettingsCategory(
                            title = "网页",
                            subtitle = "JavaScript、Cookie、桌面模式",
                            icon = Icons.Outlined.Language,
                        ) { section = SettingsSection.WEB }
                    }
                    item {
                        SettingsCategory(
                            title = "隐私与安全",
                            subtitle = "跟踪保护、清除浏览数据",
                            icon = Icons.Outlined.Lock,
                        ) { section = SettingsSection.PRIVACY }
                    }
                    item {
                        SettingsCategory(
                            title = "系统",
                            subtitle = "默认浏览器",
                            icon = Icons.Outlined.OpenInNew,
                        ) { section = SettingsSection.SYSTEM }
                    }
                    item {
                        SettingsCategory(
                            title = "关于",
                            subtitle = "版本和浏览器内核",
                            icon = Icons.Outlined.Code,
                        ) { section = SettingsSection.ABOUT }
                    }
                }

                SettingsSection.GENERAL -> {
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
                        ToggleSetting(
                            title = "恢复上次标签页",
                            subtitle = "下次启动时恢复普通标签页",
                            checked = settings.restoreTabs,
                            onChecked = { onChange(settings.copy(restoreTabs = it)) },
                        )
                    }
                }

                SettingsSection.APPEARANCE -> {
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
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                            Text(
                                "网页字体缩放：\${settings.textScale}%",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Slider(
                                value = settings.textScale.toFloat(),
                                onValueChange = {
                                    onChange(
                                        settings.copy(
                                            textScale = it.toInt().coerceIn(50, 200),
                                        ),
                                    )
                                },
                                valueRange = 50f..200f,
                            )
                        }
                    }
                }

                SettingsSection.HOME_SEARCH -> {
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
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
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
                }

                SettingsSection.WEB -> {
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
                }

                SettingsSection.PRIVACY -> {
                    item {
                        ChoiceSetting(
                            title = "跟踪保护",
                            subtitle = "GeckoView 使用原生 ETP；WebView 使用本地域名拦截",
                            values = TrackingProtection.entries,
                            selected = settings.trackingProtection,
                            label = { it.label },
                            onSelected = { onChange(settings.copy(trackingProtection = it)) },
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
                }

                SettingsSection.SYSTEM -> {
                    item {
                        ListItem(
                            headlineContent = { Text("设为默认浏览器") },
                            supportingContent = { Text("打开 Android 默认浏览器选择界面") },
                            leadingContent = { Icon(Icons.Outlined.Language, null) },
                            modifier = Modifier.clickable(onClick = onDefaultBrowser),
                        )
                    }
                }

                SettingsSection.ABOUT -> {
                    item {
                        ListItem(
                            headlineContent = { Text("YBrowser 0.5.0") },
                            supportingContent = {
                                Text("Material 3 · GeckoView + System WebView 双内核")
                            },
                            leadingContent = { Icon(Icons.Outlined.Code, null) },
                        )
                    }
                    item {
                        Text(
                            "界面采用移动优先布局：底部工具栏、Bottom Sheet 菜单、卡片式标签页和分组设置。",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCategory(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Icon(Icons.Outlined.ArrowForward, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
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

private enum class SiteBooleanChoice(val label: String) {
    FOLLOW_GLOBAL("跟随全局"),
    ENABLED("开启"),
    DISABLED("关闭"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteSettingsSheet(
    host: String,
    current: SiteSettings?,
    global: BrowserSettings,
    onSave: (SiteSettings) -> Unit,
    onReset: () -> Unit,
    onResetPermissions: () -> Unit,
    onDismiss: () -> Unit,
) {
    var jsChoice by remember(current) {
        mutableStateOf(
            when (current?.javaScriptEnabled) {
                true -> SiteBooleanChoice.ENABLED
                false -> SiteBooleanChoice.DISABLED
                null -> SiteBooleanChoice.FOLLOW_GLOBAL
            },
        )
    }
    var cookieChoice by remember(current) {
        mutableStateOf(
            when (current?.cookiesEnabled) {
                true -> SiteBooleanChoice.ENABLED
                false -> SiteBooleanChoice.DISABLED
                null -> SiteBooleanChoice.FOLLOW_GLOBAL
            },
        )
    }
    var trackingChoice by remember(current) {
        mutableStateOf(current?.trackingProtection)
    }
    var useCustomTextScale by remember(current) {
        mutableStateOf(current?.textScale != null)
    }
    var textScale by remember(current) {
        mutableStateOf((current?.textScale ?: global.textScale).coerceIn(50, 200))
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            item {
                Text(
                    "网站设置",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    host,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                ChoiceSetting(
                    title = "JavaScript",
                    subtitle = "全局：" + if (global.javaScriptEnabled) "开启" else "关闭",
                    values = SiteBooleanChoice.entries,
                    selected = jsChoice,
                    label = { it.label },
                    onSelected = { jsChoice = it },
                )
            }
            item {
                ChoiceSetting(
                    title = "Cookie",
                    subtitle = "全局：" + if (global.cookiesEnabled) "开启" else "关闭",
                    values = SiteBooleanChoice.entries,
                    selected = cookieChoice,
                    label = { it.label },
                    onSelected = { cookieChoice = it },
                )
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("跟踪保护", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "全局：" + global.trackingProtection.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(7.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        FilterChip(
                            selected = trackingChoice == null,
                            onClick = { trackingChoice = null },
                            label = { Text("跟随全局") },
                        )
                        TrackingProtection.entries.forEach { value ->
                            FilterChip(
                                selected = trackingChoice == value,
                                onClick = { trackingChoice = value },
                                label = { Text(value.label) },
                            )
                        }
                    }
                }
            }
            item {
                ListItem(
                    headlineContent = { Text("单独设置字体缩放") },
                    supportingContent = {
                        Text(
                            if (useCustomTextScale) "${textScale}%"
                            else "跟随全局 ${global.textScale}%",
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = useCustomTextScale,
                            onCheckedChange = { useCustomTextScale = it },
                        )
                    },
                    modifier = Modifier.clickable {
                        useCustomTextScale = !useCustomTextScale
                    },
                )
                if (useCustomTextScale) {
                    Slider(
                        value = textScale.toFloat(),
                        onValueChange = { textScale = it.toInt().coerceIn(50, 200) },
                        valueRange = 50f..200f,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = {
                            onReset()
                            onDismiss()
                        },
                    ) {
                        Text("恢复全局")
                    }
                    TextButton(onClick = onResetPermissions) {
                        Text("重置权限")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    TextButton(
                        onClick = {
                            onSave(
                                SiteSettings(
                                    host = host,
                                    javaScriptEnabled = when (jsChoice) {
                                        SiteBooleanChoice.FOLLOW_GLOBAL -> null
                                        SiteBooleanChoice.ENABLED -> true
                                        SiteBooleanChoice.DISABLED -> false
                                    },
                                    cookiesEnabled = when (cookieChoice) {
                                        SiteBooleanChoice.FOLLOW_GLOBAL -> null
                                        SiteBooleanChoice.ENABLED -> true
                                        SiteBooleanChoice.DISABLED -> false
                                    },
                                    trackingProtection = trackingChoice,
                                    textScale = if (useCustomTextScale) textScale else null,
                                ),
                            )
                            onDismiss()
                        },
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsSheet(
    downloads: List<BrowserDownloadState>,
    onRefresh: () -> Unit,
    onOpen: (BrowserDownloadState) -> Unit,
    onShare: (BrowserDownloadState) -> Unit,
    onRetry: (BrowserDownloadState) -> Unit,
    onDelete: (BrowserDownloadState) -> Unit,
    onClearCompleted: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "下载",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = onRefresh) {
                Text("刷新")
            }
            TextButton(
                onClick = onClearCompleted,
                enabled = downloads.any {
                    it.status == BrowserDownloadStatus.SUCCESS ||
                        it.status == BrowserDownloadStatus.FAILED ||
                        it.status == BrowserDownloadStatus.UNKNOWN
                },
            ) {
                Text("清理")
            }
        }

        if (downloads.isEmpty()) {
            Text(
                "还没有下载记录",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 30.dp),
            ) {
                items(downloads, key = { it.record.id }) { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            item.record.fileName,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            downloadStatusText(item),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        item.progress?.let { progress ->
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (item.status == BrowserDownloadStatus.SUCCESS) {
                                TextButton(onClick = { onOpen(item) }) {
                                    Text("打开")
                                }
                                TextButton(onClick = { onShare(item) }) {
                                    Text("分享")
                                }
                            }
                            if (item.status == BrowserDownloadStatus.FAILED ||
                                item.status == BrowserDownloadStatus.UNKNOWN
                            ) {
                                TextButton(onClick = { onRetry(item) }) {
                                    Text("重试")
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { onDelete(item) }) {
                                Text(
                                    if (
                                        item.status == BrowserDownloadStatus.RUNNING ||
                                        item.status == BrowserDownloadStatus.PENDING ||
                                        item.status == BrowserDownloadStatus.PAUSED
                                    ) {
                                        "取消"
                                    } else {
                                        "删除记录"
                                    },
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun downloadStatusText(item: BrowserDownloadState): String {
    val status = when (item.status) {
        BrowserDownloadStatus.PENDING -> "等待中"
        BrowserDownloadStatus.RUNNING -> "下载中"
        BrowserDownloadStatus.PAUSED -> "已暂停"
        BrowserDownloadStatus.SUCCESS -> "已完成"
        BrowserDownloadStatus.FAILED -> "失败"
        BrowserDownloadStatus.UNKNOWN -> "状态未知"
    }

    val size = when {
        item.totalBytes > 0L -> {
            formatBytes(item.bytesDownloaded) + " / " + formatBytes(item.totalBytes)
        }
        item.bytesDownloaded > 0L -> formatBytes(item.bytesDownloaded)
        else -> null
    }

    return if (size == null) status else status + " · " + size
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return bytes.toString() + " B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
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
