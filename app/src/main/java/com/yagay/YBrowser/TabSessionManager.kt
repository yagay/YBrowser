package com.yagay.YBrowser

import android.content.Context
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
            onContentLongPress = { delegate.onContentLongPress(it) },
            onWebPrompt = { delegate.onWebPrompt(it) },
            onAuthPrompt = { delegate.onAuthPrompt(it) },
            onMediaState = { delegate.onMediaState(it) },
            onToolbarVisibilityRequested = {
                delegate.onToolbarVisibilityRequested(it)
            },
        )
    }

    private data class Entry(
        val kind: BrowserEngineKind,
        val engine: BrowserEngine,
        val callbacks: CallbackRelay,
        var config: BrowserEngineConfig,
        var state: BrowserRenderState,
    )

    // Application context avoids retaining an Activity when a retained session
    // continues after its popup UI has been closed.
    private val context = context.applicationContext
    private var callbacksFactory = callbacksFactory
    private var onStateChanged = onStateChanged
    private val entries = linkedMapOf<Long, Entry>()

    fun attachHandlers(
        callbacksFactory: (Long) -> BrowserHostCallbacks,
        onStateChanged: (Long, BrowserRenderState) -> Unit,
    ) {
        this.callbacksFactory = callbacksFactory
        this.onStateChanged = onStateChanged
        entries.forEach { (tabId, entry) ->
            entry.callbacks.delegate = callbacksFactory(tabId)
            onStateChanged(tabId, entry.state)
        }
    }

    fun detachHandlers() {
        callbacksFactory = { BrowserHostCallbacks() }
        onStateChanged = { _, _ -> }
        entries.values.forEach { entry ->
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
                current.config = config
                current.engine.applyConfig(config)
            }
            return current.engine
        }

        current?.engine?.destroy()

        val relay = CallbackRelay(callbacksFactory(tab.id))
        val engine = createBrowserEngine(
            context = context,
            kind = kind,
            config = config,
            hostCallbacks = relay.stable,
        ) { state ->
            val entry = entries[tab.id]
            if (entry != null) {
                entry.state = state
            }
            onStateChanged(tab.id, state)
        }

        entries[tab.id] = Entry(
            kind = kind,
            engine = engine,
            callbacks = relay,
            config = config,
            state = BrowserRenderState(
                url = tab.url,
                title = tab.title,
            ),
        )

        if (tab.url.isNotBlank()) {
            engine.load(tab.url)
        }
        return engine
    }

    fun state(tabId: Long): BrowserRenderState? = entries[tabId]?.state

    fun kind(tabId: Long): BrowserEngineKind? = entries[tabId]?.kind

    fun has(tabId: Long): Boolean = entries.containsKey(tabId)

    fun capturePreview(tabId: Long, onComplete: (Bitmap?) -> Unit) {
        val engine = entries[tabId]?.engine
        if (engine == null) {
            onComplete(null)
            return
        }
        engine.capturePreview { captured ->
            onComplete(captured?.toPreviewThumbnail())
        }
    }

    fun captureAllPreviews(onPreview: (Long, Bitmap?) -> Unit) {
        entries.forEach { (tabId, entry) ->
            entry.engine.capturePreview { captured ->
                onPreview(tabId, captured?.toPreviewThumbnail())
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

    fun close(tabId: Long) {
        entries.remove(tabId)?.engine?.destroy()
    }

    fun replace(
        tab: BrowserTab,
        kind: BrowserEngineKind,
        config: BrowserEngineConfig,
    ): BrowserEngine {
        close(tab.id)
        return acquire(tab, kind, config)
    }

    fun applyConfig(tabId: Long, config: BrowserEngineConfig) {
        val entry = entries[tabId] ?: return
        if (entry.config == config) return
        entry.config = config
        entry.engine.applyConfig(config)
    }

    fun stop(tabId: Long) {
        entries[tabId]?.engine?.stop()
    }

    fun mediaCommand(tabId: Long, command: BrowserMediaCommand) {
        entries[tabId]?.engine?.mediaCommand(command)
    }

    fun destroyAll() {
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
const val RETAINED_AI_SESSION_POOL_KEY =
    "yagayhub_bound_ai_sessions"

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
