package com.yagay.YBrowser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Browser-specific workspace adapted from YSuite:
 * next/core/productui/browser/YueBrowserWorkspace.kt @ cfecd96b10dd458e91c00f6a6ac678b2095283fe.
 * BrowserApp remains the single owner of tabs, engine views and all browser actions.
 */
@Composable
fun YueBrowserWorkspace(
    modifier: Modifier = Modifier,
    sidePaneEnabled: Boolean,
    sidePane: @Composable () -> Unit,
    topBar: @Composable (sidePaneVisible: Boolean) -> Unit,
    bottomBar: @Composable (sidePaneVisible: Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        // Same YUI expanded breakpoint (840dp) and compact product pane width (240dp).
        val showSidePane = sidePaneEnabled && maxWidth >= 840.dp
        Row(modifier = Modifier.fillMaxSize()) {
            if (showSidePane) {
                Surface(
                    modifier = Modifier.width(240.dp).fillMaxHeight(),
                    shape = RectangleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    sidePane()
                }
            }
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                topBar(showSidePane)
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    content()
                }
                bottomBar(showSidePane)
            }
        }
    }
}

/** Expanded-screen navigation shows existing browser tabs, not cloned sessions. */
@Composable
fun BrowserWideTabPane(
    tabs: List<BrowserTab>,
    selectedTabId: Long,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onAdd: () -> Unit,
    onShowTabManager: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("标签页", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            IconButton(onClick = onShowTabManager, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Tab, contentDescription = "标签管理")
            }
            IconButton(onClick = onAdd, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Add, contentDescription = "新标签")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            items(tabs, key = { it.id }) { tab ->
                Surface(
                    onClick = { onSelect(tab.id) },
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    shape = MaterialTheme.shapes.small,
                    color = if (tab.id == selectedTabId)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (tab.privateMode) Icons.Outlined.Lock
                            else if (tab.pinned) Icons.Outlined.Bookmark
                            else Icons.Outlined.Language,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = tab.title.ifBlank { tab.url.ifBlank { "新标签页" } },
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (tab.id == selectedTabId)
                                FontWeight.SemiBold else FontWeight.Normal,
                        )
                        IconButton(
                            onClick = { onClose(tab.id) },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Outlined.Close,
                                contentDescription = "关闭标签",
                                modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
