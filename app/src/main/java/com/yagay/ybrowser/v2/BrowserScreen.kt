package com.yagay.ybrowser.v2

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun BrowserScreen(controller: BrowserController) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        val tab = controller.active
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                TabStrip(controller)
                if (tab != null) {
                    AddressBar(tab, controller)
                    if (tab.progress < 100) {
                        LinearProgressIndicator(
                            progress = { tab.progress.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Spacer(Modifier.height(4.dp))
                    }
                    key(tab.id) {
                        AndroidView(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            factory = { FrameLayout(it) },
                            update = { host ->
                                val view = tab.session.view
                                if (view.parent !== host) {
                                    (view.parent as? ViewGroup)?.removeView(view)
                                    host.removeAllViews()
                                    host.addView(
                                        view,
                                        FrameLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    )
                                }
                            }
                        )
                    }
                    NavigationBar(tab, controller)
                }
            }
        }
    }
}

@Composable
private fun TabStrip(controller: BrowserController) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        controller.tabs.forEach { tab ->
            Surface(
                onClick = { controller.select(tab.id) },
                color = if (tab.id == controller.activeId)
                    MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(end = 5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tab.title.ifBlank { "New tab" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(116.dp).padding(start = 10.dp)
                    )
                    IconButton(onClick = { controller.closeTab(tab.id) }) {
                        Icon(Icons.Default.Close, contentDescription = "Close tab")
                    }
                }
            }
        }
        IconButton(onClick = { controller.addTab() }) {
            Icon(Icons.Default.Add, contentDescription = "New tab")
        }
    }
}

@Composable
private fun AddressBar(tab: BrowserTab, controller: BrowserController) {
    var text by remember(tab.id, tab.url) { mutableStateOf(tab.url) }
    val focus = LocalFocusManager.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Search or enter address") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                controller.navigate(text)
                focus.clearFocus()
            }),
            shape = RoundedCornerShape(16.dp)
        )
        IconButton(onClick = {
            controller.navigate(text)
            focus.clearFocus()
        }) {
            Icon(Icons.Default.Search, contentDescription = "Go")
        }
    }
}

@Composable
private fun NavigationBar(tab: BrowserTab, controller: BrowserController) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { controller.back() }, enabled = tab.session.view.canGoBack()) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
        }
        IconButton(onClick = { controller.forward() }, enabled = tab.session.view.canGoForward()) {
            Icon(Icons.Default.ArrowForward, contentDescription = "Forward")
        }
        IconButton(onClick = { controller.home() }) {
            Icon(Icons.Default.Home, contentDescription = "Home")
        }
        IconButton(onClick = { controller.reload() }) {
            Icon(Icons.Default.Refresh, contentDescription = "Reload")
        }
    }
}
