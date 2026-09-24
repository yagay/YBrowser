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
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.ViewList
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
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
    addressSuggestions: List<BrowserAddressSuggestion>,
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
    onQrScan: () -> Unit,
    onReload: () -> Unit,
    onBookmark: () -> Unit,
    isBookmarked: Boolean,
    onShowBookmarks: () -> Unit,
    onShowHistory: () -> Unit,
    onNavigationTrails: () -> Unit,
    onShowFind: () -> Unit,
    onToggleDesktop: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onDownloads: () -> Unit,
    onReader: () -> Unit,
    onOfflineReader: () -> Unit,
    onSnoozedTabs: () -> Unit,
    onPrint: () -> Unit,
    onTranslate: () -> Unit,
    onViewSource: () -> Unit,
    onOpenExternal: () -> Unit,
    onAddToHome: () -> Unit,
    onSiteSettings: () -> Unit,
    onSecurityInfo: () -> Unit,
    onPrivacyReport: () -> Unit,
    blockedCount: Int,
    onUserScripts: () -> Unit,
    onCustomFilters: () -> Unit,
    onProfiles: () -> Unit,
    profileLabel: String,
    onExtensions: () -> Unit,
    onSettings: () -> Unit,
    showBindingAction: Boolean,
    bindingLabel: String,
    bindingActive: Boolean,
    bindingEnabled: Boolean,
    onBindingClick: () -> Unit,
    onMenuShortcutsChanged: (List<BrowserMenuShortcut>) -> Unit,
    onClose: (() -> Unit)? = null,
) {
    var editingShortcuts by remember(showMenu) { mutableStateOf(false) }
    var addressFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    fun shortcutLabel(shortcut: BrowserMenuShortcut): String = when (shortcut) {
        BrowserMenuShortcut.BOOKMARK ->
            if (isBookmarked) "取消收藏" else "收藏"
        BrowserMenuShortcut.DESKTOP_MODE ->
            if (selectedTab.desktopMode || settings.desktopModeByDefault) "手机版" else "桌面版"
        BrowserMenuShortcut.PRIVACY_REPORT ->
            if (blockedCount > 0) "隐私 " + blockedCount else "隐私报告"
        BrowserMenuShortcut.PROFILES -> profileLabel
        else -> shortcut.label
    }

    fun shortcutIcon(shortcut: BrowserMenuShortcut): ImageVector = when (shortcut) {
        BrowserMenuShortcut.NEW_TAB -> Icons.Outlined.Add
        BrowserMenuShortcut.PRIVATE_TAB -> Icons.Outlined.Lock
        BrowserMenuShortcut.SHARE -> Icons.Outlined.Share
        BrowserMenuShortcut.COPY_LINK -> Icons.Outlined.ContentCopy
        BrowserMenuShortcut.BOOKMARKS -> Icons.Outlined.Bookmark
        BrowserMenuShortcut.HISTORY -> Icons.Outlined.History
        BrowserMenuShortcut.NAVIGATION_TRAILS -> Icons.Outlined.History
        BrowserMenuShortcut.DOWNLOADS -> Icons.Outlined.Download
        BrowserMenuShortcut.FIND_IN_PAGE -> Icons.Outlined.FindInPage
        BrowserMenuShortcut.HOME -> Icons.Outlined.Home
        BrowserMenuShortcut.QR_SCAN -> Icons.Outlined.QrCodeScanner
        BrowserMenuShortcut.BOOKMARK ->
            if (isBookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder
        BrowserMenuShortcut.DESKTOP_MODE -> Icons.Outlined.Visibility
        BrowserMenuShortcut.READER -> Icons.Outlined.FindInPage
        BrowserMenuShortcut.OFFLINE_READER -> Icons.Outlined.Article
        BrowserMenuShortcut.SNOOZED_TABS -> Icons.Outlined.Alarm
        BrowserMenuShortcut.TRANSLATE -> Icons.Outlined.Translate
        BrowserMenuShortcut.VIEW_SOURCE -> Icons.Outlined.Code
        BrowserMenuShortcut.PRINT -> Icons.Outlined.Print
        BrowserMenuShortcut.OPEN_EXTERNAL -> Icons.Outlined.OpenInNew
        BrowserMenuShortcut.ADD_TO_HOME -> Icons.Outlined.Home
        BrowserMenuShortcut.SITE_SETTINGS -> Icons.Outlined.Language
        BrowserMenuShortcut.SECURITY_INFO -> Icons.Outlined.Lock
        BrowserMenuShortcut.PRIVACY_REPORT -> Icons.Outlined.Lock
        BrowserMenuShortcut.USER_SCRIPTS -> Icons.Outlined.Code
        BrowserMenuShortcut.CUSTOM_FILTERS -> Icons.Outlined.FilterAlt
        BrowserMenuShortcut.PROFILES -> Icons.Outlined.AccountCircle
        BrowserMenuShortcut.EXTENSIONS -> Icons.Outlined.Extension
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
            BrowserMenuShortcut.NAVIGATION_TRAILS -> onNavigationTrails()
            BrowserMenuShortcut.DOWNLOADS -> onDownloads()
            BrowserMenuShortcut.FIND_IN_PAGE -> onShowFind()
            BrowserMenuShortcut.HOME -> onHome()
            BrowserMenuShortcut.QR_SCAN -> onQrScan()
            BrowserMenuShortcut.BOOKMARK -> onBookmark()
            BrowserMenuShortcut.DESKTOP_MODE -> onToggleDesktop()
            BrowserMenuShortcut.READER -> onReader()
            BrowserMenuShortcut.OFFLINE_READER -> onOfflineReader()
            BrowserMenuShortcut.SNOOZED_TABS -> onSnoozedTabs()
            BrowserMenuShortcut.TRANSLATE -> onTranslate()
            BrowserMenuShortcut.VIEW_SOURCE -> onViewSource()
            BrowserMenuShortcut.PRINT -> onPrint()
            BrowserMenuShortcut.OPEN_EXTERNAL -> onOpenExternal()
            BrowserMenuShortcut.ADD_TO_HOME -> onAddToHome()
            BrowserMenuShortcut.SITE_SETTINGS -> onSiteSettings()
            BrowserMenuShortcut.SECURITY_INFO -> onSecurityInfo()
            BrowserMenuShortcut.PRIVACY_REPORT -> onPrivacyReport()
            BrowserMenuShortcut.USER_SCRIPTS -> onUserScripts()
            BrowserMenuShortcut.CUSTOM_FILTERS -> onCustomFilters()
            BrowserMenuShortcut.PROFILES -> onProfiles()
            BrowserMenuShortcut.EXTENSIONS -> onExtensions()
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
                        .onFocusChanged { addressFocused = it.isFocused }
                        .pointerInput(onPreviousTab, onNextTab) {
                            val swipeThreshold = 56.dp.toPx()
                            var dragDistance = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { dragDistance = 0f },
                                onHorizontalDrag = { _, amount ->
                                    dragDistance += amount
                                },
                                onDragEnd = {
                                    if (abs(dragDistance) >= swipeThreshold) {
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
                        onGo = {
                            focusManager.clearFocus()
                            onNavigate(addressInput)
                        },
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

                if (onClose != null) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "关闭",
                        )
                    }
                }
            }
        }

        if (
            addressFocused &&
            addressInput.isNotBlank() &&
            addressSuggestions.isNotEmpty()
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RectangleShape,
                tonalElevation = 2.dp,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column {
                    addressSuggestions.take(4).forEach { suggestion ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    suggestion.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    suggestion.url,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = {
                                Icon(
                                    when {
                                        suggestion.online ->
                                            Icons.Outlined.Search
                                        suggestion.bookmarked ->
                                            Icons.Outlined.Bookmark
                                        else ->
                                            Icons.Outlined.History
                                    },
                                    contentDescription = null,
                                )
                            },
                            modifier = Modifier.clickable {
                                focusManager.clearFocus()
                                onNavigate(suggestion.url)
                            },
                        )
                    }
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
                    headlineContent = { Text("扫描二维码") },
                    leadingContent = { Icon(Icons.Outlined.QrCodeScanner, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onQrScan()
                    },
                )
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
                    headlineContent = { Text("休眠标签") },
                    leadingContent = { Icon(Icons.Outlined.Alarm, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onSnoozedTabs()
                    },
                )
                ListItem(
                    headlineContent = { Text("离线阅读") },
                    supportingContent = { Text("打开已保存的阅读模式文章") },
                    leadingContent = { Icon(Icons.Outlined.Article, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onOfflineReader()
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
                    headlineContent = { Text("导航轨迹") },
                    supportingContent = { Text("查看当前标签页实际访问路径") },
                    leadingContent = { Icon(Icons.Outlined.History, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onNavigationTrails()
                    },
                )
                ListItem(
                    headlineContent = { Text("连接安全") },
                    supportingContent = { Text("查看 HTTPS、证书和混合内容状态") },
                    leadingContent = { Icon(Icons.Outlined.Lock, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onSecurityInfo()
                    },
                )
                ListItem(
                    headlineContent = { Text("网站设置") },
                    leadingContent = { Icon(Icons.Outlined.Language, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onSiteSettings()
                    },
                )
                ListItem(
                    headlineContent = { Text("隐私报告") },
                    supportingContent = {
                        Text("当前页面已拦截 " + blockedCount + " 项")
                    },
                    leadingContent = { Icon(Icons.Outlined.Lock, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onPrivacyReport()
                    },
                )
                ListItem(
                    headlineContent = { Text("自定义过滤") },
                    supportingContent = { Text("添加需要阻止的域名规则") },
                    leadingContent = { Icon(Icons.Outlined.FilterAlt, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onCustomFilters()
                    },
                )
                ListItem(
                    headlineContent = { Text("用户脚本") },
                    supportingContent = { Text("按域名管理自动注入的 JavaScript") },
                    leadingContent = { Icon(Icons.Outlined.Code, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onUserScripts()
                    },
                )
                ListItem(
                    headlineContent = { Text("Profiles") },
                    supportingContent = { Text("当前：" + profileLabel) },
                    leadingContent = { Icon(Icons.Outlined.AccountCircle, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onProfiles()
                    },
                )
                ListItem(
                    headlineContent = { Text("Firefox 扩展") },
                    supportingContent = { Text("安装和管理 GeckoView 扩展") },
                    leadingContent = { Icon(Icons.Outlined.Extension, null) },
                    modifier = Modifier.clickable {
                        onDismissMenu()
                        onExtensions()
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
    TABS_GESTURES("标签与手势"),
    APPEARANCE("外观"),
    HOME_SEARCH("主页与搜索"),
    WEB("网页"),
    DOWNLOADS("下载"),
    PRIVACY("隐私与安全"),
    SYSTEM("系统"),
    SYNC("同步"),
    EXTENSIONS("Firefox 扩展"),
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
    onSync: () -> Unit,
    onExtensions: () -> Unit,
    onUserScripts: () -> Unit,
    onCustomFilters: () -> Unit,
    onProfiles: () -> Unit,
    profileLabel: String,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onExportBookmarks: () -> Unit = {},
    onImportBookmarks: () -> Unit = {},
) {
    var homeInput by remember(settings.homepage) { mutableStateOf(settings.homepage) }
    var searxngInput by remember(settings.searxngBaseUrl) {
        mutableStateOf(settings.searxngBaseUrl)
    }
    var dohInput by remember(settings.customDnsOverHttpsUrl) {
        mutableStateOf(settings.customDnsOverHttpsUrl)
    }
    val context = LocalContext.current
    val externalDownloadManagers =
        remember {
            ExternalDownloadManager.discover(context)
        }
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
                            title = "标签与手势",
                            subtitle = "标签布局、自动清理、下拉刷新",
                            icon = Icons.Outlined.ViewList,
                        ) { section = SettingsSection.TABS_GESTURES }
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
                            title = "下载",
                            subtitle = "内置断点续传、Android 下载器、外部下载器",
                            icon = Icons.Outlined.Download,
                        ) { section = SettingsSection.DOWNLOADS }
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
                            subtitle = "默认浏览器、备份与恢复",
                            icon = Icons.Outlined.OpenInNew,
                        ) { section = SettingsSection.SYSTEM }
                    }
                    item {
                        SettingsCategory(
                            title = "同步",
                            subtitle = "通过 WebDAV 跨设备同步完整浏览器备份",
                            icon = Icons.Outlined.Refresh,
                        ) { section = SettingsSection.SYNC }
                    }
                    item {
                        SettingsCategory(
                            title = "Firefox 扩展",
                            subtitle = "安装、启停、更新和隐私模式权限",
                            icon = Icons.Outlined.Extension,
                        ) { section = SettingsSection.EXTENSIONS }
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
                    item {
                        ListItem(
                            headlineContent = { Text("浏览器 Profiles") },
                            supportingContent = { Text("当前：" + profileLabel + " · 独立 Cookie、标签、收藏、历史和站点权限") },
                            leadingContent = { Icon(Icons.Outlined.AccountCircle, null) },
                            trailingContent = { Icon(Icons.Outlined.ArrowForward, null) },
                            modifier = Modifier.clickable(onClick = onProfiles),
                        )
                    }
                }

                SettingsSection.TABS_GESTURES -> {
                    item {
                        ChoiceSetting(
                            title = "标签页布局",
                            values = TabSwitcherLayout.entries,
                            selected = settings.tabSwitcherLayout,
                            label = { it.label },
                            onSelected = {
                                onChange(settings.copy(tabSwitcherLayout = it))
                            },
                        )
                    }
                    item {
                        ChoiceSetting(
                            title = "自动清理旧标签",
                            subtitle = "仅关闭长期未访问且未固定的普通标签",
                            values = listOf(0, 1, 7, 30),
                            selected = settings.autoCloseTabsDays,
                            label = { days ->
                                when (days) {
                                    0 -> "关闭"
                                    1 -> "1 天"
                                    else -> days.toString() + " 天"
                                }
                            },
                            onSelected = {
                                onChange(settings.copy(autoCloseTabsDays = it))
                            },
                        )
                    }
                    item {
                        ToggleSetting(
                            title = "顶部下拉刷新",
                            subtitle = "网页到顶部后继续下拉触发刷新",
                            checked = settings.pullToRefreshEnabled,
                            onChecked = {
                                onChange(settings.copy(pullToRefreshEnabled = it))
                            },
                        )
                    }
                    if (settings.pullToRefreshEnabled) {
                        item {
                            Column(
                                Modifier.padding(
                                    horizontal = 20.dp,
                                    vertical = 10.dp,
                                ),
                            ) {
                                Text(
                                    "下拉刷新距离：" +
                                        settings.pullToRefreshThresholdDp +
                                        " dp",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Slider(
                                    value = settings.pullToRefreshThresholdDp.toFloat(),
                                    onValueChange = {
                                        onChange(
                                            settings.copy(
                                                pullToRefreshThresholdDp =
                                                    it.toInt().coerceIn(60, 160)
                                            )
                                        )
                                    },
                                    valueRange = 60f..160f,
                                )
                            }
                        }
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
                        ToggleSetting(
                            title = "强制深色网页",
                            subtitle = "仅 System WebView 支持；GeckoView 仍使用网站自己的深色主题",
                            checked = settings.forceDarkWebView,
                            onChecked = {
                                onChange(
                                    settings.copy(
                                        forceDarkWebView = it
                                    )
                                )
                            },
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
                                "网页字体缩放：${settings.textScale}%",
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
                        ToggleSetting(
                            title = "历史记录建议",
                            subtitle = "地址栏输入时显示匹配的浏览历史",
                            checked = settings.historySuggestionsEnabled,
                            onChecked = {
                                onChange(
                                    settings.copy(
                                        historySuggestionsEnabled = it
                                    )
                                )
                            },
                        )
                    }
                    item {
                        ToggleSetting(
                            title = "收藏夹建议",
                            subtitle = "地址栏输入时显示匹配的收藏",
                            checked = settings.bookmarkSuggestionsEnabled,
                            onChecked = {
                                onChange(
                                    settings.copy(
                                        bookmarkSuggestionsEnabled = it
                                    )
                                )
                            },
                        )
                    }
                    item {
                        ToggleSetting(
                            title = "在线搜索建议",
                            subtitle = "Google、DuckDuckGo、Bing 输入时获取联想；隐私标签始终不发送",
                            checked = settings.onlineSearchSuggestionsEnabled,
                            onChecked = {
                                onChange(
                                    settings.copy(
                                        onlineSearchSuggestionsEnabled = it
                                    )
                                )
                            },
                        )
                    }
                    if (settings.searchEngine == SearchEngine.SEARXNG) {
                        item {
                            Column(
                                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    "SearXNG 地址",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = searxngInput,
                                    onValueChange = { searxngInput = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    placeholder = { Text("https://search.example.com") },
                                )
                                TextButton(
                                    enabled = searxngInput.isNotBlank(),
                                    onClick = {
                                        onChange(
                                            settings.copy(
                                                searxngBaseUrl = searxngInput.trim(),
                                            ),
                                        )
                                    },
                                ) {
                                    Text("保存 SearXNG 地址")
                                }
                            }
                        }
                    }
                    item {
                        ListItem(
                            headlineContent = {
                                Text("导出收藏 HTML")
                            },
                            supportingContent = {
                                Text("兼容 Chrome / Firefox / Edge 的标准书签 HTML")
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Outlined.Download,
                                    null,
                                )
                            },
                            modifier =
                                Modifier.clickable(
                                    onClick =
                                        onExportBookmarks
                                ),
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = {
                                Text("导入收藏 HTML")
                            },
                            supportingContent = {
                                Text("与现有收藏按网址合并，不覆盖不同收藏")
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Outlined.OpenInNew,
                                    null,
                                )
                            },
                            modifier =
                                Modifier.clickable(
                                    onClick =
                                        onImportBookmarks
                                ),
                        )
                    }
                    item {
                        ToggleSetting(
                            title = "原生新标签页",
                            subtitle = "新标签显示 YBrowser 搜索、快捷收藏和最近访问；主页按钮仍使用下方主页地址",
                            checked = settings.nativeNewTabPage,
                            onChecked = { onChange(settings.copy(nativeNewTabPage = it)) },
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
                            title = "阻止第三方 Cookie",
                            subtitle = "保留第一方登录 Cookie，阻止第三方跨站 Cookie",
                            checked = settings.blockThirdPartyCookies,
                            onChecked = {
                                onChange(
                                    settings.copy(
                                        blockThirdPartyCookies = it
                                    )
                                )
                            },
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
                            title = "阻止媒体自动播放",
                            subtitle = "网站需要用户操作后才能开始播放；部分网站可能自行覆盖",
                            checked = settings.blockAutoplay,
                            onChecked = { onChange(settings.copy(blockAutoplay = it)) },
                        )
                    }
                    item {
                        ChoiceSetting(
                            title = "网页翻译服务",
                            values = TranslationProvider.entries,
                            selected = settings.translationProvider,
                            label = { it.label },
                            onSelected = {
                                onChange(
                                    settings.copy(
                                        translationProvider = it
                                    )
                                )
                            },
                        )
                    }
                    item {
                        ChoiceSetting(
                            title = "外部 App 链接",
                            subtitle = "电话、邮件、地图、intent:// 等非网页链接",
                            values = ExternalAppLinkHandling.entries,
                            selected = settings.externalAppLinkHandling,
                            label = { it.label },
                            onSelected = {
                                onChange(
                                    settings.copy(
                                        externalAppLinkHandling = it
                                    )
                                )
                            },
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("用户脚本") },
                            supportingContent = { Text("Toppings：按域名启用自定义 JavaScript") },
                            leadingContent = { Icon(Icons.Outlined.Code, null) },
                            trailingContent = { Icon(Icons.Outlined.ArrowForward, null) },
                            modifier = Modifier.clickable(onClick = onUserScripts),
                        )
                    }
                }

                SettingsSection.DOWNLOADS -> {
                    item {
                        ChoiceSetting(
                            title = "下载处理方式",
                            values = DownloadManagerMode.entries,
                            selected = settings.downloadManagerMode,
                            label = { it.label },
                            onSelected = {
                                onChange(settings.copy(downloadManagerMode = it))
                            },
                        )
                    }
                    if (
                        settings.downloadManagerMode ==
                        DownloadManagerMode.EXTERNAL
                    ) {
                        if (externalDownloadManagers.isEmpty()) {
                            item {
                                Text(
                                    "没有检测到支持的外部下载器（支持 1DM / ADM 等）。",
                                    modifier = Modifier.padding(
                                        horizontal = 20.dp,
                                        vertical = 10.dp,
                                    ),
                                    color =
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            item {
                                val selectedManager =
                                    externalDownloadManagers.firstOrNull {
                                        it.id == settings.externalDownloadManagerId
                                    } ?: externalDownloadManagers.first()
                                ChoiceSetting(
                                    title = "固定外部下载器",
                                    values = externalDownloadManagers,
                                    selected = selectedManager,
                                    label = { it.label },
                                    onSelected = {
                                        onChange(
                                            settings.copy(
                                                externalDownloadManagerId = it.id
                                            )
                                        )
                                    },
                                )
                            }
                        }
                    }
                    item {
                        ToggleSetting(
                            title = "向外部下载器共享会话信息",
                            subtitle = "需要时传递 Cookie、User-Agent 和 Referer；仅建议对可信下载器开启",
                            checked = settings.shareDownloadSessionData,
                            onChecked = {
                                onChange(
                                    settings.copy(
                                        shareDownloadSessionData = it
                                    )
                                )
                            },
                        )
                    }
                }

                SettingsSection.PRIVACY -> {
                    item {
                        ToggleSetting(
                            title = "HTTPS-Only",
                            subtitle = "HTTP 主页面优先升级 HTTPS；失败时询问是否继续 HTTP",
                            checked = settings.httpsOnlyMode,
                            onChecked = {
                                onChange(
                                    settings.copy(
                                        httpsOnlyMode = it
                                    )
                                )
                            },
                        )
                    }
                    item {
                        ToggleSetting(
                            title = "Do Not Track",
                            subtitle = "向网站发送 DNT=1，并暴露 navigator.doNotTrack",
                            checked = settings.doNotTrackEnabled,
                            onChecked = {
                                onChange(
                                    settings.copy(
                                        doNotTrackEnabled = it
                                    )
                                )
                            },
                        )
                    }
                    item {
                        ToggleSetting(
                            title = "Global Privacy Control",
                            subtitle = "发送 Sec-GPC=1，并暴露 navigator.globalPrivacyControl",
                            checked = settings.globalPrivacyControlEnabled,
                            onChecked = {
                                onChange(
                                    settings.copy(
                                        globalPrivacyControlEnabled = it
                                    )
                                )
                            },
                        )
                    }
                    item {
                        ChoiceSetting(
                            title = "WebRTC IP 保护",
                            subtitle = "GeckoView 支持细分 IP 策略；System WebView 的非“标准”模式会直接阻止 WebRTC",
                            values = WebRtcProtectionMode.entries,
                            selected = settings.webRtcProtectionMode,
                            label = { it.label },
                            onSelected = {
                                onChange(
                                    settings.copy(
                                        webRtcProtectionMode = it
                                    )
                                )
                            },
                        )
                    }
                    item {
                        ChoiceSetting(
                            title = "DNS over HTTPS",
                            subtitle = "仅 GeckoView 使用；System WebView 继续使用 Android 系统 DNS",
                            values = DnsOverHttpsProvider.entries,
                            selected = settings.dnsOverHttpsProvider,
                            label = { it.label },
                            onSelected = {
                                onChange(
                                    settings.copy(
                                        dnsOverHttpsProvider = it
                                    )
                                )
                            },
                        )
                    }
                    if (
                        settings.dnsOverHttpsProvider ==
                        DnsOverHttpsProvider.CUSTOM
                    ) {
                        item {
                            Column(
                                Modifier.padding(
                                    horizontal = 20.dp,
                                    vertical = 8.dp,
                                ),
                            ) {
                                Text(
                                    "自定义 DoH 地址",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = dohInput,
                                    onValueChange = {
                                        dohInput = it.take(512)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    placeholder = {
                                        Text(
                                            "https://dns.example/dns-query"
                                        )
                                    },
                                )
                                TextButton(
                                    enabled =
                                        dohInput.trim().startsWith("https://"),
                                    onClick = {
                                        onChange(
                                            settings.copy(
                                                customDnsOverHttpsUrl =
                                                    dohInput.trim()
                                            )
                                        )
                                    },
                                ) {
                                    Text("保存")
                                }
                            }
                        }
                    }
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
                            headlineContent = { Text("自定义过滤") },
                            supportingContent = { Text("管理额外的阻止域名；Gecko 强过滤可配合 Firefox 扩展") },
                            leadingContent = { Icon(Icons.Outlined.FilterAlt, null) },
                            trailingContent = { Icon(Icons.Outlined.ArrowForward, null) },
                            modifier = Modifier.clickable(onClick = onCustomFilters),
                        )
                    }
                    item {
                        ToggleSetting(
                            title = "退出时清除历史",
                            subtitle = "正常退出主浏览器时清除当前 Profile 的浏览历史",
                            checked = settings.clearHistoryOnExit,
                            onChecked = {
                                onChange(
                                    settings.copy(
                                        clearHistoryOnExit = it
                                    )
                                )
                            },
                        )
                    }
                    item {
                        ToggleSetting(
                            title = "退出时清除站点数据",
                            subtitle = "正常退出主浏览器时清 Cookie、站点存储和两个内核缓存",
                            checked = settings.clearSiteDataOnExit,
                            onChecked = {
                                onChange(
                                    settings.copy(
                                        clearSiteDataOnExit = it
                                    )
                                )
                            },
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
                    item {
                        ListItem(
                            headlineContent = { Text("导出完整备份") },
                            supportingContent = { Text("设置、标签、收藏、历史、权限、脚本、下载、休眠标签和离线文章") },
                            leadingContent = { Icon(Icons.Outlined.Download, null) },
                            modifier = Modifier.clickable(onClick = onExportBackup),
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("导入完整备份") },
                            supportingContent = { Text("从 YBrowser JSON 备份恢复本地数据") },
                            leadingContent = { Icon(Icons.Outlined.OpenInNew, null) },
                            modifier = Modifier.clickable(onClick = onImportBackup),
                        )
                    }
                }

                SettingsSection.SYNC -> {
                    item {
                        ListItem(
                            headlineContent = { Text("WebDAV 同步") },
                            supportingContent = {
                                Text("加密保存密码；可上传本机备份或下载远端备份恢复")
                            },
                            leadingContent = { Icon(Icons.Outlined.Refresh, null) },
                            trailingContent = { Icon(Icons.Outlined.ArrowForward, null) },
                            modifier = Modifier.clickable(onClick = onSync),
                        )
                    }
                    item {
                        Text(
                            "同步文件使用与“导出完整备份”相同的 JSON 格式；WebDAV 密码使用 Android Keystore 加密后保存在本机。",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SettingsSection.EXTENSIONS -> {
                    item {
                        ListItem(
                            headlineContent = { Text("管理 Firefox 扩展") },
                            supportingContent = {
                                Text("GeckoView 支持 Mozilla 签名扩展；System WebView 不加载 Firefox 扩展")
                            },
                            leadingContent = { Icon(Icons.Outlined.Extension, null) },
                            trailingContent = { Icon(Icons.Outlined.ArrowForward, null) },
                            modifier = Modifier.clickable(onClick = onExtensions),
                        )
                    }
                    item {
                        Text(
                            "安装时由 GeckoView 校验扩展签名。扩展默认不获得隐私标签访问权限，可在扩展管理中单独开启。",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SettingsSection.ABOUT -> {
                    item {
                        ListItem(
                            headlineContent = { Text("YBrowser ${BuildConfig.VERSION_NAME}") },
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
    var query by remember { mutableStateOf("") }
    val visible = remember(bookmarks, query) {
        val needle = query.trim()
        if (needle.isBlank()) bookmarks
        else bookmarks.filter {
            it.title.contains(needle, ignoreCase = true) ||
                it.url.contains(needle, ignoreCase = true)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "收藏夹",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            placeholder = { Text("搜索收藏") },
            shape = RoundedCornerShape(18.dp),
        )
        if (bookmarks.isEmpty()) {
            Text(
                "还没有收藏",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (visible.isEmpty()) {
            Text(
                "没有匹配的收藏",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.padding(bottom = 30.dp)) {
                items(visible, key = { it.url }) { item ->
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
    var query by remember { mutableStateOf("") }
    val visible = remember(history, query) {
        val needle = query.trim()
        if (needle.isBlank()) history
        else history.filter {
            it.title.contains(needle, ignoreCase = true) ||
                it.url.contains(needle, ignoreCase = true)
        }
    }

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
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            placeholder = { Text("搜索历史") },
            shape = RoundedCornerShape(18.dp),
        )
        if (history.isEmpty()) {
            Text(
                "没有历史记录",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (visible.isEmpty()) {
            Text(
                "没有匹配的历史记录",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.padding(bottom = 30.dp)) {
                items(visible, key = { it.url + it.visitedAt }) { item ->
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
    permissionDecisions: Map<BrowserSitePermission, SitePermissionDecision>,
    onPermissionChanged: (BrowserSitePermission, SitePermissionDecision) -> Unit,
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
    var siteMuted by remember(current) {
        mutableStateOf(current?.muted == true)
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
                ToggleSetting(
                    title = "静音此网站",
                    subtitle = "进入此域名时自动将网页 video/audio 静音",
                    checked = siteMuted,
                    onChecked = { siteMuted = it },
                )
            }
            item {
                SectionTitle("权限雷达")
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    BrowserSitePermission.entries.forEach { permission ->
                        val title = when (permission) {
                            BrowserSitePermission.CAMERA -> "摄像头"
                            BrowserSitePermission.MICROPHONE -> "麦克风"
                            BrowserSitePermission.LOCATION -> "位置"
                        }
                        Text(
                            title,
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            SitePermissionDecision.entries.forEach { decision ->
                                FilterChip(
                                    selected = permissionDecisions[permission] == decision,
                                    onClick = {
                                        onPermissionChanged(permission, decision)
                                    },
                                    label = { Text(decision.label) },
                                )
                            }
                        }
                    }
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
                                    muted = if (siteMuted) true else null,
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

private enum class DownloadListFilter(val label: String) {
    ALL("全部"),
    ACTIVE("进行中"),
    COMPLETED("已完成"),
    FAILED("失败"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsSheet(
    downloads: List<BrowserDownloadState>,
    onRefresh: () -> Unit,
    onOpen: (BrowserDownloadState) -> Unit,
    onShare: (BrowserDownloadState) -> Unit,
    onRetry: (BrowserDownloadState) -> Unit,
    onPause: (BrowserDownloadState) -> Unit,
    onResume: (BrowserDownloadState) -> Unit,
    onDelete: (BrowserDownloadState) -> Unit,
    onClearCompleted: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(DownloadListFilter.ALL) }
    val visible = remember(downloads, query, filter) {
        val needle = query.trim()
        downloads.filter { item ->
            val queryMatches =
                needle.isBlank() ||
                    item.record.fileName.contains(needle, ignoreCase = true) ||
                    item.record.url.contains(needle, ignoreCase = true)
            val filterMatches = when (filter) {
                DownloadListFilter.ALL -> true
                DownloadListFilter.ACTIVE ->
                    item.status == BrowserDownloadStatus.PENDING ||
                        item.status == BrowserDownloadStatus.RUNNING ||
                        item.status == BrowserDownloadStatus.PAUSED
                DownloadListFilter.COMPLETED ->
                    item.status == BrowserDownloadStatus.SUCCESS
                DownloadListFilter.FAILED ->
                    item.status == BrowserDownloadStatus.FAILED ||
                        item.status == BrowserDownloadStatus.UNKNOWN
            }
            queryMatches && filterMatches
        }
    }

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

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            placeholder = { Text("搜索下载") },
            shape = RoundedCornerShape(18.dp),
        )

        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            DownloadListFilter.entries.forEach { value ->
                FilterChip(
                    selected = filter == value,
                    onClick = { filter = value },
                    label = { Text(value.label) },
                )
            }
        }

        when {
            downloads.isEmpty() -> {
                Text(
                    "还没有下载记录",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            visible.isEmpty() -> {
                Text(
                    "没有匹配的下载记录",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 30.dp),
                ) {
                    items(visible, key = { it.record.id }) { item ->
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
                                if (item.pausable) {
                                    TextButton(onClick = { onPause(item) }) {
                                        Text("暂停")
                                    }
                                } else if (item.resumable) {
                                    TextButton(onClick = { onResume(item) }) {
                                        Text("继续")
                                    }
                                } else if (
                                    item.status == BrowserDownloadStatus.FAILED ||
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
