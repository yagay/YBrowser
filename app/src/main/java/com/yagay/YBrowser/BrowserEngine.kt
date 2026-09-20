package com.yagay.YBrowser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.os.Message
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.Toast
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebResponse

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

data class BrowserEngineConfig(
    val privateMode: Boolean = false,
    val javaScriptEnabled: Boolean = true,
    val cookiesEnabled: Boolean = true,
    val desktopMode: Boolean = false,
    val textScale: Int = 100,
)


enum class BrowserSitePermission {
    CAMERA,
    MICROPHONE,
    LOCATION,
}

data class BrowserFilePromptRequest(
    val mimeTypes: List<String>,
    val allowMultiple: Boolean,
    val complete: (List<Uri>?) -> Unit,
)

data class BrowserSitePermissionRequest(
    val origin: String,
    val permissions: Set<BrowserSitePermission>,
    val complete: (Set<BrowserSitePermission>) -> Unit,
)

data class BrowserAndroidPermissionRequest(
    val permissions: List<String>,
    val complete: (Boolean) -> Unit,
)

data class BrowserHostCallbacks(
    val onFilePrompt: (BrowserFilePromptRequest) -> Unit = { it.complete(null) },
    val onSitePermission: (BrowserSitePermissionRequest) -> Unit = { it.complete(emptySet()) },
    val onAndroidPermissions: (BrowserAndroidPermissionRequest) -> Unit = { it.complete(false) },
    val onFullscreenChanged: (Boolean) -> Unit = {},
    val onCustomView: (View?, (() -> Unit)?) -> Unit = { _, _ -> },
    val onOpenNewTab: (String) -> Unit = {},
)

interface BrowserEngine {
    val kind: BrowserEngineKind
    val view: View

    fun load(url: String)
    fun back()
    fun forward()
    fun reload()
    fun stop()
    fun applyConfig(config: BrowserEngineConfig)
    fun findInPage(query: String, forward: Boolean)
    fun clearFindInPage()
    fun exitFullscreen()
    fun destroy()
}

fun createBrowserEngine(
    context: Context,
    kind: BrowserEngineKind,
    config: BrowserEngineConfig,
    hostCallbacks: BrowserHostCallbacks = BrowserHostCallbacks(),
    onState: (BrowserRenderState) -> Unit,
): BrowserEngine = when (kind) {
    BrowserEngineKind.GECKO -> GeckoBrowserEngine(context, config, hostCallbacks, onState)
    BrowserEngineKind.SYSTEM_WEBVIEW ->
        SystemWebViewBrowserEngine(context, config, hostCallbacks, onState)
}

fun clearAllBrowserEngineData(context: Context, onComplete: (Boolean) -> Unit = {}) {
    runCatching {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        WebViewDatabase.getInstance(context).apply {
            clearFormData()
            clearHttpAuthUsernamePassword()
            clearUsernamePassword()
        }
    }

    runCatching {
        GeckoRuntimeHolder.get(context)
            .storageController
            .clearData(StorageController.ClearFlags.ALL)
            .withHandler(Handler(Looper.getMainLooper()))
            .accept(
                { onComplete(true) },
                { onComplete(false) },
            )
    }.onFailure {
        onComplete(false)
    }
}

private fun openExternal(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "没有应用可以处理这个链接", Toast.LENGTH_SHORT).show()
    }
}

private fun enqueueDownload(
    context: Context,
    url: String,
    userAgent: String? = null,
    contentDisposition: String? = null,
    mimeType: String? = null,
    cookie: String? = null,
) {
    runCatching {
        val fileName = android.webkit.URLUtil.guessFileName(
            url,
            contentDisposition,
            mimeType,
        )
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription("YBrowser")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                fileName,
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
        if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
        if (!cookie.isNullOrBlank()) request.addRequestHeader("Cookie", cookie)

        context.getSystemService(DownloadManager::class.java).enqueue(request)
        Toast.makeText(context, "开始下载：" + fileName, Toast.LENGTH_SHORT).show()
    }.onFailure {
        openExternal(context, url)
    }
}

@SuppressLint("SetJavaScriptEnabled")
private class SystemWebViewBrowserEngine(
    private val context: Context,
    initialConfig: BrowserEngineConfig,
    private val hostCallbacks: BrowserHostCallbacks,
    private val onState: (BrowserRenderState) -> Unit,
) : BrowserEngine {
    override val kind = BrowserEngineKind.SYSTEM_WEBVIEW
    private var state = BrowserRenderState()
    private val webView = WebView(context)
    private val mobileUserAgent = WebSettings.getDefaultUserAgent(context)
    private var lastFindQuery = ""

    override val view: View
        get() = webView

    init {
        webView.settings.apply {
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
            setSupportMultipleWindows(true)
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
            private var customViewCallback: CustomViewCallback? = null

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

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                hostCallbacks.onFilePrompt(
                    BrowserFilePromptRequest(
                        mimeTypes = fileChooserParams.acceptTypes.filter { it.isNotBlank() },
                        allowMultiple = fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE,
                        complete = { values ->
                            filePathCallback.onReceiveValue(
                                values?.map { it }?.toTypedArray(),
                            )
                        },
                    ),
                )
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val requested = buildSet {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources) {
                        add(BrowserSitePermission.CAMERA)
                    }
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources) {
                        add(BrowserSitePermission.MICROPHONE)
                    }
                }
                if (requested.isEmpty()) {
                    request.deny()
                    return
                }
                hostCallbacks.onSitePermission(
                    BrowserSitePermissionRequest(
                        origin = request.origin.toString(),
                        permissions = requested,
                        complete = { allowed ->
                            val resources = buildList {
                                if (BrowserSitePermission.CAMERA in allowed) {
                                    add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                                }
                                if (BrowserSitePermission.MICROPHONE in allowed) {
                                    add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                                }
                            }
                            if (resources.isEmpty()) request.deny()
                            else request.grant(resources.toTypedArray())
                        },
                    ),
                )
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback,
            ) {
                hostCallbacks.onSitePermission(
                    BrowserSitePermissionRequest(
                        origin = origin.orEmpty(),
                        permissions = setOf(BrowserSitePermission.LOCATION),
                        complete = { allowed ->
                            callback.invoke(
                                origin,
                                BrowserSitePermission.LOCATION in allowed,
                                false,
                            )
                        },
                    ),
                )
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || callback == null) return
                customViewCallback = callback
                hostCallbacks.onCustomView(view) {
                    callback.onCustomViewHidden()
                    customViewCallback = null
                    hostCallbacks.onCustomView(null, null)
                }
                hostCallbacks.onFullscreenChanged(true)
            }

            override fun onHideCustomView() {
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                hostCallbacks.onCustomView(null, null)
                hostCallbacks.onFullscreenChanged(false)
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                if (resultMsg == null) return false
                val popup = WebView(context)
                popup.settings.javaScriptEnabled = true
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val target = request?.url?.toString() ?: return true
                        hostCallbacks.onOpenNewTab(target)
                        popup.destroy()
                        return true
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        if (!url.isNullOrBlank()) hostCallbacks.onOpenNewTab(url)
                        popup.destroy()
                        return true
                    }
                }
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }

        webView.setDownloadListener(
            DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                enqueueDownload(
                    context = context,
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    cookie = CookieManager.getInstance().getCookie(url),
                )
            },
        )

        applyConfig(initialConfig)
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

    override fun applyConfig(config: BrowserEngineConfig) {
        webView.settings.javaScriptEnabled = config.javaScriptEnabled
        webView.settings.textZoom = config.textScale.coerceIn(50, 200)
        webView.settings.userAgentString = if (config.desktopMode) {
            DESKTOP_USER_AGENT
        } else {
            mobileUserAgent
        }
        CookieManager.getInstance().setAcceptCookie(config.cookiesEnabled)
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            webView,
            config.cookiesEnabled,
        )
    }

    override fun findInPage(query: String, forward: Boolean) {
        if (query.isBlank()) {
            clearFindInPage()
            return
        }
        if (query != lastFindQuery) {
            lastFindQuery = query
            webView.findAllAsync(query)
        } else {
            webView.findNext(forward)
        }
    }

    override fun clearFindInPage() {
        lastFindQuery = ""
        webView.clearMatches()
    }

    override fun exitFullscreen() {
        hostCallbacks.onCustomView(null, null)
        hostCallbacks.onFullscreenChanged(false)
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
    initialConfig: BrowserEngineConfig,
    private val hostCallbacks: BrowserHostCallbacks,
    private val onState: (BrowserRenderState) -> Unit,
) : BrowserEngine {
    override val kind = BrowserEngineKind.GECKO
    private val runtime = GeckoRuntimeHolder.get(context)
    private val session = GeckoSession(
        GeckoSessionSettings.Builder()
            .usePrivateMode(initialConfig.privateMode)
            .build(),
    )
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

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                hostCallbacks.onFullscreenChanged(fullScreen)
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val contentDisposition = response.headers.entries
                    .firstOrNull { (name, _) ->
                        name.equals("content-disposition", ignoreCase = true)
                    }
                    ?.value
                val mimeType = response.headers.entries
                    .firstOrNull { (name, _) ->
                        name.equals("content-type", ignoreCase = true)
                    }
                    ?.value

                if (response.uri.startsWith("http://") || response.uri.startsWith("https://")) {
                    enqueueDownload(
                        context = context,
                        url = response.uri,
                        contentDisposition = contentDisposition,
                        mimeType = mimeType,
                    )
                } else {
                    openExternal(context, response.uri)
                }
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

            override fun onNewSession(
                session: GeckoSession,
                uri: String,
            ): GeckoResult<GeckoSession>? {
                hostCallbacks.onOpenNewTab(uri)
                return null
            }
        }

        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<out String>?,
                callback: GeckoSession.PermissionDelegate.Callback,
            ) {
                val requested = permissions.orEmpty().toList()
                if (requested.isEmpty()) {
                    callback.reject()
                    return
                }
                hostCallbacks.onAndroidPermissions(
                    BrowserAndroidPermissionRequest(
                        permissions = requested,
                        complete = { allowed ->
                            if (allowed) callback.grant() else callback.reject()
                        },
                    ),
                )
            }

            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission,
            ): GeckoResult<Int>? {
                if (perm.permission != GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION) {
                    return null
                }
                val result = GeckoResult<Int>()
                hostCallbacks.onSitePermission(
                    BrowserSitePermissionRequest(
                        origin = perm.uri,
                        permissions = setOf(BrowserSitePermission.LOCATION),
                        complete = { allowed ->
                            result.complete(
                                if (BrowserSitePermission.LOCATION in allowed) {
                                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                                } else {
                                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                                },
                            )
                        },
                    ),
                )
                return result
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback,
            ) {
                val videoSources = video.orEmpty()
                val audioSources = audio.orEmpty()
                val requested = buildSet {
                    if (videoSources.any {
                            it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_CAMERA
                        }) {
                        add(BrowserSitePermission.CAMERA)
                    }
                    if (audioSources.any {
                            it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_MICROPHONE ||
                                it.source == GeckoSession.PermissionDelegate.MediaSource.SOURCE_AUDIOCAPTURE
                        }) {
                        add(BrowserSitePermission.MICROPHONE)
                    }
                }
                if (requested.isEmpty()) {
                    callback.reject()
                    return
                }
                hostCallbacks.onSitePermission(
                    BrowserSitePermissionRequest(
                        origin = uri,
                        permissions = requested,
                        complete = { allowed ->
                            val selectedVideo = videoSources.firstOrNull {
                                BrowserSitePermission.CAMERA in allowed &&
                                    it.source ==
                                    GeckoSession.PermissionDelegate.MediaSource.SOURCE_CAMERA
                            }
                            val selectedAudio = audioSources.firstOrNull {
                                BrowserSitePermission.MICROPHONE in allowed &&
                                    (it.source ==
                                        GeckoSession.PermissionDelegate.MediaSource.SOURCE_MICROPHONE ||
                                        it.source ==
                                        GeckoSession.PermissionDelegate.MediaSource.SOURCE_AUDIOCAPTURE)
                            }
                            if (
                                (BrowserSitePermission.CAMERA !in requested || selectedVideo != null) &&
                                (BrowserSitePermission.MICROPHONE !in requested || selectedAudio != null)
                            ) {
                                callback.grant(selectedVideo, selectedAudio)
                            } else {
                                callback.reject()
                            }
                        },
                    ),
                )
            }
        }

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                hostCallbacks.onFilePrompt(
                    BrowserFilePromptRequest(
                        mimeTypes = prompt.mimeTypes.orEmpty().toList(),
                        allowMultiple = prompt.type ==
                            GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE,
                        complete = { values ->
                            val uris = values.orEmpty().toTypedArray()
                            result.complete(
                                if (uris.isNotEmpty()) {
                                    prompt.confirm(context.applicationContext, uris)
                                } else {
                                    prompt.dismiss()
                                },
                            )
                        },
                    ),
                )
                return result
            }
        }

        session.open(runtime)
        geckoView.setSession(session)
        applyConfig(initialConfig)
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

    override fun applyConfig(config: BrowserEngineConfig) {
        session.settings.setAllowJavascript(config.javaScriptEnabled)
        session.settings.setUserAgentMode(
            if (config.desktopMode) {
                GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            } else {
                GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            },
        )
        session.settings.setViewportMode(
            if (config.desktopMode) {
                GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            } else {
                GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            },
        )
        runtime.settings.setFontSizeFactor(config.textScale.coerceIn(50, 200) / 100f)
        runtime.settings.contentBlocking.setCookieBehavior(
            if (config.cookiesEnabled) {
                ContentBlocking.CookieBehavior.ACCEPT_ALL
            } else {
                ContentBlocking.CookieBehavior.ACCEPT_NONE
            },
        )
        runtime.settings.contentBlocking.setCookieBehaviorPrivateMode(
            if (config.cookiesEnabled) {
                ContentBlocking.CookieBehavior.ACCEPT_ALL
            } else {
                ContentBlocking.CookieBehavior.ACCEPT_NONE
            },
        )
    }

    override fun findInPage(query: String, forward: Boolean) {
        if (query.isBlank()) {
            clearFindInPage()
            return
        }
        val finder = session.finder
        finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL
        finder.find(
            query,
            if (forward) {
                GeckoSession.FINDER_FIND_FORWARD
            } else {
                GeckoSession.FINDER_FIND_BACKWARDS
            },
        )
    }

    override fun clearFindInPage() {
        session.finder.clear()
    }

    override fun exitFullscreen() {
        session.exitFullScreen()
        hostCallbacks.onFullscreenChanged(false)
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

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"
