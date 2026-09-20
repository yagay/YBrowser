package com.yagay.YBrowser

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private var incomingUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incomingUrl = resolveIncomingUrl(intent)

        setContent {
            val store = remember { BrowserStore(this) }
            var settings by remember { mutableStateOf(store.loadSettings()) }

            YBrowserTheme(settings.themeMode) {
                BrowserApp(
                    store = store,
                    settings = settings,
                    onSettingsChanged = {
                        settings = it
                        store.saveSettings(it)
                    },
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
        return intent.getStringExtra(EXTRA_URL)
            ?.takeIf { it.isNotBlank() }
            ?: intent.dataString?.takeIf { it.isNotBlank() }
    }

    companion object {
        const val ACTION_OPEN_URL = "com.yagay.YBrowser.action.OPEN_URL"
        const val EXTRA_URL = "com.yagay.YBrowser.extra.URL"
    }
}

@Composable
private fun YBrowserTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

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
