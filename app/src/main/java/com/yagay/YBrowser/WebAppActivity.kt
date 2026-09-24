package com.yagay.YBrowser

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class WebAppActivity : ComponentActivity() {
    private var launchUrl by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launchUrl = resolveUrl(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchUrl = resolveUrl(intent)
    }

    private fun render() {
        setContent {
            val store = remember { BrowserStore(this@WebAppActivity) }
            var settings by remember { mutableStateOf(store.loadSettings()) }
            val url = launchUrl

            YBrowserTheme(settings.themeMode) {
                BrowserApp(
                    store = store,
                    settings = settings,
                    onSettingsChanged = {
                        settings = it
                        store.saveSettings(it)
                    },
                    incomingUrl = url,
                    incomingReuseExisting = true,
                    incomingRequestRevision = url.hashCode(),
                    onIncomingConsumed = {},
                    showBrowserChrome = false,
                    retainedSessionKey = "webapp:" + url.hashCode().toUInt().toString(16),
                    recordHistory = true,
                    onClose = { finish() },
                )
            }
        }
    }

    private fun resolveUrl(intent: Intent?): String {
        val candidate =
            intent?.getStringExtra(EXTRA_URL)
                ?.takeIf { it.isNotBlank() }
                ?: intent?.dataString.orEmpty()
        return candidate.takeIf {
            it.startsWith("https://") || it.startsWith("http://")
        } ?: "https://www.google.com/"
    }

    companion object {
        const val EXTRA_URL = "com.yagay.YBrowser.extra.WEB_APP_URL"
    }
}
