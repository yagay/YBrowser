package com.yagay.ybrowser.ai.web

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.FrameLayout
import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ChatWindow
import com.yagay.ybrowser.ai.model.ProviderSpec

class WindowWebRuntime(context: Context) : AiWorkspaceRuntime {
    private val appContext = context.applicationContext
    private val uiOwnerId = nextUiOwnerId()

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
    private var responseChangeListener:
        ((String, ProviderSpec) -> Unit)? = null

    private val geckoRuntime: GeckoProviderRuntime
        get() = requireRuntime()

    override val isStarted: Boolean
        get() = existingRuntime() != null

    companion object {
        @Volatile
        private var processRuntime: GeckoProviderRuntime? = null

        @Volatile
        private var ownerSequence: Long = 0L

        @Volatile
        private var activeUiOwnerId: Long = 0L

        private fun nextUiOwnerId(): Long =
            synchronized(this) {
                ownerSequence += 1L
                ownerSequence
            }

        private fun sharedGeckoRuntime(context: Context): GeckoProviderRuntime =
            processRuntime ?: synchronized(this) {
                processRuntime ?: GeckoProviderRuntime(
                    context.applicationContext
                ).also { processRuntime = it }
            }
    }

    private fun configure(runtime: GeckoProviderRuntime) {
        activeUiOwnerId = uiOwnerId
        runtime.setFileChooserLauncher(fileChooserLauncher)
        runtime.setFileSelectionListener(fileSelectionListener)
        runtime.setPageChangeListener(pageChangeListener)
        runtime.setPageReadyListener(pageReadyListener)
        runtime.setConversationListener(conversationListener)
        runtime.setResponseChangeListener(responseChangeListener)
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

    override fun setFileChooserLauncher(launcher: ((Intent) -> Unit)?) {
        fileChooserLauncher = launcher
        existingRuntime()?.setFileChooserLauncher(launcher)
    }

    override fun setFileSelectionListener(
        listener: ((String, ProviderSpec, List<AttachmentMeta>) -> Unit)?,
    ) {
        fileSelectionListener = listener
        existingRuntime()?.setFileSelectionListener(listener)
    }

    override fun setPageChangeListener(
        listener: ((String, ProviderSpec, String) -> Unit)?,
    ) {
        pageChangeListener = listener
        existingRuntime()?.setPageChangeListener(listener)
    }

    override fun setPageReadyListener(
        listener: ((String, ProviderSpec, String) -> Unit)?,
    ) {
        pageReadyListener = listener
        existingRuntime()?.setPageReadyListener(listener)
    }

    override fun setConversationListener(
        listener: ((String, ProviderSpec, WebRuntime.ConversationSnapshot) -> Unit)?,
    ) {
        conversationListener = listener
        existingRuntime()?.setConversationListener(listener)
    }

    override fun setResponseChangeListener(
        listener: ((String, ProviderSpec) -> Unit)?,
    ) {
        responseChangeListener = listener
        existingRuntime()?.setResponseChangeListener(listener)
    }

    override fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        existingRuntime()?.handleFileChooserResult(resultCode, data)
    }

    override fun handleAndroidPermissionResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ): Boolean = false

    override fun attach(
        host: FrameLayout,
        window: ChatWindow,
        provider: ProviderSpec,
    ) {
        activeUiOwnerId = uiOwnerId
        geckoRuntime.attach(host, window, provider)
    }

    override fun currentUrl(windowId: String, provider: ProviderSpec): String? =
        existingRuntime()?.currentUrl(windowId, provider)

    override fun detachView(
        windowId: String,
        provider: ProviderSpec,
    ) {
        existingRuntime()?.detachView(windowId, provider)
    }

    fun retainedSessionCount(): Int =
        existingRuntime()?.retainedSessionCount() ?: 0

    fun preloadState(
        windowId: String,
        provider: ProviderSpec,
    ): String =
        existingRuntime()
            ?.preloadState(windowId, provider)
            ?: "COLD"

    fun canPreload(
        windowId: String,
        provider: ProviderSpec,
    ): Boolean =
        existingRuntime()
            ?.canPreload(windowId, provider)
            ?: true

    fun attachPreload(
        host: FrameLayout,
        window: ChatWindow,
        provider: ProviderSpec,
        callback: (Boolean, String) -> Unit,
    ) {
        activeUiOwnerId = uiOwnerId
        geckoRuntime.attachPreload(
            host = host,
            window = window,
            provider = provider,
            callback = callback,
        )
    }

    fun detachPreloadView(
        windowId: String,
        provider: ProviderSpec,
    ) {
        if (activeUiOwnerId != uiOwnerId) {
            return
        }
        existingRuntime()?.detachPreloadView(
            windowId,
            provider,
        )
    }

    fun detachPreloadView() {
        if (activeUiOwnerId != uiOwnerId) {
            return
        }
        existingRuntime()?.detachPreloadView()
    }

    override fun hasLiveSession(
        windowId: String,
        provider: ProviderSpec,
    ): Boolean =
        existingRuntime()?.hasLiveSession(windowId, provider) ?: false

    override fun isSessionReady(
        windowId: String,
        provider: ProviderSpec,
    ): Boolean =
        existingRuntime()?.isSessionReady(windowId, provider) ?: false

    override fun isConversationRenderReady(
        window: ChatWindow,
        provider: ProviderSpec,
    ): Boolean =
        existingRuntime()?.isConversationRenderReady(
            window = window,
            provider = provider,
        ) ?: false

    override fun cachedSnapshotHtml(windowId: String): String? =
        geckoRuntime.cachedSnapshotHtml(windowId)

    override fun archiveStatus(
        windowId: String,
    ) = geckoRuntime.archiveStatus(windowId)

    override fun clearConversationCache(windowId: String) =
        geckoRuntime.clearConversationCache(windowId)

    override fun freezeStaleBoundSessions(
        windows: List<ChatWindow>,
        activeWindowId: String,
        inactiveMs: Long,
    ) {
        existingRuntime()?.freezeStaleBoundSessions(
            windows = windows,
            activeWindowId = activeWindowId,
            inactiveMs = inactiveMs,
        )
    }

    override fun prewarm(
        window: ChatWindow,
        provider: ProviderSpec,
    ) {
        existingRuntime()?.prewarm(
            window = window,
            provider = provider,
        )
    }

    override fun requestLiveHandoff(
        window: ChatWindow,
        provider: ProviderSpec,
        timeoutMs: Long,
        callback: (Boolean, String) -> Unit,
    ) = geckoRuntime.requestLiveHandoff(
        window = window,
        provider = provider,
        timeoutMs = timeoutMs,
        callback = callback,
    )

    override fun ensurePreferredPage(window: ChatWindow, provider: ProviderSpec) =
        geckoRuntime.ensurePreferredPage(window, provider)

    override fun reloadPage(
        window: ChatWindow,
        provider: ProviderSpec,
    ) = geckoRuntime.reloadPage(window, provider)


    override fun setChatPresentation(
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

    override suspend fun isLoggedIn(windowId: String, provider: ProviderSpec): Boolean =
        geckoRuntime.isLoggedIn(windowId, provider)

    override suspend fun attachFiles(
        windowId: String,
        provider: ProviderSpec,
        uris: List<Uri>,
    ): WebRuntime.AttachmentAttachResult =
        geckoRuntime.attachFiles(windowId, provider, uris)

    override suspend fun resolveAuthenticatedResource(
        windowId: String,
        provider: ProviderSpec,
        url: String,
        mimeHint: String?,
        maxBytes: Long,
    ): WebRuntime.ResolvedResource? =
        geckoRuntime.resolveAuthenticatedResource(
            windowId = windowId,
            provider = provider,
            url = url,
            mimeHint = mimeHint,
            maxBytes = maxBytes,
        )

    override suspend fun send(
        windowId: String,
        provider: ProviderSpec,
        prompt: String,
    ): WebRuntime.SendResult =
        geckoRuntime.send(
            windowId,
            provider,
            prompt,
        )

    override suspend fun responseSnapshot(
        windowId: String,
        provider: ProviderSpec,
    ): WebRuntime.ResponseSnapshot =
        geckoRuntime.responseSnapshot(windowId, provider)

    override suspend fun conversationSnapshot(
        window: ChatWindow,
        provider: ProviderSpec,
    ): WebRuntime.ConversationSnapshot =
        geckoRuntime.conversationSnapshot(
            windowId = window.id,
            provider = provider,
            preferredUrl = window.boundUrl ?: window.url,
        )

    override suspend fun canonicalConversationSnapshot(
        window: ChatWindow,
        provider: ProviderSpec,
        includeAllPages: Boolean,
    ): WebRuntime.ConversationSnapshot? =
        geckoRuntime.canonicalConversationSnapshot(
            windowId = window.id,
            provider = provider,
            preferredUrl = window.boundUrl ?: window.url,
            conversationId =
                window.boundConversationId,
            includeAllPages = includeAllPages,
        )

    override suspend fun startConversationHydration(
        windowId: String,
        provider: ProviderSpec,
    ): String = geckoRuntime.startConversationHydration(windowId, provider)

    override suspend fun probeSummary(windowId: String, provider: ProviderSpec): String =
        geckoRuntime.probeSummary(windowId, provider)

    override suspend fun capabilities(
        windowId: String,
        provider: ProviderSpec,
    ): ProviderCapabilities =
        geckoRuntime.capabilities(windowId, provider)

    override suspend fun performAction(
        windowId: String,
        provider: ProviderSpec,
        action: String,
        arg: String?,
    ): String = geckoRuntime.performAction(windowId, provider, action, arg)

    override suspend fun stop(windowId: String, provider: ProviderSpec) {
        geckoRuntime.stop(windowId, provider)
    }

    override fun markAttachmentsSubmitted(windowId: String, provider: ProviderSpec) =
        geckoRuntime.markAttachmentsSubmitted(windowId, provider)

    override fun canGoBack(windowId: String, provider: ProviderSpec): Boolean =
        existingRuntime()?.canGoBack(windowId, provider) ?: false

    override fun goBack(windowId: String, provider: ProviderSpec): Boolean =
        existingRuntime()?.goBack(windowId, provider) ?: false

    override fun resetProviderSession(windowId: String, provider: ProviderSpec) {
        existingRuntime()?.resetProviderSession(windowId, provider)
    }

    override fun destroyWindow(windowId: String, provider: ProviderSpec) {
        existingRuntime()?.destroyWindow(windowId, provider)
    }

    override fun flushCookies() {
        existingRuntime()?.flushCookies()
    }

    override fun releaseUi() {
        if (activeUiOwnerId != uiOwnerId) {
            return
        }
        existingRuntime()?.releaseUi()
        if (activeUiOwnerId == uiOwnerId) {
            activeUiOwnerId = 0L
        }
    }

    override fun destroy() {
        existingRuntime()?.destroy()
    }
}
