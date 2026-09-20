package com.yagay.YBrowser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

enum class BrowserEngineKind(val label: String) {
    GECKO("GeckoView"),
    SYSTEM_WEBVIEW("System WebView"),
}

data class BrowserRenderState(
    val url: String = "",
    val title: String = "",
    val progress: Int = 0,
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)

interface BrowserEngine {
    val kind: BrowserEngineKind
    val view: View
    fun load(url: String)
    fun back()
    fun forward()
    fun reload()
    fun stop()
    fun destroy()
}

fun createBrowserEngine(
    context: Context,
    kind: BrowserEngineKind,
    onState: (BrowserRenderState) -> Unit,
): BrowserEngine = when (kind) {
    BrowserEngineKind.GECKO -> GeckoBrowserEngine(context, onState)
    BrowserEngineKind.SYSTEM_WEBVIEW -> SystemWebViewBrowserEngine(context, onState)
}

private fun openExternal(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "没有应用可以处理这个链接", Toast.LENGTH_SHORT).show()
    }
}

@SuppressLint("SetJavaScriptEnabled")
private class SystemWebViewBrowserEngine(
    private val context: Context,
    private val onState: (BrowserRenderState) -> Unit,
) : BrowserEngine {
    override val kind = BrowserEngineKind.SYSTEM_WEBVIEW
    private var state = BrowserRenderState()
    private val webView = WebView(context)

    override val view: View
        get() = webView

    init {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString() ?: return true
                val scheme = request.url.scheme?.lowercase()
                return if (scheme == "http" || scheme == "https") {
                    false
                } else {
                    openExternal(context, url)
                    true
                }
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val target = url ?: return true
                val scheme = Uri.parse(target).scheme?.lowercase()
                return if (scheme == "http" || scheme == "https") {
                    false
                } else {
                    openExternal(context, target)
                    true
                }
            }

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?,
            ) {
                publish(
                    state.copy(
                        url = url.orEmpty(),
                        loading = true,
                        progress = 0,
                    ),
                )
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                publish(
                    state.copy(
                        url = url.orEmpty(),
                        title = webView.title.orEmpty(),
                        loading = false,
                        progress = 100,
                        canGoBack = webView.canGoBack(),
                        canGoForward = webView.canGoForward(),
                    ),
                )
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                publish(
                    state.copy(
                        progress = newProgress.coerceIn(0, 100),
                        loading = newProgress < 100,
                        url = webView.url.orEmpty().ifBlank { state.url },
                        title = webView.title.orEmpty().ifBlank { state.title },
                        canGoBack = webView.canGoBack(),
                        canGoForward = webView.canGoForward(),
                    ),
                )
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                publish(state.copy(title = title.orEmpty()))
            }
        }

        webView.setDownloadListener(
            DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                runCatching {
                    val fileName = android.webkit.URLUtil.guessFileName(
                        url,
                        contentDisposition,
                        mimeType,
                    )
                    val request = DownloadManager.Request(Uri.parse(url))
                        .setMimeType(mimeType)
                        .addRequestHeader("User-Agent", userAgent)
                        .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                        .setTitle(fileName)
                        .setDescription("YBrowser")
                        .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                        )
                        .setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            fileName,
                        )
                    context.getSystemService(DownloadManager::class.java).enqueue(request)
                    Toast.makeText(context, "开始下载：" + fileName, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    openExternal(context, url)
                }
            },
        )
    }

    override fun load(url: String) {
        if (url.isNotBlank()) webView.loadUrl(url)
    }

    override fun back() {
        if (webView.canGoBack()) webView.goBack()
    }

    override fun forward() {
        if (webView.canGoForward()) webView.goForward()
    }

    override fun reload() {
        webView.reload()
    }

    override fun stop() {
        webView.stopLoading()
    }

    override fun destroy() {
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.destroy()
    }

    private fun publish(next: BrowserRenderState) {
        state = next
        onState(next)
    }
}

private object GeckoRuntimeHolder {
    @Volatile
    private var runtime: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime {
        runtime?.let { return it }
        return synchronized(this) {
            runtime ?: GeckoRuntime.create(context.applicationContext).also { runtime = it }
        }
    }
}

private class GeckoBrowserEngine(
    private val context: Context,
    private val onState: (BrowserRenderState) -> Unit,
) : BrowserEngine {
    override val kind = BrowserEngineKind.GECKO
    private val runtime = GeckoRuntimeHolder.get(context)
    private val session = GeckoSession()
    private val geckoView = GeckoView(context)
    private var state = BrowserRenderState()

    override val view: View
        get() = geckoView

    init {
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                publish(
                    state.copy(
                        url = url,
                        loading = true,
                        progress = 0,
                    ),
                )
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                publish(
                    state.copy(
                        progress = progress.coerceIn(0, 100),
                        loading = progress < 100,
                    ),
                )
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                publish(state.copy(loading = false, progress = 100))
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                publish(state.copy(title = title.orEmpty()))
            }
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny> {
                val uri = runCatching { Uri.parse(request.uri) }.getOrNull()
                val scheme = uri?.scheme?.lowercase()
                return if (
                    scheme == "http" ||
                    scheme == "https" ||
                    scheme == "about"
                ) {
                    GeckoResult.fromValue(AllowOrDeny.ALLOW)
                } else {
                    openExternal(context, request.uri)
                    GeckoResult.fromValue(AllowOrDeny.DENY)
                }
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                publish(state.copy(url = url.orEmpty().ifBlank { state.url }))
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                publish(state.copy(canGoBack = canGoBack))
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                publish(state.copy(canGoForward = canGoForward))
            }
        }

        session.open(runtime)
        geckoView.setSession(session)
    }

    override fun load(url: String) {
        if (url.isNotBlank()) session.loadUri(url)
    }

    override fun back() {
        session.goBack()
    }

    override fun forward() {
        session.goForward()
    }

    override fun reload() {
        session.reload()
    }

    override fun stop() {
        session.stop()
    }

    override fun destroy() {
        runCatching { geckoView.releaseSession() }
        runCatching { session.close() }
    }

    private fun publish(next: BrowserRenderState) {
        state = next
        onState(next)
    }
}
