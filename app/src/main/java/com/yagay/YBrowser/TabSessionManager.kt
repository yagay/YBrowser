package com.yagay.YBrowser

import android.content.Context
import android.graphics.Bitmap

/**
 * Keeps one live rendering engine per tab for the lifetime of the process.
 *
 * Switching tabs only detaches/attaches the engine View in Compose; it does not
 * destroy the underlying WebView/GeckoSession. This preserves page history,
 * scroll position, forms, JavaScript state and media state while moving between tabs.
 */
class TabSessionManager(
    private val context: Context,
    private val callbacksFactory: (Long) -> BrowserHostCallbacks,
    private val onStateChanged: (Long, BrowserRenderState) -> Unit,
) {
    private data class Entry(
        val kind: BrowserEngineKind,
        val engine: BrowserEngine,
        var config: BrowserEngineConfig,
        var state: BrowserRenderState,
    )

    private val entries = linkedMapOf<Long, Entry>()

    fun acquire(
        tab: BrowserTab,
        kind: BrowserEngineKind,
        config: BrowserEngineConfig,
    ): BrowserEngine {
        val current = entries[tab.id]
        if (current != null && current.kind == kind) {
            if (current.config != config) {
                current.config = config
                current.engine.applyConfig(config)
            }
            return current.engine
        }

        current?.engine?.destroy()

        val engine = createBrowserEngine(
            context = context,
            kind = kind,
            config = config,
            hostCallbacks = callbacksFactory(tab.id),
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

    fun capturePreview(tabId: Long, onComplete: (Bitmap?) -> Unit) {
        val engine = entries[tabId]?.engine
        if (engine == null) {
            onComplete(null)
            return
        }
        engine.capturePreview(onComplete)
    }

    fun captureAllPreviews(onPreview: (Long, Bitmap?) -> Unit) {
        entries.forEach { (tabId, entry) ->
            entry.engine.capturePreview { bitmap ->
                onPreview(tabId, bitmap)
            }
        }
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

    fun destroyAll() {
        entries.values.forEach { entry ->
            runCatching { entry.engine.destroy() }
        }
        entries.clear()
    }

    fun activeCount(): Int = entries.size
}
