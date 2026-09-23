package com.yagay.ybrowser.ai.web

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.FrameLayout
import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ChatWindow
import com.yagay.ybrowser.ai.model.ProviderSpec

class WindowWebRuntime(context: Context) {
    private val geckoRuntime = sharedGeckoRuntime(context.applicationContext)

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

    fun setFileChooserLauncher(launcher: ((Intent) -> Unit)?) =
        geckoRuntime.setFileChooserLauncher(launcher)

    fun setFileSelectionListener(
        listener: ((String, ProviderSpec, List<AttachmentMeta>) -> Unit)?,
    ) = geckoRuntime.setFileSelectionListener(listener)

    fun setPageChangeListener(
        listener: ((String, ProviderSpec, String) -> Unit)?,
    ) = geckoRuntime.setPageChangeListener(listener)

    fun setPageReadyListener(
        listener: ((String, ProviderSpec, String) -> Unit)?,
    ) = geckoRuntime.setPageReadyListener(listener)

    fun setConversationListener(
        listener: ((String, ProviderSpec, WebRuntime.ConversationSnapshot) -> Unit)?,
    ) = geckoRuntime.setConversationListener(listener)

    fun handleFileChooserResult(resultCode: Int, data: Intent?) =
        geckoRuntime.handleFileChooserResult(resultCode, data)

    fun handleAndroidPermissionResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ): Boolean = false

    fun attach(host: FrameLayout, window: ChatWindow, provider: ProviderSpec) =
        geckoRuntime.attach(host, window, provider)

    fun currentUrl(windowId: String, provider: ProviderSpec): String? =
        geckoRuntime.currentUrl(windowId, provider)

    fun detachView(
        windowId: String,
        provider: ProviderSpec,
    ) = geckoRuntime.detachView(windowId, provider)

    fun hasLiveSession(
        windowId: String,
        provider: ProviderSpec,
    ): Boolean = geckoRuntime.hasLiveSession(windowId, provider)

    fun isSessionReady(
        windowId: String,
        provider: ProviderSpec,
    ): Boolean = geckoRuntime.isSessionReady(windowId, provider)

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
    ) = geckoRuntime.freezeStaleBoundSessions(
        windows = windows,
        activeWindowId = activeWindowId,
        inactiveMs = inactiveMs,
    )

    fun prewarm(
        window: ChatWindow,
        provider: ProviderSpec,
    ) = geckoRuntime.prewarm(
        window = window,
        provider = provider,
    )

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
    ) = geckoRuntime.setChatPresentation(
        windowId = windowId,
        provider = provider,
        enabled = enabled,
    )

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
        geckoRuntime.canGoBack(windowId, provider)

    fun goBack(windowId: String, provider: ProviderSpec): Boolean =
        geckoRuntime.goBack(windowId, provider)

    fun resetProviderSession(windowId: String, provider: ProviderSpec) =
        geckoRuntime.resetProviderSession(windowId, provider)

    fun destroyWindow(windowId: String, provider: ProviderSpec) =
        geckoRuntime.destroyWindow(windowId, provider)

    fun flushCookies() = geckoRuntime.flushCookies()

    fun releaseUi() = geckoRuntime.releaseUi()

    fun destroy() = geckoRuntime.destroy()
}
