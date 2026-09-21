package com.yagay.YBrowser

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabSwitcherSheet(
    tabs: List<BrowserTab>,
    selectedTabId: Long,
    previews: Map<Long, Bitmap>,
    engineLabel: String,
    canReopenClosed: Boolean,
    layout: TabSwitcherLayout,
    onLayoutChanged: (TabSwitcherLayout) -> Unit,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onTogglePin: (Long) -> Unit,
    onDuplicate: (Long) -> Unit,
    onCloseOthers: (Long) -> Unit,
    onCloseUnpinned: () -> Unit,
    onReopenClosed: () -> Unit,
    onMove: (Long, Int) -> Unit,
    onSnooze: (Long, Long) -> Unit,
    onShowSnoozed: () -> Unit,
    onAddTab: () -> Unit,
    onAddPrivateTab: () -> Unit,
) {
    var filter by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }

    val visibleTabs = remember(tabs, filter, searchQuery) {
        val base = when (filter) {
            1 -> tabs.filterNot { it.privateMode }
            2 -> tabs.filter { it.privateMode }
            else -> tabs
        }
        val query = searchQuery.trim()
        base.filter { tab ->
            query.isBlank() ||
                tab.title.contains(query, ignoreCase = true) ||
                tab.url.contains(query, ignoreCase = true)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "标签页",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        tabs.size.toString() + " 个 · " + engineLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (canReopenClosed) {
                    TextButton(onClick = onReopenClosed) {
                        Text("恢复关闭")
                    }
                }

                IconButton(onClick = onAddTab) {
                    Icon(Icons.Outlined.Add, contentDescription = "新标签页")
                }
                IconButton(onClick = onAddPrivateTab) {
                    Icon(Icons.Outlined.Lock, contentDescription = "新隐私标签")
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "标签页菜单")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("休眠标签") },
                            leadingIcon = {
                                Icon(Icons.Outlined.Alarm, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onShowSnoozed()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("关闭全部未固定标签") },
                            onClick = {
                                menuExpanded = false
                                onCloseUnpinned()
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                },
                placeholder = { Text("搜索标签页") },
                shape = RoundedCornerShape(18.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = filter == 0,
                    onClick = { filter = 0 },
                    label = { Text("全部") },
                )
                FilterChip(
                    selected = filter == 1,
                    onClick = { filter = 1 },
                    label = { Text("普通") },
                )
                FilterChip(
                    selected = filter == 2,
                    onClick = { filter = 2 },
                    label = { Text("隐私") },
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        onLayoutChanged(
                            if (layout == TabSwitcherLayout.GRID) {
                                TabSwitcherLayout.LIST
                            } else {
                                TabSwitcherLayout.GRID
                            },
                        )
                    },
                ) {
                    Icon(
                        if (layout == TabSwitcherLayout.GRID) {
                            Icons.Outlined.ViewList
                        } else {
                            Icons.Outlined.GridView
                        },
                        contentDescription = "切换标签布局",
                    )
                }
            }

            if (layout == TabSwitcherLayout.GRID) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 620.dp)
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    gridItems(visibleTabs, key = { it.id }) { tab ->
                        TabCard(
                            tab = tab,
                            selected = tab.id == selectedTabId,
                            preview = previews[tab.id],
                            showPreview = true,
                            onSelect = {
                                onSelect(tab.id)
                                onDismiss()
                            },
                            onClose = { onClose(tab.id) },
                            onTogglePin = { onTogglePin(tab.id) },
                            onDuplicate = { onDuplicate(tab.id) },
                            onCloseOthers = { onCloseOthers(tab.id) },
                            onMove = { delta -> onMove(tab.id, delta) },
                            onSnooze = { wakeAt -> onSnooze(tab.id, wakeAt) },
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 620.dp)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listItems(visibleTabs, key = { it.id }) { tab ->
                        TabCard(
                            tab = tab,
                            selected = tab.id == selectedTabId,
                            preview = previews[tab.id],
                            showPreview = false,
                            onSelect = {
                                onSelect(tab.id)
                                onDismiss()
                            },
                            onClose = { onClose(tab.id) },
                            onTogglePin = { onTogglePin(tab.id) },
                            onDuplicate = { onDuplicate(tab.id) },
                            onCloseOthers = { onCloseOthers(tab.id) },
                            onMove = { delta -> onMove(tab.id, delta) },
                            onSnooze = { wakeAt -> onSnooze(tab.id, wakeAt) },
                        )
                    }
                }
            }

            if (visibleTabs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            searchQuery.isNotBlank() -> "没有匹配的标签页"
                            filter == 2 -> "没有隐私标签页"
                            else -> "没有标签页"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TabCard(
    tab: BrowserTab,
    selected: Boolean,
    preview: Bitmap?,
    showPreview: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onTogglePin: () -> Unit,
    onDuplicate: () -> Unit,
    onCloseOthers: () -> Unit,
    onMove: (Int) -> Unit,
    onSnooze: (Long) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        tonalElevation = if (selected) 4.dp else 1.dp,
    ) {
        Column(Modifier.padding(8.dp)) {
            if (showPreview) {
                if (preview != null && !preview.isRecycled) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(108.dp)
                            .clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(108.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                tabHost(tab.url)
                                    ?.take(1)
                                    ?.uppercase()
                                    ?: "Y",
                                style = MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(7.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tab.privateMode) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                }
                if (tab.pinned) {
                    Icon(
                        Icons.Outlined.Bookmark,
                        contentDescription = "已固定",
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        tab.title.ifBlank { "新标签页" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        tabHost(tab.url) ?: tab.url,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "标签操作")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (tab.pinned) "取消固定" else "固定标签") },
                            onClick = {
                                menuExpanded = false
                                onTogglePin()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("复制标签") },
                            onClick = {
                                menuExpanded = false
                                onDuplicate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("向前移动") },
                            onClick = {
                                menuExpanded = false
                                onMove(-1)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("向后移动") },
                            onClick = {
                                menuExpanded = false
                                onMove(1)
                            },
                        )
                        if (!tab.privateMode) {
                            DropdownMenuItem(
                                text = { Text("休眠 1 小时") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Alarm, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    onSnooze(System.currentTimeMillis() + 60L * 60L * 1000L)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("休眠到明早 9 点") },
                                onClick = {
                                    menuExpanded = false
                                    onSnooze(tomorrowAtNine())
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("关闭其他未固定标签") },
                            onClick = {
                                menuExpanded = false
                                onCloseOthers()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("关闭标签") },
                            onClick = {
                                menuExpanded = false
                                onClose()
                            },
                        )
                    }
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭标签")
                }
            }
        }
    }
}

private fun tomorrowAtNine(): Long {
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.DAY_OF_YEAR, 1)
    calendar.set(Calendar.HOUR_OF_DAY, 9)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

private fun tabHost(url: String): String? =
    runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull()
