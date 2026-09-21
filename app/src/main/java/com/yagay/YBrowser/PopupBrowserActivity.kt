package com.yagay.YBrowser

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yagay.YBrowser.integration.yagayhub.YagaYHubBrowserActivity
import com.yagay.YBrowser.integration.yagayhub.YagaYHubContract

/**
 * Generic floating popup/preview browser.
 *
 * YagaYHub's full-page browser lives in YagaYHubBrowserActivity and is not
 * implemented here. The compatibility redirect only forwards intents from
 * older YagaYHub versions.
 */
class PopupBrowserActivity : ComponentActivity() {
    private var incomingUrl by mutableStateOf<String?>(null)
    private var transientPreview by mutableStateOf(false)
    private var currentPageUrl by mutableStateOf("")
    private var currentPageTitle by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (redirectLegacyYagaYHubIntent(intent)) return

        configurePopupWindow()
        handleIntent(intent)

        setContent {
            val store = remember { BrowserStore(this) }
            var settings by remember { mutableStateOf(store.loadSettings()) }

            YBrowserTheme(settings.themeMode) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp)
                        .clip(RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 12.dp,
                ) {
                    if (transientPreview) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "链接预览",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        currentPageTitle.ifBlank {
                                            currentPageUrl
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = ::finish) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "关闭预览",
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            ) {
                                BrowserApp(
                                    store = store,
                                    settings = settings.copy(
                                        restoreTabs = false,
                                    ),
                                    onSettingsChanged = { updated ->
                                        val persisted = updated.copy(
                                            homepage = settings.homepage,
                                            restoreTabs = settings.restoreTabs,
                                        )
                                        settings = persisted
                                        store.saveSettings(persisted)
                                    },
                                    incomingUrl = incomingUrl,
                                    incomingReuseExisting = false,
                                    onIncomingConsumed = {
                                        incomingUrl = null
                                    },
                                    showBrowserChrome = true,
                                    recordHistory = false,
                                    onCurrentPageChanged = { url, title ->
                                        currentPageUrl = url
                                        currentPageTitle =
                                            title.ifBlank { url }
                                    },
                                )
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            BrowserApp(
                                store = store,
                                settings = settings,
                                onSettingsChanged = {
                                    settings = it
                                    store.saveSettings(it)
                                },
                                incomingUrl = incomingUrl,
                                incomingReuseExisting = true,
                                onIncomingConsumed = {
                                    incomingUrl = null
                                },
                                showBrowserChrome = true,
                                onCurrentPageChanged = { url, title ->
                                    currentPageUrl = url
                                    currentPageTitle = title
                                },
                            )

                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 4.dp,
                            ) {
                                IconButton(onClick = ::finish) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "关闭弹窗",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        applyPopupSize()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (redirectLegacyYagaYHubIntent(intent)) return
        handleIntent(intent)
        configurePopupWindow()
        applyPopupSize()
    }

    private fun configurePopupWindow() {
        window.setBackgroundDrawable(
            ColorDrawable(Color.TRANSPARENT),
        )
        window.setDimAmount(0.42f)
        window.setGravity(Gravity.CENTER)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN,
        )
        setFinishOnTouchOutside(true)
    }

    private fun applyPopupSize() {
        val bounds = windowManager.currentWindowMetrics.bounds
        window.setLayout(
            (bounds.width() * 0.96f).toInt(),
            (bounds.height() * 0.88f).toInt(),
        )
        window.setGravity(Gravity.CENTER)
    }

    private fun handleIntent(intent: Intent?) {
        transientPreview =
            intent?.getBooleanExtra(
                EXTRA_TRANSIENT_PREVIEW,
                false,
            ) == true
        incomingUrl = intent?.getStringExtra(
            MainActivity.EXTRA_URL,
        )?.takeIf { it.isNotBlank() }
            ?: intent?.dataString?.takeIf { it.isNotBlank() }
    }

    private fun redirectLegacyYagaYHubIntent(
        intent: Intent?,
    ): Boolean {
        if (
            intent?.getBooleanExtra(
                YagaYHubContract.EXTRA_BINDING_MODE,
                false,
            ) != true
        ) {
            return false
        }

        val forwarded = Intent(
            intent,
        ).setClass(
            this,
            YagaYHubBrowserActivity::class.java,
        ).setAction(
            YagaYHubContract.ACTION_OPEN_BROWSER,
        )
        runCatching { startActivity(forwarded) }
        finish()
        return true
    }

    companion object {
        const val EXTRA_TRANSIENT_PREVIEW =
            "com.yagay.YBrowser.extra.TRANSIENT_PREVIEW"
    }
}
