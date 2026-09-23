package com.yagay.ybrowser.ai.web

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.FrameLayout
import com.yagay.ybrowser.ai.data.AiTabCacheStore
import com.yagay.ybrowser.ai.data.PendingAttachmentStore
import com.yagay.ybrowser.ai.diagnostics.DiagnosticLogger
import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ChatWindow
import com.yagay.ybrowser.ai.model.ProviderSpec
import com.yagay.ybrowser.ai.provider.ProviderCatalog
import com.yagay.browsercore.GeckoCoreCallbacks
import com.yagay.browsercore.GeckoCoreFilePromptRequest
import com.yagay.browsercore.GeckoCoreSession
import com.yagay.browsercore.GeckoCoreSessionPool
import com.yagay.browsercore.GeckoCoreViewHost
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class GeckoProviderRuntime(private val context: Context) {
    private val loader = ScriptLoader(context.applicationContext)
    private val tabCacheStore = AiTabCacheStore(context.applicationContext)
    private val pendingAttachmentStore = PendingAttachmentStore(context.applicationContext)
    private val pool = GeckoCoreSessionPool(context.applicationContext)
    private val viewHost = GeckoCoreViewHost(context.applicationContext)
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
    private val archiveFingerprints =
        mutableMapOf<String, String>()
    private val archiveExecutor =
        Executors.newSingleThreadExecutor()
    private val sessionOwners =
        mutableMapOf<String, Pair<String, ProviderSpec>>()

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
    private val conversationObservers =
        linkedMapOf<
            String,
            (String, ProviderSpec, WebRuntime.ConversationSnapshot) -> Unit
        >()
    private val pageObservers =
        linkedMapOf<
            String,
            (String, ProviderSpec, String) -> Unit
        >()

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

    fun addConversationObserver(
        key: String,
        listener: (
            String,
            ProviderSpec,
            WebRuntime.ConversationSnapshot,
        ) -> Unit,
    ) {
        synchronized(conversationObservers) {
            conversationObservers[key] = listener
        }
    }

    fun removeConversationObserver(key: String) {
        synchronized(conversationObservers) {
            conversationObservers.remove(key)
        }
    }

    fun addPageObserver(
        key: String,
        listener: (String, ProviderSpec, String) -> Unit,
    ) {
        synchronized(pageObservers) {
            pageObservers[key] = listener
        }
    }

    fun removePageObserver(key: String) {
        synchronized(pageObservers) {
            pageObservers.remove(key)
        }
    }

    private fun notifyConversation(
        windowId: String,
        provider: ProviderSpec,
        snapshot: WebRuntime.ConversationSnapshot,
    ) {
        conversationListener?.invoke(
            windowId,
            provider,
            snapshot,
        )
        val observers = synchronized(conversationObservers) {
            conversationObservers.values.toList()
        }
        observers.forEach {
            runCatching {
                it(windowId, provider, snapshot)
            }
        }
    }

    private fun notifyPage(
        windowId: String,
        provider: ProviderSpec,
        url: String,
    ) {
        pageChangeListener?.invoke(windowId, provider, url)
        val observers = synchronized(pageObservers) {
            pageObservers.values.toList()
        }
        observers.forEach {
            runCatching {
                it(windowId, provider, url)
            }
        }
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

        viewHost.attach(
            host = host,
            hostContext = host.context,
            key = runtimeKey,
            session = session,
        )
        standbyKeys.remove(runtimeKey)
        session.setActive(true)
        session.setHighPriority(true)

        val authoritativeBoundUrl =
            window.boundUrl
                ?.takeIf {
                    sameProviderOrigin(it, provider)
                }
        val attachedUrl = session.currentState.url
        if (
            authoritativeBoundUrl != null &&
            (
                attachedUrl.isBlank() ||
                    attachedUrl == "about:blank" ||
                    !sameProviderPage(
                        attachedUrl,
                        authoritativeBoundUrl,
                        provider,
                    )
            )
        ) {
            bindingRefocusKeys.add(runtimeKey)
            initialNavigationUrls[runtimeKey] =
                authoritativeBoundUrl
            preferredUrls[runtimeKey] =
                authoritativeBoundUrl
            injectedKeys.remove(runtimeKey)

            DiagnosticLogger.recordBridgeTrace(
                stage = "attach-refocus",
                provider = provider.id,
                windowId = window.id,
                url = authoritativeBoundUrl,
                detail =
                    "attached=" + attachedUrl +
                        " bound-page-authoritative",
            )
            session.load(authoritativeBoundUrl)
        }

        touchSession(runtimeKey)
        trimHotSessions(protectedKey = runtimeKey)

        if (
            provider.id == "chatgpt" &&
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
            (window.boundUrl ?: window.url)
                ?.takeIf {
                    sameProviderOrigin(it, provider)
                }
                ?: return false
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

    /**
     * Low-priority warm-up for a cached tab. This creates/restores the Gecko
     * session and starts the bound ChatGPT page in the background without
     * attaching it to the visible GeckoView or changing the cache UI.
     */
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
        if (provider.id != "chatgpt") return

        val runtimeKey = key(window.id, provider)
        if (pool.get(runtimeKey) != null) {
            enterStandby(runtimeKey)
            touchSession(runtimeKey)
            trimHotSessions(protectedKey = runtimeKey)
            return
        }

        if (window.boundUrl.isNullOrBlank()) {
            tabCacheStore.markUnbound(window.id)
        } else {
            tabCacheStore.markBound(window)
        }

        val preferred = (window.boundUrl ?: window.url)
            ?.takeIf { sameProviderOrigin(it, provider) }

        val session = obtain(
            windowId = window.id,
            provider = provider,
            preferredUrl = preferred,
        )
        standbyKeys.add(runtimeKey)
        session.setFocused(false)
        session.setActive(false)
        session.setHighPriority(false)
        touchSession(runtimeKey)
        trimHotSessions(protectedKey = runtimeKey)

        DiagnosticLogger.recordBridgeTrace(
            stage = "session-prewarm",
            provider = provider.id,
            windowId = window.id,
            url = preferred.orEmpty(),
            detail = "background cache warmup",
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

        val preferred = (window.boundUrl ?: window.url)
            ?.takeIf { sameProviderOrigin(it, provider) }
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
        val preferred = (window.boundUrl ?: window.url)
            ?.takeIf { sameProviderOrigin(it, provider) }
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
        cancelLiveHandoff(runtimeKey)
        networkAssemblies.keys.removeAll { it.startsWith("$runtimeKey|") }
        networkFingerprints.removeAll { it.startsWith("$runtimeKey|") }
        sessionRecency.remove(runtimeKey)
        standbyKeys.remove(runtimeKey)
        bindingRefocusKeys.remove(runtimeKey)
        renderReadyUrls.remove(runtimeKey)
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

        // Inactivating flushes the freshest Gecko SessionState (including
        // scroll/history/form state) before the Activity host is detached.
        sessionOwners.keys.forEach { runtimeKey ->
            pool.get(runtimeKey)?.let { session ->
                session.setFocused(false)
                session.setHighPriority(false)
            }
        }
        pool.setAllActive(false)
        pool.flushAllSessionStates()
        viewHost.detachFromUi()

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
        sessionRecency.clear()
        standbyKeys.clear()
        bindingRefocusKeys.clear()
        renderReadyUrls.clear()
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
        val requestedUrl = preferredUrl
            ?.takeIf { sameProviderOrigin(it, provider) }
        val target = requestedUrl
            ?: preferredUrls[runtimeKey]
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

                if (
                    requestedPage != null &&
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
                    requestedPage != null &&
                    url.isNotBlank() &&
                    sameProviderPage(
                        url,
                        requestedPage,
                        provider,
                    )
                ) {
                    bindingRefocusKeys.remove(runtimeKey)
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

                val requestedPage =
                    initialNavigationUrls[runtimeKey]
                if (
                    requestedPage != null &&
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

                DiagnosticLogger.recordBridgeTrace(
                    stage = "page-ready",
                    provider = provider.id,
                    windowId = windowId,
                    url = currentUrl,
                    detail = "installing watcher"
                )
                // Protocol capture is the authoritative history path for
                // every supported provider, including ChatGPT. ChatGPT keeps
                // the DOM archive watcher as a separate visual snapshot layer.
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
                    if (
                        liveHandoffCallbacks
                            .containsKey(runtimeKey)
                    ) {
                        installLiveHandoffObserver(
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
                                notifyConversation(
                                    windowId,
                                    provider,
                                    snapshot,
                                )
                            }
                        }
                    }
                    "ai-network" -> {
                        handleNetworkEvent(
                            windowId = windowId,
                            provider = provider,
                            raw = payload,
                            transport = "webrequest",
                        )
                    }
                    "ai-page-network" -> {
                        handleNetworkEvent(
                            windowId = windowId,
                            provider = provider,
                            raw = payload,
                            transport = "page",
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

        if (
            requestedUrl != null &&
            existed &&
            initialNavigationUrls[runtimeKey] == null
        ) {
            // Existing sessions are attachment-only here. Remember the
            // requested page, but never call load() just because Compose
            // switched tabs or reattached the existing GeckoView.
            initialNavigationUrls[runtimeKey] = requestedUrl
        }

        return session
    }

    private fun touchSession(runtimeKey: String) {
        sessionRecency.remove(runtimeKey)
        sessionRecency.add(runtimeKey)
    }

    /**
     * Bound project tabs stay in standby for the first 24 hours after use so
     * switching back can attach the already-loaded ChatGPT page immediately.
     * Older bound tabs are frozen by freezeStaleBoundSessions(). Only
     * unbound/transient tabs are LRU-limited here.
     */
    private fun trimHotSessions(
        protectedKey: String,
    ) {
        val maxTransientSessions = 3
        fun transientCount(): Int =
            sessionRecency.count { candidate ->
                val windowId =
                    sessionOwners[candidate]?.first
                        ?: return@count false
                !tabCacheStore.isPersistent(windowId)
            }

        while (transientCount() > maxTransientSessions) {
            val visibleKey = viewHost.currentKey
            val victim = sessionRecency.firstOrNull { candidate ->
                val windowId =
                    sessionOwners[candidate]?.first
                        ?: return@firstOrNull false
                candidate != protectedKey &&
                    candidate != visibleKey &&
                    !liveHandoffCallbacks.containsKey(candidate) &&
                    !tabCacheStore.isPersistent(windowId)
            } ?: break

            evictHotSession(victim)
        }
    }

    private fun enterStandby(runtimeKey: String) {
        val session = pool.get(runtimeKey) ?: return
        standbyKeys.add(runtimeKey)
        session.setFocused(false)
        session.setHighPriority(false)
        session.setActive(false)

        if (
            sessionOwners[runtimeKey]
                ?.second
                ?.id == "chatgpt"
        ) {
            pauseArchiveWatcher(runtimeKey)
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
        viewHost.releaseIfBound(runtimeKey)
        pool.close(runtimeKey)

        if (owner != null) {
            val (windowId, provider) = owner
            DiagnosticLogger.recordBridgeTrace(
                stage = "session-evict",
                provider = provider.id,
                windowId = windowId,
                url = preferredUrls[runtimeKey].orEmpty(),
                detail = "reason=lru-cap max=3",
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
        val persistentBound =
            owner?.first?.let(tabCacheStore::isPersistent) == true

        // Bound project tabs use the long-lived Standby policy. They are
        // frozen only by freezeStaleBoundSessions() after 24h inactivity.
        if (persistentBound) {
            enterStandby(runtimeKey)
            DiagnosticLogger.recordBridgeTrace(
                stage = "session-standby",
                provider = owner?.second?.id.orEmpty(),
                windowId = owner?.first.orEmpty(),
                url = pool.get(runtimeKey)
                    ?.currentState
                    ?.url
                    .orEmpty(),
                detail = "bound-tab; 24h-freeze-policy",
            )
            return
        }

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
                            detail = "warm-grace-expired",
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
                detail = "warm-grace-expired",
            )
        }

        freezeTasks[runtimeKey] = task
        snapshotHandler.postDelayed(task, 45_000L)
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
        fun parseMessages(
            name: String,
        ): List<WebRuntime.PageConversationMessage> {
            val array = obj.optJSONArray(name) ?: JSONArray()
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val role = item.optString("role").lowercase()
                    val text = item.optString("text").trim()
                    if (
                        role !in setOf("user", "assistant") ||
                        text.isBlank()
                    ) {
                        continue
                    }
                    add(
                        WebRuntime.PageConversationMessage(
                            id = item.optString("id").ifBlank {
                                "$role-$index-${text.hashCode()}"
                            },
                            role = role,
                            text = text,
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
        notifyConversation(windowId, provider, snapshot)
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
