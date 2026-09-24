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
    val provider =
        ProviderCatalog.byId(window.providerId)

    AndroidView(
        factory = { context ->
            FrameLayout(context)
        },
        update = { host ->
            if (visible) {
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
        modifier = modifier
            .fillMaxSize()
            .alpha(
                if (visible) 1f else 0f
            ),
    )

    DisposableEffect(
        runtime,
        window.id,
        provider.id,
    ) {
        onDispose {
            runtime.detachView(
                windowId = window.id,
                provider = provider,
            )
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
