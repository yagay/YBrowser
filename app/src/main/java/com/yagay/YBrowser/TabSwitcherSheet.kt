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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabSwitcherSheet(
    tabs: List<BrowserTab>,
    selectedTabId: Long,
    previews: Map<Long, Bitmap>,
    engineLabel: String,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onAddTab: () -> Unit,
    onAddPrivateTab: () -> Unit,
) {
    var filter by remember { mutableIntStateOf(0) }
    val visibleTabs = remember(tabs, filter) {
        when (filter) {
            1 -> tabs.filterNot { it.privateMode }
            2 -> tabs.filter { it.privateMode }
            else -> tabs
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
                        "${tabs.size} 个 · $engineLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onAddTab) {
                    Icon(Icons.Outlined.Add, contentDescription = "新标签页")
                }
                IconButton(onClick = onAddPrivateTab) {
                    Icon(Icons.Outlined.Lock, contentDescription = "新隐私标签")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visibleTabs, key = { it.id }) { tab ->
                    TabCard(
                        tab = tab,
                        selected = tab.id == selectedTabId,
                        preview = previews[tab.id],
                        onSelect = {
                            onSelect(tab.id)
                            onDismiss()
                        },
                        onClose = { onClose(tab.id) },
                    )
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
                        if (filter == 2) "没有隐私标签页" else "没有标签页",
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
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
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
                Text(
                    tab.title.ifBlank { "新标签页" },
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭标签")
                }
            }

            Text(
                tabHost(tab.url) ?: tab.url,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun tabHost(url: String): String? =
    runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull()
