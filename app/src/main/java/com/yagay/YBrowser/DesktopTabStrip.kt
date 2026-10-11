package com.yagay.YBrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** A second view of the original tabs. Selection and close actions use BrowserApp callbacks. */
@Composable
fun DesktopTabStrip(
    tabs: List<BrowserTab>,
    selectedTabId: Long,
    mode: DesktopTabBarMode,
    widthDp: Int,
    heightDp: Int,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onAdd: () -> Unit,
    onTogglePin: (Long) -> Unit,
    onDuplicate: (Long) -> Unit,
    onCloseOthers: (Long) -> Unit,
    onMove: (Long, Int) -> Unit,
    onSetGroup: (Long, String?) -> Unit,
) {
    if (mode == DesktopTabBarMode.HIDDEN) return

    val selectedGroup = tabs.firstOrNull { it.id == selectedTabId }
        ?.groupName?.takeIf { it.isNotBlank() }
    val groups = remember(tabs) {
        tabs.map { it.groupName?.takeIf(String::isNotBlank) }.distinct()
    }
    val shownTabs = if (mode == DesktopTabBarMode.GROUPED) {
        tabs.filter { it.groupName?.takeIf(String::isNotBlank) == selectedGroup }
    } else {
        tabs
    }
    val tabWidth = if (mode == DesktopTabBarMode.COMPACT) 64 else widthDp.coerceIn(96, 240)
    val rowHeight = heightDp.coerceIn(44, 68)
    val scrollState = rememberLazyListState()
    val activeIndex = shownTabs.indexOfFirst { it.id == selectedTabId }
    LaunchedEffect(selectedTabId, activeIndex, mode) {
        if (activeIndex >= 0) scrollState.animateScrollToItem(activeIndex)
    }

    Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
        Column {
            if (mode == DesktopTabBarMode.GROUPED && groups.size > 1) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) {
                    items(groups, key = { it ?: "__ungrouped__" }) { group ->
                        FilterChip(
                            selected = selectedGroup == group,
                            onClick = {
                                tabs.firstOrNull {
                                    it.groupName?.takeIf(String::isNotBlank) == group
                                }?.id?.let(onSelect)
                            },
                            label = {
                                Text(group ?: "未分组", maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 6.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LazyRow(
                    state = scrollState,
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(shownTabs, key = { it.id }) { tab ->
                        DesktopTabChip(
                            tab = tab,
                            selected = tab.id == selectedTabId,
                            compact = mode == DesktopTabBarMode.COMPACT,
                            widthDp = tabWidth,
                            heightDp = rowHeight,
                            onSelect = { onSelect(tab.id) },
                            onClose = { onClose(tab.id) },
                            onTogglePin = { onTogglePin(tab.id) },
                            onDuplicate = { onDuplicate(tab.id) },
                            onCloseOthers = { onCloseOthers(tab.id) },
                            onMove = { step -> onMove(tab.id, step) },
                            onSetGroup = { name -> onSetGroup(tab.id, name) },
                        )
                    }
                }
                IconButton(onClick = onAdd, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Add, contentDescription = "新标签页")
                }
            }
        }
    }
}

@Composable
private fun DesktopTabChip(
    tab: BrowserTab,
    selected: Boolean,
    compact: Boolean,
    widthDp: Int,
    heightDp: Int,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onTogglePin: () -> Unit,
    onDuplicate: () -> Unit,
    onCloseOthers: () -> Unit,
    onMove: (Int) -> Unit,
    onSetGroup: (String?) -> Unit,
) {
    var menuExpanded by remember(tab.id) { mutableStateOf(false) }
    var groupDialog by remember(tab.id) { mutableStateOf(false) }
    var groupInput by remember(tab.id, tab.groupName) {
        mutableStateOf(tab.groupName.orEmpty())
    }
    val density = LocalDensity.current
    val dragThresholdPx = with(density) { (widthDp * 0.6f).dp.toPx() }

    Box {
        Row(
            modifier = Modifier
                .width(widthDp.dp)
                .height(heightDp.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh
                )
                .clickable(onClick = onSelect)
                .pointerInput(tab.id, widthDp) {
                    var distance = 0f
                    detectDragGesturesAfterLongPress(
                        onDragStart = { distance = 0f },
                        onDragEnd = { distance = 0f },
                        onDragCancel = { distance = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            distance += amount.x
                            if (abs(distance) >= dragThresholdPx) {
                                onMove(if (distance > 0f) 1 else -1)
                                distance = 0f
                            }
                        },
                    )
                }
                .padding(start = 8.dp, end = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (tab.privateMode) Icons.Outlined.Lock
                else if (tab.pinned && compact) Icons.Outlined.Bookmark
                else Icons.Outlined.Language,
                contentDescription = if (tab.privateMode) "隐私标签" else null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!compact) {
                Spacer(Modifier.width(6.dp))
                Text(
                    tab.title.ifBlank { tab.url.ifBlank { "新标签页" } },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (tab.pinned && !compact) {
                Icon(Icons.Outlined.Bookmark, contentDescription = "已固定",
                    modifier = Modifier.size(14.dp))
            }
            if (!compact && !tab.pinned) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "关闭标签",
                    modifier = Modifier.size(26.dp)
                        .clickable(onClick = onClose).padding(5.dp),
                )
            }
            Icon(
                Icons.Outlined.MoreVert,
                contentDescription = "标签更多操作",
                modifier = Modifier.size(26.dp)
                    .clickable(onClick = { menuExpanded = true }).padding(3.dp),
            )
        }

        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(if (tab.pinned) "取消固定" else "固定标签") },
                onClick = { menuExpanded = false; onTogglePin() },
            )
            DropdownMenuItem(
                text = { Text("复制标签") },
                onClick = { menuExpanded = false; onDuplicate() },
            )
            DropdownMenuItem(
                text = { Text("向前移动") },
                onClick = { menuExpanded = false; onMove(-1) },
            )
            DropdownMenuItem(
                text = { Text("向后移动") },
                onClick = { menuExpanded = false; onMove(1) },
            )
            DropdownMenuItem(
                text = { Text("设置分组") },
                onClick = {
                    menuExpanded = false
                    groupInput = tab.groupName.orEmpty()
                    groupDialog = true
                },
            )
            if (!tab.groupName.isNullOrBlank()) {
                DropdownMenuItem(
                    text = { Text("取消分组") },
                    onClick = { menuExpanded = false; onSetGroup(null) },
                )
            }
            DropdownMenuItem(
                text = { Text("关闭其他未固定标签") },
                onClick = { menuExpanded = false; onCloseOthers() },
            )
            DropdownMenuItem(
                text = { Text("关闭标签") },
                onClick = { menuExpanded = false; onClose() },
            )
        }
    }

    if (groupDialog) {
        AlertDialog(
            onDismissRequest = { groupDialog = false },
            title = { Text("标签分组") },
            text = {
                OutlinedTextField(
                    value = groupInput,
                    onValueChange = { groupInput = it.take(40) },
                    label = { Text("分组名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSetGroup(groupInput.trim().takeIf { it.isNotBlank() })
                        groupDialog = false
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { groupDialog = false }) { Text("取消") }
            },
        )
    }
}
