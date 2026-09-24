package com.yagay.ybrowser.ai.web

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import androidx.core.content.FileProvider
import com.yagay.ybrowser.ai.data.AiTabCacheStore
import com.yagay.ybrowser.ai.data.PendingAttachmentStore
import com.yagay.ybrowser.ai.diagnostics.DiagnosticLogger
import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ChatWindow
import com.yagay.ybrowser.ai.model.ProviderSpec
import com.yagay.ybrowser.ai.provider.ProviderCatalog
import com.yagay.ybrowser.ai.web.provider.ChatGptProductProvider
import com.yagay.browsercore.GeckoCoreCallbacks
import com.yagay.browsercore.GeckoCoreFilePromptRequest
import com.yagay.browsercore.GeckoCoreSession
import com.yagay.browsercore.GeckoCoreSessionPool
import com.yagay.browsercore.GeckoCoreViewHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class GeckoProviderRuntime(private val context: Context) {
    private val loader = ScriptLoader(context.applicationContext)
    private val tabCacheStore = AiTabCacheStore(context.applicationContext)
    private val pendingAttachmentStore = PendingAttachmentStore(context.applicationContext)
    private val pool = GeckoCoreSessionPool(context.applicationContext)
    private val viewHost = GeckoCoreViewHost(context.applicationContext)
    private val preloadViewHost = GeckoCoreViewHost(
        context.applicationContext,
        useTextureBackend = true,
    )
    private val injectedKeys = mutableSetOf<String>()
    private val preferredUrls = mutableMapOf<String, String>()
    private val initialNavigationUrls = mutableMapOf<String, String>()
    private val queuedNativeUris = mutableMapOf<String, List<Uri>>()
    private val networkAssemblies = mutableMapOf<String, NetworkAssembly>()
    private val chatPresentationKeys = mutableSetOf<String>()
    private val snapshotHandler = Handler(Looper.getMainLooper())
    private val snapshotTasks = mutableMapOf<String, Runnable>()
    private val freezeTasks = mutableMapOf<String, Runnable>()
    private val liveHandoffCallbacks =
        mutableMapOf<String, (Boolean, String) -> Unit>()
    private val liveHandoffTimeouts =
        mutableMapOf<String, Runnable>()
    private val liveHandoffTokens =
        mutableMapOf<String, String>()
    private val sessionRecency = linkedSetOf<String>()
    private val standbyKeys = mutableSetOf<String>()
    private val bindingRefocusKeys = mutableSetOf<String>()
    private val renderReadyUrls = mutableMapOf<String, String>()
    private val networkFingerprints = linkedSetOf<String>()
    private val activeStreamOwnedRequests =
        mutableSetOf<String>()
    private val conversationWriteAcks =
        mutableMapOf<String, Long>()
    private val pendingWriteExpectations =
        mutableMapOf<String, PendingWriteExpectation>()
    private val correlatedWriteAcks =
        mutableMapOf<String, CorrelatedWriteAck>()
    private val archiveFingerprints =
        mutableMapOf<String, String>()
    private val archiveExecutor =
        Executors.newSingleThreadExecutor()
    private val sessionOwners =
        mutableMapOf<String, Pair<String, ProviderSpec>>()

    private enum class PreloadState {
        COLD,
        PRELOADING,
        READY,
        WARM,
    }

    private val preloadStates =
        mutableMapOf<String, PreloadState>()
    private val preloadStableSince =
        mutableMapOf<String, Long>()
    private val preloadProbeTasks =
        mutableMapOf<String, Runnable>()
    private val preloadCallbacks =
        mutableMapOf<String, (Boolean, String) -> Unit>()
    private val preloadRetryAfter =
        mutableMapOf<String, Long>()

    private data class NetworkAssembly(
        val template: CapturedNetworkPayload,
        val chunks: MutableList<String?>,
    )

    private data class PendingWriteExpectation(
        val promptSha256: String,
        val conversationId: String?,
        val startedAt: Long,
    )

    private data class CorrelatedWriteAck(
        val observedAt: Long,
        val conversationId: String?,
        val userMessageId: String,
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
    private var responseChangeListener:
        ((String, ProviderSpec) -> Unit)? = null

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

    fun setResponseChangeListener(
        listener: ((String, ProviderSpec) -> Unit)?
    ) {
        responseChangeListener = listener
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

        uris.forEach { uri ->
            persistReadPermission(
                uri = uri,
                intentFlags = data?.flags ?: 0,
            )
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

    private fun nativeConversationObservationEnabled(): Boolean =
        conversationListener != null ||
            responseChangeListener != null

    fun attach(
        host: FrameLayout,
        window: ChatWindow,
        provider: ProviderSpec,
    ) {
        val runtimeKey = key(window.id, provider)
        val previousKey = viewHost.currentKey

        cancelWarmFreeze(runtimeKey)

        val session = pool.get(runtimeKey) ?: run {
            if (window.boundUrl.isNullOrBlank()) {
                tabCacheStore.markUnbound(window.id)
            } else {
                tabCacheStore.markBound(window)
            }
            obtain(
                windowId = window.id,
                provider = provider,
                preferredUrl = window.boundUrl ?: window.url,
            )
        }

        if (previousKey != runtimeKey) {
            previousKey
                ?.let(sessionOwners::get)
                ?.let { (previousWindowId, previousProvider) ->
                    captureSnapshotNow(
                        windowId = previousWindowId,
                        provider = previousProvider,
                    )
                    enterStandby(previousKey)
                }

            if (window.boundUrl.isNullOrBlank()) {
                tabCacheStore.markUnbound(window.id)
            } else {
                tabCacheStore.markBound(window)
            }
            DiagnosticLogger.recordBridgeTrace(
                stage = "view-switch",
                provider = provider.id,
                windowId = window.id,
                url = session.currentState.url,
                detail =
                    "from=" + (previousKey ?: "none") +
                        " to=" + runtimeKey,
            )
        }

        if (preloadViewHost.currentKey == runtimeKey) {
            cancelPreloadProbe(
                runtimeKey = runtimeKey,
                notify = false,
            )
            preloadViewHost.releaseIfBound(runtimeKey)
        }

        viewHost.attach(
            host = host,
            hostContext = host.context,
            key = runtimeKey,
            session = session,
        )
        standbyKeys.remove(runtimeKey)
        session.setActive(true)
        session.setHighPriority(true)
        if (
            preloadStates[runtimeKey] ==
                PreloadState.WARM
        ) {
            preloadStates[runtimeKey] =
                PreloadState.READY
        }

        // Existing sessions are browser tabs: attaching a GeckoView must not
        // navigate them back to boundUrl. A missing session is already created
        // by obtain() with the saved/bound URL as its cold-start target.

        touchSession(runtimeKey)
        trimHotSessions(protectedKey = runtimeKey)

        if (
            provider.id == "chatgpt" &&
            nativeConversationObservationEnabled() &&
            !session.currentState.loading &&
            session.currentState.url.isNotBlank()
        ) {
            installArchiveWatcher(
                windowId = window.id,
                provider = provider,
            )
        }

        if (
            previousKey != null &&
            previousKey != runtimeKey
        ) {
            scheduleWarmFreeze(previousKey)
        }
    }

    fun preloadState(
        windowId: String,
        provider: ProviderSpec,
    ): String {
        val runtimeKey = key(windowId, provider)
        if (pool.get(runtimeKey) == null) {
            return PreloadState.COLD.name
        }
        return (
            preloadStates[runtimeKey]
                ?: PreloadState.COLD
            ).name
    }

    fun canPreload(
        windowId: String,
        provider: ProviderSpec,
    ): Boolean {
        if (provider.id != "chatgpt") return false
        val runtimeKey = key(windowId, provider)
        if (viewHost.currentKey == runtimeKey) return false
        if (
            preloadStates[runtimeKey] in
                setOf(
                    PreloadState.PRELOADING,
                    PreloadState.READY,
                    PreloadState.WARM,
                )
        ) {
            return false
        }
        return (
            preloadRetryAfter[runtimeKey] ?: 0L
            ) <= System.currentTimeMillis()
    }

    fun attachPreload(
        host: FrameLayout,
        window: ChatWindow,
        provider: ProviderSpec,
        callback: (Boolean, String) -> Unit,
    ) {
        if (provider.id != "chatgpt") {
            callback(false, "unsupported-provider")
            return
        }

        val runtimeKey = key(window.id, provider)
        if (viewHost.currentKey == runtimeKey) {
            callback(true, "already-visible")
            return
        }
        if (
            preloadStates[runtimeKey] ==
                PreloadState.PRELOADING &&
            preloadViewHost.currentKey ==
                runtimeKey
        ) {
            preloadCallbacks[runtimeKey] =
                callback
            return
        }
        if (
            preloadStates[runtimeKey] in
                setOf(
                    PreloadState.READY,
                    PreloadState.WARM,
                ) &&
            pool.get(runtimeKey) != null
        ) {
            callback(true, "already-ready")
            return
        }
        val retryAt = preloadRetryAfter[runtimeKey] ?: 0L
        if (retryAt > System.currentTimeMillis()) {
            callback(false, "cooldown")
            return
        }

        val previousPreloadKey =
            preloadViewHost.currentKey
        if (
            previousPreloadKey != null &&
            previousPreloadKey != runtimeKey
        ) {
            cancelPreloadProbe(
                runtimeKey = previousPreloadKey,
                notify = true,
                reason = "replaced",
            )
            preloadViewHost.detachFromUi()
            pool.get(previousPreloadKey)?.let {
                it.setFocused(false)
                it.setHighPriority(false)
                it.setActive(false)
                it.flushSessionState()
            }
            if (
                preloadStates[previousPreloadKey] ==
                    PreloadState.PRELOADING
            ) {
                preloadStates[previousPreloadKey] =
                    PreloadState.COLD
            }
        }

        if (window.boundUrl.isNullOrBlank()) {
            tabCacheStore.markUnbound(window.id)
        } else {
            tabCacheStore.markBound(window)
        }

        val preferred =
            usableProviderNavigationUrl(
                window.boundUrl,
                provider,
            ) ?: usableProviderNavigationUrl(
                window.url,
                provider,
            )
        val session = pool.get(runtimeKey) ?: obtain(
            windowId = window.id,
            provider = provider,
            preferredUrl = preferred,
        )

        cancelWarmFreeze(runtimeKey)
        preloadCallbacks[runtimeKey] = callback
        preloadStates[runtimeKey] =
            PreloadState.PRELOADING
        preloadStableSince.remove(runtimeKey)

        preloadViewHost.attach(
            host = host,
            hostContext = host.context,
            key = runtimeKey,
            session = session,
        )
        // A preload tab has a real, window-attached viewport but never takes
        // user focus or high scheduling priority.
        session.setFocused(false)
        session.setHighPriority(false)
        session.setActive(true)
        standbyKeys.remove(runtimeKey)
        touchSession(runtimeKey)
        trimHotSessions(protectedKey = runtimeKey)

        DiagnosticLogger.recordBridgeTrace(
            stage = "preload-start",
            provider = provider.id,
            windowId = window.id,
            url = session.currentState.url.ifBlank {
                preferred.orEmpty()
            },
            detail = "texture-view real-viewport",
        )

        schedulePreloadProbe(
            windowId = window.id,
            provider = provider,
            attempt = 0,
        )
    }

    fun detachPreloadView(
        windowId: String,
        provider: ProviderSpec,
    ) {
        val runtimeKey = key(windowId, provider)
        if (
            preloadViewHost.currentKey !=
                runtimeKey
        ) {
            return
        }
        detachPreloadView()
    }

    fun detachPreloadView() {
        val runtimeKey =
            preloadViewHost.currentKey
                ?: return
        cancelPreloadProbe(
            runtimeKey = runtimeKey,
            notify = false,
        )
        preloadViewHost.detachFromUi()
        pool.get(runtimeKey)?.let { session ->
            session.setFocused(false)
            session.setHighPriority(false)
            session.setActive(false)
            session.flushSessionState()
        }
        if (
            preloadStates[runtimeKey] ==
                PreloadState.PRELOADING
        ) {
            preloadStates[runtimeKey] =
                PreloadState.COLD
        }
    }

    private fun schedulePreloadProbe(
        windowId: String,
        provider: ProviderSpec,
        attempt: Int,
    ) {
        val runtimeKey = key(windowId, provider)
        preloadProbeTasks
            .remove(runtimeKey)
            ?.let(snapshotHandler::removeCallbacks)

        if (
            preloadStates[runtimeKey] !=
                PreloadState.PRELOADING
        ) {
            return
        }

        if (attempt >= 60) {
            finishPreload(
                windowId = windowId,
                provider = provider,
                ready = false,
                detail = "composer-timeout",
            )
            return
        }

        val task = Runnable {
            preloadProbeTasks.remove(runtimeKey)
            val session = pool.get(runtimeKey)
            if (
                session == null ||
                preloadViewHost.currentKey !=
                    runtimeKey ||
                preloadStates[runtimeKey] !=
                    PreloadState.PRELOADING
            ) {
                return@Runnable
            }

            val state = session.currentState
            if (
                state.url.isBlank() ||
                state.loading ||
                !sameProviderOrigin(
                    state.url,
                    provider,
                )
            ) {
                schedulePreloadProbe(
                    windowId = windowId,
                    provider = provider,
                    attempt = attempt + 1,
                )
                return@Runnable
            }

            session.evaluate(
                code =
                    """
                        try {
                            const ready =
                                document.readyState === "interactive" ||
                                document.readyState === "complete";
                            const viewport =
                                window.innerWidth > 0 &&
                                window.innerHeight > 0;
                            const composer =
                                !!document.querySelector(
                                    "#prompt-textarea, " +
                                    "textarea, " +
                                    "[data-testid='composer'] [contenteditable='true'], " +
                                    "form [contenteditable='true']"
                                );
                            return JSON.stringify({
                                ready,
                                viewport,
                                composer,
                                width: window.innerWidth,
                                height: window.innerHeight
                            });
                        } catch (error) {
                            return JSON.stringify({
                                ready: false,
                                error: String(error)
                            });
                        }
                    """.trimIndent(),
                timeoutMs = 1_500L,
            ) { valueJson, error ->
                if (
                    preloadStates[runtimeKey] !=
                        PreloadState.PRELOADING ||
                    preloadViewHost.currentKey !=
                        runtimeKey
                ) {
                    return@evaluate
                }

                val raw =
                    runCatching {
                        when (
                            val value =
                                JSONTokener(
                                    valueJson.orEmpty()
                                ).nextValue()
                        ) {
                            is String -> value
                            null, JSONObject.NULL -> ""
                            else -> value.toString()
                        }
                    }.getOrDefault("")
                val result =
                    runCatching {
                        JSONObject(raw)
                    }.getOrNull()
                val ready =
                    error.isNullOrBlank() &&
                        result != null &&
                        result.optBoolean(
                            "ready",
                            false,
                        ) &&
                        result.optBoolean(
                            "viewport",
                            false,
                        ) &&
                        result.optBoolean(
                            "composer",
                            false,
                        )

                if (!ready) {
                    preloadStableSince.remove(runtimeKey)
                    snapshotHandler.postDelayed(
                        {
                            schedulePreloadProbe(
                                windowId = windowId,
                                provider = provider,
                                attempt = attempt + 1,
                            )
                        },
                        250L,
                    )
                    return@evaluate
                }

                val now = System.currentTimeMillis()
                val stableSince =
                    preloadStableSince
                        .getOrPut(runtimeKey) { now }
                val stableMs = now - stableSince
                if (stableMs >= 2_000L) {
                    finishPreload(
                        windowId = windowId,
                        provider = provider,
                        ready = true,
                        detail =
                            "composer-stable-" +
                                stableMs +
                                "ms",
                    )
                } else {
                    snapshotHandler.postDelayed(
                        {
                            schedulePreloadProbe(
                                windowId = windowId,
                                provider = provider,
                                attempt = attempt + 1,
                            )
                        },
                        250L,
                    )
                }
            }
        }
        preloadProbeTasks[runtimeKey] = task
        snapshotHandler.postDelayed(
            task,
            if (attempt == 0) 250L else 0L,
        )
    }

    private fun finishPreload(
        windowId: String,
        provider: ProviderSpec,
        ready: Boolean,
        detail: String,
    ) {
        val runtimeKey = key(windowId, provider)
        preloadProbeTasks
            .remove(runtimeKey)
            ?.let(snapshotHandler::removeCallbacks)
        preloadStableSince.remove(runtimeKey)

        if (preloadViewHost.currentKey == runtimeKey) {
            preloadViewHost.detachFromUi()
        }

        val session = pool.get(runtimeKey)
        if (ready && session != null) {
            preloadStates[runtimeKey] =
                PreloadState.WARM
            preloadRetryAfter.remove(runtimeKey)
            session.setFocused(false)
            session.setHighPriority(false)
            session.setActive(false)
            session.flushSessionState()
            standbyKeys.add(runtimeKey)
        } else {
            preloadStates[runtimeKey] =
                PreloadState.COLD
            preloadRetryAfter[runtimeKey] =
                System.currentTimeMillis() +
                    5L * 60L * 1_000L
            session?.let {
                it.setFocused(false)
                it.setHighPriority(false)
                it.setActive(false)
                it.flushSessionState()
            }
        }

        DiagnosticLogger.recordBridgeTrace(
            stage =
                if (ready) {
                    "preload-ready"
                } else {
                    "preload-failed"
                },
            provider = provider.id,
            windowId = windowId,
            url = session?.currentState?.url.orEmpty(),
            detail = detail,
        )

        preloadCallbacks
            .remove(runtimeKey)
            ?.invoke(ready, detail)
    }

    private fun cancelPreloadProbe(
        runtimeKey: String,
        notify: Boolean,
        reason: String = "cancelled",
    ) {
        preloadProbeTasks
            .remove(runtimeKey)
            ?.let(snapshotHandler::removeCallbacks)
        preloadStableSince.remove(runtimeKey)
        val callback =
            preloadCallbacks.remove(runtimeKey)
        if (notify) {
            callback?.invoke(false, reason)
        }
    }

    fun detachView(
        windowId: String,
        provider: ProviderSpec,
    ) {
        val runtimeKey = key(windowId, provider)
        val detachedKey = viewHost.currentKey
        viewHost.detachFromUi()
        detachedKey?.let {
            enterStandby(it)
            scheduleWarmFreeze(it)
        }
        DiagnosticLogger.recordBridgeTrace(
            stage = "view-detach",
            provider = provider.id,
            windowId = windowId,
            url = pool.get(runtimeKey)?.currentState?.url.orEmpty(),
            detail =
                "visible=" + (detachedKey ?: "none") +
                    " session-retained",
        )
    }

    fun retainedSessionCount(): Int =
        pool.activeCount()

    fun hasLiveSession(
        windowId: String,
        provider: ProviderSpec,
    ): Boolean {
        val session = pool.get(key(windowId, provider)) ?: return false
        return session.currentState.url.isNotBlank()
    }

    fun isSessionReady(
        windowId: String,
        provider: ProviderSpec,
    ): Boolean {
        val state = pool.get(key(windowId, provider))
            ?.currentState
            ?: return false
        return state.url.isNotBlank() && !state.loading
    }

    fun isConversationRenderReady(
        window: ChatWindow,
        provider: ProviderSpec,
    ): Boolean {
        if (provider.id != "chatgpt") {
            return isSessionReady(window.id, provider)
        }
        val runtimeKey = key(window.id, provider)
        val readyUrl = renderReadyUrls[runtimeKey]
            ?: return false
        val expected =
            if (
                shouldAllowChatGptTransientProductRoute(
                    requested = window.boundUrl,
                    current = window.url,
                    provider = provider,
                )
            ) {
                window.url
            } else {
                usableProviderNavigationUrl(
                    window.boundUrl,
                    provider,
                ) ?: usableProviderNavigationUrl(
                    window.url,
                    provider,
                )
            } ?: return false
        return sameProviderPage(
            readyUrl,
            expected,
            provider,
        )
    }

    fun cachedSnapshotHtml(windowId: String): String? =
        if (tabCacheStore.isPersistent(windowId)) {
            tabCacheStore.readSnapshotHtml(windowId)
        } else {
            null
        }

    fun archiveStatus(
        windowId: String,
    ): AiTabCacheStore.ArchiveStatus =
        tabCacheStore.archiveStatus(windowId)

    fun clearConversationCache(windowId: String) {
        tabCacheStore.clearConversationContent(windowId)
        synchronized(archiveFingerprints) {
            archiveFingerprints.remove(windowId)
        }
        DiagnosticLogger.recordBridgeTrace(
            stage = "conversation-cache-cleared",
            provider = "chatgpt",
            windowId = windowId,
            url = "",
            detail = "content artifacts cleared; binding preserved",
        )
    }

    fun currentUrl(windowId: String, provider: ProviderSpec): String? =
        pool.get(key(windowId, provider))?.currentState?.url
            ?.takeIf { it.isNotBlank() }

    suspend fun resolveAuthenticatedResource(
        windowId: String,
        provider: ProviderSpec,
        url: String,
        mimeHint: String? = null,
        maxBytes: Long = 6L * 1024L * 1024L,
    ): WebRuntime.ResolvedResource? {
        val target = url.trim()
        val parsed = runCatching { Uri.parse(target) }.getOrNull()
            ?: return null

        if (parsed.scheme !in setOf("http", "https")) {
            return null
        }

        ensureLoaded(windowId, provider)
        val session = pool.get(key(windowId, provider))
            ?: return null

        val safeLimit = maxBytes.coerceIn(
            64L * 1024L,
            8L * 1024L * 1024L,
        )
        val urlJs = JSONObject.quote(target)
        val mimeJs = JSONObject.quote(mimeHint.orEmpty())

        val raw = evalRaw(
            session,
            """
                const target = $urlJs;
                const mimeHint = $mimeJs;
                const maxBytes = $safeLimit;
                try {
                    const response = await fetch(target, {
                        credentials: "include",
                        redirect: "follow",
                        cache: "default",
                    });
                    if (!response.ok) {
                        return JSON.stringify({
                            ok: false,
                            error: "http-" + response.status,
                        });
                    }

                    const blob = await response.blob();
                    if (blob.size > maxBytes) {
                        return JSON.stringify({
                            ok: false,
                            error: "too-large",
                            size: blob.size,
                            mime: blob.type || mimeHint,
                        });
                    }

                    const buffer = new Uint8Array(
                        await blob.arrayBuffer()
                    );
                    let binary = "";
                    const chunk = 0x8000;
                    for (
                        let offset = 0;
                        offset < buffer.length;
                        offset += chunk
                    ) {
                        binary += String.fromCharCode(
                            ...buffer.subarray(
                                offset,
                                Math.min(
                                    offset + chunk,
                                    buffer.length
                                )
                            )
                        );
                    }

                    let name = "";
                    try {
                        const disposition =
                            response.headers.get(
                                "content-disposition"
                            ) || "";
                        const match =
                            /filename\*?=(?:UTF-8''|")?([^";]+)/i
                                .exec(disposition);
                        if (match && match[1]) {
                            name = decodeURIComponent(
                                match[1].replace(/^"|"$/g, "")
                            );
                        }
                    } catch (_) {}
                    if (!name) {
                        try {
                            name =
                                new URL(
                                    response.url || target,
                                    location.href
                                )
                                    .pathname
                                    .split("/")
                                    .filter(Boolean)
                                    .pop() || "";
                        } catch (_) {}
                    }

                    return JSON.stringify({
                        ok: true,
                        base64: btoa(binary),
                        mime:
                            blob.type ||
                            response.headers.get("content-type") ||
                            mimeHint ||
                            "application/octet-stream",
                        size: blob.size,
                        name: name || "attachment",
                    });
                } catch (error) {
                    return JSON.stringify({
                        ok: false,
                        error: String(
                            error && (error.message || error) || error
                        ),
                    });
                }
            """.trimIndent()
        ) ?: return null

        val obj = runCatching { JSONObject(raw) }.getOrNull()
            ?: return null
        if (!obj.optBoolean("ok", false)) {
            DiagnosticLogger.d(
                "GECKO_MEDIA",
                "resolve_failed provider=" + provider.id +
                    " window=" + windowId.take(12) +
                    " error=" +
                    DiagnosticLogger.scrub(
                        obj.optString("error"),
                        180,
                    ),
            )
            return null
        }

        val encoded = obj.optString("base64")
        if (encoded.isBlank()) return null

        val bytes = runCatching {
            Base64.decode(encoded, Base64.DEFAULT)
        }.getOrNull() ?: return null

        if (bytes.size.toLong() > safeLimit) return null

        val mime = obj.optString("mime")
            .substringBefore(';')
            .trim()
            .ifBlank {
                mimeHint.orEmpty()
                    .ifBlank {
                        "application/octet-stream"
                    }
            }
        val name = obj.optString("name")
            .trim()
            .ifBlank { "attachment" }

        return withContext(Dispatchers.IO) {
            runCatching {
                val directory =
                    File(
                        context.cacheDir,
                        "ai-media",
                    ).apply {
                        mkdirs()
                    }
                cleanupMediaCache(
                    directory = directory,
                    keepBytes =
                        64L * 1024L * 1024L,
                )

                val extension =
                    MimeTypeMap.getSingleton()
                        .getExtensionFromMimeType(mime)
                        ?.takeIf {
                            it.matches(
                                Regex("[A-Za-z0-9]{1,8}")
                            )
                        }
                        ?: name
                            .substringAfterLast(
                                '.',
                                "",
                            )
                            .takeIf {
                                it.matches(
                                    Regex("[A-Za-z0-9]{1,8}")
                                )
                            }
                        ?: "bin"

                val digest =
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(
                            target.toByteArray(
                                Charsets.UTF_8
                            )
                        )
                        .take(16)
                        .joinToString("") {
                            "%02x".format(it)
                        }
                val file =
                    File(
                        directory,
                        "$digest.$extension",
                    )
                file.writeBytes(bytes)
                file.setLastModified(
                    System.currentTimeMillis()
                )

                val contentUri =
                    FileProvider.getUriForFile(
                        context,
                        context.packageName +
                            ".fileprovider",
                        file,
                    )

                WebRuntime.ResolvedResource(
                    uri = contentUri.toString(),
                    mimeType = mime,
                    sizeBytes =
                        obj.optLong(
                            "size",
                            bytes.size.toLong(),
                        ),
                    name = name,
                )
            }.onFailure {
                DiagnosticLogger.w(
                    "GECKO_MEDIA",
                    "cache_failed type=" +
                        it.javaClass.simpleName,
                )
            }.getOrNull()
        }
    }

    private fun cleanupMediaCache(
        directory: File,
        keepBytes: Long,
    ) {
        val files =
            directory.listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending {
                    it.lastModified()
                }
                .orEmpty()
        var retained = 0L
        files.forEach { file ->
            retained += file.length()
            if (retained > keepBytes) {
                runCatching { file.delete() }
            }
        }
    }

    /**
     * Low-priority warm-up for a cached tab. This creates/restores the Gecko
     * session and starts the bound ChatGPT page in the background without
     * attaching it to the visible GeckoView or changing the cache UI.
     */
    fun handleTrimMemory(level: Int) {
        // UI_HIDDEN (20) is a normal app-background transition and must not
        // destroy browser tabs. Only react to explicit running-low pressure
        // (10..15) or severe background pressure (60+).
        val runningPressure =
            level in 10..15
        val severeBackgroundPressure =
            level >= 60
        if (
            !runningPressure &&
            !severeBackgroundPressure
        ) {
            return
        }

        detachPreloadView()

        val visibleKey = viewHost.currentKey
        sessionOwners.keys
            .filter { it != visibleKey }
            .forEach { runtimeKey ->
                pool.get(runtimeKey)?.let { session ->
                    session.setFocused(false)
                    session.setHighPriority(false)
                    session.setActive(false)
                    session.flushSessionState()
                }
            }

        if (severeBackgroundPressure) {
            val keepWarm =
                if (level >= 80) {
                    emptySet()
                } else {
                    sessionRecency
                        .toList()
                        .asReversed()
                        .asSequence()
                        .filter {
                            it != visibleKey
                        }
                        .take(2)
                        .toSet()
                }

            sessionRecency
                .toList()
                .filter { runtimeKey ->
                    runtimeKey != visibleKey &&
                        runtimeKey !in keepWarm
                }
                .forEach { runtimeKey ->
                    val windowId =
                        sessionOwners[runtimeKey]
                            ?.first
                    if (
                        windowId != null &&
                        tabCacheStore.isPersistent(
                            windowId
                        )
                    ) {
                        freezeBoundSession(
                            runtimeKey = runtimeKey,
                            reason =
                                "memory-pressure-" +
                                    level,
                        )
                    } else {
                        evictHotSession(runtimeKey)
                    }
                }
        }

        DiagnosticLogger.i(
            "GECKO_MEMORY",
            "trim level=" +
                level +
                " retained=" +
                pool.activeCount() +
                " visible=" +
                visibleKey.orEmpty(),
        )
    }

    fun freezeStaleBoundSessions(
        windows: List<ChatWindow>,
        activeWindowId: String,
        inactiveMs: Long = 24L * 60L * 60L * 1_000L,
    ) {
        val cutoff = System.currentTimeMillis() -
            inactiveMs.coerceAtLeast(60_000L)

        windows
            .asSequence()
            .filter {
                it.id != activeWindowId &&
                    !it.boundUrl.isNullOrBlank() &&
                    it.lastActiveAt in 1 until cutoff
            }
            .forEach { window ->
                val provider =
                    ProviderCatalog.byId(window.providerId)
                val runtimeKey = key(window.id, provider)
                if (pool.get(runtimeKey) == null) {
                    return@forEach
                }

                freezeBoundSession(
                    runtimeKey = runtimeKey,
                    reason = "inactive-24h",
                )
            }
    }

    fun prewarm(
        window: ChatWindow,
        provider: ProviderSpec,
    ) {
        // Legacy API retained for compatibility. Creating/loading a ChatGPT
        // GeckoSession without a real attached viewport can leave the SPA
        // partially hydrated (notably without the composer), so browser-first
        // AIUI only preloads through attachPreload().
        DiagnosticLogger.recordBridgeTrace(
            stage = "detached-prewarm-skipped",
            provider = provider.id,
            windowId = window.id,
            url = window.boundUrl ?: window.url,
            detail = "use real-viewport preload host",
        )
    }

    /**
     * Start one event-driven ChatGPT live hand-off.
     *
     * Native never polls the page. The request is remembered until the page
     * RPC bridge is ready; page-side MutationObserver waits for a real
     * conversation turn, moves the live document to the latest content, then
     * emits exactly one ai-live-ready event. A short native timeout is only a
     * safety valve so the UI can never remain covered forever.
     */
    fun requestLiveHandoff(
        window: ChatWindow,
        provider: ProviderSpec,
        timeoutMs: Long = 4_000L,
        callback: (Boolean, String) -> Unit,
    ) {
        if (provider.id != "chatgpt") {
            callback(true, "unsupported-provider")
            return
        }

        val runtimeKey = key(window.id, provider)
        cancelLiveHandoff(runtimeKey)

        val token =
            System.currentTimeMillis().toString() + "-" +
                window.id.take(8)
        liveHandoffTokens[runtimeKey] = token
        liveHandoffCallbacks[runtimeKey] = callback

        val timeout = Runnable {
            if (liveHandoffCallbacks.containsKey(runtimeKey)) {
                finishLiveHandoff(
                    windowId = window.id,
                    provider = provider,
                    ready = false,
                    detail = "timeout",
                )
            }
        }
        liveHandoffTimeouts[runtimeKey] = timeout
        snapshotHandler.postDelayed(
            timeout,
            timeoutMs.coerceIn(1_500L, 10_000L),
        )

        val preferred =
            usableProviderNavigationUrl(
                window.boundUrl,
                provider,
            ) ?: usableProviderNavigationUrl(
                window.url,
                provider,
            )
        val session = pool.get(runtimeKey) ?: obtain(
            windowId = window.id,
            provider = provider,
            preferredUrl = preferred,
        )
        touchSession(runtimeKey)
        trimHotSessions(protectedKey = runtimeKey)

        DiagnosticLogger.recordBridgeTrace(
            stage = "live-handoff-request",
            provider = provider.id,
            windowId = window.id,
            url = session.currentState.url.ifBlank {
                preferred.orEmpty()
            },
            detail = "timeoutMs=$timeoutMs",
        )

        // If the bridge is already live this succeeds immediately. If it is
        // still reconnecting, onPageReady will install the same pending
        // request once, without a native retry loop.
        if (
            session.currentState.url.isNotBlank() &&
            !session.currentState.loading
        ) {
            installLiveHandoffObserver(
                windowId = window.id,
                provider = provider,
            )
        }
    }

    private fun installLiveHandoffObserver(
        windowId: String,
        provider: ProviderSpec,
    ) {
        val runtimeKey = key(windowId, provider)
        if (!liveHandoffCallbacks.containsKey(runtimeKey)) return
        val token = liveHandoffTokens[runtimeKey] ?: return
        val session = pool.get(runtimeKey) ?: return
        val tokenJs = JSONObject.quote(token)

        session.evaluate(
            """
                try {
                    const emit =
                        globalThis.__YBROWSER_RPC_EMIT__;
                    if (typeof emit !== "function") {
                        return "rpc-unavailable";
                    }

                    const token = $tokenJs;
                    const previous =
                        window.__AIHUB_LIVE_HANDOFF__;
                    try { previous?.disconnect?.(); } catch (_) {}

                    let observer = null;
                    let settleTimer = 0;
                    let finishTimer = 0;
                    let readySent = false;
                    const timers = [];

                    const conversationPath =
                        (location.pathname || "")
                            .includes("/c/");

                    const collectTurns = () => {
                        const unique = [];
                        const seen = new Set();
                        const add = (node) => {
                            if (!node) return;
                            const canonical =
                                node.closest?.(
                                    "[data-testid^='conversation-turn']"
                                ) || node;
                            if (seen.has(canonical)) return;
                            seen.add(canonical);
                            unique.push(canonical);
                        };

                        document.querySelectorAll(
                            "[data-testid^='conversation-turn']"
                        ).forEach(add);

                        if (!unique.length) {
                            const cfg =
                                window.__AIHUB_CONFIG__ || {};
                            (cfg.turnSelectors || [])
                                .forEach((selector) => {
                                    try {
                                        document
                                            .querySelectorAll(selector)
                                            .forEach(add);
                                    } catch (_) {}
                                });
                        }
                        return unique;
                    };

                    const composer = () =>
                        document.querySelector(
                            "#thread-bottom-container"
                        ) ||
                        document.querySelector(
                            "[data-testid='composer-root']"
                        ) ||
                        document.querySelector(
                            "#prompt-textarea"
                        ) ||
                        document.querySelector(
                            "#mobile-composer-prompt"
                        );

                    const scrollRootFor = (node) => {
                        let current =
                            node?.parentElement || null;
                        for (
                            let depth = 0;
                            current && depth < 18;
                            depth++,
                            current = current.parentElement
                        ) {
                            try {
                                const style =
                                    getComputedStyle(current);
                                if (
                                    /(auto|scroll|overlay)/i.test(
                                        style.overflowY || ""
                                    ) &&
                                    current.scrollHeight >
                                        current.clientHeight + 24
                                ) {
                                    return current;
                                }
                            } catch (_) {}
                        }
                        return (
                            document.scrollingElement ||
                            document.documentElement ||
                            document.body
                        );
                    };

                    const move = () => {
                        const turns = collectTurns();
                        const latest =
                            turns[turns.length - 1] ||
                            composer();
                        if (!latest) return turns.length;

                        try {
                            latest.scrollIntoView({
                                block: "end",
                                inline: "nearest",
                                behavior: "auto"
                            });
                        } catch (_) {}

                        try {
                            const root =
                                scrollRootFor(latest);
                            if (
                                root ===
                                    document.documentElement ||
                                root === document.body ||
                                root ===
                                    document.scrollingElement
                            ) {
                                window.scrollTo(
                                    0,
                                    Math.max(
                                        document.body
                                            ?.scrollHeight || 0,
                                        document
                                            .documentElement
                                            ?.scrollHeight || 0
                                    )
                                );
                            } else if (root) {
                                root.scrollTop =
                                    root.scrollHeight;
                            }
                        } catch (_) {}

                        return turns.length;
                    };

                    const disconnect = () => {
                        clearTimeout(settleTimer);
                        clearTimeout(finishTimer);
                        timers.forEach((id) =>
                            clearTimeout(id)
                        );
                        try {
                            observer?.disconnect?.();
                        } catch (_) {}
                        if (
                            window.__AIHUB_LIVE_HANDOFF__
                                ?.token === token
                        ) {
                            delete window
                                .__AIHUB_LIVE_HANDOFF__;
                        }
                    };

                    const emitReady = (reason) => {
                        if (readySent) return;
                        const turns = collectTurns();
                        if (
                            conversationPath &&
                            !turns.length
                        ) {
                            return;
                        }
                        if (
                            !turns.length &&
                            !composer()
                        ) {
                            return;
                        }

                        move();
                        readySent = true;

                        try {
                            emit(
                                "ai-live-ready",
                                JSON.stringify({
                                    token,
                                    url: location.href,
                                    turns: turns.length,
                                    reason:
                                        String(reason || "")
                                })
                            );
                        } catch (_) {}

                        // Keep following the page for a short time after
                        // Native reveals it; late layout shifts must not
                        // bounce the user back toward the top.
                        finishTimer = setTimeout(
                            disconnect,
                            1200
                        );
                    };

                    const check = (reason) => {
                        const turns = collectTurns();
                        if (
                            conversationPath &&
                            !turns.length
                        ) {
                            return;
                        }
                        if (
                            !turns.length &&
                            !composer()
                        ) {
                            return;
                        }

                        move();
                        if (!settleTimer) {
                            settleTimer = setTimeout(
                                () => {
                                    settleTimer = 0;
                                    emitReady(reason);
                                },
                                220
                            );
                        }
                    };

                    observer = new MutationObserver(
                        () => {
                            if (readySent) {
                                move();
                            } else {
                                check("mutation");
                            }
                        }
                    );
                    observer.observe(
                        document.documentElement ||
                            document.body,
                        {
                            subtree: true,
                            childList: true,
                            characterData: true
                        }
                    );

                    [0, 80, 220, 500, 900].forEach(
                        (ms) => {
                            timers.push(
                                setTimeout(
                                    () => check(
                                        ms === 0
                                            ? "install"
                                            : "timer-" + ms
                                    ),
                                    ms
                                )
                            );
                        }
                    );

                    window.__AIHUB_LIVE_HANDOFF__ = {
                        token,
                        disconnect
                    };

                    return "observer-installed";
                } catch (error) {
                    return "error:" + String(error);
                }
            """.trimIndent()
        ) { value, error ->
            DiagnosticLogger.recordBridgeTrace(
                stage =
                    if (error.isNullOrBlank()) {
                        "live-handoff-observer"
                    } else {
                        "live-handoff-observer-failed"
                    },
                provider = provider.id,
                windowId = windowId,
                url = session.currentState.url,
                detail = DiagnosticLogger.scrub(
                    error?.takeIf { it.isNotBlank() }
                        ?: value.orEmpty(),
                    240,
                ),
            )
        }
    }

    private fun handleLiveHandoffReady(
        windowId: String,
        provider: ProviderSpec,
        payload: String,
    ) {
        val runtimeKey = key(windowId, provider)
        val obj = runCatching {
            JSONObject(payload)
        }.getOrNull() ?: return
        val expected = liveHandoffTokens[runtimeKey]
            ?: return
        val received = obj.optString("token")
        if (received != expected) {
            DiagnosticLogger.recordBridgeTrace(
                stage = "live-handoff-stale",
                provider = provider.id,
                windowId = windowId,
                url = obj.optString("url"),
                detail = "ignored stale token",
            )
            return
        }

        val readyUrl = obj.optString("url")
            .ifBlank {
                pool.get(runtimeKey)
                    ?.currentState
                    ?.url
                    .orEmpty()
            }
        if (
            obj.optInt("turns", 0) > 0 &&
            readyUrl.isNotBlank()
        ) {
            renderReadyUrls[runtimeKey] =
                readyUrl
        }

        finishLiveHandoff(
            windowId = windowId,
            provider = provider,
            ready = true,
            detail =
                "turns=" + obj.optInt("turns", 0) +
                    " reason=" +
                    obj.optString("reason"),
        )
    }

    private fun finishLiveHandoff(
        windowId: String,
        provider: ProviderSpec,
        ready: Boolean,
        detail: String,
    ) {
        val runtimeKey = key(windowId, provider)
        liveHandoffTimeouts
            .remove(runtimeKey)
            ?.let(snapshotHandler::removeCallbacks)
        liveHandoffTokens.remove(runtimeKey)
        val callback =
            liveHandoffCallbacks.remove(runtimeKey)
                ?: return

        DiagnosticLogger.recordBridgeTrace(
            stage =
                if (ready) {
                    "live-handoff-ready"
                } else {
                    "live-handoff-fallback"
                },
            provider = provider.id,
            windowId = windowId,
            url = pool.get(runtimeKey)
                ?.currentState
                ?.url
                .orEmpty(),
            detail = detail,
        )
        callback(ready, detail)
    }

    private fun cancelLiveHandoff(
        runtimeKey: String,
    ) {
        liveHandoffTimeouts
            .remove(runtimeKey)
            ?.let(snapshotHandler::removeCallbacks)
        liveHandoffTokens.remove(runtimeKey)
        liveHandoffCallbacks.remove(runtimeKey)
    }

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


    fun reloadPage(
        window: ChatWindow,
        provider: ProviderSpec,
    ) {
        val runtimeKey = key(window.id, provider)
        val preferred =
            usableProviderNavigationUrl(
                window.boundUrl,
                provider,
            ) ?: usableProviderNavigationUrl(
                window.url,
                provider,
            )
        val session = pool.get(runtimeKey) ?: obtain(
            windowId = window.id,
            provider = provider,
            preferredUrl = preferred,
        )
        val current =
            preferred
                ?: session.currentState.url
                    .takeIf { it.isNotBlank() }
                ?: preferredUrls[runtimeKey]
                ?: provider.homeUrl
        if (preferred != null) {
            initialNavigationUrls[runtimeKey] =
                preferred
            preferredUrls[runtimeKey] =
                preferred
        }
        injectedKeys.remove(runtimeKey)
        DiagnosticLogger.recordBridgeTrace(
            stage = "manual-reload",
            provider = provider.id,
            windowId = window.id,
            url = current,
            detail =
                if (preferred != null) {
                    "bound-page-authoritative"
                } else {
                    "explicit user refresh"
                },
        )
        session.load(current)
    }

    fun ensurePreferredPage(
        window: ChatWindow,
        provider: ProviderSpec,
    ) {
        val preferred =
            usableProviderNavigationUrl(
                window.boundUrl,
                provider,
            ) ?: usableProviderNavigationUrl(
                window.url,
                provider,
            )
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

        uris.forEach { uri ->
            persistReadPermission(uri)
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
        prompt: String,
    ): WebRuntime.SendResult {
        ensureLoaded(windowId, provider)
        val runtimeKey = key(windowId, provider)
        val submitStartedAt =
            System.currentTimeMillis()
        val expectedConversationId =
            if (provider.id == "chatgpt") {
                sequenceOf(
                    preferredUrls[runtimeKey],
                    pool.get(runtimeKey)
                        ?.currentState
                        ?.url,
                )
                    .mapNotNull(::chatGptConversationId)
                    .firstOrNull {
                        !it.startsWith(
                            "WEB:",
                            ignoreCase = true,
                        )
                    }
            } else {
                null
            }

        if (provider.id == "chatgpt") {
            val promptSha256 =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                        prompt.toByteArray(
                            Charsets.UTF_8,
                        )
                    )
                    .joinToString("") {
                        "%02x".format(it)
                    }
            pendingWriteExpectations[runtimeKey] =
                PendingWriteExpectation(
                    promptSha256 = promptSha256,
                    conversationId =
                        expectedConversationId,
                    startedAt = submitStartedAt,
                )
            correlatedWriteAcks.remove(runtimeKey)
            conversationWriteAcks.remove(runtimeKey)
        }

        try {
            val result = call(
                windowId,
                provider,
                "send",
                JSONObject.quote(prompt),
            )
            DiagnosticLogger.i(
                "GECKO_JS",
                "adapter_send provider=${provider.id} " +
                    "window=${windowId.take(12)} " +
                    "chars=${prompt.length} " +
                    "result=${result ?: "null"}",
            )

            if (result == "ok") {
                return WebRuntime.SendResult(
                    state =
                        WebRuntime.SendState.CONFIRMED,
                    reason = "page-confirmed",
                    conversationId =
                        expectedConversationId,
                )
            }
            if (
                result != "verify" &&
                result != "queued"
            ) {
                return WebRuntime.SendResult(
                    state =
                        WebRuntime.SendState.FAILED,
                    reason =
                        result ?: "send-unavailable",
                )
            }

            // One page-owned write has already been delegated. Everything
            // below is observation only. Never call send() again.
            val observationChecks =
                if (result == "queued") 72 else 24
            var lastSubmissionStatus = ""
            var delegatedWriteObserved = false

            repeat(observationChecks) { check ->
                delay(220)

                if (provider.id == "chatgpt") {
                    val correlated =
                        correlatedWriteAcks[runtimeKey]
                    if (
                        correlated != null &&
                        correlated.observedAt >=
                            submitStartedAt
                    ) {
                        DiagnosticLogger.recordBridgeTrace(
                            stage =
                                "submit-request-correlated",
                            provider = provider.id,
                            windowId = windowId,
                            url =
                                pool.get(runtimeKey)
                                    ?.currentState
                                    ?.url
                                    .orEmpty(),
                            detail =
                                "messageId=" +
                                    correlated
                                        .userMessageId
                                        .take(96) +
                                    " conversationId=" +
                                    correlated
                                        .conversationId
                                        .orEmpty()
                                        .take(96),
                        )
                        return WebRuntime.SendResult(
                            state =
                                WebRuntime.SendState
                                    .CONFIRMED,
                            reason =
                                "request-bound-product-write",
                            conversationId =
                                correlated
                                    .conversationId
                                    ?: expectedConversationId,
                            userMessageId =
                                correlated.userMessageId,
                        )
                    }

                    val networkAckAt =
                        conversationWriteAcks[
                            runtimeKey
                        ] ?: 0L
                    if (
                        networkAckAt >=
                        submitStartedAt
                    ) {
                        delegatedWriteObserved = true
                    }
                }

                val domAcknowledged =
                    call(
                        windowId,
                        provider,
                        "submissionAcknowledged",
                    ) == "true"

                if (provider.id != "chatgpt") {
                    if (domAcknowledged) {
                        return WebRuntime.SendResult(
                            state =
                                WebRuntime.SendState
                                    .CONFIRMED,
                            reason =
                                "provider-dom-ack",
                        )
                    }
                } else if (domAcknowledged) {
                    delegatedWriteObserved = true
                }

                if (
                    check == 0 ||
                    check == 7 ||
                    check == 23 ||
                    check ==
                        observationChecks - 1
                ) {
                    lastSubmissionStatus =
                        call(
                            windowId,
                            provider,
                            "submissionStatus",
                        ).orEmpty()
                    if (
                        lastSubmissionStatus.contains(
                            "attachment-button-timeout"
                        )
                    ) {
                        return WebRuntime.SendResult(
                            state =
                                if (
                                    delegatedWriteObserved
                                ) {
                                    WebRuntime.SendState
                                        .AMBIGUOUS
                                } else {
                                    WebRuntime.SendState
                                        .FAILED
                                },
                            reason =
                                "attachment-button-timeout",
                            conversationId =
                                expectedConversationId,
                        )
                    }
                }
            }

            DiagnosticLogger.w(
                "GECKO_JS",
                "adapter_send_unconfirmed provider=" +
                    provider.id +
                    " window=" +
                    windowId.take(12) +
                    " initial=" +
                    result +
                    " delegated=" +
                    delegatedWriteObserved +
                    " status=" +
                    DiagnosticLogger.scrub(
                        lastSubmissionStatus,
                        260,
                    ),
            )

            return WebRuntime.SendResult(
                state =
                    if (
                        provider.id == "chatgpt" &&
                        delegatedWriteObserved
                    ) {
                        WebRuntime.SendState.AMBIGUOUS
                    } else {
                        WebRuntime.SendState.FAILED
                    },
                reason =
                    if (delegatedWriteObserved) {
                        "delegated-write-unresolved"
                    } else {
                        "submission-not-observed"
                    },
                conversationId =
                    expectedConversationId,
            )
        } finally {
            pendingWriteExpectations.remove(runtimeKey)
            correlatedWriteAcks.remove(runtimeKey)
        }
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

    /**
     * CWA-style canonical observation plane for ordinary ChatGPT chats.
     *
     * The protected write remains page-owned. Incremental DOM/SSE/network
     * observations are never promoted to finality here. Instead we read the
     * product-owned canonical conversation surface with the current authenticated
     * Gecko session and hand the body to the ChatGPT product provider.
     *
     * The current endpoint + legacy 404 compatibility path mirrors CWA's
     * browser-owned canonical-read v2 behavior. This is one read plane, not a
     * fallback write transport.
     */
    suspend fun canonicalConversationSnapshot(
        windowId: String,
        provider: ProviderSpec,
        preferredUrl: String? = null,
        conversationId: String? = null,
        includeAllPages: Boolean = false,
    ): WebRuntime.ConversationSnapshot? {
        if (provider.id != "chatgpt") return null

        val session = obtain(
            windowId = windowId,
            provider = provider,
            preferredUrl = preferredUrl,
        )
        ensureLoaded(windowId, provider)

        val pageUrl =
            session.currentState.url
                .ifBlank { preferredUrl.orEmpty() }
        val canonicalConversationId =
            conversationId
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        !it.startsWith(
                            "WEB:",
                            ignoreCase = true,
                        )
                }
                ?: chatGptConversationId(pageUrl)
                    ?.takeUnless {
                        it.startsWith(
                            "WEB:",
                            ignoreCase = true,
                        )
                    }
                ?: chatGptConversationId(
                    preferredUrl
                )?.takeUnless {
                    it.startsWith(
                        "WEB:",
                        ignoreCase = true,
                    )
                }
                ?: return null
        val conversationJs =
            JSONObject.quote(
                canonicalConversationId
            )
        val canonicalSource =
            loader.cwaCanonicalReadScript()
        val productTimeoutMs =
            if (includeAllPages) {
                CANONICAL_HISTORY_PRODUCT_TIMEOUT_MS
            } else {
                CANONICAL_READ_PRODUCT_TIMEOUT_MS
            }
        val rpcTimeoutMs =
            productTimeoutMs + CANONICAL_RPC_RETURN_RESERVE_MS

        repeat(CANONICAL_TIMEOUT_MAX_ATTEMPTS) { attempt ->
            val raw = evalRaw(
                session,
                """
                    try {
                        $canonicalSource
                        const cwa =
                            window.__YBROWSER_CWA__;
                        if (
                            !cwa ||
                            typeof cwa.canonicalRead !==
                                "function"
                        ) {
                            return {
                                ok: false,
                                status: 0,
                                reason:
                                    "CANONICAL_READ_RUNTIME_UNAVAILABLE"
                            };
                        }

                        return await cwa.canonicalRead(
                            $conversationJs,
                            ${includeAllPages},
                            $productTimeoutMs
                        );
                    } catch (error) {
                        return {
                            ok: false,
                            status: 0,
                            reason:
                                "CANONICAL_READ_RUNTIME_ERROR"
                        };
                    }
                """.trimIndent(),
                timeoutMs = rpcTimeoutMs,
            )

            if (raw == null) {
                if (
                    attempt + 1 <
                    CANONICAL_TIMEOUT_MAX_ATTEMPTS
                ) {
                    DiagnosticLogger.recordBridgeTrace(
                        stage = "canonical-read-retry",
                        provider = provider.id,
                        windowId = windowId,
                        url = pageUrl,
                        detail =
                            "reason=bridge-timeout attempt=" +
                                (attempt + 1),
                    )
                    delay(
                        CANONICAL_TIMEOUT_RETRY_DELAY_MS
                    )
                    return@repeat
                }
                return null
            }

            val envelope =
                runCatching { JSONObject(raw) }
                    .getOrNull()
                    ?: return null

            if (!envelope.optBoolean("ok", false)) {
                val reason =
                    envelope.optString("reason")
                        .ifBlank {
                            "CANONICAL_READ_FAILED"
                        }
                val retryableTimeout =
                    reason == "CANONICAL_READ_TIMEOUT"

                DiagnosticLogger.recordBridgeTrace(
                    stage =
                        if (retryableTimeout) {
                            "canonical-read-timeout"
                        } else {
                            "canonical-read-failed"
                        },
                    provider = provider.id,
                    windowId = windowId,
                    url = pageUrl,
                    detail =
                        reason.take(220) +
                            " status=" +
                            envelope.optInt("status", 0) +
                            " attempt=" +
                            (attempt + 1),
                )

                if (
                    retryableTimeout &&
                    attempt + 1 <
                    CANONICAL_TIMEOUT_MAX_ATTEMPTS
                ) {
                    delay(
                        CANONICAL_TIMEOUT_RETRY_DELAY_MS
                    )
                    return@repeat
                }
                return null
            }

            val body =
                envelope.optJSONObject("payload")
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?: envelope.optString("body")
                        .takeIf { it.isNotBlank() }
                    ?: return null
            val endpoint =
                envelope.optString("endpoint")
            if (endpoint.isBlank()) {
                return null
            }

            val canonical =
                ChatGptProductProvider
                    .parseCanonicalRead(
                        provider = provider,
                        body = body,
                        endpoint = endpoint,
                        pageUrl = pageUrl,
                    )
                    ?: return null

            DiagnosticLogger.recordBridgeTrace(
                stage = "canonical-read",
                provider = provider.id,
                windowId = windowId,
                url = pageUrl,
                detail =
                    "messages=" +
                        canonical.messages.size +
                        " source=" +
                        canonical.source +
                        " allPages=" +
                        includeAllPages +
                        " attempt=" +
                        (attempt + 1),
                candidateCount =
                    canonical.candidateCount,
                messageCount =
                    canonical.messages.size,
            )
            return canonical
        }

        return null
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
        cancelLiveHandoff(runtimeKey)
        networkAssemblies.keys.removeAll { it.startsWith("$runtimeKey|") }
        networkFingerprints.removeAll { it.startsWith("$runtimeKey|") }
        activeStreamOwnedRequests.removeAll {
            it.startsWith("$runtimeKey|")
        }
        conversationWriteAcks.remove(runtimeKey)
        pendingWriteExpectations.remove(runtimeKey)
        correlatedWriteAcks.remove(runtimeKey)
        sessionRecency.remove(runtimeKey)
        standbyKeys.remove(runtimeKey)
        bindingRefocusKeys.remove(runtimeKey)
        renderReadyUrls.remove(runtimeKey)
        preloadStates.remove(runtimeKey)
        preloadRetryAfter.remove(runtimeKey)
        cancelPreloadProbe(
            runtimeKey = runtimeKey,
            notify = false,
        )
        preloadViewHost.releaseIfBound(runtimeKey)
        synchronized(archiveFingerprints) {
            archiveFingerprints.remove(windowId)
        }
        snapshotTasks.remove(runtimeKey)?.let(snapshotHandler::removeCallbacks)
        freezeTasks.remove(runtimeKey)?.let(snapshotHandler::removeCallbacks)
        sessionOwners.remove(runtimeKey)
        viewHost.releaseIfBound(runtimeKey)
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
        liveHandoffCallbacks.keys
            .toList()
            .forEach(::cancelLiveHandoff)

        pendingFilePrompt?.complete(null)
        pendingFilePrompt = null
        pendingFileWindowId = null
        pendingFileProvider = null

        sessionOwners.values.forEach { (windowId, provider) ->
            captureSnapshotNow(windowId, provider)
        }

        freezeTasks.values.forEach(snapshotHandler::removeCallbacks)
        freezeTasks.clear()

        // Detach the Activity surface, then move persistent ChatGPT
        // product runtimes into low-priority Standby instead of suspending
        // them. Native chat may still have an in-flight product-owned send,
        // and reopening must find the same live session. Transient/non-ChatGPT
        // sessions are inactivated normally.
        detachPreloadView()
        viewHost.detachFromUi()
        sessionOwners.forEach { (runtimeKey, owner) ->
            val (windowId, provider) = owner
            val persistentChatGpt =
                provider.id == "chatgpt" &&
                    tabCacheStore.isPersistent(windowId)

            if (persistentChatGpt) {
                scheduleWarmFreeze(runtimeKey)
            } else {
                pool.get(runtimeKey)?.let { session ->
                    session.setFocused(false)
                    session.setHighPriority(false)
                    session.setActive(false)
                }
            }
        }
        pool.flushAllSessionStates()

        sessionRecency.lastOrNull()?.let { newest ->
            trimHotSessions(protectedKey = newest)
        }

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
        networkFingerprints.clear()
        activeStreamOwnedRequests.clear()
        conversationWriteAcks.clear()
        pendingWriteExpectations.clear()
        correlatedWriteAcks.clear()
        sessionRecency.clear()
        standbyKeys.clear()
        bindingRefocusKeys.clear()
        renderReadyUrls.clear()
        preloadProbeTasks.values
            .forEach(snapshotHandler::removeCallbacks)
        preloadProbeTasks.clear()
        preloadCallbacks.clear()
        preloadStableSince.clear()
        preloadRetryAfter.clear()
        preloadStates.clear()
        preloadViewHost.detachFromUi()
        synchronized(archiveFingerprints) {
            archiveFingerprints.clear()
        }
        liveHandoffCallbacks.keys
            .toList()
            .forEach(::cancelLiveHandoff)
        snapshotTasks.values.forEach(snapshotHandler::removeCallbacks)
        snapshotTasks.clear()
        freezeTasks.values.forEach(snapshotHandler::removeCallbacks)
        freezeTasks.clear()
        sessionOwners.clear()
        chatPresentationKeys.clear()
        injectedKeys.clear()
        preferredUrls.clear()
        initialNavigationUrls.clear()
        viewHost.detachFromUi()
        pool.closeAll()
    }

    private fun obtain(
        windowId: String,
        provider: ProviderSpec,
        preferredUrl: String? = null,
    ): GeckoCoreSession {
        val runtimeKey = key(windowId, provider)
        val requestedUrl =
            usableProviderNavigationUrl(
                preferredUrl,
                provider,
            )
        val target = requestedUrl
            ?: usableProviderNavigationUrl(
                preferredUrls[runtimeKey],
                provider,
            )
            ?: provider.homeUrl
        val existing = pool.get(runtimeKey)
        val existed = existing != null

        sessionOwners[runtimeKey] = windowId to provider

        val callbacks = GeckoCoreCallbacks(
            onState = stateUpdate@{ state ->
                val url = state.url
                val requestedPage =
                    initialNavigationUrls[runtimeKey]

                renderReadyUrls[runtimeKey]
                    ?.takeIf {
                        url.isNotBlank() &&
                            !sameProviderPage(
                                it,
                                url,
                                provider,
                            )
                    }
                    ?.let {
                        renderReadyUrls.remove(runtimeKey)
                    }

                val allowTransientProductRoute =
                    requestedPage != null &&
                        shouldAllowChatGptTransientProductRoute(
                            requested = requestedPage,
                            current = url,
                            provider = provider,
                        )
                val adoptProductRedirect =
                    requestedPage != null &&
                        shouldAdoptChatGptProductRedirect(
                            requested = requestedPage,
                            current = url,
                            provider = provider,
                        )

                if (adoptProductRedirect) {
                    preferredUrls[runtimeKey] = url
                    bindingRefocusKeys.remove(runtimeKey)
                    initialNavigationUrls.remove(runtimeKey)
                    DiagnosticLogger.recordBridgeTrace(
                        stage = "product-redirect-adopted",
                        provider = provider.id,
                        windowId = windowId,
                        url = url,
                        detail =
                            "requested=" +
                                requestedPage.orEmpty().take(180),
                    )
                }

                if (
                    requestedPage != null &&
                    !allowTransientProductRoute &&
                    !adoptProductRedirect &&
                    url.isNotBlank() &&
                    url != "about:blank" &&
                    sameProviderOrigin(url, provider) &&
                    !sameProviderPage(
                        url,
                        requestedPage,
                        provider,
                    )
                ) {
                    if (bindingRefocusKeys.add(runtimeKey)) {
                        preferredUrls[runtimeKey] =
                            requestedPage
                        injectedKeys.remove(runtimeKey)
                        DiagnosticLogger.recordBridgeTrace(
                            stage = "state-refocus",
                            provider = provider.id,
                            windowId = windowId,
                            url = requestedPage,
                            detail =
                                "state=" + url +
                                    " bound-page-authoritative",
                        )
                        pool.get(runtimeKey)
                            ?.load(requestedPage)
                    }
                    return@stateUpdate
                }

                if (
                    adoptProductRedirect ||
                    (
                        requestedPage != null &&
                            url.isNotBlank() &&
                            sameProviderPage(
                                url,
                                requestedPage,
                                provider,
                            )
                        )
                ) {
                    bindingRefocusKeys.remove(runtimeKey)
                    // The requested page only guards cold restore. Once the
                    // live document reaches it (or the product redirects to
                    // its canonical route), normal in-tab navigation belongs
                    // to the browser session and must remain untouched.
                    initialNavigationUrls.remove(runtimeKey)
                }

                if (
                    url.isNotBlank() &&
                    sameProviderOrigin(url, provider)
                ) {
                    preferredUrls[runtimeKey] = url
                    pageChangeListener?.invoke(
                        windowId,
                        provider,
                        url,
                    )
                }
            },
            onSessionState = { value ->
                tabCacheStore.writeSessionState(
                    windowId = windowId,
                    value = value,
                )
            },
            onPageReady = pageReady@{
                injectedKeys.remove(runtimeKey)
                val currentUrl = pool.get(runtimeKey)
                    ?.currentState
                    ?.url
                    .orEmpty()

                if (
                    currentUrl.isBlank() ||
                    currentUrl == "about:blank" ||
                    currentUrl.startsWith("about:srcdoc") ||
                    !sameProviderOrigin(currentUrl, provider)
                ) {
                    DiagnosticLogger.recordBridgeTrace(
                        stage = "page-ready-skip",
                        provider = provider.id,
                        windowId = windowId,
                        url = currentUrl,
                        detail = "non-provider/transient document",
                    )
                    return@pageReady
                }

                val requestedPage =
                    initialNavigationUrls[runtimeKey]
                val allowTransientProductRoute =
                    requestedPage != null &&
                        shouldAllowChatGptTransientProductRoute(
                            requested = requestedPage,
                            current = currentUrl,
                            provider = provider,
                        )
                val adoptProductRedirect =
                    requestedPage != null &&
                        shouldAdoptChatGptProductRedirect(
                            requested = requestedPage,
                            current = currentUrl,
                            provider = provider,
                        )
                if (adoptProductRedirect) {
                    preferredUrls[runtimeKey] = currentUrl
                    bindingRefocusKeys.remove(runtimeKey)
                    initialNavigationUrls.remove(runtimeKey)
                    DiagnosticLogger.recordBridgeTrace(
                        stage = "restore-product-redirect-adopted",
                        provider = provider.id,
                        windowId = windowId,
                        url = currentUrl,
                        detail =
                            "requested=" +
                                requestedPage.orEmpty().take(180),
                    )
                }
                if (
                    requestedPage != null &&
                    !allowTransientProductRoute &&
                    !adoptProductRedirect &&
                    currentUrl.isNotBlank() &&
                    currentUrl != "about:blank" &&
                    sameProviderOrigin(currentUrl, provider) &&
                    !sameProviderPage(
                        currentUrl,
                        requestedPage,
                        provider,
                    )
                ) {
                    DiagnosticLogger.recordBridgeTrace(
                        stage = "restore-refocus",
                        provider = provider.id,
                        windowId = windowId,
                        url = requestedPage,
                        detail =
                            "restored=" + currentUrl +
                                " bound-page-authoritative",
                    )
                    bindingRefocusKeys.add(runtimeKey)
                    injectedKeys.remove(runtimeKey)
                    pool.get(runtimeKey)?.load(requestedPage)
                    return@pageReady
                }

                if (requestedPage != null) {
                    initialNavigationUrls.remove(runtimeKey)
                    bindingRefocusKeys.remove(runtimeKey)
                }

                DiagnosticLogger.recordBridgeTrace(
                    stage = "page-ready",
                    provider = provider.id,
                    windowId = windowId,
                    url = currentUrl,
                    detail = "installing watcher"
                )
                if (viewHost.currentKey == runtimeKey) {
                    probeVisibleComposerReady(
                        windowId = windowId,
                        provider = provider,
                    )
                }
                // Browser-first AIUI does not mirror product message bodies
                // into a second native transcript. Skip the expensive dual
                // network/DOM capture path unless a native consumer was
                // explicitly registered.
                if (nativeConversationObservationEnabled()) {
                    enableNetworkCapture(
                        windowId = windowId,
                        provider = provider,
                    )
                    if (provider.id == "chatgpt") {
                        if (runtimeKey !in standbyKeys) {
                            installArchiveWatcher(
                                windowId = windowId,
                                provider = provider,
                            )
                        }
                    } else {
                        installConversationWatcher(
                            windowId = windowId,
                            provider = provider,
                        )
                    }
                }
                if (
                    provider.id == "chatgpt" &&
                    liveHandoffCallbacks.containsKey(runtimeKey)
                ) {
                    installLiveHandoffObserver(
                        windowId = windowId,
                        provider = provider,
                    )
                }
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
                scheduleSnapshotCapture(
                    windowId = windowId,
                    provider = provider,
                )
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
                    "ai-archive-dirty" -> {
                        responseChangeListener?.invoke(
                            windowId,
                            provider,
                        )
                        scheduleSnapshotCapture(
                            windowId = windowId,
                            provider = provider,
                        )
                    }
                    "ai-live-ready" -> {
                        handleLiveHandoffReady(
                            windowId = windowId,
                            provider = provider,
                            payload = payload,
                        )
                    }
                    "ai-conversation" -> {
                        if (provider.id == "chatgpt") {
                            scheduleSnapshotCapture(
                                windowId = windowId,
                                provider = provider,
                            )
                        } else {
                            val snapshot =
                                parseConversationSnapshot(payload)
                            val userCount = snapshot.messages.count {
                                it.role == "user"
                            }
                            val assistantCount = snapshot.messages.count {
                                it.role == "assistant"
                            }
                            DiagnosticLogger.recordBridgeTrace(
                                stage = "conversation-event",
                                provider = provider.id,
                                windowId = windowId,
                                url = snapshot.url,
                                detail = (
                                    "source=" + snapshot.source +
                                        " " + snapshot.error
                                    ).trim(),
                                candidateCount =
                                    snapshot.candidateCount,
                                messageCount =
                                    snapshot.messages.size,
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
                    }
                    "ai-network" -> {
                        responseChangeListener?.invoke(
                            windowId,
                            provider,
                        )
                        handleNetworkEvent(
                            windowId = windowId,
                            provider = provider,
                            raw = payload,
                            transport = "webrequest",
                        )
                    }
                    "ai-page-network" -> {
                        responseChangeListener?.invoke(
                            windowId,
                            provider,
                        )
                        handleNetworkEvent(
                            windowId = windowId,
                            provider = provider,
                            raw = payload,
                            transport = "page",
                        )
                    }
                    "ai-page-write" -> {
                        handleWriteObservation(
                            windowId = windowId,
                            provider = provider,
                            raw = payload,
                        )
                    }
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
                preloadStates.remove(runtimeKey)
                preloadRetryAfter.remove(runtimeKey)
                cancelPreloadProbe(
                    runtimeKey = runtimeKey,
                    notify = true,
                    reason = "content-process-lost",
                )
                preloadViewHost.releaseIfBound(runtimeKey)
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

        if (requestedUrl != null && !existed) {
            initialNavigationUrls[runtimeKey] = requestedUrl
        }

        val session = if (existing != null) {
            existing.updateCallbacks(callbacks)
            existing
        } else {
            pool.acquire(
                key = runtimeKey,
                initialUrl = target,
                initialSessionState =
                    if (tabCacheStore.isPersistent(windowId)) {
                        tabCacheStore.readSessionState(windowId)
                    } else {
                        null
                    },
                callbacks = callbacks
            )
        }

        // Existing GeckoSessions keep their live route. preferredUrl is only
        // a cold-create hint and must never become a new navigation guard.

        touchSession(runtimeKey)
        trimHotSessions(protectedKey = runtimeKey)
        return session
    }

    private fun touchSession(runtimeKey: String) {
        sessionRecency.remove(runtimeKey)
        sessionRecency.add(runtimeKey)
    }

    /**
     * Keep a practical browser-style live-session budget. The old Native
     * Chat path used only two hot sessions, which forced reloads while
     * rotating through projects. Twelve live tabs covers the normal project
     * workspace while stale/older sessions can still be frozen.
     */
    private fun trimHotSessions(
        protectedKey: String,
    ) {
        val maxHotSessions = 12

        while (pool.activeCount() > maxHotSessions) {
            val visibleKey = viewHost.currentKey
            val victim =
                sessionRecency.firstOrNull { candidate ->
                    candidate != protectedKey &&
                        candidate != visibleKey &&
                        candidate != preloadViewHost.currentKey &&
                        preloadStates[candidate] !=
                            PreloadState.PRELOADING &&
                        !liveHandoffCallbacks.containsKey(candidate)
                } ?: break

            val windowId =
                sessionOwners[victim]?.first
            if (
                windowId != null &&
                tabCacheStore.isPersistent(windowId)
            ) {
                freezeBoundSession(
                    runtimeKey = victim,
                    reason = "global-hot-cap-12",
                )
            } else {
                evictHotSession(victim)
            }
        }
    }

    private fun enterStandby(runtimeKey: String) {
        val session = pool.get(runtimeKey) ?: return
        val owner = sessionOwners[runtimeKey]
        val keepProductRuntimeActive =
            owner?.let { (windowId, provider) ->
                provider.id == "chatgpt" &&
                    tabCacheStore.isPersistent(windowId)
            } == true

        standbyKeys.add(runtimeKey)
        if (
            preloadStates[runtimeKey] ==
                PreloadState.READY
        ) {
            preloadStates[runtimeKey] =
                PreloadState.WARM
        }
        session.setFocused(false)
        session.setHighPriority(false)

        // A bound ChatGPT tab is still the product runtime behind the Native
        // chat surface. GeckoSession.setActive(false) suspends enough page
        // work that a later DOM send can return "verify" without ever
        // reaching the ChatGPT backend. Keep persistent ChatGPT sessions
        // active (but unfocused / low priority) while in Standby. The global
        // hot-session cap and the 24h stale-session freezer still bound memory.
        session.setActive(keepProductRuntimeActive)

        if (
            owner?.second?.id == "chatgpt" &&
            nativeConversationObservationEnabled()
        ) {
            pauseArchiveWatcher(runtimeKey)
        }
    }

    private fun probeVisibleComposerReady(
        windowId: String,
        provider: ProviderSpec,
        attempt: Int = 0,
    ) {
        if (provider.id != "chatgpt") return
        if (attempt >= 24) return

        val runtimeKey = key(windowId, provider)
        if (viewHost.currentKey != runtimeKey) return

        val session = pool.get(runtimeKey) ?: return
        val state = session.currentState
        if (
            state.url.isBlank() ||
            state.loading ||
            !sameProviderOrigin(
                state.url,
                provider,
            )
        ) {
            snapshotHandler.postDelayed(
                {
                    probeVisibleComposerReady(
                        windowId,
                        provider,
                        attempt + 1,
                    )
                },
                250L,
            )
            return
        }

        session.evaluate(
            code =
                """
                    try {
                        const composer =
                            !!document.querySelector(
                                "#prompt-textarea, " +
                                "textarea, " +
                                "[data-testid='composer'] [contenteditable='true'], " +
                                "form [contenteditable='true']"
                            );
                        const viewport =
                            window.innerWidth > 0 &&
                            window.innerHeight > 0;
                        return String(composer && viewport);
                    } catch (_) {
                        return "false";
                    }
                """.trimIndent(),
            timeoutMs = 1_200L,
        ) { valueJson, error ->
            if (viewHost.currentKey != runtimeKey) {
                return@evaluate
            }
            val decoded =
                runCatching {
                    JSONTokener(
                        valueJson.orEmpty()
                    ).nextValue()
                }.getOrNull()
            val ready =
                error.isNullOrBlank() &&
                    (
                        decoded == true ||
                            decoded?.toString() == "true"
                        )

            if (ready) {
                preloadStates[runtimeKey] =
                    PreloadState.READY
                preloadRetryAfter.remove(runtimeKey)
                DiagnosticLogger.recordBridgeTrace(
                    stage = "visible-composer-ready",
                    provider = provider.id,
                    windowId = windowId,
                    url = session.currentState.url,
                    detail = "tab no longer needs preload",
                )
            } else {
                snapshotHandler.postDelayed(
                    {
                        probeVisibleComposerReady(
                            windowId,
                            provider,
                            attempt + 1,
                        )
                    },
                    300L,
                )
            }
        }
    }

    private fun pauseArchiveWatcher(runtimeKey: String) {
        val session = pool.get(runtimeKey) ?: return
        session.evaluate(
            """
                try {
                    const watcher =
                        window.__AIHUB_ARCHIVE_WATCHER__;
                    if (
                        watcher &&
                        typeof watcher.disconnect === "function"
                    ) {
                        watcher.disconnect();
                    }
                    delete window.__AIHUB_ARCHIVE_WATCHER__;
                    return "standby";
                } catch (error) {
                    return String(error);
                }
            """.trimIndent()
        ) { _, _ -> }
    }

    private fun freezeBoundSession(
        runtimeKey: String,
        reason: String,
    ) {
        val owner = sessionOwners[runtimeKey]
        val session = pool.get(runtimeKey) ?: return

        session.setFocused(false)
        session.setHighPriority(false)
        session.setActive(false)
        session.flushSessionState()

        injectedKeys.remove(runtimeKey)
        initialNavigationUrls.remove(runtimeKey)
        queuedNativeUris.remove(runtimeKey)
        chatPresentationKeys.remove(runtimeKey)
        networkAssemblies.keys.removeAll {
            it.startsWith("$runtimeKey|")
        }
        networkFingerprints.removeAll {
            it.startsWith("$runtimeKey|")
        }
        activeStreamOwnedRequests.removeAll {
            it.startsWith("$runtimeKey|")
        }
        snapshotTasks
            .remove(runtimeKey)
            ?.let(snapshotHandler::removeCallbacks)
        freezeTasks
            .remove(runtimeKey)
            ?.let(snapshotHandler::removeCallbacks)
        sessionOwners.remove(runtimeKey)
        sessionRecency.remove(runtimeKey)
        standbyKeys.remove(runtimeKey)
        bindingRefocusKeys.remove(runtimeKey)
        renderReadyUrls.remove(runtimeKey)
        preloadStates.remove(runtimeKey)
        preloadRetryAfter.remove(runtimeKey)
        cancelPreloadProbe(
            runtimeKey = runtimeKey,
            notify = false,
        )
        preloadViewHost.releaseIfBound(runtimeKey)
        viewHost.releaseIfBound(runtimeKey)
        pool.close(runtimeKey)

        if (owner != null) {
            val (windowId, provider) = owner
            DiagnosticLogger.recordBridgeTrace(
                stage = "session-freeze",
                provider = provider.id,
                windowId = windowId,
                url = preferredUrls[runtimeKey].orEmpty(),
                detail = "reason=$reason cache-and-state-kept",
            )
        }
    }

    private fun evictHotSession(runtimeKey: String) {
        val owner = sessionOwners[runtimeKey]
        pool.get(runtimeKey)?.let { session ->
            session.setFocused(false)
            session.setHighPriority(false)
            session.setActive(false)
            session.flushSessionState()
        }

        injectedKeys.remove(runtimeKey)
        initialNavigationUrls.remove(runtimeKey)
        queuedNativeUris.remove(runtimeKey)
        chatPresentationKeys.remove(runtimeKey)
        networkAssemblies.keys.removeAll {
            it.startsWith("$runtimeKey|")
        }
        networkFingerprints.removeAll {
            it.startsWith("$runtimeKey|")
        }
        snapshotTasks
            .remove(runtimeKey)
            ?.let(snapshotHandler::removeCallbacks)
        freezeTasks
            .remove(runtimeKey)
            ?.let(snapshotHandler::removeCallbacks)
        sessionOwners.remove(runtimeKey)
        sessionRecency.remove(runtimeKey)
        standbyKeys.remove(runtimeKey)
        bindingRefocusKeys.remove(runtimeKey)
        renderReadyUrls.remove(runtimeKey)
        preloadStates.remove(runtimeKey)
        preloadRetryAfter.remove(runtimeKey)
        cancelPreloadProbe(
            runtimeKey = runtimeKey,
            notify = false,
        )
        preloadViewHost.releaseIfBound(runtimeKey)
        viewHost.releaseIfBound(runtimeKey)
        pool.close(runtimeKey)

        if (owner != null) {
            val (windowId, provider) = owner
            DiagnosticLogger.recordBridgeTrace(
                stage = "session-evict",
                provider = provider.id,
                windowId = windowId,
                url = preferredUrls[runtimeKey].orEmpty(),
                detail = "reason=global-hot-cap max=12",
            )
        }
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

    private fun installArchiveWatcher(
        windowId: String,
        provider: ProviderSpec,
    ) {
        val session = pool.get(key(windowId, provider)) ?: return
        session.evaluate(
            """
                try {
                    const emit =
                        globalThis.__YBROWSER_RPC_EMIT__;
                    if (typeof emit !== "function") {
                        return "archive-watcher-rpc-unavailable";
                    }

                    const previous =
                        window.__AIHUB_ARCHIVE_WATCHER__;
                    if (
                        previous &&
                        typeof previous.disconnect === "function"
                    ) {
                        previous.disconnect();
                    }

                    let timer = 0;
                    const push = (reason) => {
                        try {
                            emit(
                                "ai-archive-dirty",
                                JSON.stringify({
                                    url: location.href,
                                    reason: String(reason || "")
                                })
                            );
                        } catch (_) {}
                    };
                    const schedule = (reason) => {
                        clearTimeout(timer);
                        timer = setTimeout(
                            () => push(reason),
                            320
                        );
                    };

                    const observer = new MutationObserver(
                        () => schedule("mutation")
                    );
                    observer.observe(
                        document.documentElement ||
                            document.body,
                        {
                            subtree: true,
                            childList: true,
                            characterData: true
                        }
                    );

                    const onScroll = () =>
                        schedule("scroll");
                    document.addEventListener(
                        "scroll",
                        onScroll,
                        true
                    );

                    window.__AIHUB_ARCHIVE_WATCHER__ = {
                        disconnect() {
                            clearTimeout(timer);
                            observer.disconnect();
                            document.removeEventListener(
                                "scroll",
                                onScroll,
                                true
                            );
                        }
                    };

                    push("install");
                    return "ok";
                } catch (error) {
                    return String(error);
                }
            """.trimIndent()
        ) { value, error ->
            DiagnosticLogger.recordBridgeTrace(
                stage = if (error.isNullOrBlank()) {
                    "archive-watcher"
                } else {
                    "archive-watcher-failed"
                },
                provider = provider.id,
                windowId = windowId,
                url = session.currentState.url,
                detail = error ?: value.orEmpty(),
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

    private fun scheduleWarmFreeze(
        runtimeKey: String,
    ) {
        freezeTasks.remove(runtimeKey)
            ?.let(snapshotHandler::removeCallbacks)

        val owner = sessionOwners[runtimeKey]

        // Browser-first tabs stay hot briefly for fast back-and-forth
        // switching, then suspend without closing. A generating ChatGPT page
        // is detected below and keeps extending its warm grace period.
        val task = Runnable {
            freezeTasks.remove(runtimeKey)
            if (viewHost.currentKey == runtimeKey) {
                return@Runnable
            }

            val session = pool.get(runtimeKey)
                ?: return@Runnable
            val owner = sessionOwners[runtimeKey]

            // Do not freeze a background ChatGPT tab while it is still
            // generating a reply. Keep it warm and check again later.
            if (owner?.second?.id == "chatgpt") {
                session.evaluate(
                    """
                        try {
                            const selectors = [
                                "button[data-testid='stop-button']",
                                "button[aria-label*='Stop' i]"
                            ];
                            const generating = selectors.some(
                                (selector) => {
                                    const node =
                                        document.querySelector(selector);
                                    if (!node) return false;
                                    const rect =
                                        node.getBoundingClientRect();
                                    const style =
                                        getComputedStyle(node);
                                    return (
                                        rect.width > 0 &&
                                        rect.height > 0 &&
                                        style.display !== "none" &&
                                        style.visibility !== "hidden"
                                    );
                                }
                            );
                            return generating;
                        } catch (_) {
                            return false;
                        }
                    """.trimIndent()
                ) { value, _ ->
                    if (
                        value == "true" &&
                        viewHost.currentKey != runtimeKey
                    ) {
                        DiagnosticLogger.recordBridgeTrace(
                            stage = "session-freeze-deferred",
                            provider = owner.second.id,
                            windowId = owner.first,
                            url = session.currentState.url,
                            detail = "generation-active",
                        )
                        scheduleWarmFreeze(runtimeKey)
                    } else if (
                        viewHost.currentKey != runtimeKey
                    ) {
                        session.setFocused(false)
                        session.setActive(false)
                        session.flushSessionState()
                        DiagnosticLogger.recordBridgeTrace(
                            stage = "session-frozen",
                            provider = owner?.second?.id.orEmpty(),
                            windowId = owner?.first.orEmpty(),
                            url = session.currentState.url,
                            detail = "warm-grace-3m-expired",
                        )
                    }
                }
                return@Runnable
            }

            session.setFocused(false)
            session.setActive(false)
            session.flushSessionState()
            DiagnosticLogger.recordBridgeTrace(
                stage = "session-frozen",
                provider = owner?.second?.id.orEmpty(),
                windowId = owner?.first.orEmpty(),
                url = session.currentState.url,
                detail = "warm-grace-3m-expired",
            )
        }

        freezeTasks[runtimeKey] = task
        snapshotHandler.postDelayed(task, 3L * 60L * 1_000L)
    }

    private fun cancelWarmFreeze(
        runtimeKey: String,
    ) {
        freezeTasks.remove(runtimeKey)
            ?.let(snapshotHandler::removeCallbacks)
        pool.get(runtimeKey)?.setActive(true)
    }

    private fun scheduleSnapshotCapture(
        windowId: String,
        provider: ProviderSpec,
    ) {
        val runtimeKey = key(windowId, provider)
        if (
            provider.id != "chatgpt" ||
            !tabCacheStore.isPersistent(windowId)
        ) {
            return
        }

        snapshotTasks.remove(runtimeKey)
            ?.let(snapshotHandler::removeCallbacks)

        val task = Runnable {
            snapshotTasks.remove(runtimeKey)
            captureSnapshotNow(windowId, provider)
        }
        snapshotTasks[runtimeKey] = task
        snapshotHandler.postDelayed(task, 1_200L)
    }

    private fun captureSnapshotNow(
        windowId: String,
        provider: ProviderSpec,
    ) {
        val runtimeKey = key(windowId, provider)
        if (
            provider.id != "chatgpt" ||
            !tabCacheStore.isPersistent(windowId)
        ) {
            return
        }

        val session = pool.get(runtimeKey) ?: return
        if (session.currentState.url.isBlank()) return

        val includeCss = !tabCacheStore.hasStyles(windowId)

        session.evaluate(
            """
                try {
                    const cfg = window.__AIHUB_CONFIG__ || {};
                    const simpleHash = (value) => {
                        let h = 2166136261;
                        const text = String(value || "");
                        for (let i = 0; i < text.length; i++) {
                            h ^= text.charCodeAt(i);
                            h = Math.imul(h, 16777619);
                        }
                        return (h >>> 0).toString(16);
                    };

                    const generating = (cfg.stopSelectors || []).some(
                        (selector) => {
                            try {
                                const node = document.querySelector(selector);
                                if (!node) return false;
                                const rect = node.getBoundingClientRect();
                                const style = getComputedStyle(node);
                                return (
                                    rect.width > 0 &&
                                    rect.height > 0 &&
                                    style.display !== "none" &&
                                    style.visibility !== "hidden"
                                );
                            } catch (_) {
                                return false;
                            }
                        }
                    );

                    if (generating) {
                        return JSON.stringify({
                            skip: "generating",
                            url: location.href
                        });
                    }

                    const preferred = Array.from(
                        document.querySelectorAll(
                            "[data-testid^='conversation-turn']"
                        )
                    );

                    const candidates = [];
                    const seen = new Set();
                    const addTurn = (node) => {
                        if (!node || seen.has(node)) return;
                        const canonical =
                            node.closest?.(
                                "[data-testid^='conversation-turn']"
                            ) || node;
                        if (seen.has(canonical)) return;
                        seen.add(canonical);
                        candidates.push(canonical);
                    };

                    if (preferred.length) {
                        preferred.forEach(addTurn);
                    } else {
                        (cfg.turnSelectors || []).forEach((selector) => {
                            try {
                                document.querySelectorAll(selector)
                                    .forEach(addTurn);
                            } catch (_) {}
                        });
                    }

                    candidates.sort((a, b) => {
                        if (a === b) return 0;
                        const relation =
                            a.compareDocumentPosition?.(b) || 0;
                        if (
                            relation &
                            Node.DOCUMENT_POSITION_FOLLOWING
                        ) {
                            return -1;
                        }
                        if (
                            relation &
                            Node.DOCUMENT_POSITION_PRECEDING
                        ) {
                            return 1;
                        }
                        return 0;
                    });

                    const turns = candidates.filter((node, index, all) => {
                        return !all.some((other, otherIndex) =>
                            otherIndex !== index &&
                            other.contains?.(node)
                        );
                    });

                    const fallbackCounts = new Map();
                    const keyFor = (node) => {
                        const direct = [
                            node.getAttribute?.("data-message-id"),
                            node.getAttribute?.("data-testid"),
                            node.id
                        ].find((value) =>
                            value && String(value).trim()
                        );
                        if (direct) {
                            return "dom:" + String(direct).trim();
                        }

                        const roleNode =
                            node.matches?.(
                                "[data-message-author-role],[data-turn]"
                            )
                                ? node
                                : node.querySelector?.(
                                    "[data-message-author-role],[data-turn]"
                                );
                        const role =
                            roleNode?.getAttribute?.(
                                "data-message-author-role"
                            ) ||
                            roleNode?.getAttribute?.("data-turn") ||
                            "turn";
                        const text = String(
                            node.innerText ||
                            node.textContent ||
                            ""
                        )
                            .replace(/\s+/g, " ")
                            .trim()
                            .slice(0, 420);
                        const base =
                            role + ":" + simpleHash(text);
                        const occurrence =
                            Number(fallbackCounts.get(base) || 0);
                        fallbackCounts.set(
                            base,
                            occurrence + 1
                        );
                        return (
                            "fallback:" +
                            base +
                            ":" +
                            occurrence
                        );
                    };

                    let anchorKey = "";
                    let anchorOffset = 0;
                    const serialized = [];

                    turns.forEach((node, index) => {
                        const key = keyFor(node);
                        const rect = node.getBoundingClientRect();
                        if (
                            !anchorKey &&
                            rect.bottom > 0 &&
                            rect.top < innerHeight
                        ) {
                            anchorKey = key;
                            anchorOffset = rect.top;
                        }

                        const clone = node.cloneNode(true);
                        clone.setAttribute(
                            "data-aihub-archive-key",
                            key
                        );
                        clone.querySelectorAll(
                            "script,iframe,video,audio,object,embed"
                        ).forEach((child) => child.remove());
                        clone.querySelectorAll(
                            "[contenteditable='true']"
                        ).forEach((child) => {
                            child.setAttribute(
                                "contenteditable",
                                "false"
                            );
                        });
                        clone.querySelectorAll(
                            "input,textarea"
                        ).forEach((child) => {
                            child.setAttribute(
                                "readonly",
                                "readonly"
                            );
                        });
                        clone.querySelectorAll("button")
                            .forEach((child) => {
                                child.setAttribute(
                                    "disabled",
                                    "disabled"
                                );
                            });
                        [clone, ...clone.querySelectorAll("*")]
                            .forEach((child) => {
                                Array.from(child.attributes || [])
                                    .forEach((attribute) => {
                                        const name =
                                            String(
                                                attribute.name || ""
                                            ).toLowerCase();
                                        if (
                                            name.startsWith("on") ||
                                            name === "srcdoc"
                                        ) {
                                            child.removeAttribute(
                                                attribute.name
                                            );
                                        }
                                    });
                            });

                        serialized.push({
                            key,
                            html: clone.outerHTML
                        });
                    });

                    if (!serialized.length) {
                        return JSON.stringify({
                            skip: "no-turns",
                            url: location.href
                        });
                    }

                    const firstTurn = turns[0] || null;
                    const thread = firstTurn?.parentElement || null;

                    let liveScrollRoot = null;
                    let node = thread;
                    for (
                        let depth = 0;
                        node && depth < 14;
                        depth++, node = node.parentElement
                    ) {
                        try {
                            const style = getComputedStyle(node);
                            if (
                                /(auto|scroll|overlay)/i.test(
                                    style.overflowY || ""
                                ) &&
                                node.scrollHeight >
                                    node.clientHeight + 80
                            ) {
                                liveScrollRoot = node;
                                break;
                            }
                        } catch (_) {}
                    }

                    liveScrollRoot =
                        liveScrollRoot ||
                        document.scrollingElement ||
                        document.documentElement;

                    let cssText = "";
                    if (${includeCss}) {
                        Array.from(document.styleSheets || [])
                            .forEach((sheet) => {
                                try {
                                    Array.from(sheet.cssRules || [])
                                        .forEach((rule) => {
                                            cssText +=
                                                rule.cssText + "\n";
                                        });
                                } catch (_) {}
                            });
                    }

                    return JSON.stringify({
                        url: location.href,
                        title: document.title || "ChatGPT",
                        scrollTop: Number(
                            liveScrollRoot?.scrollTop ||
                            window.scrollY ||
                            0
                        ),
                        anchorKey,
                        anchorOffset,
                        htmlClass:
                            document.documentElement?.className || "",
                        bodyClass: document.body?.className || "",
                        htmlStyle:
                            document.documentElement?.getAttribute(
                                "style"
                            ) || "",
                        bodyStyle:
                            document.body?.getAttribute("style") || "",
                        threadClass: thread?.className || "",
                        threadStyle:
                            thread?.getAttribute?.("style") || "",
                        turns: serialized,
                        css: cssText
                    });
                } catch (error) {
                    return JSON.stringify({
                        error: String(error)
                    });
                }
            """.trimIndent()
        ) { valueJson, error ->
            if (
                !error.isNullOrBlank() ||
                valueJson.isNullOrBlank()
            ) {
                return@evaluate
            }

            val decoded = runCatching {
                JSONTokener(valueJson).nextValue()
            }.getOrNull()
            val raw = when (decoded) {
                is String -> decoded
                null, JSONObject.NULL -> ""
                else -> decoded.toString()
            }
            val obj = runCatching { JSONObject(raw) }.getOrNull()
                ?: return@evaluate

            val skip = obj.optString("skip")
            if (skip.isNotBlank()) {
                DiagnosticLogger.recordBridgeTrace(
                    stage = "archive-skip",
                    provider = provider.id,
                    windowId = windowId,
                    url = obj.optString("url"),
                    detail = skip,
                )
                return@evaluate
            }

            val array = obj.optJSONArray("turns") ?: JSONArray()
            val turns = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val archiveKey = item.optString("key").trim()
                    val html = item.optString("html")
                    if (
                        archiveKey.isBlank() ||
                        html.isBlank()
                    ) {
                        continue
                    }
                    add(
                        AiTabCacheStore.ArchiveTurn(
                            key = archiveKey,
                            html = html,
                        )
                    )
                }
            }

            if (turns.isEmpty()) return@evaluate

            val captureUrl = obj.optString("url")
            if (captureUrl.isNotBlank()) {
                renderReadyUrls[runtimeKey] =
                    captureUrl
            }

            val capture = AiTabCacheStore.ArchiveCapture(
                url = obj.optString("url"),
                title = obj.optString("title"),
                scrollTop = obj.optDouble("scrollTop", 0.0),
                anchorKey = obj.optString("anchorKey"),
                anchorOffset =
                    obj.optDouble("anchorOffset", 0.0),
                htmlClass = obj.optString("htmlClass"),
                bodyClass = obj.optString("bodyClass"),
                htmlStyle = obj.optString("htmlStyle"),
                bodyStyle = obj.optString("bodyStyle"),
                threadClass = obj.optString("threadClass"),
                threadStyle = obj.optString("threadStyle"),
                turns = turns,
                css = obj.optString("css"),
            )

            val archiveFingerprint = buildString {
                append(capture.url)
                append('|').append(capture.title)
                append('|').append(capture.htmlClass)
                append('|').append(capture.bodyClass)
                append('|').append(capture.threadClass)
                turns.forEach { turn ->
                    append('|')
                    append(turn.key)
                    append(':')
                    append(turn.html.hashCode())
                }
                append("|css=")
                append(capture.css.hashCode())
            }

            val unchanged = synchronized(archiveFingerprints) {
                if (
                    archiveFingerprints[windowId] ==
                        archiveFingerprint
                ) {
                    true
                } else {
                    archiveFingerprints[windowId] =
                        archiveFingerprint
                    false
                }
            }
            if (unchanged) {
                return@evaluate
            }

            archiveExecutor.execute {
                val total =
                    tabCacheStore.writeConversationArchive(
                        windowId = windowId,
                        capture = capture,
                    )

                if (total <= 0) {
                    synchronized(archiveFingerprints) {
                        if (
                            archiveFingerprints[windowId] ==
                                archiveFingerprint
                        ) {
                            archiveFingerprints.remove(windowId)
                        }
                    }
                }

                DiagnosticLogger.recordBridgeTrace(
                    stage =
                        if (total > 0) {
                            "archive-saved"
                        } else {
                            "archive-save-skipped"
                        },
                    provider = provider.id,
                    windowId = windowId,
                    url = capture.url,
                    detail =
                        "seen=" + turns.size +
                            " total=" + total,
                )
            }
        }
    }

    private suspend fun ensureLoaded(
        windowId: String,
        provider: ProviderSpec
    ) {
        val runtimeKey = key(windowId, provider)
        val session = obtain(windowId, provider)

        val wasStandby = standbyKeys.remove(runtimeKey)

        // Every product operation requires a running Gecko session. Activity
        // release, cold/prewarm paths, and legacy standby code can all leave a
        // session inactive without necessarily leaving a standby marker, so
        // activation must be unconditional here.
        session.setActive(true)
        touchSession(runtimeKey)

        if (wasStandby) {
            DiagnosticLogger.recordBridgeTrace(
                stage = "session-operation-wake",
                provider = provider.id,
                windowId = windowId,
                url = session.currentState.url,
                detail = "standby->active",
            )
        }

        if (session.currentState.url.isBlank()) {
            session.load(
                preferredUrls[runtimeKey]
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
        code: String,
        timeoutMs: Long = 15_000L,
    ): String? =
        suspendCoroutine { continuation ->
            session.evaluate(
                code = code,
                timeoutMs = timeoutMs,
            ) { valueJson, error ->
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
        fun parseMessages(
            name: String,
        ): List<WebRuntime.PageConversationMessage> {
            val array = obj.optJSONArray(name) ?: JSONArray()
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val role = item.optString("role").lowercase()
                    val text = item.optString("text").trim()
                    val attachments =
                        buildList {
                            val media =
                                item.optJSONArray(
                                    "attachments"
                                ) ?: JSONArray()
                            for (
                                mediaIndex in 0 until
                                    media.length()
                            ) {
                                val attachment =
                                    media.optJSONObject(
                                        mediaIndex
                                    ) ?: continue
                                val uri =
                                    attachment
                                        .optString("uri")
                                        .takeIf {
                                            it.isNotBlank()
                                        }
                                val name =
                                    attachment
                                        .optString("name")
                                        .ifBlank {
                                            "attachment-" +
                                                (mediaIndex + 1)
                                        }
                                add(
                                    AttachmentMeta(
                                        id =
                                            attachment
                                                .optString("id")
                                                .ifBlank {
                                                    "$role-$index-media-$mediaIndex"
                                                },
                                        name = name,
                                        mimeType =
                                            attachment
                                                .optString(
                                                    "mimeType",
                                                    "application/octet-stream",
                                                ),
                                        sizeBytes =
                                            attachment
                                                .optLong(
                                                    "sizeBytes",
                                                    0L,
                                                ),
                                        uri = uri,
                                    )
                                )
                            }
                        }
                    if (
                        role !in setOf("user", "assistant") ||
                        (
                            text.isBlank() &&
                                attachments.isEmpty()
                            )
                    ) {
                        continue
                    }
                    add(
                        WebRuntime.PageConversationMessage(
                            id = item.optString("id").ifBlank {
                                "$role-$index-${(text + attachments.joinToString { it.uri.orEmpty() }).hashCode()}"
                            },
                            role = role,
                            text = text,
                            attachments = attachments,
                        )
                    )
                }
            }
        }

        return WebRuntime.ConversationSnapshot(
            url = obj.optString("url"),
            title = obj.optString("title"),
            candidateCount = obj.optInt("candidateCount", -1),
            error = obj.optString("error"),
            source = obj.optString("source", "dom"),
            complete = obj.optBoolean("complete", false),
            messages = parseMessages("messages"),
            visibleMessages = parseMessages("visibleMessages"),
        )
    }

    private fun handleWriteObservation(
        windowId: String,
        provider: ProviderSpec,
        raw: String,
    ) {
        if (provider.id != "chatgpt") return

        val runtimeKey = key(windowId, provider)
        val expectation =
            pendingWriteExpectations[runtimeKey]
                ?: return
        val obj =
            runCatching { JSONObject(raw) }
                .getOrNull()
                ?: return

        if (!obj.optBoolean("actionNext", false)) {
            return
        }
        if (
            !obj.optBoolean(
                "allUserMessagesHaveId",
                false,
            )
        ) {
            return
        }

        val observedAt =
            obj.optLong(
                "capturedAt",
                System.currentTimeMillis(),
            )
        if (observedAt < expectation.startedAt) {
            return
        }

        val observedConversationId =
            obj.optString("conversationId")
                .trim()
                .takeIf { it.isNotBlank() }
        if (
            expectation.conversationId != null &&
            observedConversationId !=
                expectation.conversationId
        ) {
            DiagnosticLogger.recordBridgeTrace(
                stage = "write-correlation-mismatch",
                provider = provider.id,
                windowId = windowId,
                url = obj.optString("url"),
                detail =
                    "reason=conversation-id expected=" +
                        expectation.conversationId.take(96) +
                        " observed=" +
                        observedConversationId
                            .orEmpty()
                            .take(96),
            )
            return
        }
        if (
            expectation.conversationId == null &&
            observedConversationId != null
        ) {
            // A fresh chat request is expected to omit conversation_id.
            return
        }

        val users = obj.optJSONArray("userMessages")
            ?: return
        var matchedMessageId: String? = null
        var matchCount = 0
        for (index in 0 until users.length()) {
            val user = users.optJSONObject(index)
                ?: continue
            val messageId =
                user.optString("id").trim()
            val textHash =
                user.optString("textSha256")
                    .trim()
            if (
                messageId.isNotBlank() &&
                textHash.equals(
                    expectation.promptSha256,
                    ignoreCase = true,
                )
            ) {
                matchCount++
                matchedMessageId = messageId
            }
        }

        if (matchCount != 1 || matchedMessageId == null) {
            DiagnosticLogger.recordBridgeTrace(
                stage = "write-correlation-mismatch",
                provider = provider.id,
                windowId = windowId,
                url = obj.optString("url"),
                detail =
                    "reason=exact-user-message matches=" +
                        matchCount +
                        " users=" +
                        users.length(),
            )
            return
        }

        correlatedWriteAcks[runtimeKey] =
            CorrelatedWriteAck(
                observedAt = observedAt,
                conversationId =
                    observedConversationId,
                userMessageId =
                    matchedMessageId,
            )

        DiagnosticLogger.recordBridgeTrace(
            stage = "write-correlation-proven",
            provider = provider.id,
            windowId = windowId,
            url = obj.optString("url"),
            detail =
                "messageId=" +
                    matchedMessageId.take(96) +
                    " conversationId=" +
                    observedConversationId
                        .orEmpty()
                        .take(96),
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

        val runtimeKey = key(windowId, provider)
        val endpoint = runCatching {
            Uri.parse(assembled.url).path.orEmpty()
        }.getOrDefault("")

        if (
            provider.id == "chatgpt" &&
            assembled.method.equals(
                "POST",
                ignoreCase = true,
            ) &&
            Regex(
                """^/backend-api/(?:f/)?conversation/?$"""
            ).matches(endpoint) &&
            assembled.statusCode in 200..299
        ) {
            conversationWriteAcks[runtimeKey] =
                maxOf(
                    conversationWriteAcks[
                        runtimeKey
                    ] ?: 0L,
                    assembled.capturedAt,
                )
            DiagnosticLogger.recordBridgeTrace(
                stage = "conversation-write-observed",
                provider = provider.id,
                windowId = windowId,
                url = assembled.url,
                detail =
                    "request=" +
                        requestId.take(24) +
                        " status=" +
                        assembled.statusCode +
                        " transport=" +
                        transport,
            )
        }

        if (
            provider.id == "chatgpt" &&
            transport == "page" &&
            assembled.method.equals(
                "POST",
                ignoreCase = true,
            ) &&
            Regex(
                """^/backend-api/(?:f/)?conversation(?:/resume)?/?$"""
            ).matches(endpoint)
        ) {
            DiagnosticLogger.recordBridgeTrace(
                stage =
                    "passive-chatgpt-post-skip",
                provider = provider.id,
                windowId = windowId,
                url = assembled.url,
                detail =
                    "active-stream owns realtime response " +
                        "request=" +
                        requestId.take(24),
            )
            return
        }

        if (
            provider.id == "chatgpt" &&
            transport == "webrequest" &&
            assembled.truncated &&
            Regex(
                """^/backend-api/conversations/[^/]+$"""
            ).matches(endpoint)
        ) {
            DiagnosticLogger.recordBridgeTrace(
                stage = "network-truncated-skip",
                provider = provider.id,
                windowId = windowId,
                url = assembled.url,
                detail =
                    "transport=$transport endpoint=" +
                        DiagnosticLogger.scrub(endpoint, 180) +
                        " chars=" + assembled.body.length +
                        " waiting=page-capture",
            )
            return
        }

        val fingerprint =
            "$runtimeKey|" +
                assembled.url +
                "|" + assembled.statusCode +
                "|" + assembled.complete +
                "|" + assembled.truncated +
                "|" + assembled.body.length +
                "|" + assembled.body.hashCode()
        if (!networkFingerprints.add(fingerprint)) {
            return
        }
        while (networkFingerprints.size > 192) {
            networkFingerprints
                .firstOrNull()
                ?.let(networkFingerprints::remove)
                ?: break
        }

        val pageUrl = pool.get(runtimeKey)
            ?.currentState
            ?.url
            .orEmpty()
        if (!sameProviderOrigin(pageUrl, provider)) return

        if (
            provider.id == "chatgpt" &&
            transport == "webrequest" &&
            assembled.stream
        ) {
            val activeSnapshot =
                ChatGptProductProvider
                    .parseActiveStream(
                        provider = provider,
                        capture = assembled,
                        pageUrl = pageUrl,
                    )

            if (activeSnapshot != null) {
                val userCount =
                    activeSnapshot.messages.count {
                        it.role == "user"
                    }
                val assistantCount =
                    activeSnapshot.messages.count {
                        it.role == "assistant"
                    }

                DiagnosticLogger.recordBridgeTrace(
                    stage = "active-stream",
                    provider = provider.id,
                    windowId = windowId,
                    url = activeSnapshot.url,
                    detail =
                        "request=" +
                            requestId.take(24) +
                            " conversationId=" +
                            activeSnapshot
                                .conversationId
                                .orEmpty()
                                .take(96) +
                            " complete=" +
                            activeSnapshot.complete +
                            " chars=" +
                            assembled.body.length,
                    candidateCount =
                        activeSnapshot.candidateCount,
                    messageCount =
                        activeSnapshot.messages.size,
                    userCount = userCount,
                    assistantCount =
                        assistantCount,
                )
                activeStreamOwnedRequests +=
                    "$runtimeKey|$requestId"
                conversationListener?.invoke(
                    windowId,
                    provider,
                    activeSnapshot,
                )
                return
            }
        }

        val activeRequestKey =
            "$runtimeKey|$requestId"
        if (
            provider.id == "chatgpt" &&
            transport == "webrequest" &&
            !assembled.stream &&
            assembled.complete &&
            assembled.method.equals(
                "POST",
                ignoreCase = true,
            ) &&
            Regex(
                """^/backend-api/(?:f/)?conversation(?:/resume)?/?$"""
            ).matches(endpoint) &&
            activeStreamOwnedRequests
                .remove(activeRequestKey)
        ) {
            DiagnosticLogger.recordBridgeTrace(
                stage =
                    "active-stream-owned-final-response",
                provider = provider.id,
                windowId = windowId,
                url = assembled.url,
                detail =
                    "request=" +
                        requestId.take(24) +
                        " chars=" +
                        assembled.body.length,
            )
            return
        }

        val snapshot = ProviderNetworkParser.parse(
            provider = provider,
            capture = assembled,
            pageUrl = pageUrl,
        )
        if (snapshot == null) {
            val body = assembled.body
            val markerNames = buildList {
                if (body.contains("\"mapping\"")) add("mapping")
                if (body.contains("\"messages\"")) add("messages")
                if (body.contains("\"current_node\"")) add("current_node")
                if (body.contains("\"page_info\"")) add("page_info")
                if (body.contains("\"message\"")) add("message")
                if (body.contains("\"author\"")) add("author")
                if (body.contains("\"role\"")) add("role")
                if (body.contains("\"p\"")) add("patch_p")
                if (body.contains("\"v\"")) add("patch_v")
                if (body.trimStart().startsWith("data:")) add("sse")
            }
            val replacementCount = body.count { it == '\uFFFD' }
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
            sizeBytes = size,
            uri = uri.toString(),
        )
    }

    private fun persistReadPermission(
        uri: Uri,
        intentFlags: Int = 0,
    ) {
        if (uri.scheme != "content") return

        val flags =
            (
                intentFlags and
                    (
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
            ).takeIf { it != 0 }
                ?: Intent.FLAG_GRANT_READ_URI_PERMISSION

        runCatching {
            context.contentResolver
                .takePersistableUriPermission(
                    uri,
                    flags,
                )
        }.onFailure {
            DiagnosticLogger.d(
                "GECKO_FILE",
                "persist_uri_permission_unavailable uri=" +
                    DiagnosticLogger.scrub(
                        uri.toString(),
                        240,
                    ) +
                    " type=" +
                    it.javaClass.simpleName,
            )
        }
    }

    private fun chatGptConversationId(
        rawUrl: String?,
    ): String? {
        val path =
            runCatching {
                Uri.parse(
                    rawUrl.orEmpty()
                ).path.orEmpty()
            }.getOrDefault("")
        return Regex(
            """(?:^|/)c/([^/?#]+)(?:/|$)"""
        ).find(path)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
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

    private fun isTransientChatGptConversationPage(
        rawUrl: String?,
    ): Boolean =
        chatGptConversationId(rawUrl)
            ?.startsWith("WEB:", ignoreCase = true) == true

    private fun usableProviderNavigationUrl(
        rawUrl: String?,
        provider: ProviderSpec,
    ): String? =
        rawUrl
            ?.takeIf { sameProviderOrigin(it, provider) }
            ?.takeUnless {
                provider.id == "chatgpt" &&
                    isTransientChatGptConversationPage(it)
            }

    private fun shouldAllowChatGptTransientProductRoute(
        requested: String?,
        current: String?,
        provider: ProviderSpec,
    ): Boolean {
        if (provider.id != "chatgpt") return false
        if (!sameProviderOrigin(current.orEmpty(), provider)) {
            return false
        }

        val currentId =
            chatGptConversationId(current)
                ?: return false
        if (!currentId.startsWith("WEB:", ignoreCase = true)) {
            return false
        }

        val requestedId = chatGptConversationId(requested)
        return requestedId == null ||
            requestedId.startsWith("WEB:", ignoreCase = true)
    }

    private fun shouldAdoptChatGptProductRedirect(
        requested: String?,
        current: String?,
        provider: ProviderSpec,
    ): Boolean {
        if (provider.id != "chatgpt") return false
        if (!sameProviderOrigin(current.orEmpty(), provider)) {
            return false
        }

        val currentId =
            chatGptConversationId(current)
                ?: return false
        if (currentId.startsWith("WEB:", ignoreCase = true)) {
            return false
        }

        val requestedId = chatGptConversationId(requested)
        return requestedId == null ||
            requestedId.startsWith("WEB:", ignoreCase = true)
    }

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

    private companion object {
        const val CANONICAL_READ_PRODUCT_TIMEOUT_MS = 30_000L
        const val CANONICAL_HISTORY_PRODUCT_TIMEOUT_MS = 45_000L
        const val CANONICAL_RPC_RETURN_RESERVE_MS = 6_000L
        const val CANONICAL_TIMEOUT_MAX_ATTEMPTS = 2
        const val CANONICAL_TIMEOUT_RETRY_DELAY_MS = 250L
    }
}
