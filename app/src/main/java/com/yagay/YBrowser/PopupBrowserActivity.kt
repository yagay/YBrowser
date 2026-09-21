package com.yagay.YBrowser

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

class PopupBrowserActivity : ComponentActivity() {
    private var incomingUrl by mutableStateOf<String?>(null)
    private var chatBindingRepo by mutableStateOf<String?>(null)
    private var chatBindingProject by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0.42f)
        window.setGravity(Gravity.CENTER)
        setFinishOnTouchOutside(true)

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
                            onIncomingConsumed = { incomingUrl = null },
                            chatBindingRepo = chatBindingRepo,
                            chatBindingProject = chatBindingProject,
                            onChatBindingComplete = { url, title ->
                                returnChatBinding(
                                    repo = chatBindingRepo.orEmpty(),
                                    project = chatBindingProject.orEmpty(),
                                    url = url,
                                    title = title,
                                )
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

    override fun onStart() {
        super.onStart()
        val bounds = windowManager.currentWindowMetrics.bounds
        val width = (bounds.width() * 0.96f).toInt()
        val height = (bounds.height() * 0.88f).toInt()
        window.setLayout(width, height)
        window.setGravity(Gravity.CENTER)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        incomingUrl = intent?.getStringExtra(MainActivity.EXTRA_URL)
            ?.takeIf { it.isNotBlank() }
            ?: "https://chatgpt.com/"
        chatBindingRepo = intent?.getStringExtra(MainActivity.EXTRA_BIND_REPO)
            ?.takeIf { it.isNotBlank() }
        chatBindingProject = intent?.getStringExtra(MainActivity.EXTRA_BIND_PROJECT)
            ?.takeIf { it.isNotBlank() }
    }

    private fun returnChatBinding(
        repo: String,
        project: String,
        url: String,
        title: String,
    ) {
        if (repo.isBlank() || url.isBlank()) return

        val result = Intent(MainActivity.ACTION_CHATGPT_BOUND).apply {
            setPackage(MainActivity.YAGAYHUB_PACKAGE)
            putExtra(MainActivity.EXTRA_BIND_REPO, repo)
            putExtra(MainActivity.EXTRA_BIND_PROJECT, project)
            putExtra(MainActivity.EXTRA_BIND_URL, url)
            putExtra(MainActivity.EXTRA_BIND_TITLE, title)
        }
        sendBroadcast(result)
        finish()
    }
}
