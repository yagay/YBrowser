package com.yagay.ybrowser.ai.web

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.FrameLayout
import com.yagay.ybrowser.ai.data.PendingAttachmentStore
import com.yagay.ybrowser.ai.diagnostics.DiagnosticLogger
import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ChatWindow
import com.yagay.ybrowser.ai.model.ProviderSpec
import com.yagay.browsercore.GeckoCoreCallbacks
import com.yagay.browsercore.GeckoCoreFilePromptRequest
import com.yagay.browsercore.GeckoCoreSession
import com.yagay.browsercore.GeckoCoreSessionPool
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class GeckoProviderRuntime(private val context: Context) {
    private val loader = ScriptLoader(context.applicationContext)
    private val pendingAttachmentStore = PendingAttachmentStore(context.applicationContext)
    private val pool = GeckoCoreSessionPool(context.applicationContext)
    private val injectedKeys = mutableSetOf<String>()
    private val preferredUrls = mutableMapOf<String, String>()
    private val initialNavigationUrls = mutableMapOf<String, String>()
    private val queuedNativeUris = mutableMapOf<String, List<Uri>>()
    private val networkAssemblies = mutableMapOf<String, NetworkAssembly>()
    private val chatPresentationKeys = mutableSetOf<String>()

    private data class NetworkAssembly(
        val template: CapturedNetworkPayload,
        val chunks: MutableList<String?>,
    )

    private var fileChooserLauncher: ((Intent) -> Unit)? = null
    private var fileSelectionListener:
        ((String, ProviderSpec, List<AttachmentMeta>) -> Unit)? = null
    private var pageChangeListener:
        ((String, ProviderSpec, String) -> Unit)? = null
    private var pageReadyListener:
        ((String, ProviderSpec, String) -> Unit)? = null
    private var conversationListener:
        ((String, ProviderSpec, WebRuntime.ConversationSnapshot) -> Unit)? = null

    private var pendingFilePrompt: GeckoCoreFilePromptRequest? = null
    private var pendingFileWindowId: String? = null
    private var pendingFileProvider: ProviderSpec? = null

    fun setFileChooserLauncher(launcher: ((Intent) -> Unit)?) {
        fileChooserLauncher = launcher
    }

    fun setFileSelectionListener(
        listener: ((String, ProviderSpec, List<AttachmentMeta>) -> Unit)?
    ) {
        fileSelectionListener = listener
    }

    fun setPageChangeListener(
        listener: ((String, ProviderSpec, String) -> Unit)?
    ) {
        pageChangeListener = listener
    }

    fun setPageReadyListener(
        listener: ((String, ProviderSpec, String) -> Unit)?
    ) {
        pageReadyListener = listener
    }

    fun setConversationListener(
        listener: ((String, ProviderSpec, WebRuntime.ConversationSnapshot) -> Unit)?
    ) {
        conversationListener = listener
    }

    fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        val prompt = pendingFilePrompt ?: return
        val windowId = pendingFileWindowId
        val provider = pendingFileProvider

        pendingFilePrompt = null
        pendingFileWindowId = null
        pendingFileProvider = null

        val uris = if (resultCode == Activity.RESULT_OK) {
            when {
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    List(clip.itemCount) { index -> clip.getItemAt(index).uri }
                }
                data?.data != null -> listOf(data.data!!)
                else -> emptyList()
            }
        } else {
            emptyList()
        }

        prompt.complete(uris.takeIf { it.isNotEmpty() })

        if (windowId != null && provider != null && uris.isNotEmpty()) {
            val attachments = uris.mapIndexed { index, uri ->
                queryAttachmentMeta(uri, index)
            }
            pendingAttachmentStore.save(sessionKey(windowId, provider), attachments)
            fileSelectionListener?.invoke(windowId, provider, attachments)
            DiagnosticLogger.i(
                "GECKO_FILE",
                "file_chooser_result provider=${provider.id} window=${windowId.take(12)} selected=${uris.size}"
            )
        }
    }

    fun attach(
        host: FrameLayout,
        window: ChatWindow,
        provider: ProviderSpec
    ) {
        DiagnosticLogger.recordBridgeTrace(
            stage = "attach",
            provider = provider.id,
            windowId = window.id,
            url = window.boundUrl ?: window.url.orEmpty(),
            detail = "hostChildren=${host.childCount}"
        )
        val session = obtain(
            windowId = window.id,
            provider = provider,
            preferredUrl = window.boundUrl ?: window.url,
            hostContext = host.context
        )
        val view = session.androidView
        if (view.parent !== host || host.childCount != 1 || host.getChildAt(0) !== view) {
            (view.parent as? ViewGroup)?.removeView(view)
            host.removeAllViews()
            host.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    fun currentUrl(windowId: String, provider: ProviderSpec): String? =
        pool.get(key(windowId, provider))?.currentState?.url
            ?.takeIf { it.isNotBlank() }

    fun setChatPresentation(
        windowId: String,
        provider: ProviderSpec,
        enabled: Boolean,
    ) {
        val runtimeKey = key(windowId, provider)
        if (provider.id != "chatgpt") {
            chatPresentationKeys.remove(runtimeKey)
            return
        }

        if (enabled) {
            chatPresentationKeys += runtimeKey
        } else {
            chatPresentationKeys.remove(runtimeKey)
        }

        applyChatPresentation(
            windowId = windowId,
            provider = provider,
            enabled = enabled,
        )
    }

    fun ensurePreferredPage(
        window: ChatWindow,
        provider: ProviderSpec,
    ) {
        val preferred = (window.boundUrl ?: window.url)
            ?.takeIf { sameProviderOrigin(it, provider) }
            ?: return
        val runtimeKey = key(window.id, provider)
        val existing = pool.get(runtimeKey)

        if (existing == null) {
            obtain(
                windowId = window.id,
                provider = provider,
                preferredUrl = preferred,
            )
            return
        }

        val currentState = existing.currentState
        val current = currentState.url
        if (currentState.loading || current.isBlank()) return
        if (sameProviderPage(current, preferred, provider)) return

        initialNavigationUrls[runtimeKey] = preferred
        preferredUrls[runtimeKey] = preferred
        injectedKeys.remove(runtimeKey)
        DiagnosticLogger.recordBridgeTrace(
            stage = "session-refocus",
            provider = provider.id,
            windowId = window.id,
            url = preferred,
            detail = "from=$current",
        )
        existing.load(preferred)
    }

    suspend fun isLoggedIn(
        windowId: String,
        provider: ProviderSpec
    ): Boolean =
        call(windowId, provider, "isLoggedIn") == "true"

    suspend fun attachFiles(
        windowId: String,
        provider: ProviderSpec,
        uris: List<Uri>
    ): WebRuntime.AttachmentAttachResult {
        if (uris.isEmpty()) {
            return WebRuntime.AttachmentAttachResult(
                attachedCount = 0,
                names = emptyList(),
                failure = "no-selection"
            )
        }

        ensureLoaded(windowId, provider)
        if (!ensureRuntimeInjected(windowId, provider)) {
            return WebRuntime.AttachmentAttachResult(
                attachedCount = 0,
                names = emptyList(),
                failure = "runtime-unavailable"
            )
        }

        val runtimeKey = key(windowId, provider)
        queuedNativeUris[runtimeKey] = uris

        val opened = call(windowId, provider, "openAttachmentPicker").orEmpty()
        if (
            opened != "opened-input" &&
            opened != "opened-button" &&
            opened != "scheduled"
        ) {
            queuedNativeUris.remove(runtimeKey)
            return WebRuntime.AttachmentAttachResult(
                attachedCount = 0,
                names = emptyList(),
                failure = opened.ifBlank { "picker-unavailable" }
            )
        }

        val metadata = uris.mapIndexed { index, uri ->
            queryAttachmentMeta(uri, index)
        }

        repeat(48) {
            delay(250)
            val raw = call(windowId, provider, "attachmentProbe").orEmpty()
            val probe = runCatching { JSONObject(raw) }.getOrNull()
            val count = maxOf(
                probe?.optInt("lastAttachedCount", 0) ?: 0,
                probe?.optInt("liveFilesCount", 0) ?: 0
            )
            if (count > 0) {
                val accepted = metadata.take(minOf(count, metadata.size))
                pendingAttachmentStore.save(
                    sessionKey(windowId, provider),
                    accepted
                )
                fileSelectionListener?.invoke(windowId, provider, accepted)
                queuedNativeUris.remove(runtimeKey)
                DiagnosticLogger.i(
                    "GECKO_FILE",
                    "native_attachment_confirmed provider=${provider.id} window=${windowId.take(12)} attached=${accepted.size}"
                )
                return WebRuntime.AttachmentAttachResult(
                    attachedCount = accepted.size,
                    names = accepted.map { it.name }
                )
            }
        }

        queuedNativeUris.remove(runtimeKey)
        return WebRuntime.AttachmentAttachResult(
            attachedCount = 0,
            names = emptyList(),
            failure = "attachment-not-confirmed"
        )
    }

    suspend fun send(
        windowId: String,
        provider: ProviderSpec,
        prompt: String
    ): Boolean {
        ensureLoaded(windowId, provider)
        val result = call(
            windowId,
            provider,
            "send",
            JSONObject.quote(prompt)
        )
        DiagnosticLogger.i(
            "GECKO_JS",
            "adapter_send provider=${provider.id} window=${windowId.take(12)} chars=${prompt.length} result=${result ?: "null"}"
        )

        if (result == "ok") return true
        if (result != "verify" && result != "queued") return false

        val maxAttempts = if (result == "queued") 72 else 24
        repeat(maxAttempts) { attempt ->
            delay(220)
            if (call(windowId, provider, "submissionAcknowledged") == "true") {
                return true
            }
            if (attempt == 0 || attempt == 7 || attempt == 23 || attempt == maxAttempts - 1) {
                val status = call(windowId, provider, "submissionStatus").orEmpty()
                if (status.contains("attachment-button-timeout")) {
                    return false
                }
            }
        }
        return false
    }

    suspend fun responseSnapshot(
        windowId: String,
        provider: ProviderSpec
    ): WebRuntime.ResponseSnapshot {
        ensureLoaded(windowId, provider)
        return parseResponseSnapshot(
            call(windowId, provider, "generationState").orEmpty()
        )
    }

    suspend fun conversationSnapshot(
        windowId: String,
        provider: ProviderSpec,
        preferredUrl: String? = null
    ): WebRuntime.ConversationSnapshot {
        val session = obtain(
            windowId = windowId,
            provider = provider,
            preferredUrl = preferredUrl
        )
        ensureLoaded(windowId, provider)

        val source = loader.conversationScript(provider.scriptAsset)
        val raw = evalRaw(
            session,
            """
                try {
                    $source
                    const reader = window.__AIHUB_CONVERSATION_READER__;
                    if (typeof reader !== "function") {
                        return JSON.stringify({
                            url: location.href,
                            title: document.title || "",
                            messages: [],
                            error: "reader-unavailable"
                        });
                    }
                    return JSON.stringify(reader());
                } catch (error) {
                    return JSON.stringify({
                        url: location.href,
                        title: document.title || "",
                        messages: [],
                        error: String(error)
                    });
                }
            """.trimIndent()
        ).orEmpty()

        return parseConversationSnapshot(raw)
    }

    suspend fun startConversationHydration(
        windowId: String,
        provider: ProviderSpec,
    ): String {
        ensureLoaded(windowId, provider)
        return call(
            windowId = windowId,
            provider = provider,
            action = "startConversationHydration",
        ).orEmpty()
    }

    suspend fun probeSummary(
        windowId: String,
        provider: ProviderSpec
    ): String {
        ensureLoaded(windowId, provider)
        return call(windowId, provider, "probeSummary").orEmpty()
    }

    suspend fun capabilities(
        windowId: String,
        provider: ProviderSpec
    ): ProviderCapabilities {
        ensureLoaded(windowId, provider)
        return ProviderCapabilities.fromJson(
            call(windowId, provider, "capabilities")
        )
    }

    suspend fun performAction(
        windowId: String,
        provider: ProviderSpec,
        action: String,
        value: String? = null
    ): String {
        ensureLoaded(windowId, provider)
        val args =
            "${JSONObject.quote(action)},${JSONObject.quote(value.orEmpty())}"
        return call(
            windowId,
            provider,
            "performAction",
            args
        ).orEmpty()
    }

    suspend fun stop(
        windowId: String,
        provider: ProviderSpec
    ) {
        call(windowId, provider, "stop")
    }

    fun markAttachmentsSubmitted(
        windowId: String,
        provider: ProviderSpec
    ) {
        pendingAttachmentStore.clear(sessionKey(windowId, provider))
        fileSelectionListener?.invoke(windowId, provider, emptyList())
        pool.get(key(windowId, provider))?.evaluate(
            """
                if (window.__AIHUB_ATTACHMENT_STATE__) {
                    window.__AIHUB_ATTACHMENT_STATE__.lastAttachedCount = 0;
                    window.__AIHUB_ATTACHMENT_STATE__.lastAttachedAt = 0;
                }
                return true;
            """.trimIndent()
        ) { _, _ -> }
    }

    fun canGoBack(windowId: String, provider: ProviderSpec): Boolean =
        pool.get(key(windowId, provider))?.currentState?.canGoBack == true

    fun goBack(windowId: String, provider: ProviderSpec): Boolean =
        pool.get(key(windowId, provider))?.goBack() == true

    fun resetProviderSession(
        windowId: String,
        provider: ProviderSpec
    ) {
        destroyWindow(windowId, provider)
    }

    fun destroyWindow(
        windowId: String,
        provider: ProviderSpec
    ) {
        val runtimeKey = key(windowId, provider)
        injectedKeys.remove(runtimeKey)
        preferredUrls.remove(runtimeKey)
        initialNavigationUrls.remove(runtimeKey)
        queuedNativeUris.remove(runtimeKey)
        chatPresentationKeys.remove(runtimeKey)
        networkAssemblies.keys.removeAll { it.startsWith("$runtimeKey|") }
        pool.close(runtimeKey)
    }

    fun flushCookies() {
        // Gecko persists its storage through GeckoRuntime. No explicit flush is required.
    }

    fun releaseUi() {
        // The AI workspace Activity may be finished when the user returns to
        // YagaYHub. Keep all GeckoSession instances alive in the process, but
        // release Activity-bound launchers/listeners/context references.
        fileChooserLauncher = null
        fileSelectionListener = null
        pageChangeListener = null
        pageReadyListener = null
        conversationListener = null

        pendingFilePrompt?.complete(null)
        pendingFilePrompt = null
        pendingFileWindowId = null
        pendingFileProvider = null

        pool.detachAll()

        DiagnosticLogger.i(
            "GECKO",
            "ui_released sessions_retained=" + pool.activeCount()
        )
    }

    fun destroy() {
        pendingFilePrompt?.complete(null)
        pendingFilePrompt = null
        pendingFileWindowId = null
        pendingFileProvider = null
        queuedNativeUris.clear()
        networkAssemblies.clear()
        chatPresentationKeys.clear()
        injectedKeys.clear()
        preferredUrls.clear()
        initialNavigationUrls.clear()
        pool.closeAll()
    }

    private fun obtain(
        windowId: String,
        provider: ProviderSpec,
        preferredUrl: String? = null,
        hostContext: Context? = null
    ): GeckoCoreSession {
        val runtimeKey = key(windowId, provider)
        val requestedUrl = preferredUrl
            ?.takeIf { sameProviderOrigin(it, provider) }
        val target = requestedUrl
            ?: preferredUrls[runtimeKey]
            ?: provider.homeUrl
        val existing = pool.get(runtimeKey)
        val existed = existing != null

        val callbacks = GeckoCoreCallbacks(
            onState = { state ->
                val url = state.url
                if (url.isNotBlank() && sameProviderOrigin(url, provider)) {
                    preferredUrls[runtimeKey] = url
                    pageChangeListener?.invoke(windowId, provider, url)
                }
            },
            onPageReady = {
                injectedKeys.remove(runtimeKey)
                val currentUrl = pool.get(runtimeKey)
                    ?.currentState
                    ?.url
                    .orEmpty()
                DiagnosticLogger.recordBridgeTrace(
                    stage = "page-ready",
                    provider = provider.id,
                    windowId = windowId,
                    url = currentUrl,
                    detail = "installing watcher"
                )
                enableNetworkCapture(
                    windowId = windowId,
                    provider = provider,
                )
                installConversationWatcher(
                    windowId = windowId,
                    provider = provider,
                )
                if (runtimeKey in chatPresentationKeys) {
                    applyChatPresentation(
                        windowId = windowId,
                        provider = provider,
                        enabled = true,
                    )
                }
                if (currentUrl.isNotBlank()) {
                    pageReadyListener?.invoke(
                        windowId,
                        provider,
                        currentUrl
                    )
                }
            },
            onRpcEvent = { event, payload ->
                DiagnosticLogger.recordBridgeTrace(
                    stage = "rpc-event",
                    provider = provider.id,
                    windowId = windowId,
                    url = pool.get(runtimeKey)?.currentState?.url.orEmpty(),
                    detail = "event=$event bytes=${payload.length}"
                )
                when (event) {
                    "ai-conversation" -> {
                        val snapshot = parseConversationSnapshot(payload)
                        val userCount = snapshot.messages.count { it.role == "user" }
                        val assistantCount = snapshot.messages.count { it.role == "assistant" }
                        DiagnosticLogger.recordBridgeTrace(
                            stage = "conversation-event",
                            provider = provider.id,
                            windowId = windowId,
                            url = snapshot.url,
                            detail = "source=${snapshot.source} ${snapshot.error}".trim(),
                            candidateCount = snapshot.candidateCount,
                            messageCount = snapshot.messages.size,
                            userCount = userCount,
                            assistantCount = assistantCount,
                        )
                        if (snapshot.url.isNotBlank()) {
                            conversationListener?.invoke(
                                windowId,
                                provider,
                                snapshot
                            )
                        }
                    }
                    "ai-network" -> handleNetworkEvent(
                        windowId = windowId,
                        provider = provider,
                        raw = payload,
                        transport = "webrequest",
                    )
                    "ai-page-network" -> handleNetworkEvent(
                        windowId = windowId,
                        provider = provider,
                        raw = payload,
                        transport = "page",
                    )
                }
            },
            onRpcDiagnostic = { stage, detail ->
                DiagnosticLogger.recordBridgeTrace(
                    stage = "rpc-$stage",
                    provider = provider.id,
                    windowId = windowId,
                    url = pool.get(runtimeKey)?.currentState?.url.orEmpty(),
                    detail = detail,
                )
            },
            onFilePrompt = { request ->
                val queued = queuedNativeUris.remove(runtimeKey)
                if (!queued.isNullOrEmpty()) {
                    request.complete(queued)
                } else {
                    launchFilePrompt(windowId, provider, request)
                }
            },
            onNewWindow = { uri ->
                if (uri.isNotBlank()) {
                    pool.get(runtimeKey)?.load(uri)
                }
            },
            onExternalUri = { uri ->
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    )
                }
            },
            onCrash = {
                injectedKeys.remove(runtimeKey)
                DiagnosticLogger.w(
                    "GECKO",
                    "content_process_lost provider=${provider.id} window=${windowId.take(12)}"
                )
            }
        )

        DiagnosticLogger.recordBridgeTrace(
            stage = if (existing != null) "session-reuse" else "session-create",
            provider = provider.id,
            windowId = windowId,
            url = target,
            detail = "requested=${requestedUrl.orEmpty()}"
        )

        val session = if (existing != null) {
            existing.updateCallbacks(callbacks)
            hostContext?.let(existing::attachHostContext)
            existing
        } else {
            pool.acquire(
                key = runtimeKey,
                hostContext = hostContext ?: context,
                initialUrl = target,
                callbacks = callbacks
            )
        }

        if (requestedUrl != null && !existed) {
            initialNavigationUrls[runtimeKey] = requestedUrl
        } else if (
            requestedUrl != null &&
            initialNavigationUrls[runtimeKey] == null
        ) {
            // Existing sessions are attachment-only here. Remember the
            // requested page, but never call load() just because Compose
            // switched tabs or reattached the existing GeckoView.
            initialNavigationUrls[runtimeKey] = requestedUrl
        }

        return session
    }

    private fun applyChatPresentation(
        windowId: String,
        provider: ProviderSpec,
        enabled: Boolean,
    ) {
        if (provider.id != "chatgpt") return
        val session = pool.get(key(windowId, provider)) ?: return
        val source = loader.chatPresentationScript(provider.scriptAsset)
        session.evaluate(
            """
                try {
                    $source
                    const presentation = window.__AIHUB_CHAT_PRESENTATION__;
                    if (
                        !presentation ||
                        typeof presentation.setEnabled !== "function"
                    ) {
                        return "presentation-unavailable";
                    }
                    return presentation.setEnabled(${enabled});
                } catch (error) {
                    return String(error);
                }
            """.trimIndent()
        ) { value, error ->
            val detail = error?.takeIf { it.isNotBlank() }
                ?: value.orEmpty()
            DiagnosticLogger.recordBridgeTrace(
                stage = if (error.isNullOrBlank()) {
                    "chat-presentation"
                } else {
                    "chat-presentation-failed"
                },
                provider = provider.id,
                windowId = windowId,
                url = session.currentState.url,
                detail = "enabled=$enabled result=" +
                    DiagnosticLogger.scrub(detail, 240),
            )
        }
    }

    private fun enableNetworkCapture(
        windowId: String,
        provider: ProviderSpec,
    ) {
        val session = pool.get(key(windowId, provider)) ?: return
        val hints = ProviderNetworkParser.captureUrlHints(provider)
        val hintsJson = JSONArray().apply {
            hints.forEach { put(it) }
        }.toString()
        session.evaluate(
            """
                const enable = globalThis.__YBROWSER_ENABLE_NETWORK_CAPTURE__;
                if (typeof enable !== "function") return "capture-unavailable";
                return enable($hintsJson);
            """.trimIndent()
        ) { value, error ->
            val detail = buildString {
                append(error?.takeIf { it.isNotBlank() } ?: value.orEmpty())
                append(" hints=")
                append(hints.size)
            }
            DiagnosticLogger.recordBridgeTrace(
                stage = if (error.isNullOrBlank()) {
                    "network-capture-enable"
                } else {
                    "network-capture-failed"
                },
                provider = provider.id,
                windowId = windowId,
                url = session.currentState.url,
                detail = detail,
            )
        }
    }

    private fun installConversationWatcher(
        windowId: String,
        provider: ProviderSpec,
    ) {
        val session = pool.get(key(windowId, provider)) ?: return
        val source = loader.conversationScript(provider.scriptAsset)
        session.evaluate(
            """
                try {
                    $source
                    const emit = globalThis.__YBROWSER_RPC_EMIT__;
                    const reader = window.__AIHUB_CONVERSATION_READER__;
                    if (typeof emit !== "function" || typeof reader !== "function") {
                        return "conversation-push-unavailable";
                    }

                    const previous = window.__AIHUB_CONVERSATION_WATCHER__;
                    if (previous && typeof previous.disconnect === "function") {
                        previous.disconnect();
                    }

                    let timer = 0;
                    let last = "";
                    const push = () => {
                        try {
                            const snapshot = reader();
                            const encoded = JSON.stringify(snapshot || {});
                            if (encoded && encoded !== last) {
                                last = encoded;
                                emit("ai-conversation", encoded);
                            }
                        } catch (_) {}
                    };
                    const schedule = () => {
                        clearTimeout(timer);
                        timer = setTimeout(push, 180);
                    };
                    const observer = new MutationObserver(schedule);
                    observer.observe(
                        document.documentElement || document.body,
                        {
                            subtree: true,
                            childList: true,
                            characterData: true
                        }
                    );
                    window.addEventListener("aihub-conversation-updated", schedule);
                    window.__AIHUB_CONVERSATION_WATCHER__ = {
                        disconnect() {
                            clearTimeout(timer);
                            observer.disconnect();
                            window.removeEventListener("aihub-conversation-updated", schedule);
                        }
                    };
                    push();
                    return "ok";
                } catch (error) {
                    return String(error);
                }
            """.trimIndent()
        ) { value, error ->
            if (!error.isNullOrBlank()) {
                DiagnosticLogger.recordBridgeTrace(
                    stage = "watcher-install-failed",
                    provider = provider.id,
                    windowId = windowId,
                    url = session.currentState.url,
                    detail = error,
                )
                DiagnosticLogger.w(
                    "GECKO_PUSH",
                    "watcher_install_failed provider=${provider.id} " +
                        "window=${windowId.take(12)} error=${DiagnosticLogger.scrub(error, 400)}"
                )
            } else {
                DiagnosticLogger.recordBridgeTrace(
                    stage = "watcher-install",
                    provider = provider.id,
                    windowId = windowId,
                    url = session.currentState.url,
                    detail = value.orEmpty(),
                )
                DiagnosticLogger.d(
                    "GECKO_PUSH",
                    "watcher_install provider=${provider.id} " +
                        "window=${windowId.take(12)} result=${value.orEmpty().take(80)}"
                )
            }
        }
    }

    private fun launchFilePrompt(
        windowId: String,
        provider: ProviderSpec,
        request: GeckoCoreFilePromptRequest
    ) {
        val launcher = fileChooserLauncher
        if (launcher == null) {
            request.complete(null)
            return
        }

        pendingFilePrompt?.complete(null)
        pendingFilePrompt = request
        pendingFileWindowId = windowId
        pendingFileProvider = provider

        val mimeTypes = request.mimeTypes
            .filter { it.isNotBlank() }
            .distinct()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = when {
                mimeTypes.size == 1 -> mimeTypes.first()
                else -> "*/*"
            }
            if (mimeTypes.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request.allowMultiple)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launcher(intent)
    }

    private suspend fun ensureLoaded(
        windowId: String,
        provider: ProviderSpec
    ) {
        val session = obtain(windowId, provider)
        if (session.currentState.url.isBlank()) {
            session.load(
                preferredUrls[key(windowId, provider)]
                    ?: provider.homeUrl
            )
        }

        repeat(60) {
            val state = session.currentState
            if (state.url.isNotBlank() && !state.loading) {
                return
            }
            delay(200)
        }
        DiagnosticLogger.w(
            "GECKO",
            "document_ready_timeout provider=${provider.id} window=${windowId.take(12)}"
        )
    }

    private suspend fun ensureRuntimeInjected(
        windowId: String,
        provider: ProviderSpec
    ): Boolean {
        val runtimeKey = key(windowId, provider)
        val session = obtain(windowId, provider)

        if (runtimeKey in injectedKeys) {
            val present = evalRaw(
                session,
                """
                    return typeof window.__AIHUB__ === "object" &&
                        typeof window.__AIHUB__.generationState === "function";
                """.trimIndent()
            ) == "true"
            if (present) return true
            injectedKeys.remove(runtimeKey)
        }

        val source = loader.providerScript(provider.scriptAsset)
        val ready = evalRaw(
            session,
            """
                try {
                    $source
                    return typeof window.__AIHUB__ === "object" &&
                        typeof window.__AIHUB__.generationState === "function";
                } catch (error) {
                    return false;
                }
            """.trimIndent()
        ) == "true"

        if (ready) {
            injectedKeys += runtimeKey
            DiagnosticLogger.i(
                "GECKO_JS",
                "runtime_injected provider=${provider.id} window=${windowId.take(12)} bytes=${source.length}"
            )
        } else {
            DiagnosticLogger.w(
                "GECKO_JS",
                "runtime_injection_failed provider=${provider.id} window=${windowId.take(12)}"
            )
        }
        return ready
    }

    private suspend fun call(
        windowId: String,
        provider: ProviderSpec,
        action: String,
        argumentJs: String? = null
    ): String? {
        ensureLoaded(windowId, provider)
        if (!ensureRuntimeInjected(windowId, provider)) return null

        val invocation =
            if (argumentJs == null) "$action()"
            else "$action($argumentJs)"

        val raw = evalRaw(
            obtain(windowId, provider),
            """
                try {
                    const api = window.__AIHUB__;
                    if (!api || typeof api.$action !== "function") {
                        return JSON.stringify({
                            ok: false,
                            error: "action-unavailable"
                        });
                    }
                    const value = api.$invocation;
                    return JSON.stringify({
                        ok: true,
                        value: value
                    });
                } catch (error) {
                    return JSON.stringify({
                        ok: false,
                        error: String(error)
                    });
                }
            """.trimIndent()
        ) ?: return null

        val obj = runCatching { JSONObject(raw) }.getOrNull()
            ?: return null
        if (!obj.optBoolean("ok", false)) {
            DiagnosticLogger.w(
                "GECKO_JS",
                "adapter_failed provider=${provider.id} action=$action error=${DiagnosticLogger.scrub(obj.optString("error"))}"
            )
            return null
        }

        val value = obj.opt("value")
        return when (value) {
            null, JSONObject.NULL -> null
            is Boolean -> value.toString()
            else -> value.toString()
        }
    }

    private suspend fun evalRaw(
        session: GeckoCoreSession,
        code: String
    ): String? =
        suspendCoroutine { continuation ->
            session.evaluate(code) { valueJson, error ->
                if (!error.isNullOrBlank()) {
                    DiagnosticLogger.w(
                        "GECKO_JS",
                        "evaluate_failed error=${DiagnosticLogger.scrub(error, 600)}"
                    )
                    continuation.resume(null)
                    return@evaluate
                }

                if (valueJson.isNullOrBlank() || valueJson == "null") {
                    continuation.resume(null)
                    return@evaluate
                }

                val decoded = runCatching {
                    JSONTokener(valueJson).nextValue()
                }.getOrNull()

                continuation.resume(
                    when (decoded) {
                        null, JSONObject.NULL -> null
                        is String -> decoded
                        else -> decoded.toString()
                    }
                )
            }
        }

    private fun parseConversationSnapshot(
        raw: String
    ): WebRuntime.ConversationSnapshot {
        val obj = runCatching { JSONObject(raw) }.getOrNull()
            ?: return WebRuntime.ConversationSnapshot()
        val array = obj.optJSONArray("messages") ?: JSONArray()
        val messages = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val role = item.optString("role").lowercase()
                val text = item.optString("text").trim()
                if (role !in setOf("user", "assistant") || text.isBlank()) continue
                add(
                    WebRuntime.PageConversationMessage(
                        id = item.optString("id").ifBlank {
                            "$role-$index-${text.hashCode()}"
                        },
                        role = role,
                        text = text
                    )
                )
            }
        }
        return WebRuntime.ConversationSnapshot(
            url = obj.optString("url"),
            title = obj.optString("title"),
            candidateCount = obj.optInt("candidateCount", -1),
            error = obj.optString("error"),
            source = obj.optString("source", "dom"),
            complete = obj.optBoolean("complete", false),
            messages = messages
        )
    }

    private fun handleNetworkEvent(
        windowId: String,
        provider: ProviderSpec,
        raw: String,
        transport: String,
    ) {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val requestId = obj.optString("requestId")
        if (requestId.isBlank()) return

        val capture = CapturedNetworkPayload(
            requestId = requestId,
            url = obj.optString("url"),
            method = obj.optString("method", "GET"),
            statusCode = obj.optInt("statusCode", 0),
            contentType = obj.optString("contentType"),
            body = obj.optString("body"),
            stream = obj.optBoolean("stream", false),
            complete = obj.optBoolean("complete", false),
            truncated = obj.optBoolean("truncated", false),
            capturedAt = obj.optLong("capturedAt", System.currentTimeMillis()),
        )

        val chunkCount = obj.optInt("chunkCount", 1).coerceAtLeast(1)
        val chunkIndex = obj.optInt("chunkIndex", 0).coerceIn(0, chunkCount - 1)
        val assembled = if (capture.stream || chunkCount == 1) {
            capture
        } else {
            val runtimeKey = key(windowId, provider)
            val assemblyKey = "$runtimeKey|$requestId"
            val assembly = networkAssemblies.getOrPut(assemblyKey) {
                NetworkAssembly(
                    template = capture.copy(body = ""),
                    chunks = MutableList(chunkCount) { null },
                )
            }
            if (assembly.chunks.size != chunkCount) {
                networkAssemblies.remove(assemblyKey)
                return
            }
            assembly.chunks[chunkIndex] = capture.body
            if (assembly.chunks.any { it == null }) return

            networkAssemblies.remove(assemblyKey)
            assembly.template.copy(
                body = assembly.chunks.joinToString(separator = "") { it.orEmpty() },
            )
        }

        val pageUrl = pool.get(key(windowId, provider))
            ?.currentState
            ?.url
            .orEmpty()
        if (!sameProviderOrigin(pageUrl, provider)) return

        val snapshot = ProviderNetworkParser.parse(
            provider = provider,
            capture = assembled,
            pageUrl = pageUrl,
        )
        if (snapshot == null) {
            val body = assembled.body
            val markerNames = buildList {
                if (body.contains("\"mapping\"")) add("mapping")
                if (body.contains("\"current_node\"")) add("current_node")
                if (body.contains("\"message\"")) add("message")
                if (body.contains("\"author\"")) add("author")
                if (body.contains("\"role\"")) add("role")
                if (body.contains("\"p\"")) add("patch_p")
                if (body.contains("\"v\"")) add("patch_v")
                if (body.trimStart().startsWith("data:")) add("sse")
            }
            val replacementCount = body.count { it == '\uFFFD' }
            val endpoint = runCatching {
                Uri.parse(assembled.url).path.orEmpty()
            }.getOrDefault("")
            DiagnosticLogger.recordBridgeTrace(
                stage = "network-unparsed",
                provider = provider.id,
                windowId = windowId,
                url = pageUrl,
                detail =
                    "transport=$transport endpoint=${DiagnosticLogger.scrub(endpoint, 180)} " +
                        "status=${assembled.statusCode} type=${assembled.contentType.take(100)} " +
                        "chars=${body.length} truncated=${assembled.truncated} " +
                        "replacement=$replacementCount markers=${markerNames.joinToString(",")}",
            )
            return
        }

        val userCount = snapshot.messages.count { it.role == "user" }
        val assistantCount = snapshot.messages.count { it.role == "assistant" }
        DiagnosticLogger.recordBridgeTrace(
            stage = "network-conversation",
            provider = provider.id,
            windowId = windowId,
            url = snapshot.url,
            detail =
                "transport=$transport source=${snapshot.source} request=${requestId.take(24)} " +
                    "response=${assembled.statusCode} chars=${assembled.body.length} " +
                    "truncated=${assembled.truncated}",
            candidateCount = snapshot.candidateCount,
            messageCount = snapshot.messages.size,
            userCount = userCount,
            assistantCount = assistantCount,
        )
        conversationListener?.invoke(windowId, provider, snapshot)
    }

    private fun parseResponseSnapshot(
        raw: String
    ): WebRuntime.ResponseSnapshot {
        val obj = runCatching { JSONObject(raw) }.getOrNull()
            ?: return WebRuntime.ResponseSnapshot()
        return WebRuntime.ResponseSnapshot(
            text = obj.optString("text"),
            key = obj.optString("key"),
            source = obj.optString("source", "none"),
            responseCount = obj.optInt("responseCount", 0),
            turnCount = obj.optInt("turnCount", 0),
            state = obj.optString("state", "idle"),
            reason = obj.optString("reason"),
            path = obj.optString("pathHash"),
            quietMs = obj.optLong("quietMs", -1L)
        )
    }

    private fun queryAttachmentMeta(
        uri: Uri,
        index: Int
    ): AttachmentMeta {
        var name = ""
        var size = 0L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex =
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex =
                        cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) {
                        name = cursor.getString(nameIndex).orEmpty()
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        }
        return AttachmentMeta(
            name = name.ifBlank {
                uri.lastPathSegment
                    ?.substringAfterLast('/')
                    ?: "attachment-${index + 1}"
            },
            mimeType = context.contentResolver
                .getType(uri)
                .orEmpty()
                .ifBlank { "application/octet-stream" },
            sizeBytes = size
        )
    }

    private fun sameDocument(
        left: String?,
        right: String?,
    ): Boolean = runCatching {
        fun normalized(value: String?): String {
            val uri = Uri.parse(value.orEmpty())
            return uri.buildUpon()
                .fragment(null)
                .build()
                .toString()
                .trimEnd('/')
        }
        normalized(left) == normalized(right)
    }.getOrDefault(false)

    private fun sameProviderPage(
        left: String?,
        right: String?,
        provider: ProviderSpec,
    ): Boolean {
        if (provider.id != "chatgpt") {
            return sameDocument(left, right)
        }

        return runCatching {
            fun chatIdentity(value: String?): String {
                val uri = Uri.parse(value.orEmpty())
                val scheme = uri.scheme?.lowercase().orEmpty()
                val host = uri.host?.lowercase().orEmpty()
                val path = uri.path.orEmpty()
                    .trimEnd('/')
                    .ifBlank { "/" }

                // ChatGPT appends or changes query/fragment parameters while
                // staying in the same conversation. A project tab is bound to
                // the conversation path (/c/<id> or /g/.../c/<id>), so query
                // and fragment changes must never trigger session.load().
                return "$scheme://$host$path"
            }

            val a = chatIdentity(left)
            val b = chatIdentity(right)
            a.isNotBlank() && a == b
        }.getOrDefault(false)
    }

    private fun sameProviderOrigin(
        raw: String,
        provider: ProviderSpec
    ): Boolean = runCatching {
        val target = Uri.parse(raw)
        val home = Uri.parse(provider.homeUrl)
        target.scheme in setOf("http", "https") &&
            target.host.equals(home.host, ignoreCase = true)
    }.getOrDefault(false)

    private fun key(
        windowId: String,
        provider: ProviderSpec
    ): String = "${provider.id}_$windowId"

    private fun sessionKey(
        windowId: String,
        provider: ProviderSpec
    ) = com.yagay.ybrowser.ai.model.WindowSessionKey(
        providerId = provider.id,
        windowId = windowId
    )
}
