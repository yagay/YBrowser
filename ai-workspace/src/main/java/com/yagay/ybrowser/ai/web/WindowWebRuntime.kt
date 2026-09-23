package com.yagay.ybrowser.ai.web

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.FrameLayout
import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ChatWindow
import com.yagay.ybrowser.ai.model.ProviderSpec

class WindowWebRuntime(context: Context) {
    private val appContext = context.applicationContext

    @Volatile
    private var delegate: GeckoProviderRuntime? = null

    private var fileChooserLauncher: ((Intent) -> Unit)? = null
    private var fileSelectionListener:
        ((String, ProviderSpec, List<AttachmentMeta>) -> Unit)? = null
    private var pageChangeListener:
        ((String, ProviderSpec, String) -> Unit)? = null
    private var pageReadyListener:
        ((String, ProviderSpec, String) -> Unit)? = null
    private var conversationListener:
        ((String, ProviderSpec, WebRuntime.ConversationSnapshot) -> Unit)? = null

    private val geckoRuntime: GeckoProviderRuntime
        get() = requireRuntime()

    val isStarted: Boolean
        get() = existingRuntime() != null

    companion object {
        @Volatile
        private var processRuntime: GeckoProviderRuntime? = null

        private fun sharedGeckoRuntime(context: Context): GeckoProviderRuntime =
            processRuntime ?: synchronized(this) {
                processRuntime ?: GeckoProviderRuntime(
                    context.applicationContext
                ).also { processRuntime = it }
            }
    }

    private fun configure(runtime: GeckoProviderRuntime) {
        runtime.setFileChooserLauncher(fileChooserLauncher)
        runtime.setFileSelectionListener(fileSelectionListener)
        runtime.setPageChangeListener(pageChangeListener)
        runtime.setPageReadyListener(pageReadyListener)
        runtime.setConversationListener(conversationListener)
    }

    private fun existingRuntime(): GeckoProviderRuntime? {
        delegate?.let { return it }

        val shared = processRuntime ?: return null
        synchronized(this) {
            if (delegate == null) {
                delegate = shared
                configure(shared)
            }
            return delegate
        }
    }

    private fun requireRuntime(): GeckoProviderRuntime {
        existingRuntime()?.let { return it }

        synchronized(this) {
            existingRuntime()?.let { return it }

            return sharedGeckoRuntime(appContext).also {
                delegate = it
                configure(it)
            }
        }
    }

    fun setFileChooserLauncher(launcher: ((Intent) -> Unit)?) {
        fileChooserLauncher = launcher
        existingRuntime()?.setFileChooserLauncher(launcher)
    }

    fun setFileSelectionListener(
        listener: ((String, ProviderSpec, List<AttachmentMeta>) -> Unit)?,
    ) {
        fileSelectionListener = listener
        existingRuntime()?.setFileSelectionListener(listener)
    }

    fun setPageChangeListener(
        listener: ((String, ProviderSpec, String) -> Unit)?,
    ) {
        pageChangeListener = listener
        existingRuntime()?.setPageChangeListener(listener)
    }

    fun setPageReadyListener(
        listener: ((String, ProviderSpec, String) -> Unit)?,
    ) {
        pageReadyListener = listener
        existingRuntime()?.setPageReadyListener(listener)
    }

    fun setConversationListener(
        listener: ((String, ProviderSpec, WebRuntime.ConversationSnapshot) -> Unit)?,
    ) {
        conversationListener = listener
        existingRuntime()?.setConversationListener(listener)
    }

    fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        existingRuntime()?.handleFileChooserResult(resultCode, data)
    }

    fun handleAndroidPermissionResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ): Boolean = false

    fun attach(host: FrameLayout, window: ChatWindow, provider: ProviderSpec) =
        geckoRuntime.attach(host, window, provider)

    fun currentUrl(windowId: String, provider: ProviderSpec): String? =
        existingRuntime()?.currentUrl(windowId, provider)

    fun detachView(
        windowId: String,
        provider: ProviderSpec,
    ) {
        existingRuntime()?.detachView(windowId, provider)
    }

    fun hasLiveSession(
        windowId: String,
        provider: ProviderSpec,
    ): Boolean =
        existingRuntime()?.hasLiveSession(windowId, provider) ?: false

    fun isSessionReady(
        windowId: String,
        provider: ProviderSpec,
    ): Boolean =
        existingRuntime()?.isSessionReady(windowId, provider) ?: false

    fun isConversationRenderReady(
        window: ChatWindow,
        provider: ProviderSpec,
    ): Boolean =
        existingRuntime()?.isConversationRenderReady(
            window = window,
            provider = provider,
        ) ?: false

    fun cachedSnapshotHtml(windowId: String): String? =
        geckoRuntime.cachedSnapshotHtml(windowId)

    fun archiveStatus(
        windowId: String,
    ) = geckoRuntime.archiveStatus(windowId)

    fun clearConversationCache(windowId: String) =
        geckoRuntime.clearConversationCache(windowId)

    fun freezeStaleBoundSessions(
        windows: List<ChatWindow>,
        activeWindowId: String,
        inactiveMs: Long = 24L * 60L * 60L * 1_000L,
    ) {
        existingRuntime()?.freezeStaleBoundSessions(
            windows = windows,
            activeWindowId = activeWindowId,
            inactiveMs = inactiveMs,
        )
    }

    fun prewarm(
        window: ChatWindow,
        provider: ProviderSpec,
    ) {
        existingRuntime()?.prewarm(
            window = window,
            provider = provider,
        )
    }

    fun requestLiveHandoff(
        window: ChatWindow,
        provider: ProviderSpec,
        timeoutMs: Long = 4_000L,
        callback: (Boolean, String) -> Unit,
    ) = geckoRuntime.requestLiveHandoff(
        window = window,
        provider = provider,
        timeoutMs = timeoutMs,
        callback = callback,
    )

    fun ensurePreferredPage(window: ChatWindow, provider: ProviderSpec) =
        geckoRuntime.ensurePreferredPage(window, provider)

    fun reloadPage(
        window: ChatWindow,
        provider: ProviderSpec,
    ) = geckoRuntime.reloadPage(window, provider)


    fun setChatPresentation(
        windowId: String,
        provider: ProviderSpec,
        enabled: Boolean,
    ) {
        existingRuntime()?.setChatPresentation(
            windowId = windowId,
            provider = provider,
            enabled = enabled,
        )
    }

    suspend fun isLoggedIn(windowId: String, provider: ProviderSpec): Boolean =
        geckoRuntime.isLoggedIn(windowId, provider)

    suspend fun attachFiles(
        windowId: String,
        provider: ProviderSpec,
        uris: List<Uri>,
    ): WebRuntime.AttachmentAttachResult =
        geckoRuntime.attachFiles(windowId, provider, uris)

    suspend fun send(
        windowId: String,
        provider: ProviderSpec,
        prompt: String,
    ): Boolean = geckoRuntime.send(windowId, provider, prompt)

    suspend fun responseSnapshot(
        windowId: String,
        provider: ProviderSpec,
    ): WebRuntime.ResponseSnapshot =
        geckoRuntime.responseSnapshot(windowId, provider)

    suspend fun conversationSnapshot(
        window: ChatWindow,
        provider: ProviderSpec,
    ): WebRuntime.ConversationSnapshot =
        geckoRuntime.conversationSnapshot(
            windowId = window.id,
            provider = provider,
            preferredUrl = window.boundUrl ?: window.url,
        )

    suspend fun startConversationHydration(
        windowId: String,
        provider: ProviderSpec,
    ): String = geckoRuntime.startConversationHydration(windowId, provider)

    suspend fun probeSummary(windowId: String, provider: ProviderSpec): String =
        geckoRuntime.probeSummary(windowId, provider)

    suspend fun capabilities(
        windowId: String,
        provider: ProviderSpec,
    ): ProviderCapabilities =
        geckoRuntime.capabilities(windowId, provider)

    suspend fun performAction(
        windowId: String,
        provider: ProviderSpec,
        action: String,
        arg: String? = null,
    ): String = geckoRuntime.performAction(windowId, provider, action, arg)

    suspend fun stop(windowId: String, provider: ProviderSpec) {
        geckoRuntime.stop(windowId, provider)
    }

    fun markAttachmentsSubmitted(windowId: String, provider: ProviderSpec) =
        geckoRuntime.markAttachmentsSubmitted(windowId, provider)

    fun canGoBack(windowId: String, provider: ProviderSpec): Boolean =
        existingRuntime()?.canGoBack(windowId, provider) ?: false

    fun goBack(windowId: String, provider: ProviderSpec): Boolean =
        existingRuntime()?.goBack(windowId, provider) ?: false

    fun resetProviderSession(windowId: String, provider: ProviderSpec) {
        existingRuntime()?.resetProviderSession(windowId, provider)
    }

    fun destroyWindow(windowId: String, provider: ProviderSpec) {
        existingRuntime()?.destroyWindow(windowId, provider)
    }

    fun flushCookies() {
        existingRuntime()?.flushCookies()
    }

    fun releaseUi() {
        existingRuntime()?.releaseUi()
    }

    fun destroy() {
        existingRuntime()?.destroy()
    }
}
