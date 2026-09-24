package com.yagay.YBrowser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.print.PrintAttributes
import android.print.PrintManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.os.Message
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.Toast
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.yagay.browsercore.SharedGeckoRuntime
import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

enum class BrowserEngineKind(val label: String) {
    GECKO("GeckoView"),
    SYSTEM_WEBVIEW("System WebView"),
}

data class BrowserPageError(
    val url: String,
    val description: String,
    val code: Int? = null,
)

data class BrowserRenderState(
    val url: String = "",
    val title: String = "",
    val progress: Int = 0,
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val pageError: BrowserPageError? = null,
)

data class BrowserEngineConfig(
    val privateMode: Boolean = false,
    val javaScriptEnabled: Boolean = true,
    val cookiesEnabled: Boolean = true,
    val desktopMode: Boolean = false,
    val textScale: Int = 100,
    val trackingProtection: TrackingProtection = TrackingProtection.STANDARD,
    val blockAutoplay: Boolean = false,
    val blockThirdPartyCookies: Boolean = true,
    val muted: Boolean = false,
    val userScripts: List<BrowserUserScript> = emptyList(),
    val customBlockedHosts: Set<String> = emptySet(),
    val profileId: String = DEFAULT_BROWSER_PROFILE_ID,
    val downloadManagerMode: DownloadManagerMode = DownloadManagerMode.SYSTEM,
    val externalDownloadManagerId: String? = null,
    val shareDownloadSessionData: Boolean = false,
    val dnsOverHttpsProvider: DnsOverHttpsProvider =
        DnsOverHttpsProvider.SYSTEM,
    val customDnsOverHttpsUrl: String = "",
)

data class BrowserPrivacyEvent(
    val url: String,
    val category: String,
)

data class BrowserDownloadChoiceRequest(
    val request: BrowserDownloadRequest,
    val apps: List<ExternalDownloadManagerApp>,
    val complete: (ExternalDownloadManagerApp?) -> Unit,
)


enum class BrowserSitePermission {
    CAMERA,
    MICROPHONE,
    LOCATION,
}

enum class BrowserFilePromptKind {
    FILE,
    FOLDER,
}

enum class BrowserFileCapture {
    NONE,
    ANY,
    USER,
    ENVIRONMENT,
}

data class BrowserFilePromptRequest(
    val mimeTypes: List<String>,
    val allowMultiple: Boolean,
    val kind: BrowserFilePromptKind = BrowserFilePromptKind.FILE,
    val capture: BrowserFileCapture = BrowserFileCapture.NONE,
    val pickerIntent: Intent? = null,
    val parsePickerResult: ((Int, Intent?) -> List<Uri>?)? = null,
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


enum class BrowserContentTargetKind {
    LINK,
    IMAGE,
    IMAGE_LINK,
}

data class BrowserContentTarget(
    val kind: BrowserContentTargetKind,
    val url: String,
    val imageUrl: String? = null,
)

enum class BrowserWebPromptKind {
    ALERT,
    CONFIRM,
    TEXT,
    BEFORE_UNLOAD,
    REPOST,
}

data class BrowserWebPromptRequest(
    val kind: BrowserWebPromptKind,
    val title: String?,
    val message: String?,
    val defaultValue: String?,
    val confirm: (String?) -> Unit,
    val dismiss: () -> Unit,
)

data class BrowserAuthPromptRequest(
    val uri: String,
    val realm: String?,
    val onlyPassword: Boolean,
    val confirm: (String, String) -> Unit,
    val dismiss: () -> Unit,
)

data class BrowserHostCallbacks(
    val onFilePrompt: (BrowserFilePromptRequest) -> Unit = { it.complete(null) },
    val onSitePermission: (BrowserSitePermissionRequest) -> Unit = { it.complete(emptySet()) },
    val onAndroidPermissions: (BrowserAndroidPermissionRequest) -> Unit = { it.complete(false) },
    val onFullscreenChanged: (Boolean) -> Unit = {},
    val onCustomView: (View?, (() -> Unit)?) -> Unit = { _, _ -> },
    val onOpenNewTab: (String) -> Unit = {},
    val onUserNavigation: (String) -> Boolean = { false },
    val onContentLongPress: (BrowserContentTarget) -> Unit = {},
    val onWebPrompt: (BrowserWebPromptRequest) -> Unit = { it.dismiss() },
    val onAuthPrompt: (BrowserAuthPromptRequest) -> Unit = { it.dismiss() },
    val onMediaState: (BrowserMediaState?) -> Unit = {},
    val onContentBlocked: (BrowserPrivacyEvent) -> Unit = {},
    val onEngineCrashed: () -> Unit = {},
    val onDownloadChoice: (BrowserDownloadChoiceRequest) -> Unit = {
        it.complete(null)
    },
)

interface BrowserEngine {
    val kind: BrowserEngineKind
    val view: View

    fun load(url: String)
    fun back()
    fun forward()
    fun reload()
    fun isAtTop(): Boolean
    fun stop()
    fun applyConfig(config: BrowserEngineConfig)
    fun findInPage(query: String, forward: Boolean)
    fun clearFindInPage()
    fun capturePreview(onComplete: (Bitmap?) -> Unit)
    fun extractReader(onComplete: (ReaderDocument?) -> Unit)
    fun mediaCommand(command: BrowserMediaCommand)
    fun exitFullscreen()
    fun printPage(): Boolean
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

private val BLOCKED_EXTERNAL_SCHEMES = setOf(
    "about",
    "blob",
    "content",
    "data",
    "file",
    "javascript",
)

private val LEGACY_SAFE_EXTERNAL_SCHEMES = setOf(
    "geo",
    "mailto",
    "market",
    "sms",
    "smsto",
    "tel",
)

private fun canOpenExternalNavigation(
    uri: Uri,
    hasUserGesture: Boolean,
    legacyCallback: Boolean = false,
): Boolean {
    val scheme = uri.scheme?.lowercase()?.trim().orEmpty()
    if (scheme.isBlank() || scheme in BLOCKED_EXTERNAL_SCHEMES) return false
    if (scheme == "http" || scheme == "https" || scheme == "view-source") return false
    return if (legacyCallback) {
        scheme in LEGACY_SAFE_EXTERNAL_SCHEMES
    } else {
        hasUserGesture
    }
}

private fun configureSystemWebViewCredentials(
    webView: WebView,
    privateMode: Boolean,
) {
    webView.importantForAutofill = if (privateMode) {
        View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    } else {
        View.IMPORTANT_FOR_AUTOFILL_YES
    }

    if (
        !privateMode &&
        runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)
        }.getOrDefault(false)
    ) {
        runCatching {
            WebSettingsCompat.setWebAuthenticationSupport(
                webView.settings,
                WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER,
            )
        }
    }
}

private fun openExternal(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "没有应用可以处理这个链接", Toast.LENGTH_SHORT).show()
    }
}

private fun enqueueDownload(
    context: Context,
    config: BrowserEngineConfig,
    hostCallbacks: BrowserHostCallbacks,
    url: String,
    userAgent: String? = null,
    contentDisposition: String? = null,
    mimeType: String? = null,
    cookie: String? = null,
    referrer: String? = null,
) {
    val fileName = android.webkit.URLUtil.guessFileName(
        url,
        contentDisposition,
        mimeType,
    )
    val request = BrowserDownloadRequest(
        url = url,
        fileName = fileName,
        mimeType = mimeType,
        userAgent = userAgent,
        cookies = cookie,
        referrer = referrer,
    )

    fun builtIn(): Boolean {
        val id = BrowserDownloadRepository.enqueue(
            context = context,
            url = url,
            userAgent = userAgent,
            contentDisposition = contentDisposition,
            mimeType = mimeType,
            cookie = cookie,
        )
        if (id != null) {
            Toast.makeText(
                context,
                "开始下载：" + fileName,
                Toast.LENGTH_SHORT,
            ).show()
            return true
        }
        return false
    }

    fun external(app: ExternalDownloadManagerApp): Boolean {
        val launched = ExternalDownloadManager.launch(
            context = context,
            request = request,
            app = app,
            shareSessionData = config.shareDownloadSessionData,
        )
        if (launched) {
            Toast.makeText(
                context,
                "已发送到 " + app.label,
                Toast.LENGTH_SHORT,
            ).show()
        }
        return launched
    }

    when (config.downloadManagerMode) {
        DownloadManagerMode.SYSTEM -> {
            if (!builtIn()) openExternal(context, url)
        }

        DownloadManagerMode.EXTERNAL -> {
            val app = ExternalDownloadManager.find(
                context,
                config.externalDownloadManagerId,
                request,
            )
            if (app == null || !external(app)) {
                if (!builtIn()) openExternal(context, url)
            }
        }

        DownloadManagerMode.ASK_EVERY_TIME -> {
            val apps = ExternalDownloadManager.discover(context, request)
            if (apps.isEmpty()) {
                if (!builtIn()) openExternal(context, url)
            } else {
                hostCallbacks.onDownloadChoice(
                    BrowserDownloadChoiceRequest(
                        request = request,
                        apps = apps,
                        complete = { selected ->
                            if (selected == null) {
                                if (!builtIn()) openExternal(context, url)
                            } else if (!external(selected)) {
                                if (!builtIn()) openExternal(context, url)
                            }
                        },
                    ),
                )
            }
        }
    }
}

private class SystemWebViewBrowserEngine(
    private val context: Context,
    initialConfig: BrowserEngineConfig,
    private val hostCallbacks: BrowserHostCallbacks,
    private val onState: (BrowserRenderState) -> Unit,
) : BrowserEngine {
    override val kind = BrowserEngineKind.SYSTEM_WEBVIEW
    private var state = BrowserRenderState()
    private val webView = WebView(context).also { view ->
        val profileName = BrowserProfileStorage.webViewProfileName(initialConfig.profileId)
        if (
            profileName != null &&
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        ) {
            runCatching {
                WebViewCompat.setProfile(view, profileName)
            }
        }
    }
    private val assignedProfileName =
        BrowserProfileStorage.webViewProfileName(initialConfig.profileId)
    private val mobileUserAgent = WebSettings.getDefaultUserAgent(context)
    private val mediaBridge = WebViewMediaJavascriptBridge(hostCallbacks.onMediaState)
    private var lastFindQuery = ""
    private var currentConfig = initialConfig

    override val view: View
        get() = webView

    init {
        webView.addJavascriptInterface(mediaBridge, "YBrowserMediaNative")
        configureSystemWebViewCredentials(webView, initialConfig.privateMode)
        webView.settings.apply {
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = true
            safeBrowsingEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
        }

        webView.setOnLongClickListener {
            val hit = webView.hitTestResult
            val extra = hit.extra?.takeIf { value -> value.isNotBlank() }
            val target = when (hit.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE,
                WebView.HitTestResult.EMAIL_TYPE,
                WebView.HitTestResult.PHONE_TYPE -> {
                    extra?.let {
                        BrowserContentTarget(
                            kind = BrowserContentTargetKind.LINK,
                            url = it,
                        )
                    }
                }

                WebView.HitTestResult.IMAGE_TYPE -> {
                    extra?.let {
                        BrowserContentTarget(
                            kind = BrowserContentTargetKind.IMAGE,
                            url = it,
                            imageUrl = it,
                        )
                    }
                }

                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val hrefMessage = Message.obtain()
                    hrefMessage.target = Handler(Looper.getMainLooper()) { message ->
                        val data = message.data
                        val href = data.getString("url")
                            ?.takeIf { value -> value.isNotBlank() }
                            ?: extra
                        if (!href.isNullOrBlank()) {
                            hostCallbacks.onContentLongPress(
                                BrowserContentTarget(
                                    kind = BrowserContentTargetKind.IMAGE_LINK,
                                    url = href,
                                    imageUrl = extra,
                                ),
                            )
                        }
                        true
                    }
                    webView.requestFocusNodeHref(hrefMessage)
                    return@setOnLongClickListener true
                }

                else -> null
            }

            if (target != null) {
                hostCallbacks.onContentLongPress(target)
                true
            } else {
                false
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString() ?: return true
                val scheme = request.url.scheme?.lowercase()
                return if (scheme == "http" || scheme == "https" || scheme == "view-source") {
                    if (
                        request.isForMainFrame &&
                        request.hasGesture() &&
                        hostCallbacks.onUserNavigation(url)
                    ) {
                        true
                    } else {
                        false
                    }
                } else {
                    if (
                        request.isForMainFrame &&
                        canOpenExternalNavigation(
                            request.url,
                            hasUserGesture = request.hasGesture(),
                        )
                    ) {
                        openExternal(context, url)
                    }
                    true
                }
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val target = url ?: return true
                val scheme = Uri.parse(target).scheme?.lowercase()
                return if (scheme == "http" || scheme == "https" || scheme == "view-source") {
                    false
                } else {
                    val targetUri = Uri.parse(target)
                    if (
                        canOpenExternalNavigation(
                            targetUri,
                            hasUserGesture = false,
                            legacyCallback = true,
                        )
                    ) {
                        openExternal(context, target)
                    }
                    true
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val uri = request?.url ?: return null
                if (CustomFilterRepository.matches(currentConfig.customBlockedHosts, uri)) {
                    Handler(Looper.getMainLooper()).post {
                        hostCallbacks.onContentBlocked(
                            BrowserPrivacyEvent(
                                url = uri.toString(),
                                category = "自定义过滤",
                            ),
                        )
                    }
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0)),
                    )
                }
                if (isBlockedTracker(uri, currentConfig.trackingProtection)) {
                    Handler(Looper.getMainLooper()).post {
                        hostCallbacks.onContentBlocked(
                            BrowserPrivacyEvent(
                                url = uri.toString(),
                                category = trackerCategory(uri),
                            ),
                        )
                    }
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0)),
                    )
                }
                return null
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
                        pageError = null,
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
                if (currentConfig.javaScriptEnabled) {
                    runCatching {
                        webView.evaluateJavascript(WEBVIEW_MEDIA_MONITOR_SCRIPT, null)
                    }
                    val finalUrl = url.orEmpty()
                    currentConfig.userScripts
                        .filter { BrowserUserScriptRepository.matches(it, finalUrl) }
                        .forEach { script ->
                            runCatching {
                                webView.evaluateJavascript(
                                    "(function(){try{" +
                                        script.code +
                                        "}catch(e){console.error('YBrowser user script',e);}})();",
                                    null,
                                )
                            }
                        }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                publish(
                    state.copy(
                        url = request.url.toString(),
                        loading = false,
                        pageError = BrowserPageError(
                            url = request.url.toString(),
                            description = error?.description?.toString()
                                ?.takeIf { it.isNotBlank() }
                                ?: "网页加载失败",
                            code = error?.errorCode,
                        ),
                    ),
                )
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?,
            ) {
                handler?.cancel()
                val failedUrl = error?.url.orEmpty().ifBlank { state.url }
                publish(
                    state.copy(
                        url = failedUrl,
                        loading = false,
                        pageError = BrowserPageError(
                            url = failedUrl,
                            description = "安全连接失败",
                            code = error?.primaryError,
                        ),
                    ),
                )
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                publish(
                    state.copy(
                        loading = false,
                        pageError = BrowserPageError(
                            url = state.url,
                            description = if (detail?.didCrash() == true) {
                                "网页渲染进程崩溃，正在重新创建页面"
                            } else {
                                "网页渲染进程已被系统回收，正在重新创建页面"
                            },
                        ),
                    ),
                )
                hostCallbacks.onEngineCrashed()
                return true
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: HttpAuthHandler?,
                host: String?,
                realm: String?,
            ) {
                if (handler == null) return
                hostCallbacks.onAuthPrompt(
                    BrowserAuthPromptRequest(
                        uri = "https://" + host.orEmpty(),
                        realm = realm,
                        onlyPassword = false,
                        confirm = { username, password ->
                            handler.proceed(username, password)
                        },
                        dismiss = handler::cancel,
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


            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                if (result == null) return false
                hostCallbacks.onWebPrompt(
                    BrowserWebPromptRequest(
                        kind = BrowserWebPromptKind.ALERT,
                        title = view?.title,
                        message = message,
                        defaultValue = null,
                        confirm = { result.confirm() },
                        dismiss = { result.cancel() },
                    ),
                )
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                if (result == null) return false
                hostCallbacks.onWebPrompt(
                    BrowserWebPromptRequest(
                        kind = BrowserWebPromptKind.CONFIRM,
                        title = view?.title,
                        message = message,
                        defaultValue = null,
                        confirm = { result.confirm() },
                        dismiss = { result.cancel() },
                    ),
                )
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?,
            ): Boolean {
                if (result == null) return false
                hostCallbacks.onWebPrompt(
                    BrowserWebPromptRequest(
                        kind = BrowserWebPromptKind.TEXT,
                        title = view?.title,
                        message = message,
                        defaultValue = defaultValue,
                        confirm = { value -> result.confirm(value.orEmpty()) },
                        dismiss = { result.cancel() },
                    ),
                )
                return true
            }

            override fun onJsBeforeUnload(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                if (result == null) return false
                hostCallbacks.onWebPrompt(
                    BrowserWebPromptRequest(
                        kind = BrowserWebPromptKind.BEFORE_UNLOAD,
                        title = view?.title,
                        message = message,
                        defaultValue = null,
                        confirm = { result.confirm() },
                        dismiss = { result.cancel() },
                    ),
                )
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                val chooserIntent = runCatching {
                    fileChooserParams.createIntent().apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }.getOrNull()
                hostCallbacks.onFilePrompt(
                    BrowserFilePromptRequest(
                        mimeTypes = fileChooserParams.acceptTypes.filter { it.isNotBlank() },
                        allowMultiple =
                            fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE,
                        capture = if (fileChooserParams.isCaptureEnabled) {
                            BrowserFileCapture.ANY
                        } else {
                            BrowserFileCapture.NONE
                        },
                        pickerIntent = chooserIntent,
                        parsePickerResult = { resultCode, data ->
                            FileChooserParams.parseResult(resultCode, data)
                                ?.toList()
                                ?.takeIf { it.isNotEmpty() }
                        },
                        complete = { values ->
                            filePathCallback.onReceiveValue(
                                values?.toTypedArray(),
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
                assignedProfileName?.let { profileName ->
                    if (
                        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
                    ) {
                        runCatching { WebViewCompat.setProfile(popup, profileName) }
                    }
                }
                configureSystemWebViewCredentials(popup, initialConfig.privateMode)
                popup.settings.javaScriptEnabled = false
                popup.settings.allowFileAccess = false
                popup.settings.allowContentAccess = true
                popup.settings.safeBrowsingEnabled = true
                val destroyed = java.util.concurrent.atomic.AtomicBoolean(false)
                val destroyPopup = {
                    if (destroyed.compareAndSet(false, true)) {
                        runCatching { popup.stopLoading() }
                        runCatching { popup.destroy() }
                    }
                }
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val target = request?.url?.toString() ?: return true
                        val scheme = request.url.scheme?.lowercase()
                        if (
                            scheme == "http" ||
                            scheme == "https" ||
                            scheme == "view-source"
                        ) {
                            hostCallbacks.onOpenNewTab(target)
                        } else if (
                            request.hasGesture() &&
                            canOpenExternalNavigation(
                                request.url,
                                hasUserGesture = true,
                            )
                        ) {
                            openExternal(context, target)
                        }
                        destroyPopup()
                        return true
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        url: String?,
                    ): Boolean {
                        val target = url.orEmpty()
                        val uri = runCatching { Uri.parse(target) }.getOrNull()
                        val scheme = uri?.scheme?.lowercase()
                        when {
                            target.isBlank() -> Unit
                            scheme == "http" ||
                                scheme == "https" ||
                                scheme == "view-source" ->
                                hostCallbacks.onOpenNewTab(target)
                            uri != null &&
                                canOpenExternalNavigation(
                                    uri,
                                    hasUserGesture = false,
                                    legacyCallback = true,
                                ) ->
                                openExternal(context, target)
                        }
                        destroyPopup()
                        return true
                    }
                }
                Handler(Looper.getMainLooper()).postDelayed(
                    { destroyPopup() },
                    5_000L,
                )
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: run {
                    destroyPopup()
                    return false
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }

        webView.setDownloadListener(
            DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                enqueueDownload(
                    context = context,
                    config = currentConfig,
                    hostCallbacks = hostCallbacks,
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

    override fun isAtTop(): Boolean =
        webView.scrollY <= 1

    override fun stop() {
        webView.stopLoading()
    }

    override fun applyConfig(config: BrowserEngineConfig) {
        currentConfig = config
        webView.settings.javaScriptEnabled = config.javaScriptEnabled
        webView.settings.mediaPlaybackRequiresUserGesture = config.blockAutoplay
        webView.settings.textZoom = config.textScale.coerceIn(50, 200)
        webView.settings.userAgentString = if (config.desktopMode) {
            DESKTOP_USER_AGENT
        } else {
            mobileUserAgent
        }
        CookieManager.getInstance().setAcceptCookie(config.cookiesEnabled)
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            webView,
            config.cookiesEnabled &&
                !config.blockThirdPartyCookies &&
                config.trackingProtection == TrackingProtection.OFF,
        )
        if (config.javaScriptEnabled) {
            val muted = if (config.muted) "true" else "false"
            webView.evaluateJavascript(
                "document.querySelectorAll('video,audio').forEach(e=>e.muted=" + muted + ")",
                null,
            )
        }
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

    override fun capturePreview(onComplete: (Bitmap?) -> Unit) {
        if (webView.width <= 0 || webView.height <= 0 || !webView.isShown) {
            onComplete(null)
            return
        }
        runCatching {
            val bitmap = Bitmap.createBitmap(
                webView.width,
                webView.height,
                Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            bitmap
        }.onSuccess(onComplete)
            .onFailure { onComplete(null) }
    }

    override fun extractReader(onComplete: (ReaderDocument?) -> Unit) {
        if (state.url.isBlank() ||
            (!state.url.startsWith("http://") && !state.url.startsWith("https://"))
        ) {
            onComplete(null)
            return
        }
        runCatching {
            webView.evaluateJavascript(WEBVIEW_READER_EXTRACTION_SCRIPT) { encoded ->
                onComplete(
                    ReaderDocumentParser.parse(
                        decodeJavascriptStringResult(encoded),
                    ),
                )
            }
        }.onFailure {
            onComplete(null)
        }
    }

    override fun mediaCommand(command: BrowserMediaCommand) {
        if (!currentConfig.javaScriptEnabled) return
        val value = command.name.lowercase()
        runCatching {
            webView.evaluateJavascript(
                "window.__ybrowserMediaCommand?.('" + value + "')",
                null,
            )
        }
    }

    override fun exitFullscreen() {
        hostCallbacks.onCustomView(null, null)
        hostCallbacks.onFullscreenChanged(false)
    }

    override fun printPage(): Boolean {
        val printManager = context.getSystemService(PrintManager::class.java) ?: return false
        val title = webView.title?.takeIf { it.isNotBlank() } ?: "YBrowser page"
        printManager.print(
            title,
            webView.createPrintDocumentAdapter(title),
            PrintAttributes.Builder().build(),
        )
        return true
    }

    override fun destroy() {
        hostCallbacks.onMediaState(null)
        webView.stopLoading()
        runCatching { webView.removeJavascriptInterface("YBrowserMediaNative") }
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.destroy()
    }

    private fun publish(next: BrowserRenderState) {
        state = next
        onState(next)
    }
}

internal object GeckoRuntimeHolder {
    fun get(context: Context): GeckoRuntime =
        SharedGeckoRuntime.get(context)
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
            .apply {
                BrowserProfileStorage.geckoContextId(initialConfig.profileId)
                    ?.let { contextId(it) }
            }
            .build(),
    )
    private val geckoView = GeckoView(context)
    private val readerBridge = GeckoReaderExtensionHost.bind(
        runtime = runtime,
        session = session,
        onMediaState = hostCallbacks.onMediaState,
        onContentBlocked = hostCallbacks.onContentBlocked,
    )
    private val uploadStager = GeckoUploadStager(context)
    private var state = BrowserRenderState()
    private var currentConfig = initialConfig
    @Volatile
    private var contentScrollY = 0

    override val view: View
        get() = geckoView

    init {
        session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(
                session: GeckoSession,
                scrollX: Int,
                scrollY: Int,
            ) {
                contentScrollY = scrollY.coerceAtLeast(0)
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                contentScrollY = 0
                publish(
                    state.copy(
                        url = url,
                        loading = true,
                        progress = 0,
                        pageError = null,
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

            override fun onCrash(session: GeckoSession) {
                publish(
                    state.copy(
                        loading = false,
                        pageError = BrowserPageError(
                            url = state.url,
                            description = "Gecko 网页内容进程崩溃，正在重新创建页面",
                        ),
                    ),
                )
                hostCallbacks.onEngineCrashed()
            }

            override fun onKill(session: GeckoSession) {
                publish(
                    state.copy(
                        loading = false,
                        pageError = BrowserPageError(
                            url = state.url,
                            description = "Gecko 网页内容进程已退出，正在重新创建页面",
                        ),
                    ),
                )
                hostCallbacks.onEngineCrashed()
            }


            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement,
            ) {
                val link = element.linkUri?.takeIf { it.isNotBlank() }
                val image = element.srcUri?.takeIf { it.isNotBlank() }
                val target = when {
                    link != null && image != null -> BrowserContentTarget(
                        kind = BrowserContentTargetKind.IMAGE_LINK,
                        url = link,
                        imageUrl = image,
                    )
                    link != null -> BrowserContentTarget(
                        kind = BrowserContentTargetKind.LINK,
                        url = link,
                    )
                    image != null -> BrowserContentTarget(
                        kind = BrowserContentTargetKind.IMAGE,
                        url = image,
                        imageUrl = image,
                    )
                    else -> null
                }
                target?.let(hostCallbacks.onContentLongPress)
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
                        config = currentConfig,
                        hostCallbacks = hostCallbacks,
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
                    scheme == "about" ||
                    scheme == "view-source"
                ) {
                    if (
                        request.hasUserGesture &&
                        !request.isDirectNavigation &&
                        !request.isRedirect &&
                        hostCallbacks.onUserNavigation(request.uri)
                    ) {
                        GeckoResult.fromValue(AllowOrDeny.DENY)
                    } else {
                        GeckoResult.fromValue(AllowOrDeny.ALLOW)
                    }
                } else {
                    if (
                        uri != null &&
                        canOpenExternalNavigation(
                            uri,
                            hasUserGesture = request.hasUserGesture,
                        )
                    ) {
                        openExternal(context, request.uri)
                    }
                    GeckoResult.fromValue(AllowOrDeny.DENY)
                }
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String>? {
                val failedUrl = uri.orEmpty().ifBlank { state.url }
                publish(
                    state.copy(
                        url = failedUrl,
                        loading = false,
                        pageError = BrowserPageError(
                            url = failedUrl,
                            description = error.message
                                ?.takeIf { it.isNotBlank() }
                                ?: "网页加载失败",
                            code = error.code,
                        ),
                    ),
                )
                return null
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

        session.contentBlockingDelegate = object : ContentBlocking.Delegate {
            override fun onContentBlocked(
                session: GeckoSession,
                event: ContentBlocking.BlockEvent,
            ) {
                hostCallbacks.onContentBlocked(
                    BrowserPrivacyEvent(
                        url = event.uri,
                        category = geckoTrackingCategory(event.getAntiTrackingCategory()),
                    ),
                )
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
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                hostCallbacks.onWebPrompt(
                    BrowserWebPromptRequest(
                        kind = BrowserWebPromptKind.ALERT,
                        title = prompt.title,
                        message = prompt.message,
                        defaultValue = null,
                        confirm = {
                            result.complete(prompt.dismiss())
                        },
                        dismiss = {
                            result.complete(prompt.dismiss())
                        },
                    ),
                )
                return result
            }

            override fun onButtonPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ButtonPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                hostCallbacks.onWebPrompt(
                    BrowserWebPromptRequest(
                        kind = BrowserWebPromptKind.CONFIRM,
                        title = prompt.title,
                        message = prompt.message,
                        defaultValue = null,
                        confirm = {
                            result.complete(
                                prompt.confirm(
                                    GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE,
                                ),
                            )
                        },
                        dismiss = {
                            result.complete(
                                prompt.confirm(
                                    GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE,
                                ),
                            )
                        },
                    ),
                )
                return result
            }

            override fun onTextPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.TextPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                hostCallbacks.onWebPrompt(
                    BrowserWebPromptRequest(
                        kind = BrowserWebPromptKind.TEXT,
                        title = prompt.title,
                        message = prompt.message,
                        defaultValue = prompt.defaultValue,
                        confirm = { value ->
                            result.complete(prompt.confirm(value.orEmpty()))
                        },
                        dismiss = {
                            result.complete(prompt.dismiss())
                        },
                    ),
                )
                return result
            }

            override fun onBeforeUnloadPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                hostCallbacks.onWebPrompt(
                    BrowserWebPromptRequest(
                        kind = BrowserWebPromptKind.BEFORE_UNLOAD,
                        title = null,
                        message = null,
                        defaultValue = null,
                        confirm = {
                            result.complete(prompt.confirm(AllowOrDeny.ALLOW))
                        },
                        dismiss = {
                            result.complete(prompt.confirm(AllowOrDeny.DENY))
                        },
                    ),
                )
                return result
            }

            override fun onRepostConfirmPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                hostCallbacks.onWebPrompt(
                    BrowserWebPromptRequest(
                        kind = BrowserWebPromptKind.REPOST,
                        title = null,
                        message = "是否重新提交表单数据？",
                        defaultValue = null,
                        confirm = {
                            result.complete(prompt.confirm(AllowOrDeny.ALLOW))
                        },
                        dismiss = {
                            result.complete(prompt.confirm(AllowOrDeny.DENY))
                        },
                    ),
                )
                return result
            }

            override fun onAuthPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AuthPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val flags = prompt.authOptions.flags
                val onlyPassword = flags and
                    GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
                hostCallbacks.onAuthPrompt(
                    BrowserAuthPromptRequest(
                        uri = prompt.authOptions.uri.orEmpty(),
                        realm = prompt.message ?: prompt.title,
                        onlyPassword = onlyPassword,
                        confirm = { username, password ->
                            result.complete(
                                if (onlyPassword) {
                                    prompt.confirm(password)
                                } else {
                                    prompt.confirm(username, password)
                                },
                            )
                        },
                        dismiss = {
                            result.complete(prompt.dismiss())
                        },
                    ),
                )
                return result
            }

            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val kind = if (
                    prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.FOLDER
                ) {
                    BrowserFilePromptKind.FOLDER
                } else {
                    BrowserFilePromptKind.FILE
                }
                val capture = when (prompt.capture) {
                    GeckoSession.PromptDelegate.FilePrompt.Capture.ANY ->
                        BrowserFileCapture.ANY
                    GeckoSession.PromptDelegate.FilePrompt.Capture.USER ->
                        BrowserFileCapture.USER
                    GeckoSession.PromptDelegate.FilePrompt.Capture.ENVIRONMENT ->
                        BrowserFileCapture.ENVIRONMENT
                    else -> BrowserFileCapture.NONE
                }

                hostCallbacks.onFilePrompt(
                    BrowserFilePromptRequest(
                        mimeTypes = prompt.mimeTypes.orEmpty().toList(),
                        allowMultiple = prompt.type ==
                            GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE,
                        kind = kind,
                        capture = capture,
                        complete = { values ->
                            val selected = values.orEmpty()
                            val response = when {
                                selected.isEmpty() -> prompt.dismiss()
                                kind == BrowserFilePromptKind.FOLDER ->
                                    prompt.confirm(
                                        context.applicationContext,
                                        selected.first(),
                                    )
                                else -> {
                                    val staged = uploadStager.stage(selected)
                                    if (!staged.isNullOrEmpty()) {
                                        BrowserNavigationLog.log(
                                            context,
                                            "GECKO_UPLOAD_CONFIRM",
                                            "count=" + staged.size +
                                                " uris=" + staged.joinToString(),
                                        )
                                        prompt.confirm(
                                            context.applicationContext,
                                            staged,
                                        )
                                    } else {
                                        BrowserNavigationLog.log(
                                            context,
                                            "GECKO_UPLOAD_CONFIRM",
                                            "dismissed because staging returned no files",
                                        )
                                        prompt.dismiss()
                                    }
                                }
                            }
                            result.complete(response)
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

    override fun isAtTop(): Boolean =
        contentScrollY <= 1

    override fun stop() {
        session.stop()
    }

    override fun applyConfig(config: BrowserEngineConfig) {
        currentConfig = config
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
        val cookieBehavior = when {
            !config.cookiesEnabled -> ContentBlocking.CookieBehavior.ACCEPT_NONE
            config.blockThirdPartyCookies ->
                ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
            config.trackingProtection == TrackingProtection.OFF ->
                ContentBlocking.CookieBehavior.ACCEPT_ALL
            config.trackingProtection == TrackingProtection.STRICT ->
                ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
            else -> ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS
        }
        runtime.settings.contentBlocking
            .setCookieBehavior(cookieBehavior)
            .setCookieBehaviorPrivateMode(cookieBehavior)
            .setAntiTracking(
                when (config.trackingProtection) {
                    TrackingProtection.OFF -> ContentBlocking.AntiTracking.NONE
                    TrackingProtection.STANDARD -> ContentBlocking.AntiTracking.DEFAULT
                    TrackingProtection.STRICT -> ContentBlocking.AntiTracking.STRICT
                },
            )
            .setEnhancedTrackingProtectionLevel(
                when (config.trackingProtection) {
                    TrackingProtection.OFF -> ContentBlocking.EtpLevel.NONE
                    TrackingProtection.STANDARD -> ContentBlocking.EtpLevel.DEFAULT
                    TrackingProtection.STRICT -> ContentBlocking.EtpLevel.STRICT
                },
            )
            .setQueryParameterStrippingEnabled(
                config.trackingProtection != TrackingProtection.OFF,
            )
            .setQueryParameterStrippingPrivateBrowsingEnabled(
                config.trackingProtection != TrackingProtection.OFF,
            )
            .setCookiePurging(
                config.trackingProtection != TrackingProtection.OFF,
            )

        runtime.settings.setDohAutoselectEnabled(false)
        val dohEndpoint =
            when (config.dnsOverHttpsProvider) {
                DnsOverHttpsProvider.SYSTEM ->
                    null
                DnsOverHttpsProvider.CUSTOM ->
                    config.customDnsOverHttpsUrl
                        .trim()
                        .takeIf {
                            it.startsWith("https://")
                        }
                else ->
                    config.dnsOverHttpsProvider.endpoint
            }
        if (dohEndpoint == null) {
            runtime.settings
                .setTrustedRecursiveResolverMode(
                    org.mozilla.geckoview.GeckoRuntimeSettings
                        .TRR_MODE_DISABLED
                )
            runtime.settings
                .setTrustedRecursiveResolverUri("")
        } else {
            runtime.settings
                .setTrustedRecursiveResolverUri(
                    dohEndpoint
                )
            runtime.settings
                .setTrustedRecursiveResolverMode(
                    org.mozilla.geckoview.GeckoRuntimeSettings
                        .TRR_MODE_ONLY
                )
        }

        readerBridge.setPageMuted(config.muted)
        readerBridge.setUserScripts(config.userScripts)
        readerBridge.setCustomBlockedHosts(config.customBlockedHosts)
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

    override fun capturePreview(onComplete: (Bitmap?) -> Unit) {
        if (!geckoView.isAttachedToWindow ||
            geckoView.width <= 0 ||
            geckoView.height <= 0
        ) {
            onComplete(null)
            return
        }
        val result = runCatching { geckoView.capturePixels() }
            .getOrElse {
                onComplete(null)
                return
            }
        result.withHandler(Handler(Looper.getMainLooper())).accept(
            { bitmap -> onComplete(bitmap) },
            { onComplete(null) },
        )
    }

    override fun extractReader(onComplete: (ReaderDocument?) -> Unit) {
        if (state.url.isBlank() ||
            (!state.url.startsWith("http://") && !state.url.startsWith("https://"))
        ) {
            onComplete(null)
            return
        }
        readerBridge.extract { raw ->
            onComplete(ReaderDocumentParser.parse(raw))
        }
    }

    override fun mediaCommand(command: BrowserMediaCommand) {
        readerBridge.sendMediaCommand(command)
    }

    override fun exitFullscreen() {
        session.exitFullScreen()
        hostCallbacks.onFullscreenChanged(false)
    }

    override fun printPage(): Boolean {
        session.didPrintPageContent()
        return true
    }

    override fun destroy() {
        readerBridge.close()
        uploadStager.releaseAll()
        runCatching { geckoView.releaseSession() }
        runCatching { session.close() }
    }

    private fun publish(next: BrowserRenderState) {
        state = next
        onState(next)
    }
}

private class WebViewMediaJavascriptBridge(
    private val onState: (BrowserMediaState?) -> Unit,
) {
    @JavascriptInterface
    fun onMediaState(raw: String?) {
        val state = runCatching {
            val value = JSONObject(raw.orEmpty())
            BrowserMediaState(
                title = value.optString("title").ifBlank { "网页媒体" },
                url = value.optString("url"),
                playing = value.optBoolean("playing", false),
                durationMs = value.optLong("durationMs", -1L),
                positionMs = value.optLong("positionMs", 0L),
            )
        }.getOrNull()

        Handler(Looper.getMainLooper()).post {
            onState(state)
        }
    }
}

private fun decodeJavascriptStringResult(value: String?): String? {
    if (value.isNullOrBlank() || value == "null") return null
    return runCatching {
        JSONArray("[" + value + "]").getString(0)
    }.getOrNull()
}

private fun trackerCategory(uri: Uri): String {
    val host = uri.host?.lowercase().orEmpty()
    return when {
        "analytics" in host || "metric" in host || "segment" in host ||
            "mixpanel" in host || "hotjar" in host -> "分析"
        "facebook" in host || "twitter" in host || "tiktok" in host -> "社交"
        "doubleclick" in host || "adservice" in host || "adsystem" in host ||
            "criteo" in host || "taboola" in host || "outbrain" in host -> "广告"
        else -> "跟踪器"
    }
}

private fun geckoTrackingCategory(category: Int): String = when {
    category and ContentBlocking.AntiTracking.AD != 0 -> "广告"
    category and ContentBlocking.AntiTracking.ANALYTIC != 0 -> "分析"
    category and ContentBlocking.AntiTracking.SOCIAL != 0 -> "社交"
    category and ContentBlocking.AntiTracking.CRYPTOMINING != 0 -> "挖矿"
    category and ContentBlocking.AntiTracking.FINGERPRINTING != 0 -> "指纹识别"
    category and ContentBlocking.AntiTracking.EMAIL != 0 -> "邮件跟踪"
    else -> "跟踪器"
}

private fun isBlockedTracker(uri: Uri, protection: TrackingProtection): Boolean {
    if (protection == TrackingProtection.OFF) return false
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
    val standard = TRACKER_DOMAINS_STANDARD.any { domain ->
        host == domain || host.endsWith("." + domain)
    }
    if (standard) return true
    return protection == TrackingProtection.STRICT &&
        TRACKER_DOMAINS_STRICT.any { domain ->
            host == domain || host.endsWith("." + domain)
        }
}

private val TRACKER_DOMAINS_STANDARD = setOf(
    "doubleclick.net",
    "google-analytics.com",
    "googletagmanager.com",
    "googlesyndication.com",
    "googleadservices.com",
    "facebook.net",
    "connect.facebook.net",
    "scorecardresearch.com",
    "quantserve.com",
    "hotjar.com",
    "segment.io",
    "segment.com",
    "mixpanel.com",
    "app-measurement.com",
)

private val TRACKER_DOMAINS_STRICT = setOf(
    "ads-twitter.com",
    "analytics.twitter.com",
    "bat.bing.com",
    "clarity.ms",
    "criteo.com",
    "criteo.net",
    "taboola.com",
    "outbrain.com",
    "adnxs.com",
    "amazon-adsystem.com",
    "demdex.net",
    "omtrdc.net",
    "mathtag.com",
    "rubiconproject.com",
)

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"
