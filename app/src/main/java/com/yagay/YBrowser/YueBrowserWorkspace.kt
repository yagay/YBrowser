package com.yagay.YBrowser

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val CHROME_TRANSLATION_MS = 190

/** Browser-specific workspace adapted from YSuite YueBrowserWorkspace; owns chrome, not sessions. */
@Composable
fun YueBrowserWorkspace(
    modifier: Modifier = Modifier,
    overlayChrome: Boolean = false,
    chromeHidden: Boolean = false,
    sidePaneEnabled: Boolean,
    sidePane: @Composable (collapsed: Boolean, onToggle: () -> Unit) -> Unit,
    topBar: @Composable (sidePaneVisible: Boolean) -> Unit,
    bottomBar: @Composable (sidePaneVisible: Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val showSidePane = sidePaneEnabled && maxWidth >= 840.dp
        var collapsed by rememberSaveable { mutableStateOf(false) }
        val hideChrome = overlayChrome && chromeHidden

        // Keep one stable content slot and one stable toolbar hierarchy for both
        // normal and auto-hiding modes. Never recreate GeckoView/WebView when
        // changing the auto-hide setting or entering/leaving the native home.
        var topChromeHeight by remember { mutableStateOf(0) }
        var bottomChromeHeight by remember { mutableStateOf(0) }
        var hideAnimationFinished by remember { mutableStateOf(false) }
        val chromeHideFraction by animateFloatAsState(
            targetValue = if (hideChrome) 1f else 0f,
            animationSpec = tween(
                durationMillis = CHROME_TRANSLATION_MS,
                easing = FastOutSlowInEasing,
            ),
            label = "browser-chrome-translation",
        )

        // Chromium delays viewport resizing until browser-controls movement
        // settles. Shrink the viewport *before* revealing the bars, so no
        // webpage element is obscured. When hiding them, leave space until
        // their exit animation finishes, then expand the viewport once.
        LaunchedEffect(hideChrome) {
            hideAnimationFinished = false
            if (hideChrome) {
                delay(CHROME_TRANSLATION_MS.toLong() + 40L)
                hideAnimationFinished = true
            }
        }
        val reserveChromeSpace = !hideChrome || !hideAnimationFinished
        val density = LocalDensity.current
        val topInset = with(density) {
            if (reserveChromeSpace) topChromeHeight.toDp() else 0.dp
        }
        val bottomInset = with(density) {
            if (reserveChromeSpace) bottomChromeHeight.toDp() else 0.dp
        }

        Row(modifier = Modifier.fillMaxSize()) {
            if (showSidePane) {
                Surface(
                    modifier = Modifier
                        .width(
                            if (collapsed) BrowserUiMetrics.CollapsedPaneWidth
                            else BrowserUiMetrics.SidePaneWidth
                        )
                        .fillMaxHeight(),
                    shape = RectangleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    sidePane(collapsed) { collapsed = !collapsed }
                }
            }

            Box(
                modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds(),
            ) {
                // A real content inset, not an overlay: page headers, fixed
                // bottoms, links and input fields remain reachable under
                // visible browser controls. The viewport is resized only on
                // settled show/hide transitions, never on each animation frame.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topInset, bottom = bottomInset),
                ) {
                    content()
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .offset {
                            IntOffset(
                                0,
                                -(topChromeHeight * chromeHideFraction).roundToInt(),
                            )
                        }
                        .onSizeChanged { topChromeHeight = it.height },
                ) {
                    topBar(showSidePane)
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .offset {
                            IntOffset(
                                0,
                                (bottomChromeHeight * chromeHideFraction).roundToInt(),
                            )
                        }
                        .onSizeChanged { bottomChromeHeight = it.height },
                ) {
                    bottomBar(showSidePane)
                }
            }
        }
    }
}

/** Expanded browser side pane, backed by the exact same tabs and callbacks. */
@Composable
fun BrowserWideTabPane(
    tabs: List<BrowserTab>,
    selectedTabId: Long,
    icons: Map<Long, Bitmap> = emptyMap(),
    collapsed: Boolean = false,
    onToggleCollapsed: () -> Unit = {},
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onAdd: () -> Unit,
    onShowTabManager: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (collapsed) {
            IconButton(
                onClick = onToggleCollapsed,
                modifier = Modifier.size(BrowserUiMetrics.ActionTouch),
            ) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = "展开标签侧栏")
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("标签页", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onToggleCollapsed,
                    modifier = Modifier.size(BrowserUiMetrics.ActionTouch)) {
                    Icon(Icons.Outlined.ChevronLeft, contentDescription = "折叠标签侧栏")
                }
                IconButton(onClick = onShowTabManager,
                    modifier = Modifier.size(BrowserUiMetrics.ActionTouch)) {
                    Icon(Icons.Outlined.Tab, contentDescription = "标签管理")
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .padding(horizontal = if (collapsed) 2.dp else 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(tabs, key = { it.id }) { tab ->
                Surface(
                    onClick = { onSelect(tab.id) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = if (tab.id == selectedTabId) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = BrowserUiMetrics.ActionTouch)
                            .padding(start = if (collapsed) 12.dp else 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val bitmap = icons[tab.id]
                        if (!tab.privateMode && bitmap != null && !bitmap.isRecycled) {
                            Image(bitmap = bitmap.asImageBitmap(), contentDescription = null,
                                modifier = Modifier.size(BrowserUiMetrics.ActionIcon))
                        } else {
                            Icon(
                                if (tab.privateMode) Icons.Outlined.Lock
                                else if (tab.pinned) Icons.Outlined.Bookmark
                                else Icons.Outlined.Language,
                                contentDescription = null,
                                modifier = Modifier.size(BrowserUiMetrics.ActionIcon))
                        }
                        if (!collapsed) {
                            Text(
                                text = tab.title.ifBlank { tab.url.ifBlank { "新标签页" } },
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (tab.id == selectedTabId)
                                    FontWeight.SemiBold else FontWeight.Normal,
                            )
                            IconButton(onClick = { onClose(tab.id) },
                                modifier = Modifier.size(BrowserUiMetrics.ActionTouch)) {
                                Icon(Icons.Outlined.Close, contentDescription = "关闭标签",
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
        IconButton(onClick = onAdd,
            modifier = Modifier.size(BrowserUiMetrics.ActionTouch)) {
            Icon(Icons.Outlined.Add, contentDescription = "新标签页")
        }
    }
}
