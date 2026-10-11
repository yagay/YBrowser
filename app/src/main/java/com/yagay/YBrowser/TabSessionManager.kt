package com.yagay.YBrowser

import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import kotlin.math.roundToInt
import java.util.UUID

/**
 * Keeps one live rendering engine per tab for the lifetime of the manager.
 *
 * For retained AI sessions the manager itself can live for the whole process.
 * UI callbacks are relayed and can be re-attached when a new Activity/Compose
 * tree takes ownership, so retaining an engine does not retain the old UI.
 */
class TabSessionManager(
    context: Context,
    callbacksFactory: (Long) -> BrowserHostCallbacks = { BrowserHostCallbacks() },
    onStateChanged: (Long, BrowserRenderState) -> Unit = { _, _ -> },
) {
    private class CallbackRelay(
        initial: BrowserHostCallbacks,
    ) {
        var delegate: BrowserHostCallbacks = initial

        val stable = BrowserHostCallbacks(
            onFilePrompt = { delegate.onFilePrompt(it) },
            onSitePermission = { delegate.onSitePermission(it) },
            onAndroidPermissions = { delegate.onAndroidPermissions(it) },
            onFullscreenChanged = { delegate.onFullscreenChanged(it) },
            onCustomView = { view, exit -> delegate.onCustomView(view, exit) },
            onOpenNewTab = { delegate.onOpenNewTab(it) },
            onUserNavigation = { delegate.onUserNavigation(it) },
            onContentLongPress = { delegate.onContentLongPress(it) },
            onWebPrompt = { delegate.onWebPrompt(it) },
            onAuthPrompt = { delegate.onAuthPrompt(it) },
            onMediaState = { delegate.onMediaState(it) },
            onContentBlocked = { delegate.onContentBlocked(it) },
            onEngineCrashed = { delegate.onEngineCrashed() },
            onDownloadChoice = { delegate.onDownloadChoice(it) },
        )
    }

    private data class Entry(
        val token: Any,
        val kind: BrowserEngineKind,
        val engine: BrowserEngine,
        val callbacks: CallbackRelay,
        val contextWrapper: MutableContextWrapper,
        var config: BrowserEngineConfig,
        var state: BrowserRenderState,
    )

    // Keep only the application context permanently. Each live engine gets a
    // MutableContextWrapper so it can use the current Activity while visible,
    // then fall back to the application context without destroying the page.
    private val context = context.applicationContext
    private var hostContext: Context? = null
    private var callbacksFactory = callbacksFactory
    private var onStateChanged = onStateChanged
    private val entries = linkedMapOf<Long, Entry>()
    // Retain a token after eviction only while a late Gecko state flush is valid.
    // Reopening/replacing the tab invalidates callbacks from the former engine.
    private val sessionTokens = TabCallbackOwnership()
    private val sessionStateStore = BrowserTabSessionStateStore(context.applicationContext)

    fun attachHandlers(
        hostContext: Context,
        callbacksFactory: (Long) -> BrowserHostCallbacks,
        onStateChanged: (Long, BrowserRenderState) -> Unit,
    ) {
        // This can be called from Compose SideEffect. Identical registrations must
        // never replay state and cause another recomposition/registration cycle.
        if (this.hostContext === hostContext &&
            this.callbacksFactory === callbacksFactory &&
            this.onStateChanged === onStateChanged
        ) {
            // Refresh action listeners to the current composition without sending
            // identical page state back into Compose (which caused a feedback loop).
            entries.forEach { (tabId, entry) ->
                entry.callbacks.delegate = callbacksFactory(tabId)
            }
            return
        }
        this.hostContext = hostContext
        this.callbacksFactory = callbacksFactory
        this.onStateChanged = onStateChanged
        entries.forEach { (tabId, entry) ->
            entry.contextWrapper.baseContext = hostContext
            entry.callbacks.delegate = callbacksFactory(tabId)
            onStateChanged(tabId, entry.state)
        }
    }

    fun detachHandlers() {
        hostContext = null
        callbacksFactory = { BrowserHostCallbacks() }
        onStateChanged = { _, _ -> }
        entries.values.forEach { entry ->
            entry.contextWrapper.baseContext = context
            entry.callbacks.delegate = BrowserHostCallbacks()
        }
    }

    fun acquire(
        tab: BrowserTab,
        kind: BrowserEngineKind,
        config: BrowserEngineConfig,
    ): BrowserEngine {
        val current = entries[tab.id]
        if (current != null && current.kind == kind) {
            current.callbacks.delegate = callbacksFactory(tab.id)
            if (current.config != config) {
                current.engine.applyConfig(config)
                current.config = config
            }
            return current.engine
        }

        val token = sessionTokens.begin(tab.id)
        entries.remove(tab.id)
        // Failure while shutting down a crashed renderer must not prevent a
        // replacement renderer from being created.
        current?.engine?.let { old -> runCatching { old.destroy() } }
        if (current != null &&
            (current.kind != kind || current.config.profileId != config.profileId)
        ) {
            // A different renderer/profile must not later restore a stale Gecko
            // back-forward stack for the same logical tab ID.
            sessionStateStore.delete(current.config.profileId, tab.id)
        }

        val stateFromDisk =
            if (kind == BrowserEngineKind.GECKO && !tab.privateMode) {
                sessionStateStore.read(config.profileId, tab.id)
            } else null
        val relay = CallbackRelay(callbacksFactory(tab.id))
        var initializing = true
        var initialState = BrowserRenderState(url = tab.url, title = tab.title)
        val contextWrapper = MutableContextWrapper(
            hostContext ?: context,
        )
        val engine = createBrowserEngine(
            context = contextWrapper,
            kind = kind,
            config = config,
            hostCallbacks = relay.stable,
            onState = { state ->
                // Once evicted, the token remains valid for Gecko state flushes,
                // but must NOT forward stale page events to the UI.
                if (sessionTokens.isCurrent(tab.id, token) &&
                    (initializing || entries[tab.id]?.token === token)
                ) {
                    initialState = state
                    entries[tab.id]?.takeIf { it.token === token }?.state = state
                    onStateChanged(tab.id, state)
                }
            },
            savedSessionState = stateFromDisk,
            onSessionStateSaved = { encoded ->
                if (!tab.privateMode && sessionTokens.isCurrent(tab.id, token)) {
                    sessionStateStore.saveAsync(config.profileId, tab.id, encoded)
                }
            },
        )

        entries[tab.id] = Entry(
            token = token,
            kind = kind,
            engine = engine,
            callbacks = relay,
            contextWrapper = contextWrapper,
            config = config,
            state = initialState,
        )
        initializing = false

        if (tab.url.isNotBlank() && !engine.restoredFromSavedState) {
            engine.load(tab.url)
        }
        return engine
    }

    fun state(tabId: Long): BrowserRenderState? = entries[tabId]?.state

    /** The exact in-memory renderer for this tab, never a new navigation. */
    fun engine(tabId: Long): BrowserEngine? = entries[tabId]?.engine

    fun kind(tabId: Long): BrowserEngineKind? = entries[tabId]?.kind

    fun has(tabId: Long): Boolean = entries.containsKey(tabId)

    fun capturePreview(tabId: Long, onComplete: (Bitmap?) -> Unit) {
        val engine = entries[tabId]?.engine
        if (engine == null) {
            onComplete(null)
            return
        }
        engine.capturePreview { captured ->
            if (entries[tabId]?.engine === engine) {
                onComplete(captured?.toPreviewThumbnail())
            } else {
                if (captured != null && !captured.isRecycled) captured.recycle()
                onComplete(null)
            }
        }
    }

    fun captureAllPreviews(onPreview: (Long, Bitmap?) -> Unit) {
        // Capture callbacks may complete synchronously and change the tab set.
        entries.toList().forEach { (tabId, entry) ->
            entry.engine.capturePreview { captured ->
                if (entries[tabId]?.engine === entry.engine) {
                    onPreview(tabId, captured?.toPreviewThumbnail())
                } else {
                    if (captured != null && !captured.isRecycled) captured.recycle()
                }
            }
        }
    }

    private fun Bitmap.toPreviewThumbnail(): Bitmap {
        if (width <= 0 || height <= 0) return this
        val widthScale = PREVIEW_MAX_WIDTH_PX.toFloat() / width.toFloat()
        val heightScale = PREVIEW_MAX_HEIGHT_PX.toFloat() / height.toFloat()
        val scale = minOf(1f, widthScale, heightScale)
        if (scale >= 1f) return this

        val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
        val thumbnail = Bitmap.createScaledBitmap(
            this,
            targetWidth,
            targetHeight,
            true,
        )
        if (thumbnail !== this && !isRecycled) recycle()
        return thumbnail
    }

    fun close(
        tabId: Long,
        preserveSavedState: Boolean = false,
        profileId: String? = null,
    ) {
        val entry = entries.remove(tabId)
        sessionTokens.retire(tabId, preserveSavedState)
        if (preserveSavedState) {
            entry?.engine?.let { runCatching { it.flushSessionState() } }
        }
        entry?.engine?.let { runCatching { it.destroy() } }
        if (!preserveSavedState) {
            // A tab evicted from RAM may no longer have an Entry; its persisted
            // Gecko session must still be removed when the user closes it.
            (entry?.config?.profileId ?: profileId)?.let {
                sessionStateStore.delete(it, tabId)
            }
        }
    }

    fun replace(
        tab: BrowserTab,
        kind: BrowserEngineKind,
        config: BrowserEngineConfig,
    ): BrowserEngine {
        close(tab.id, profileId = config.profileId)
        return acquire(tab, kind, config)
    }

    fun applyConfig(tabId: Long, config: BrowserEngineConfig) {
        val entry = entries[tabId] ?: return
        if (entry.config == config) return
        entry.engine.applyConfig(config)
        entry.config = config
    }

    fun stop(tabId: Long) {
        entries[tabId]?.engine?.stop()
    }

    fun load(tabId: Long, url: String): Boolean {
        val entry = entries[tabId] ?: return false
        entry.state = entry.state.copy(url = url, title = url)
        entry.engine.load(url)
        return true
    }

    fun mediaCommand(tabId: Long, command: BrowserMediaCommand) {
        entries[tabId]?.engine?.mediaCommand(command)
    }

    fun destroyAll() {
        sessionTokens.clear()
        entries.values.forEach { entry ->
            runCatching { entry.engine.destroy() }
        }
        entries.clear()
    }

    fun activeCount(): Int = entries.size

    companion object {
        private const val PREVIEW_MAX_WIDTH_PX = 420
        private const val PREVIEW_MAX_HEIGHT_PX = 720
    }
}

/**
 * Process-wide retained session pools. A retained pool survives Activity and
 * Compose disposal, allowing bound AI pages to keep their network/JS session
 * alive while the popup UI is closed.
 */
object BrowserSessionRegistry {
    private val pools = linkedMapOf<String, TabSessionManager>()

    @Synchronized
    fun get(
        context: Context,
        key: String,
    ): TabSessionManager {
        return pools.getOrPut(key) {
            TabSessionManager(context.applicationContext)
        }
    }

    @Synchronized
    fun close(
        key: String,
        tabId: Long,
    ) {
        pools[key]?.close(tabId)
    }

    @Synchronized
    fun destroy(key: String) {
        pools.remove(key)?.destroyAll()
    }

    @Synchronized
    fun activeCount(key: String): Int =
        pools[key]?.activeCount() ?: 0
}

fun retainedSessionTabId(url: String): Long =
    UUID.nameUUIDFromBytes(
        url.trim().trimEnd('/').toByteArray(Charsets.UTF_8),
    ).mostSignificantBits
