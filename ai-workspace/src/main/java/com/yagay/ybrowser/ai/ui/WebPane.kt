package com.yagay.ybrowser.ai.ui

import android.app.Application
import android.content.Intent
import android.widget.FrameLayout
import android.widget.Toast
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yagay.ybrowser.ai.diagnostics.DiagnosticLogger
import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ChatMessage
import com.yagay.ybrowser.ai.model.ChatWindow
import com.yagay.ybrowser.ai.model.MessageRole
import com.yagay.ybrowser.ai.model.WindowViewMode
import com.yagay.ybrowser.ai.provider.ProviderCatalog
import com.yagay.ybrowser.ai.web.AiWorkspaceRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WorkspaceWebHost(
    runtime: AiWorkspaceRuntime,
    window: ChatWindow,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val provider = ProviderCatalog.byId(window.providerId)
    val hadLiveSession = remember(
        window.id,
        window.boundUrl,
    ) {
        runtime.hasLiveSession(window.id, provider)
    }
    val liveRenderReady = remember(
        window.id,
        window.boundUrl,
        window.url,
    ) {
        runtime.isConversationRenderReady(
            window = window,
            provider = provider,
        )
    }
    val coldCandidate =
        provider.id == "chatgpt" &&
            !window.boundUrl.isNullOrBlank() &&
            !liveRenderReady

    var cachedSnapshot by remember(
        window.id,
        window.boundUrl,
    ) {
        mutableStateOf<String?>(null)
    }
    var archiveChecked by remember(
        window.id,
        window.boundUrl,
    ) {
        mutableStateOf(!coldCandidate)
    }
    var onlineRequested by remember(
        window.id,
        window.boundUrl,
    ) {
        mutableStateOf(
            hadLiveSession ||
                !coldCandidate ||
                window.viewMode == WindowViewMode.WEB
        )
    }
    var showSnapshot by remember(
        window.id,
        window.boundUrl,
    ) {
        mutableStateOf(false)
    }
    var handoffInFlight by remember(
        window.id,
        window.boundUrl,
    ) {
        mutableStateOf(false)
    }
    var snapshotVisualReady by remember(
        window.id,
        window.boundUrl,
    ) {
        mutableStateOf(false)
    }
    var holdLiveReveal by remember(
        window.id,
        window.boundUrl,
    ) {
        mutableStateOf(false)
    }
    var recoveryReloaded by remember(
        window.id,
        window.boundUrl,
    ) {
        mutableStateOf(false)
    }

    fun beginLiveHandoff(reason: String) {
        if (handoffInFlight) return
        handoffInFlight = true
        onlineRequested = true

        DiagnosticLogger.i(
            "COLD",
            "live_handoff_begin window=" +
                window.id.take(12) +
                " reason=" + reason
        )

        runtime.requestLiveHandoff(
            window = window,
            provider = provider,
            timeoutMs = 4_000L,
        ) { ready, detail ->
            handoffInFlight = false

            DiagnosticLogger.i(
                "COLD",
                "live_handoff_finish window=" +
                    window.id.take(12) +
                    " ready=" + ready +
                    " detail=" + detail
            )

            holdLiveReveal = false
            if (ready) {
                recoveryReloaded = false
                showSnapshot = false
            } else if (
                showSnapshot &&
                !cachedSnapshot.isNullOrBlank()
            ) {
                // Never replace a usable archive with a live Gecko page that
                // still has no rendered conversation turns.
                showSnapshot = true

                if (!recoveryReloaded) {
                    recoveryReloaded = true
                    runtime.reloadPage(
                        window = window,
                        provider = provider,
                    )
                    beginLiveHandoff(
                        "snapshot-reload-retry"
                    )
                }
            } else {
                showSnapshot = false
            }
        }
    }

    // Cold ChatGPT tabs check the compressed local archive on IO first.
    // Until this completes, no GeckoSession is created and no network request
    // is allowed to start.
    androidx.compose.runtime.LaunchedEffect(
        window.id,
        window.boundUrl,
        window.viewMode,
        coldCandidate,
    ) {
        if (!coldCandidate) {
            archiveChecked = true
            onlineRequested = true
            showSnapshot = false
            snapshotVisualReady = false
            holdLiveReveal = false
            handoffInFlight = false
            return@LaunchedEffect
        }

        if (window.viewMode == WindowViewMode.WEB) {
            archiveChecked = true
            onlineRequested = true
            showSnapshot = false
            snapshotVisualReady = false
            holdLiveReveal = false
            handoffInFlight = false
            return@LaunchedEffect
        }

        archiveChecked = false
        snapshotVisualReady = false
        holdLiveReveal = false
        handoffInFlight = false
        recoveryReloaded = false
        DiagnosticLogger.i(
            "COLD",
            "archive_check_start window=" +
                window.id.take(12)
        )
        val local = withContext(Dispatchers.IO) {
            runtime.archiveStatus(window.id) to
                runtime.cachedSnapshotHtml(window.id)
        }
        val status = local.first
        val localHtml = local.second
        val usable =
            status.hasUsableArchive &&
                !localHtml.isNullOrBlank()
        cachedSnapshot = if (usable) localHtml else null
        archiveChecked = true

        if (!usable) {
            DiagnosticLogger.i(
                "COLD",
                "archive_miss window=" +
                    window.id.take(12) +
                    " kind=" + status.kind +
                    " turns=" + status.turnCount +
                    " archiveBytes=" +
                    status.archiveBytes +
                    " legacyBytes=" +
                    status.legacySnapshotBytes +
                    " -> online"
            )
            showSnapshot = false
            // No local archive to cover Gecko. Keep an app-owned surface while
            // the page prepares itself, but only until the one-shot live
            // handoff completes or its bounded fallback fires.
            holdLiveReveal = true
            beginLiveHandoff("archive-miss")
        } else {
            DiagnosticLogger.i(
                "COLD",
                "archive_hit window=" +
                    window.id.take(12) +
                    " kind=" + status.kind +
                    " turns=" + status.turnCount +
                    " archiveBytes=" +
                    status.archiveBytes +
                    " stylesBytes=" +
                    status.stylesBytes +
                    " sessionStateBytes=" +
                    status.sessionStateBytes +
                    " htmlChars=" +
                    localHtml.length
            )
            showSnapshot = true
            snapshotVisualReady = false
            holdLiveReveal = false
            onlineRequested =
                runtime.hasLiveSession(window.id, provider)
        }
    }



    // A GeckoSession is not considered visually usable until real
    // conversation turns exist in its DOM. Keep the archive on top while a
    // half-awake standby session resumes. If it already exists, ask the
    // event-driven live handoff to confirm turns; otherwise warm it quietly.
    androidx.compose.runtime.LaunchedEffect(
        window.id,
        provider.id,
        visible,
        showSnapshot,
        snapshotVisualReady,
        onlineRequested,
    ) {
        if (
            !visible ||
            provider.id != "chatgpt" ||
            !showSnapshot ||
            !snapshotVisualReady
        ) {
            return@LaunchedEffect
        }

        if (
            runtime.isConversationRenderReady(
                window = window,
                provider = provider,
            )
        ) {
            showSnapshot = false
            return@LaunchedEffect
        }

        if (
            runtime.hasLiveSession(
                window.id,
                provider,
            )
        ) {
            delay(120)
            if (
                visible &&
                showSnapshot &&
                !handoffInFlight
            ) {
                beginLiveHandoff(
                    "snapshot-live-not-ready"
                )
            }
            return@LaunchedEffect
        }

        if (!onlineRequested) {
            delay(650)
            if (
                visible &&
                showSnapshot &&
                !onlineRequested
            ) {
                runtime.prewarm(
                    window = window,
                    provider = provider,
                )
                DiagnosticLogger.i(
                    "COLD",
                    "snapshot_prewarm window=" +
                        window.id.take(12)
                )
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .alpha(if (visible) 1f else 0f)
            .zIndex(if (visible) 2f else -1f)
            .then(
                if (visible) {
                    Modifier.background(
                        MaterialTheme.colorScheme.background
                    )
                } else {
                    Modifier
                }
            )
    ) {
        AndroidView(
            factory = { context ->
                FrameLayout(context)
            },
            update = { host ->
                if (visible && onlineRequested) {
                    runtime.attach(
                        host = host,
                        window = window,
                        provider = provider,
                    )
                } else {
                    runtime.detachView(
                        windowId = window.id,
                        provider = provider,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (
            visible &&
            coldCandidate &&
            !archiveChecked
        ) {
            Text(
                "正在读取本地历史…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (
            visible &&
            showSnapshot &&
            !cachedSnapshot.isNullOrBlank()
        ) {
            StaticSnapshotWebView(
                html = cachedSnapshot.orEmpty(),
                baseUrl = window.boundUrl ?: window.url,
                onVisualReady = {
                    if (!snapshotVisualReady) {
                        snapshotVisualReady = true
                        DiagnosticLogger.i(
                            "COLD",
                            "snapshot_visual_ready window=" +
                                window.id.take(12)
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(4f),
            )

            if (
                snapshotVisualReady &&
                !handoffInFlight &&
                (
                    !onlineRequested ||
                        !runtime.isConversationRenderReady(
                            window = window,
                            provider = provider,
                        )
                )
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp)
                        .zIndex(6f),
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 6.dp,
                ) {
                    TextButton(
                        onClick = {
                            DiagnosticLogger.i(
                                "COLD",
                                "cold_to_hot_requested window=" +
                                    window.id.take(12) +
                                    " reason=continue-chat mode=event"
                            )
                            if (onlineRequested) {
                                recoveryReloaded = true
                                runtime.reloadPage(
                                    window = window,
                                    provider = provider,
                                )
                                beginLiveHandoff(
                                    "manual-reconnect"
                                )
                            } else {
                                beginLiveHandoff(
                                    "continue-chat"
                                )
                            }
                        },
                        modifier = Modifier.padding(
                            horizontal = 8.dp,
                        ),
                    ) {
                        Text(
                            if (onlineRequested) {
                                "重新连接聊天"
                            } else {
                                "继续聊天（联网）"
                            }
                        )
                    }
                }
            }
        }

        if (
            visible &&
            showSnapshot &&
            !snapshotVisualReady
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .zIndex(5f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "正在读取本地历史…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (
            visible &&
            holdLiveReveal &&
            !showSnapshot
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .zIndex(5f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "正在加载最新聊天内容…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun StaticSnapshotWebView(
    html: String,
    baseUrl: String?,
    onVisualReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                isLongClickable = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean = true

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        url: String?,
                    ): Boolean = true

                    override fun onPageFinished(
                        view: WebView,
                        url: String?,
                    ) {
                        view.evaluateJavascript(
                            """
                                (() => {
                                    document.documentElement.style.setProperty(
                                        'height',
                                        'auto',
                                        'important'
                                    );
                                    document.documentElement.style.setProperty(
                                        'overflow-y',
                                        'auto',
                                        'important'
                                    );
                                    document.body?.style?.setProperty(
                                        'height',
                                        'auto',
                                        'important'
                                    );
                                    document.body?.style?.setProperty(
                                        'overflow-y',
                                        'auto',
                                        'important'
                                    );
                                    document.body?.style?.setProperty(
                                        'touch-action',
                                        'pan-y pinch-zoom',
                                        'important'
                                    );

                                    // Cached chat always opens at the newest
                                    // archived content. Do not restore an old
                                    // top/middle scroll position on cold entry.
                                    const moveToBottom = () => {
                                        const turns = Array.from(
                                            document.querySelectorAll(
                                                '[data-aihub-archive-key]'
                                            )
                                        );
                                        const latest =
                                            turns[turns.length - 1] ||
                                            document.querySelector(
                                                '#aihub-frozen-thread'
                                            );
                                        try {
                                            latest?.scrollIntoView?.({
                                                block: 'end',
                                                inline: 'nearest',
                                                behavior: 'auto'
                                            });
                                        } catch (_) {}

                                        try {
                                            const root =
                                                document.scrollingElement ||
                                                document.documentElement ||
                                                document.body;
                                            if (root) {
                                                root.scrollTop =
                                                    root.scrollHeight;
                                            }
                                            window.scrollTo(
                                                0,
                                                Math.max(
                                                    document.body?.scrollHeight || 0,
                                                    document.documentElement?.scrollHeight || 0
                                                )
                                            );
                                        } catch (_) {}
                                    };

                                    moveToBottom();
                                    requestAnimationFrame(() => {
                                        moveToBottom();
                                        requestAnimationFrame(
                                            moveToBottom
                                        );
                                    });
                                    setTimeout(moveToBottom, 120);
                                })();
                            """.trimIndent(),
                        ) {
                            view.postVisualStateCallback(
                                1L,
                                object : WebView.VisualStateCallback() {
                                    override fun onComplete(
                                        requestId: Long,
                                    ) {
                                        onVisualReady()
                                    }
                                },
                            )
                        }
                    }
                }

                loadDataWithBaseURL(
                    baseUrl,
                    html,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
        },
        update = { },
        modifier = modifier,
    )
}
