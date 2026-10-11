package com.yagay.YBrowser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * App-like HTTPS window. External domains and pages outside the installed
 * manifest scope are handed to the regular browser with a visible address bar.
 */
class WebAppActivity : ComponentActivity() {
    private var launchUrl by mutableStateOf("")
    private var appId by mutableStateOf<String?>(null)
    private var openingExternal = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!acceptIntent(intent)) {
            finish()
            return
        }
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openingExternal = false
        if (!acceptIntent(intent)) finish()
    }

    private fun acceptIntent(intent: Intent?): Boolean {
        val candidate = intent?.getStringExtra(EXTRA_URL)
            ?.takeIf(String::isNotBlank) ?: intent?.dataString.orEmpty()
        val start = WebAppScope.canonicalHttps(candidate)
        if (start == null) {
            // Older YBrowser builds created standalone shortcuts for HTTP.
            // Keep them usable, but route through a visible browser address
            // bar rather than silently treating HTTP as a secure PWA.
            val legacy = runCatching { Uri.parse(candidate) }.getOrNull()
            if (legacy?.scheme.equals("http", ignoreCase = true) &&
                !legacy?.host.isNullOrBlank()
            ) {
                runCatching {
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        action = MainActivity.ACTION_OPEN_URL
                        data = legacy
                        putExtra(MainActivity.EXTRA_URL, candidate)
                    })
                }
            }
            return false
        }
        launchUrl = start.toString()
        appId = intent?.getStringExtra(EXTRA_APP_ID)
        return true
    }

    private fun render() {
        setContent {
            val store = remember { BrowserStore(this@WebAppActivity) }
            var settings by remember { mutableStateOf(store.loadSettings()) }
            val requestedUrl = launchUrl
            val entry = appId?.let { InstalledWebAppRegistry.find(this, it) }
            val url = if (entry != null &&
                WebAppScope.contains(entry.scope, requestedUrl)
            ) requestedUrl else entry?.startUrl ?: requestedUrl
            val scope = entry?.scope ?: WebAppScope.defaultScope(url)

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
                    retainedSessionKey = "webapp:" + (entry?.id ?: url.hashCode().toString()),
                    recordHistory = true,
                    onCurrentPageChanged = { currentUrl, _ ->
                        if (!openingExternal && scope != null &&
                            (currentUrl.startsWith("http://", true) ||
                                currentUrl.startsWith("https://", true)) &&
                            !WebAppScope.contains(scope, currentUrl)
                        ) {
                            openingExternal = true
                            runOnUiThread {
                                runCatching {
                                    startActivity(
                                        Intent(this, MainActivity::class.java).apply {
                                            action = MainActivity.ACTION_OPEN_URL
                                            data = Uri.parse(currentUrl)
                                            putExtra(MainActivity.EXTRA_URL, currentUrl)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        },
                                    )
                                }
                                finish()
                            }
                        }
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_URL = "com.yagay.YBrowser.extra.WEB_APP_URL"
        const val EXTRA_APP_ID = "com.yagay.YBrowser.extra.WEB_APP_ID"
    }
}
