package com.yagay.ybrowser.ai.web

import android.content.Intent
import android.net.Uri
import android.widget.FrameLayout
import com.yagay.ybrowser.ai.data.AiTabCacheStore
import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ChatWindow
import com.yagay.ybrowser.ai.model.ProviderSpec

interface AiChatRuntime {
    val isStarted: Boolean

    fun currentUrl(
        windowId: String,
        provider: ProviderSpec,
    ): String?

    fun clearConversationCache(windowId: String)

    fun ensurePreferredPage(
        window: ChatWindow,
        provider: ProviderSpec,
    )

    fun reloadPage(
        window: ChatWindow,
        provider: ProviderSpec,
    )

    suspend fun attachFiles(
        windowId: String,
        provider: ProviderSpec,
        uris: List<Uri>,
    ): WebRuntime.AttachmentAttachResult

    suspend fun resolveAuthenticatedResource(
        windowId: String,
        provider: ProviderSpec,
        url: String,
        mimeHint: String? = null,
        maxBytes: Long = 6L * 1024L * 1024L,
    ): WebRuntime.ResolvedResource?

    suspend fun send(
        windowId: String,
        provider: ProviderSpec,
        prompt: String,
    ): Boolean

    suspend fun responseSnapshot(
        windowId: String,
        provider: ProviderSpec,
    ): WebRuntime.ResponseSnapshot

    suspend fun conversationSnapshot(
        window: ChatWindow,
        provider: ProviderSpec,
    ): WebRuntime.ConversationSnapshot

    suspend fun probeSummary(
        windowId: String,
        provider: ProviderSpec,
    ): String

    suspend fun stop(
        windowId: String,
        provider: ProviderSpec,
    )

    fun markAttachmentsSubmitted(
        windowId: String,
        provider: ProviderSpec,
    )

    fun destroyWindow(
        windowId: String,
        provider: ProviderSpec,
    )
}

interface AiWorkspaceRuntime : AiChatRuntime {
    fun setFileChooserLauncher(
        launcher: ((Intent) -> Unit)?,
    )

    fun setFileSelectionListener(
        listener:
            ((String, ProviderSpec, List<AttachmentMeta>) -> Unit)?,
    )

    fun setPageChangeListener(
        listener: ((String, ProviderSpec, String) -> Unit)?,
    )

    fun setPageReadyListener(
        listener: ((String, ProviderSpec, String) -> Unit)?,
    )

    fun setConversationListener(
        listener:
            ((String, ProviderSpec, WebRuntime.ConversationSnapshot) -> Unit)?,
    )

    fun setResponseChangeListener(
        listener: ((String, ProviderSpec) -> Unit)?,
    )

    fun handleFileChooserResult(
        resultCode: Int,
        data: Intent?,
    )

    fun handleAndroidPermissionResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ): Boolean

    fun attach(
        host: FrameLayout,
        window: ChatWindow,
        provider: ProviderSpec,
    )

    fun detachView(
        windowId: String,
        provider: ProviderSpec,
    )

    fun hasLiveSession(
        windowId: String,
        provider: ProviderSpec,
    ): Boolean

    fun isSessionReady(
        windowId: String,
        provider: ProviderSpec,
    ): Boolean

    fun isConversationRenderReady(
        window: ChatWindow,
        provider: ProviderSpec,
    ): Boolean

    fun cachedSnapshotHtml(windowId: String): String?

    fun archiveStatus(
        windowId: String,
    ): AiTabCacheStore.ArchiveStatus

    fun freezeStaleBoundSessions(
        windows: List<ChatWindow>,
        activeWindowId: String,
        inactiveMs: Long =
            24L * 60L * 60L * 1_000L,
    )

    fun prewarm(
        window: ChatWindow,
        provider: ProviderSpec,
    )

    fun requestLiveHandoff(
        window: ChatWindow,
        provider: ProviderSpec,
        timeoutMs: Long = 4_000L,
        callback: (Boolean, String) -> Unit,
    )

    fun setChatPresentation(
        windowId: String,
        provider: ProviderSpec,
        enabled: Boolean,
    )

    suspend fun isLoggedIn(
        windowId: String,
        provider: ProviderSpec,
    ): Boolean

    suspend fun startConversationHydration(
        windowId: String,
        provider: ProviderSpec,
    ): String

    suspend fun capabilities(
        windowId: String,
        provider: ProviderSpec,
    ): ProviderCapabilities

    suspend fun performAction(
        windowId: String,
        provider: ProviderSpec,
        action: String,
        arg: String? = null,
    ): String

    fun canGoBack(
        windowId: String,
        provider: ProviderSpec,
    ): Boolean

    fun goBack(
        windowId: String,
        provider: ProviderSpec,
    ): Boolean

    fun resetProviderSession(
        windowId: String,
        provider: ProviderSpec,
    )

    fun flushCookies()

    fun releaseUi()

    fun destroy()
}
