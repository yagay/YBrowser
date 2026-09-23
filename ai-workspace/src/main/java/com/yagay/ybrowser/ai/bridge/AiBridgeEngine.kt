package com.yagay.ybrowser.ai.bridge

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.yagay.ybrowser.ai.data.ConversationStore
import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ChatMessage
import com.yagay.ybrowser.ai.model.ChatWindow
import com.yagay.ybrowser.ai.model.MessageRole
import com.yagay.ybrowser.ai.model.WindowSessionKey
import com.yagay.ybrowser.ai.provider.ProviderCatalog
import com.yagay.ybrowser.ai.web.WebRuntime
import com.yagay.ybrowser.ai.web.WindowWebRuntime

/**
 * YBrowser-owned AI engine.
 *
 * AIHub is only a UI client. Browser session state, provider protocol parsing,
 * history persistence, file injection and send/stop all live here.
 */
internal class AiBridgeEngine private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val runtime = WindowWebRuntime(appContext)
    private val conversations = ConversationStore(appContext)
    private val sessions = AiBridgeSessionStore(appContext)
    private val pendingAttachments =
        mutableMapOf<String, List<AttachmentMeta>>()

    init {
        runtime.addConversationObserver(
            key = OBSERVER_KEY,
        ) { windowId, provider, snapshot ->
            val window = sessions.load(windowId)
                ?: return@addConversationObserver
            if (window.providerId != provider.id) {
                return@addConversationObserver
            }
            if (
                !window.boundUrl.isNullOrBlank() &&
                !sameConversationPage(
                    window.boundUrl,
                    snapshot.url,
                )
            ) {
                return@addConversationObserver
            }

            val incoming =
                (snapshot.messages.ifEmpty {
                    snapshot.visibleMessages
                }).mapNotNull { message ->
                    val role = when (
                        message.role.lowercase()
                    ) {
                        "user" -> MessageRole.USER
                        "assistant" -> MessageRole.ASSISTANT
                        else -> null
                    } ?: return@mapNotNull null

                    ChatMessage(
                        id = message.id.ifBlank {
                            role.name.lowercase() +
                                "-" +
                                message.text.hashCode()
                        },
                        role = role,
                        text = message.text,
                    )
                }

            if (incoming.isNotEmpty()) {
                val key = sessionKey(window)
                val previous = conversations.load(key)
                val merged = mergeMessages(
                    previous = previous,
                    incoming = incoming,
                )
                if (merged != previous) {
                    conversations.save(key, merged)
                    notifyHistory(windowId)
                }
            }
        }

        runtime.addPageObserver(
            key = OBSERVER_KEY,
        ) { windowId, provider, url ->
            val window = sessions.load(windowId)
                ?: return@addPageObserver
            if (window.providerId != provider.id) {
                return@addPageObserver
            }
            sessions.updateUrl(windowId, url)
            notifyHistory(windowId)
        }
    }

    fun ensureSession(window: ChatWindow) {
        sessions.save(window)
        val provider = ProviderCatalog.byId(window.providerId)
        runtime.ensurePreferredPage(
            window = window,
            provider = provider,
        )
    }

    fun updateBinding(window: ChatWindow) {
        val previous = sessions.load(window.id)
        sessions.save(window)
        val provider = ProviderCatalog.byId(window.providerId)

        if (
            previous?.boundUrl != null &&
            window.boundUrl != null &&
            !sameConversationPage(
                previous.boundUrl,
                window.boundUrl,
            )
        ) {
            // Keep project history. Only the browser target changes.
            runtime.reloadPage(
                window = window,
                provider = provider,
            )
        } else {
            runtime.ensurePreferredPage(
                window = window,
                provider = provider,
            )
        }
        notifyHistory(window.id)
    }

    suspend fun attachFiles(
        window: ChatWindow,
        uris: List<Uri>,
    ): WebRuntime.AttachmentAttachResult {
        ensureSession(window)
        val provider = ProviderCatalog.byId(window.providerId)
        val result = runtime.attachFiles(
            windowId = window.id,
            provider = provider,
            uris = uris,
        )

        if (result.attachedCount > 0) {
            val accepted = uris
                .take(result.attachedCount)
                .mapIndexed(::queryAttachmentMeta)
            synchronized(pendingAttachments) {
                pendingAttachments[window.id] = accepted
            }
            notifyHistory(window.id)
        }
        return result
    }

    suspend fun send(
        window: ChatWindow,
        prompt: String,
    ): Boolean {
        ensureSession(window)
        val provider = ProviderCatalog.byId(window.providerId)
        val attachments = synchronized(pendingAttachments) {
            pendingAttachments[window.id].orEmpty()
        }

        val visibleText = when {
            attachments.isNotEmpty() &&
                prompt.isBlank() ->
                "📎 " +
                    attachments.joinToString(", ") {
                        it.name
                    }
            attachments.isNotEmpty() ->
                prompt +
                    "\n\n📎 " +
                    attachments.joinToString(", ") {
                        it.name
                    }
            else -> prompt
        }

        if (
            visibleText.isNotBlank() ||
            attachments.isNotEmpty()
        ) {
            val key = sessionKey(window)
            val previous =
                conversations.load(key)
            val optimistic = ChatMessage(
                id =
                    "bridge-user-" +
                        System.nanoTime(),
                role = MessageRole.USER,
                text = visibleText,
                attachments = attachments,
            )
            conversations.save(
                key,
                mergeMessages(
                    previous,
                    listOf(optimistic),
                ),
            )
            notifyHistory(window.id)
        }

        val sent = runtime.send(
            windowId = window.id,
            provider = provider,
            prompt = prompt,
        )

        if (sent && attachments.isNotEmpty()) {
            runtime.markAttachmentsSubmitted(
                window.id,
                provider,
            )
            synchronized(pendingAttachments) {
                pendingAttachments.remove(window.id)
            }
            notifyHistory(window.id)
        }

        runtime.currentUrl(
            window.id,
            provider,
        )?.let {
            sessions.updateUrl(window.id, it)
        }

        return sent
    }

    suspend fun responseSnapshot(
        window: ChatWindow,
    ): WebRuntime.ResponseSnapshot {
        ensureSession(window)
        return runtime.responseSnapshot(
            window.id,
            ProviderCatalog.byId(window.providerId),
        )
    }

    suspend fun stop(window: ChatWindow) {
        runtime.stop(
            window.id,
            ProviderCatalog.byId(window.providerId),
        )
    }

    suspend fun syncConversation(
        window: ChatWindow,
    ): Int {
        ensureSession(window)

        val provider =
            ProviderCatalog.byId(window.providerId)
        val snapshot =
            runtime.conversationSnapshot(
                window = window,
                provider = provider,
            )

        val incoming =
            (snapshot.messages.ifEmpty {
                snapshot.visibleMessages
            }).mapNotNull { message ->
                val role =
                    when (
                        message.role.lowercase()
                    ) {
                        "user" -> MessageRole.USER
                        "assistant" ->
                            MessageRole.ASSISTANT
                        else -> null
                    } ?: return@mapNotNull null

                ChatMessage(
                    id =
                        message.id.ifBlank {
                            role.name.lowercase() +
                                "-" +
                                message.text.hashCode()
                        },
                    role = role,
                    text = message.text,
                )
            }

        val key = sessionKey(window)
        val previous = conversations.load(key)
        val merged =
            if (incoming.isEmpty()) {
                previous
            } else {
                mergeMessages(
                    previous = previous,
                    incoming = incoming,
                )
            }

        if (merged != previous) {
            conversations.save(key, merged)
        }

        if (
            snapshot.url.isNotBlank() &&
            snapshot.url != window.url
        ) {
            sessions.updateUrl(
                window.id,
                snapshot.url,
            )
        }

        notifyHistory(window.id)
        return merged.size
    }

    fun reload(window: ChatWindow) {
        sessions.save(window)
        runtime.reloadPage(
            window,
            ProviderCatalog.byId(window.providerId),
        )
    }

    fun markAttachmentsSubmitted(window: ChatWindow) {
        runtime.markAttachmentsSubmitted(
            window.id,
            ProviderCatalog.byId(window.providerId),
        )
        synchronized(pendingAttachments) {
            pendingAttachments.remove(window.id)
        }
        notifyHistory(window.id)
    }

    fun currentUrl(window: ChatWindow): String? {
        val provider = ProviderCatalog.byId(window.providerId)
        return runtime.currentUrl(
            window.id,
            provider,
        ) ?: sessions.load(window.id)?.url
    }

    fun messages(windowId: String): List<ChatMessage> {
        val window = sessions.load(windowId)
            ?: return emptyList()
        return conversations.load(
            sessionKey(window)
        )
    }

    fun storedSession(windowId: String): ChatWindow? =
        sessions.load(windowId)

    private fun mergeMessages(
        previous: List<ChatMessage>,
        incoming: List<ChatMessage>,
    ): List<ChatMessage> {
        if (incoming.isEmpty()) return previous

        val result = previous.toMutableList()

        incoming.forEach { next ->
            val byId = result.indexOfFirst {
                it.id == next.id &&
                    next.id.isNotBlank()
            }
            val byContent = result.indexOfFirst {
                it.role == next.role &&
                    normalizeText(it.text) ==
                        normalizeText(next.text) &&
                    normalizeText(next.text).isNotBlank()
            }

            val index = when {
                byId >= 0 -> byId
                byContent >= 0 -> byContent
                else -> -1
            }

            if (index >= 0) {
                val old = result[index]
                result[index] = next.copy(
                    timestamp = old.timestamp,
                    attachments =
                        if (old.attachments.isNotEmpty()) {
                            old.attachments
                        } else {
                            next.attachments
                        },
                )
            } else {
                result += next
            }
        }

        return result
    }

    private fun normalizeText(raw: String): String =
        raw.trim()
            .replace(
                Regex("[\\t ]+"),
                " ",
            )
            .replace(
                Regex("\\n{3,}"),
                "\n\n",
            )

    private fun notifyHistory(windowId: String) {
        appContext.contentResolver.notifyChange(
            Uri.parse(
                AiBridgeContract.BASE_URI +
                    "/" +
                    AiBridgeContract.PATH_CONVERSATIONS +
                    "/" +
                    Uri.encode(windowId)
            ),
            null,
        )
        appContext.contentResolver.notifyChange(
            Uri.parse(AiBridgeContract.BASE_URI),
            null,
        )
    }

    private fun queryAttachmentMeta(
        index: Int,
        uri: Uri,
    ): AttachmentMeta {
        var name = ""
        var size = 0L

        runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex =
                        cursor.getColumnIndex(
                            OpenableColumns.DISPLAY_NAME
                        )
                    val sizeIndex =
                        cursor.getColumnIndex(
                            OpenableColumns.SIZE
                        )
                    if (nameIndex >= 0) {
                        name =
                            cursor.getString(nameIndex)
                                .orEmpty()
                    }
                    if (
                        sizeIndex >= 0 &&
                        !cursor.isNull(sizeIndex)
                    ) {
                        size =
                            cursor.getLong(sizeIndex)
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
            mimeType =
                appContext.contentResolver
                    .getType(uri)
                    .orEmpty()
                    .ifBlank {
                        "application/octet-stream"
                    },
            sizeBytes = size,
        )
    }

    private fun sessionKey(
        window: ChatWindow,
    ) = WindowSessionKey(
        providerId = window.providerId,
        windowId = window.id,
    )

    private fun sameConversationPage(
        first: String?,
        second: String?,
    ): Boolean {
        if (
            first.isNullOrBlank() ||
            second.isNullOrBlank()
        ) {
            return false
        }
        return pageIdentity(first) == pageIdentity(second)
    }

    private fun pageIdentity(raw: String): String? =
        runCatching {
            val uri = Uri.parse(raw)
            val host = uri.host
                ?.lowercase()
                ?: return@runCatching null
            val path = uri.path
                .orEmpty()
                .trimEnd('/')
            if (path.isBlank()) {
                return@runCatching null
            }
            host + path
        }.getOrNull()

    companion object {
        private const val OBSERVER_KEY =
            "cross-app-ai-bridge"

        @Volatile
        private var instance: AiBridgeEngine? = null

        fun get(context: Context): AiBridgeEngine =
            instance ?: synchronized(this) {
                instance ?: AiBridgeEngine(
                    context.applicationContext
                ).also { instance = it }
            }
    }
}
