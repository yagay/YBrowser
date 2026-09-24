package com.yagay.browsercore

import android.content.Context
import android.net.Uri
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

data class GeckoCoreState(
    val url: String = "",
    val title: String = "",
    val progress: Int = 0,
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val error: String? = null,
)

data class GeckoCoreFilePromptRequest(
    val mimeTypes: List<String>,
    val allowMultiple: Boolean,
    val complete: (List<Uri>?) -> Unit,
)

data class GeckoCoreCallbacks(
    val onState: (GeckoCoreState) -> Unit = {},
    val onPageReady: () -> Unit = {},
    val onSessionState: (String) -> Unit = {},
    val onRpcEvent: (String, String) -> Unit = { _, _ -> },
    val onRpcDiagnostic: (String, String) -> Unit = { _, _ -> },
    val onFilePrompt: (GeckoCoreFilePromptRequest) -> Unit = {
        it.complete(null)
    },
    val onNewWindow: (String) -> Unit = {},
    val onExternalUri: (String) -> Unit = {},
    val onCrash: () -> Unit = {},
)

class GeckoCoreSession(
    context: Context,
    initialUrl: String? = null,
    privateMode: Boolean = false,
    sessionContextId: String? = null,
    initialSessionState: String? = null,
    callbacks: GeckoCoreCallbacks = GeckoCoreCallbacks(),
) {
    private val appContext = context.applicationContext
    private var callbacks = callbacks
    private var state = GeckoCoreState()
    private val runtime = SharedGeckoRuntime.get(appContext)
    private val session = GeckoSession(
        GeckoSessionSettings.Builder()
            .usePrivateMode(privateMode)
            .suspendMediaWhenInactive(true)
            .apply {
                sessionContextId?.takeIf { it.isNotBlank() }?.let(::contextId)
            }
            .build(),
    )
    private val uploadStager = GeckoCoreUploadStager(appContext)
    private var sessionOpened = false
    private var rpcExtensionResolved = false
    private val restoredSessionState =
        initialSessionState
            ?.takeIf { it.isNotBlank() }
            ?.let { GeckoSession.SessionState.fromString(it) }
    private var pendingLoadUrl: String? =
        if (restoredSessionState == null) {
            initialUrl?.takeIf { it.isNotBlank() }
        } else {
            null
        }
    private val rpcBridge = GeckoRpcExtensionHost.bind(
        runtime = runtime,
        session = session,
        onExtensionReady = {
            rpcExtensionResolved = true
            flushPendingLoad()
        },
        onReady = {
            callbacks.onPageReady()
        },
        onEvent = { event, payload ->
            callbacks.onRpcEvent(event, payload)
        },
        onDiagnostic = { stage, detail ->
            callbacks.onRpcDiagnostic(stage, detail)
        },
    )

    init {
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onSessionStateChange(
                session: GeckoSession,
                sessionState: GeckoSession.SessionState,
            ) {
                sessionState.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(callbacks.onSessionState)
            }

            override fun onPageStart(session: GeckoSession, url: String) {
                publish(
                    state.copy(
                        url = url,
                        loading = true,
                        progress = 0,
                        error = null,
                    ),
                )
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                // Navigation lifecycle owns the loading flag. Gecko may deliver
                // a late progress callback after onPageStop(), especially on
                // long-lived SPAs such as ChatGPT; deriving loading from
                // progress can therefore flip a ready document back to
                // "loading" forever and block the AI bridge.
                publish(
                    state.copy(
                        progress = progress.coerceIn(0, 100),
                    ),
                )
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                publish(
                    state.copy(
                        loading = false,
                        progress = 100,
                        error = if (success) null else state.error,
                    ),
                )
                callbacks.onPageReady()
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
                        error = "Gecko content process crashed",
                    ),
                )
                callbacks.onCrash()
            }

            override fun onKill(session: GeckoSession) {
                publish(
                    state.copy(
                        loading = false,
                        error = "Gecko content process was killed",
                    ),
                )
                callbacks.onCrash()
            }
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny> {
                val uri = runCatching { Uri.parse(request.uri) }.getOrNull()
                val scheme = uri?.scheme?.lowercase().orEmpty()
                return when (scheme) {
                    "http", "https", "about", "view-source" ->
                        GeckoResult.fromValue(AllowOrDeny.ALLOW)
                    else -> {
                        callbacks.onExternalUri(request.uri)
                        GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                }
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String>? {
                publish(
                    state.copy(
                        url = uri.orEmpty().ifBlank { state.url },
                        loading = false,
                        error = error.message
                            ?.takeIf { it.isNotBlank() }
                            ?: "Page load failed",
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
                callbacks.onNewWindow(uri)
                return null
            }
        }

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                callbacks.onFilePrompt(
                    GeckoCoreFilePromptRequest(
                        mimeTypes = prompt.mimeTypes.orEmpty().toList(),
                        allowMultiple =
                            prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE,
                        complete = { selected ->
                            val values = selected.orEmpty()
                            val response = if (values.isEmpty()) {
                                prompt.dismiss()
                            } else {
                                val staged = uploadStager.stage(values)
                                if (staged.isNullOrEmpty()) {
                                    prompt.dismiss()
                                } else {
                                    prompt.confirm(
                                        appContext,
                                        staged,
                                    )
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
        restoredSessionState?.let(session::restoreState)
        sessionOpened = true
        flushPendingLoad()
    }

    val currentState: GeckoCoreState
        get() = state

    fun attachTo(view: GeckoView) {
        if (view.getSession() === session) return
        view.setSession(session)
    }

    fun updateCallbacks(callbacks: GeckoCoreCallbacks) {
        this.callbacks = callbacks
        callbacks.onState(state)
    }

    fun load(url: String) {
        if (url.isBlank()) return
        if (!rpcExtensionResolved) {
            pendingLoadUrl = url
            return
        }
        session.loadUri(url)
    }

    fun reload() = session.reload()

    fun setActive(active: Boolean) = session.setActive(active)

    fun setFocused(focused: Boolean) = session.setFocused(focused)

    fun setHighPriority(high: Boolean) =
        session.setPriorityHint(
            if (high) {
                GeckoSession.PRIORITY_HIGH
            } else {
                GeckoSession.PRIORITY_DEFAULT
            }
        )

    fun flushSessionState() = session.flushSessionState()

    fun stop() = session.stop()

    fun goBack(): Boolean {
        if (!state.canGoBack) return false
        session.goBack()
        return true
    }

    fun goForward(): Boolean {
        if (!state.canGoForward) return false
        session.goForward()
        return true
    }

    fun evaluate(
        code: String,
        callback: (valueJson: String?, error: String?) -> Unit,
    ) {
        rpcBridge.evaluate(code, callback)
    }

    fun destroy() {
        rpcBridge.close()
        uploadStager.releaseAll()
        runCatching { session.close() }
    }

    private fun flushPendingLoad() {
        if (!sessionOpened || !rpcExtensionResolved) return
        val url = pendingLoadUrl?.takeIf { it.isNotBlank() } ?: return
        pendingLoadUrl = null
        session.loadUri(url)
    }

    private fun publish(next: GeckoCoreState) {
        state = next
        callbacks.onState(next)
    }
}
