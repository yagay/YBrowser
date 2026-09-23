package com.yagay.ybrowser.ai.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yagay.ybrowser.ai.AiWorkspaceContract
import com.yagay.ybrowser.ai.data.AiTabCacheStore
import com.yagay.ybrowser.ai.data.ConversationStore
import com.yagay.ybrowser.ai.data.PendingAttachmentStore
import com.yagay.ybrowser.ai.data.WindowStore
import com.yagay.ybrowser.ai.diagnostics.DiagnosticLogger
import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ChatMessage
import com.yagay.ybrowser.ai.model.ChatWindow
import com.yagay.ybrowser.ai.model.MessageRole
import com.yagay.ybrowser.ai.model.ProviderSpec
import com.yagay.ybrowser.ai.model.WindowSessionKey
import com.yagay.ybrowser.ai.model.WindowViewMode
import com.yagay.ybrowser.ai.provider.ProviderCatalog
import com.yagay.ybrowser.ai.web.WebRuntime
import com.yagay.ybrowser.ai.web.WindowWebRuntime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private data class SharedBinding(
        val repoKey: String,
        val project: String,
        val url: String,
        val title: String,
    )

    private val windowStore = WindowStore(application)
    private val aiTabCacheStore = AiTabCacheStore(application)
    private val conversationStore = ConversationStore(application)
    private val pendingAttachmentStore = PendingAttachmentStore(application)

    val providers = ProviderCatalog.all

    var windows by mutableStateOf<List<ChatWindow>>(emptyList())
        private set

    var activeWindowId by mutableStateOf("")
        private set

    val messages = mutableStateListOf<ChatMessage>()

    private val statuses = mutableStateMapOf<String, String?>()
    private val pendingAttachments = mutableStateMapOf<String, List<AttachmentMeta>>()
    private val drafts = mutableStateMapOf<String, String>()
    private val generationJobs = mutableMapOf<String, Job>()
    private val syncJobs = mutableMapOf<String, Job>()
    private val networkHistoryReady = mutableSetOf<String>()

    init {
        aiTabCacheStore.cleanupTransientFromPreviousRun()

        val restored = windowStore.load()
        windows = if (restored.isEmpty()) {
            listOf(createWindowModel(ProviderCatalog.all.first().id))
        } else {
            restored.map { it.copy(generating = false, unread = false) }
        }
        activeWindowId = windowStore.loadActiveId()
            ?.takeIf { id -> windows.any { it.id == id } }
            ?: windows.first().id
        // Native chat is the primary presentation again. Keep persisted
        // ChatGPT protocol history in Room so tabs can render immediately
        // without waiting for Gecko or the provider DOM.
        aiTabCacheStore.reconcile(windows)
        persist()
        reloadConversation()
        DiagnosticLogger.i(
            "WORKSPACE",
            "workspace_created windows=${windows.size} active=${activeWindowId.take(12)}"
        )
    }

    val activeWindow: ChatWindow
        get() = windows.firstOrNull { it.id == activeWindowId } ?: windows.first()

    val activeProvider: ProviderSpec
        get() = ProviderCatalog.byId(activeWindow.providerId)

    val activeStatus: String?
        get() = statuses[activeWindowId]

    val activePendingAttachments: List<AttachmentMeta>
        get() = pendingAttachments[activeWindowId].orEmpty()

    val activeDraft: String
        get() = drafts[activeWindowId].orEmpty()

    val boundWindows: List<ChatWindow>
        get() = windows.filter {
            !it.boundUrl.isNullOrBlank() ||
                !it.boundRepo.isNullOrBlank() ||
                !it.boundProject.isNullOrBlank()
        }

    val tabWindows: List<ChatWindow>
        get() {
            val bound = boundWindows
            val active = windows.firstOrNull { it.id == activeWindowId }
            return if (active != null && active.boundUrl.isNullOrBlank()) {
                bound + active
            } else {
                bound
            }
        }

    fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return

        val targetsRaw = intent.getStringExtra(AiWorkspaceContract.EXTRA_TARGETS_JSON)
        if (!targetsRaw.isNullOrBlank()) {
            val array = runCatching { JSONArray(targetsRaw) }.getOrNull()
            if (array != null) {
                var merged = windows
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val url = item.optString("url").trim()
                    val provider = ProviderCatalog.fromUrl(url) ?: continue
                    val repoKey = item.optString("repoKey").trim()
                    val project = item.optString("project").trim()
                        .ifBlank { repoKey.substringAfterLast('/').takeIf { repoKey.isNotBlank() }.orEmpty() }
                    val displayTitle = project
                        .ifBlank { item.optString("title").trim() }
                        .ifBlank { provider.name }

                    val existingIndex = merged.indexOfFirst {
                        sameProjectBinding(
                            window = it,
                            repoKey = repoKey,
                            project = project,
                        ) ||
                            (
                                repoKey.isBlank() &&
                                    project.isBlank() &&
                                    sameBoundPage(
                                        it.boundUrl ?: it.url,
                                        url,
                                    )
                            )
                    }
                    if (existingIndex >= 0) {
                        merged = merged.mapIndexed {
                                windowIndex,
                                window,
                            ->
                            if (windowIndex == existingIndex) {
                                window.copy(
                                    providerId = provider.id,
                                    title = displayTitle,
                                    url = url,
                                    boundUrl = url,
                                    conversationUrls =
                                        mergeConversationUrls(
                                            window,
                                            url,
                                        ),
                                    boundRepo =
                                        repoKey.takeIf {
                                            it.isNotBlank()
                                        },
                                    boundProject =
                                        project.takeIf {
                                            it.isNotBlank()
                                        },
                                )
                            } else {
                                window
                            }
                        }
                    } else {
                        merged = merged + ChatWindow(
                            providerId = provider.id,
                            title = displayTitle,
                            url = url,
                            boundUrl = url,
                            conversationUrls = listOf(url),
                            boundRepo = repoKey.takeIf { it.isNotBlank() },
                            boundProject = project.takeIf { it.isNotBlank() },
                            viewMode = WindowViewMode.CHAT,
                            createdAt = item.optLong("addedAt", System.currentTimeMillis()),
                            lastActiveAt = item.optLong("addedAt", System.currentTimeMillis()),
                        )
                    }
                }

                // Launch payloads may be partial or temporarily stale.
                // They are merge-only: update/add bindings, but never remove an
                // existing binding. Only explicit unbind/delete actions may do
                // that, otherwise a bound project can disappear from the tab
                // strip simply because one launch omitted it.
                windows = merged
            }
        }

        val requestedWindowId = intent
            .getStringExtra(AiWorkspaceContract.EXTRA_WINDOW_ID)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val requestedWebMode =
            intent.action ==
                AiWorkspaceContract.ACTION_OPEN_AI_WEB ||
                intent.action ==
                    AiWorkspaceContract.ACTION_LEGACY_AIHUB_OPEN_AI_WEB
        val requestedBindUrl = intent
            .getStringExtra(
                AiWorkspaceContract.EXTRA_BIND_URL
            )
            ?.trim()
            ?.takeIf {
                it.startsWith("http://") ||
                    it.startsWith("https://")
            }
        val requestedUrl = intent
            .getStringExtra(AiWorkspaceContract.EXTRA_URL)
            ?.trim()
            ?.takeIf {
                it.startsWith("http://") ||
                    it.startsWith("https://")
            }
            ?: requestedBindUrl
        val requestedProviderId = intent
            .getStringExtra(AiWorkspaceContract.EXTRA_PROVIDER_ID)
            ?.takeIf { id -> providers.any { it.id == id } }
        val requestedRepo = intent
            .getStringExtra(AiWorkspaceContract.EXTRA_BIND_REPO)
            .orEmpty()
            .trim()
        val requestedProject = intent
            .getStringExtra(AiWorkspaceContract.EXTRA_BIND_PROJECT)
            .orEmpty()
            .trim()
            .ifBlank {
                requestedRepo.substringAfterLast('/')
                    .takeIf { requestedRepo.isNotBlank() }
                    .orEmpty()
            }
        val requestedBindingTitle = intent
            .getStringExtra(AiWorkspaceContract.EXTRA_BIND_TITLE)
            .orEmpty()
            .trim()
        val requestedIsBinding =
            requestedBindUrl != null ||
                requestedRepo.isNotBlank() ||
                requestedProject.isNotBlank() ||
                requestedBindingTitle.isNotBlank()

        if (
            requestedWindowId != null &&
            windows.none {
                it.id == requestedWindowId
            } &&
            (
                requestedUrl != null ||
                    requestedProviderId != null
            )
        ) {
            val provider =
                requestedUrl
                    ?.let(ProviderCatalog::fromUrl)
                    ?: requestedProviderId
                        ?.let(ProviderCatalog::byId)
                    ?: ProviderCatalog.byId("chatgpt")

            val created = ChatWindow(
                id = requestedWindowId,
                providerId = provider.id,
                title =
                    requestedProject
                        .ifBlank {
                            requestedBindingTitle
                        }
                        .ifBlank { provider.name },
                url = requestedUrl,
                boundUrl =
                    requestedUrl.takeIf {
                        requestedIsBinding
                    },
                conversationUrls =
                    requestedUrl
                        ?.takeIf { requestedIsBinding }
                        ?.let(::listOf)
                        .orEmpty(),
                boundRepo =
                    requestedRepo.takeIf {
                        it.isNotBlank()
                    },
                boundProject =
                    requestedProject.takeIf {
                        it.isNotBlank()
                    },
                viewMode =
                    if (requestedWebMode) {
                        WindowViewMode.WEB
                    } else {
                        WindowViewMode.CHAT
                    },
            )
            windows = windows + created
            activeWindowId = created.id

            DiagnosticLogger.i(
                "WORKSPACE",
                "bridge_window_created id=" +
                    created.id.take(12) +
                    " provider=" +
                    created.providerId +
                    " web=" +
                    requestedWebMode,
            )
        }

        when {
            requestedWindowId != null &&
                requestedUrl != null &&
                requestedIsBinding -> {
                val target =
                    windows.firstOrNull {
                        it.id == requestedWindowId
                    }
                if (target != null) {
                    if (
                        !target.boundUrl.isNullOrBlank() &&
                        !sameBoundPage(
                            target.boundUrl,
                            requestedUrl,
                        )
                    ) {
                        networkHistoryReady.remove(target.id)
                        DiagnosticLogger.i(
                            "WORKSPACE",
                            "project_rebind_keep_history window=" +
                                target.id.take(12) +
                                " from=" +
                                target.boundUrl.orEmpty().take(160) +
                                " to=" +
                                requestedUrl.take(160),
                        )
                    }
                    updateWindow(target.id) {
                        it.copy(
                            providerId =
                                ProviderCatalog
                                    .fromUrl(requestedUrl)
                                    ?.id
                                    ?: it.providerId,
                            title =
                                requestedProject
                                    .ifBlank {
                                        requestedBindingTitle
                                    }
                                    .ifBlank {
                                        it.boundProject
                                            .orEmpty()
                                            .ifBlank { it.title }
                                    },
                            url = requestedUrl,
                            boundUrl = requestedUrl,
                            conversationUrls =
                                mergeConversationUrls(
                                    it,
                                    requestedUrl,
                                ),
                            boundRepo =
                                requestedRepo
                                    .takeIf {
                                        value ->
                                        value.isNotBlank()
                                    }
                                    ?: it.boundRepo,
                            boundProject =
                                requestedProject
                                    .takeIf {
                                        value ->
                                        value.isNotBlank()
                                    }
                                    ?: it.boundProject,
                            viewMode =
                                if (requestedWebMode) {
                                    WindowViewMode.WEB
                                } else {
                                    WindowViewMode.CHAT
                                },
                        )
                    }
                    aiTabCacheStore.reconcile(windows)
                    switchWindow(target.id)
                }
            }

            requestedWindowId != null -> {
                switchWindow(requestedWindowId)
                if (requestedWebMode) {
                    setViewModeFor(
                        requestedWindowId,
                        WindowViewMode.WEB,
                    )
                }
            }

            requestedUrl != null -> {
                val existing = windows.firstOrNull {
                    sameProjectBinding(
                        window = it,
                        repoKey = requestedRepo,
                        project = requestedProject,
                    ) ||
                        (
                            requestedRepo.isBlank() &&
                                requestedProject.isBlank() &&
                                sameBoundPage(
                                    it.boundUrl ?: it.url,
                                    requestedUrl,
                                )
                        )
                }
                if (existing != null) {
                    if (requestedIsBinding) {
                        if (
                            !existing.boundUrl.isNullOrBlank() &&
                            !sameBoundPage(
                                existing.boundUrl,
                                requestedUrl,
                            )
                        ) {
                            networkHistoryReady.remove(
                                existing.id
                            )
                        }
                        updateWindow(existing.id) {
                            it.copy(
                                title = requestedProject
                                    .ifBlank { requestedBindingTitle }
                                    .ifBlank { it.title },
                                url = requestedUrl,
                                boundUrl = requestedUrl,
                                conversationUrls =
                                    mergeConversationUrls(
                                        it,
                                        requestedUrl,
                                    ),
                                boundRepo =
                                    requestedRepo
                                        .takeIf {
                                            value ->
                                            value.isNotBlank()
                                        }
                                        ?: it.boundRepo,
                                boundProject =
                                    requestedProject
                                        .takeIf {
                                            value ->
                                            value.isNotBlank()
                                        }
                                        ?: it.boundProject,
                            )
                        }
                    }
                    switchWindow(existing.id)
                } else {
                    val provider = ProviderCatalog.fromUrl(requestedUrl)
                    if (provider != null) {
                        val title = requestedProject
                            .ifBlank { requestedBindingTitle }
                            .ifBlank { provider.name }
                        val window = ChatWindow(
                            providerId = provider.id,
                            title = title,
                            url = requestedUrl,
                            boundUrl = requestedUrl.takeIf { requestedIsBinding },
                            conversationUrls =
                                requestedUrl
                                    .takeIf { requestedIsBinding }
                                    .let(::listOf),
                            boundRepo = requestedRepo.takeIf { it.isNotBlank() },
                            boundProject = requestedProject.takeIf { it.isNotBlank() },
                            viewMode = WindowViewMode.CHAT,
                        )
                        windows = windows + window
                        activeWindowId = window.id
                    }
                }
            }

            requestedProviderId != null -> newWindow(requestedProviderId)
        }

        persist()
        reloadConversation()
    }

    private fun normalizeUrl(value: String?): String =
        value.orEmpty().trim().trimEnd('/')

    private fun pageIdentity(value: String?): String? = runCatching {
        val uri = Uri.parse(value.orEmpty().trim())
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        if (scheme !in setOf("http", "https") || host.isBlank()) {
            return@runCatching null
        }
        val path = uri.path.orEmpty()
            .ifBlank { "/" }
            .trimEnd('/')
            .ifBlank { "/" }
        "$scheme://$host$path"
    }.getOrNull()

    private fun sameBoundPage(left: String?, right: String?): Boolean {
        val a = pageIdentity(left) ?: return false
        val b = pageIdentity(right) ?: return false
        return a == b
    }

    private fun sameProjectBinding(
        window: ChatWindow,
        repoKey: String,
        project: String,
    ): Boolean {
        if (
            repoKey.isNotBlank() &&
            !window.boundRepo.isNullOrBlank()
        ) {
            return window.boundRepo.equals(
                repoKey,
                ignoreCase = true,
            )
        }

        return repoKey.isBlank() &&
            project.isNotBlank() &&
            window.boundRepo.isNullOrBlank() &&
            window.boundProject.equals(
                project,
                ignoreCase = true,
            )
    }

    private fun mergeConversationUrls(
        window: ChatWindow,
        newUrl: String?,
    ): List<String> {
        val ordered =
            buildList {
                window.conversationUrls.forEach { add(it) }
                window.boundUrl?.let(::add)
                newUrl?.let(::add)
            }

        val seen = mutableSetOf<String>()
        return ordered.map(String::trim)
            .filter(String::isNotBlank)
            .filter { raw ->
                val identity = pageIdentity(raw) ?: raw
                seen.add(identity)
            }
    }

    private fun conversationSourceKey(
        url: String?,
    ): String {
        val identity =
            pageIdentity(url)
                ?: normalizeUrl(url)
                .ifBlank { "unknown" }
        return Integer.toHexString(identity.hashCode())
    }

    private fun sameConversationContent(
        left: List<ChatMessage>,
        right: List<ChatMessage>,
    ): Boolean {
        if (left.isEmpty() || right.isEmpty()) return false
        val leftKeys = left.asSequence()
            .map { it.role.name + "|" + it.text.replace(Regex("\\s+"), " ").trim() }
            .toHashSet()
        return right.any {
            (it.role.name + "|" + it.text.replace(Regex("\\s+"), " ").trim()) in leftKeys
        }
    }

    private fun mergePassiveConversation(
        previous: List<ChatMessage>,
        incoming: List<ChatMessage>,
    ): List<ChatMessage> {
        if (incoming.isEmpty()) return previous
        if (previous.isEmpty()) return incoming

        val previousKeys = previous.map(::normalizedMessageKey)
        val incomingKeys = incoming.map(::normalizedMessageKey)

        fun containsSequence(
            haystack: List<String>,
            needle: List<String>,
        ): Int {
            if (needle.isEmpty() || needle.size > haystack.size) return -1
            for (start in 0..haystack.size - needle.size) {
                var same = true
                for (index in needle.indices) {
                    if (haystack[start + index] != needle[index]) {
                        same = false
                        break
                    }
                }
                if (same) return start
            }
            return -1
        }

        // A shorter browser snapshot is normally just the currently loaded
        // virtualized window. Never let it delete history already confirmed
        // and stored locally.
        if (containsSequence(previousKeys, incomingKeys) >= 0) {
            return previous
        }
        if (containsSequence(incomingKeys, previousKeys) >= 0) {
            return incoming
        }

        fun suffixPrefixOverlap(
            left: List<String>,
            right: List<String>,
        ): Int {
            val max = minOf(left.size, right.size)
            for (size in max downTo 1) {
                var same = true
                for (index in 0 until size) {
                    if (left[left.size - size + index] != right[index]) {
                        same = false
                        break
                    }
                }
                if (same) return size
            }
            return 0
        }

        val appendOverlap = suffixPrefixOverlap(previousKeys, incomingKeys)
        if (appendOverlap > 0) {
            return previous + incoming.drop(appendOverlap)
        }

        val prependOverlap = suffixPrefixOverlap(incomingKeys, previousKeys)
        if (prependOverlap > 0) {
            return incoming.dropLast(prependOverlap) + previous
        }

        // No trustworthy overlap means we cannot prove ordering or identity.
        // Keep the confirmed local history instead of guessing.
        return previous
    }

    private fun importPageMessages(
        items: List<WebRuntime.PageConversationMessage>,
        prefix: String,
    ): List<ChatMessage> =
        items.mapNotNull { pageMessage ->
            val role = when (pageMessage.role) {
                "user" -> MessageRole.USER
                "assistant" -> MessageRole.ASSISTANT
                else -> null
            } ?: return@mapNotNull null

            ChatMessage(
                id = "$prefix-${pageMessage.id}",
                role = role,
                text = pageMessage.text,
            )
        }

    private fun importSnapshotMessages(
        snapshot: WebRuntime.ConversationSnapshot,
    ): List<ChatMessage> {
        val transport =
            if (snapshot.source.startsWith("network")) {
                "network"
            } else {
                "page"
            }
        val sourceKey =
            conversationSourceKey(snapshot.url)
        return importPageMessages(
            snapshot.messages,
            "$transport:$sourceKey",
        )
    }

    private fun normalizedMessageKey(message: ChatMessage): String =
        message.role.name + "|" +
            message.text.replace(Regex("\\s+"), " ").trim()

    private fun mergeNetworkDelta(
        previous: List<ChatMessage>,
        incoming: List<ChatMessage>,
    ): List<ChatMessage> {
        if (incoming.isEmpty()) return previous
        if (previous.isEmpty()) return incoming

        val merged = previous.toMutableList()
        incoming.forEach { message ->
            val sameId = merged.indexOfFirst {
                it.id == message.id
            }
            if (sameId >= 0) {
                val old = merged[sameId]
                merged[sameId] = message.copy(
                    timestamp = old.timestamp,
                    attachments =
                        if (old.attachments.isNotEmpty()) {
                            old.attachments
                        } else {
                            message.attachments
                        },
                )
                return@forEach
            }

            // Only optimistic local messages may be reconciled by content.
            // Different ChatGPT conversations under one project are allowed
            // to contain identical text and must remain separate history.
            val optimistic = merged.indexOfFirst {
                it.id.startsWith("local-") &&
                    normalizedMessageKey(it) ==
                        normalizedMessageKey(message)
            }
            if (optimistic >= 0) {
                val old = merged[optimistic]
                merged[optimistic] = message.copy(
                    timestamp = old.timestamp,
                    attachments =
                        if (old.attachments.isNotEmpty()) {
                            old.attachments
                        } else {
                            message.attachments
                        },
                )
                return@forEach
            }

            merged += message
        }
        return merged
    }

    private fun mergeSnapshot(
        window: ChatWindow,
        snapshot: WebRuntime.ConversationSnapshot,
        previous: List<ChatMessage>,
        incoming: List<ChatMessage>,
    ): List<ChatMessage> = when {
        incoming.isEmpty() -> previous

        snapshot.source == "dom" && window.id in networkHistoryReady ->
            previous

        snapshot.source == "network-history" &&
            snapshot.complete &&
            (
                !window.boundRepo.isNullOrBlank() ||
                    !window.boundProject.isNullOrBlank()
            ) ->
            mergeNetworkDelta(
                previous = previous,
                incoming = incoming,
            )

        snapshot.source == "network-history" &&
            snapshot.complete -> {
            val authoritative = incoming.map { message ->
                val old = previous.firstOrNull {
                    normalizedMessageKey(it) ==
                        normalizedMessageKey(message)
                }
                if (
                    old != null &&
                    old.attachments.isNotEmpty()
                ) {
                    message.copy(
                        timestamp = old.timestamp,
                        attachments = old.attachments,
                    )
                } else {
                    message
                }
            }

            val pendingLocal = previous.filter { local ->
                local.id.startsWith("local-") &&
                    authoritative.none {
                        normalizedMessageKey(it) ==
                            normalizedMessageKey(local)
                    }
            }

            authoritative + pendingLocal
        }

        snapshot.source.startsWith("network") ->
            mergeNetworkDelta(previous, incoming)

        (
            !window.boundRepo.isNullOrBlank() ||
                !window.boundProject.isNullOrBlank()
        ) ->
            // Project tabs accumulate every bound conversation. Source-aware
            // message IDs make a DOM fallback safe even when separate chats
            // reuse the same turn numbers or contain identical text.
            mergeNetworkDelta(previous, incoming)

        else -> mergePassiveConversation(
            previous = previous,
            incoming = incoming,
        )
    }

    fun syncPage(
        runtime: WindowWebRuntime,
        windowId: String = activeWindowId,
    ) {
        val target = windows.firstOrNull { it.id == windowId } ?: return
        val provider = ProviderCatalog.byId(target.providerId)

        if (syncJobs[windowId]?.isActive == true) {
            DiagnosticLogger.d(
                "WORKSPACE",
                "page_sync_already_running provider=" + provider.id +
                    " window=" + windowId.take(12)
            )
            return
        }

        syncJobs[windowId] = viewModelScope.launch {
            val hadLocalMessages =
                conversationStore
                    .load(session(target))
                    .isNotEmpty()
            if (!hadLocalMessages && windowId == activeWindowId) {
                setStatus(
                    windowId,
                    "正在同步聊天历史…",
                )
            }

            try {
                if (provider.id == "chatgpt") {
                    // The native project chat is persistent and independent
                    // from Web rendering. Old project history remains visible
                    // while the currently bound web conversation refreshes in
                    // the background and merges into ConversationStore.
                    val liveTarget =
                        windows.firstOrNull {
                            it.id == windowId
                        } ?: target

                    runtime.ensurePreferredPage(
                        liveTarget,
                        provider,
                    )

                    if (windowId in networkHistoryReady) {
                        if (windowId == activeWindowId) {
                            setStatus(windowId, null)
                        }
                        return@launch
                    }

                    if (
                        !liveTarget.boundUrl.isNullOrBlank()
                    ) {
                        runtime.reloadPage(
                            liveTarget,
                            provider,
                        )
                        DiagnosticLogger.i(
                            "WORKSPACE",
                            "project_history_refresh window=" +
                                windowId.take(12) +
                                " bound=" +
                                liveTarget.boundUrl
                                    .orEmpty()
                                    .take(160),
                        )
                    }

                    if (
                        hadLocalMessages &&
                        windowId == activeWindowId
                    ) {
                        // Keep the old merged history visible. Status is only
                        // informational; no blank/loading replacement.
                        setStatus(
                            windowId,
                            "正在合并当前绑定的聊天历史…",
                        )
                    }

                    repeat(40) {
                        delay(200)
                        if (
                            windowId in networkHistoryReady
                        ) {
                            val stored =
                                conversationStore.load(
                                    session(liveTarget)
                                )
                            if (
                                windowId == activeWindowId
                            ) {
                                messages.clear()
                                messages.addAll(stored)
                                setStatus(windowId, null)
                            }
                            return@launch
                        }
                    }

                    if (windowId == activeWindowId) {
                        setStatus(
                            windowId,
                            if (hadLocalMessages) {
                                "旧聊天已保留，新绑定历史仍在后台同步。"
                            } else {
                                "暂未读取到聊天历史，后台仍在同步。"
                            },
                        )
                    }
                    return@launch
                }

                // Other providers keep the passive DOM fallback.
                repeat(6) { attempt ->
                    delay(if (attempt == 0) 500 else 300)

                    if (windowId in networkHistoryReady) {
                        if (windowId == activeWindowId) setStatus(windowId, null)
                        return@launch
                    }

                    val latestWindow = windows.firstOrNull { it.id == windowId } ?: target
                    val snapshot = runCatching {
                        runtime.conversationSnapshot(latestWindow, provider)
                    }.onFailure {
                        DiagnosticLogger.w(
                            "WORKSPACE",
                            "page_sync_failed provider=" + provider.id +
                                " window=" + windowId.take(12) +
                                " attempt=" + attempt,
                            it,
                        )
                    }.getOrNull()

                    if (
                        snapshot != null &&
                        providerOwnsPage(snapshot.url, provider)
                    ) {
                        val imported = importSnapshotMessages(snapshot)
                        if (imported.isNotEmpty()) {
                            val liveWindow =
                                windows.firstOrNull { it.id == windowId } ?: latestWindow
                            val previous = conversationStore.load(session(liveWindow))
                            val stored = mergeSnapshot(
                                window = liveWindow,
                                snapshot = snapshot,
                                previous = previous,
                                incoming = imported,
                            )
                            conversationStore.save(session(liveWindow), stored)

                            snapshot.url
                                .takeIf { it.isNotBlank() }
                                ?.let { currentUrl ->
                                    updateWindow(windowId) {
                                        it.copy(
                                            url = currentUrl,
                                            lastActiveAt = System.currentTimeMillis(),
                                        )
                                    }
                                }

                            if (
                                snapshot.title.isNotBlank() &&
                                liveWindow.title == "新对话"
                            ) {
                                updateWindow(windowId) {
                                    it.copy(title = snapshot.title.take(48))
                                }
                            }

                            if (windowId == activeWindowId) {
                                if (stored != previous) {
                                    setStatus(windowId, "正在加载新内容…")
                                    // Keep the restored loading hint visible long
                                    // enough for Compose to render it before the
                                    // newly synced messages replace the local view.
                                    delay(180)
                                }
                                messages.clear()
                                messages.addAll(stored)
                                setStatus(windowId, null)
                            } else if (stored != previous) {
                                updateWindow(windowId) { it.copy(unread = true) }
                            }

                            DiagnosticLogger.i(
                                "WORKSPACE",
                                "page_synced_passive provider=" + provider.id +
                                    " window=" + windowId.take(12) +
                                    " stored=" + stored.size +
                                    " loadedNow=" + imported.size +
                                    " source=" + snapshot.source,
                            )
                            return@launch
                        }
                    }
                }

                if (!hadLocalMessages && windowId == activeWindowId) {
                    setStatus(windowId, "暂未读取到聊天内容")
                } else if (windowId == activeWindowId) {
                    setStatus(windowId, null)
                }
            } finally {
                syncJobs.remove(windowId)
            }
        }
    }

    fun refreshConversation(
        runtime: WindowWebRuntime,
        windowId: String = activeWindowId,
    ) {
        val target = windows.firstOrNull { it.id == windowId } ?: return
        val provider = ProviderCatalog.byId(target.providerId)
        if (target.generating) return

        syncJobs.remove(windowId)?.cancel()
        networkHistoryReady.remove(windowId)

        val keepProjectHistory =
            provider.id == "chatgpt" &&
                (
                    !target.boundRepo.isNullOrBlank() ||
                        !target.boundProject.isNullOrBlank()
                )

        if (!keepProjectHistory) {
            conversationStore.clear(session(target))
        }

        if (provider.id == "chatgpt") {
            runtime.clearConversationCache(windowId)
        }

        if (windowId == activeWindowId) {
            if (!keepProjectHistory) {
                messages.clear()
            }
            setStatus(
                windowId,
                when {
                    keepProjectHistory ->
                        "正在重新同步当前绑定历史…"
                    provider.id == "chatgpt" ->
                        "正在清除旧缓存并重新加载当前对话…"
                    else ->
                        "正在重新读取网页已加载内容…"
                }
            )
        }

        DiagnosticLogger.i(
            "WORKSPACE",
            "conversation_refresh provider=" + provider.id +
                " window=" + windowId.take(12) +
                " mode=" + if (provider.id == "chatgpt") {
                    "clear-content-cache-reload-and-resync"
                } else {
                    "clear-local-and-passive-resync"
                }
        )

        if (provider.id == "chatgpt") {
            runtime.reloadPage(target, provider)
        }
        syncPage(runtime, windowId)
    }

    private fun providerOwnsPage(
        value: String?,
        provider: ProviderSpec,
    ): Boolean = runCatching {
        val target = Uri.parse(value.orEmpty())
        val home = Uri.parse(provider.homeUrl)
        val targetHost = target.host.orEmpty()
        val homeHost = home.host.orEmpty()
        target.scheme in setOf("http", "https") &&
            targetHost.equals(homeHost, ignoreCase = true)
    }.getOrDefault(false)

    fun windowsFor(providerId: String): List<ChatWindow> =
        windows.filter { it.providerId == providerId }
            .sortedByDescending { it.lastActiveAt }

    fun refreshBindingsFromSharedStore() {
        val prefs =
            getApplication<Application>()
                .getSharedPreferences(
                    "ybrowser_store",
                    0,
                )
        if (!prefs.contains("chat_bindings")) return

        val array = runCatching {
            JSONArray(
                prefs.getString(
                    "chat_bindings",
                    "[]",
                ) ?: "[]"
            )
        }.getOrElse { JSONArray() }

        val bindings = buildList {
            for (index in 0 until array.length()) {
                val item =
                    array.optJSONObject(index)
                        ?: continue
                val repoKey =
                    item.optString("repoKey").trim()
                val url =
                    item.optString("url").trim()
                if (
                    repoKey.isBlank() ||
                    url.isBlank()
                ) {
                    continue
                }
                add(
                    SharedBinding(
                        repoKey = repoKey,
                        project =
                            item.optString("project")
                                .trim()
                                .ifBlank {
                                    repoKey.substringAfterLast(
                                        '/'
                                    )
                                },
                        url = url,
                        title =
                            item.optString("title")
                                .trim()
                                .ifBlank { "AI" },
                    )
                )
            }
        }

        var changed = false
        var merged = windows.map { window ->
            val page =
                window.boundUrl ?: window.url
            val match = bindings.firstOrNull {
                sameProjectBinding(
                    window = window,
                    repoKey = it.repoKey,
                    project = it.project,
                ) ||
                    (
                        window.boundRepo.isNullOrBlank() &&
                            window.boundProject.isNullOrBlank() &&
                            sameBoundPage(it.url, page)
                    )
            }

            if (match != null) {
                if (
                    !window.boundUrl.isNullOrBlank() &&
                    !sameBoundPage(
                        window.boundUrl,
                        match.url,
                    )
                ) {
                    networkHistoryReady.remove(window.id)
                }
                val updated = window.copy(
                    title =
                        match.project.ifBlank {
                            window.title
                        },
                    url = match.url,
                    boundUrl = match.url,
                    conversationUrls =
                        mergeConversationUrls(
                            window,
                            match.url,
                        ),
                    boundRepo = match.repoKey,
                    boundProject = match.project,
                )
                if (updated != window) {
                    changed = true
                }
                updated
            } else {
                // Shared binding data is merge-only. A missing row can be a
                // transient sync issue and must never hide an existing bound
                // tab. Explicit unbind/delete remains authoritative.
                window
            }
        }

        bindings.forEach { binding ->
            val exists = merged.any { window ->
                sameProjectBinding(
                    window = window,
                    repoKey = binding.repoKey,
                    project = binding.project,
                )
            }
            if (!exists) {
                val provider =
                    ProviderCatalog.fromUrl(binding.url)
                        ?: return@forEach
                merged = merged + ChatWindow(
                    providerId = provider.id,
                    title =
                        binding.project.ifBlank {
                            binding.title
                        },
                    url = binding.url,
                    boundUrl = binding.url,
                    conversationUrls = listOf(binding.url),
                    boundRepo = binding.repoKey,
                    boundProject =
                        binding.project.takeIf {
                            it.isNotBlank()
                        },
                    viewMode = WindowViewMode.CHAT,
                )
                changed = true
                DiagnosticLogger.i(
                    "WORKSPACE",
                    "binding_restored_from_shared_store " +
                        "repo=" + binding.repoKey +
                        " url=" + binding.url.take(160),
                )
            }
        }

        if (changed) {
            windows = merged
            aiTabCacheStore.reconcile(windows)
            persist()
        }

        DiagnosticLogger.i(
            "WORKSPACE",
            "binding_sync merge_only incoming=" +
                bindings.size +
                " bound=" +
                boundWindows.size +
                " total=" +
                windows.size,
        )
    }

    fun requestBinding(windowId: String) {
        val target =
            windows.firstOrNull {
                it.id == windowId
            } ?: return
        val url = (
            if (target.viewMode == WindowViewMode.WEB) {
                target.url ?: target.boundUrl
            } else {
                target.boundUrl ?: target.url
            }
        )?.takeIf { it.isNotBlank() }
        if (url == null) {
            setStatus(
                windowId,
                "请先开始这个聊天，等 ChatGPT 生成会话地址后再绑定项目。",
            )
            return
        }
        val app = getApplication<Application>()
        val intent = Intent(AiWorkspaceContract.ACTION_REQUEST_BINDING).apply {
            setPackage(AiWorkspaceContract.YAGAYHUB_PACKAGE)
            putExtra(
                AiWorkspaceContract.EXTRA_WINDOW_ID,
                windowId,
            )
            putExtra(
                AiWorkspaceContract.EXTRA_BIND_URL,
                url,
            )
            putExtra(
                AiWorkspaceContract.EXTRA_REQUESTER_PACKAGE,
                getApplication<Application>().packageName,
            )
            target.boundRepo
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    putExtra(
                        AiWorkspaceContract.EXTRA_BIND_REPO,
                        it,
                    )
                }
            target.boundProject
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    putExtra(
                        AiWorkspaceContract.EXTRA_BIND_PROJECT,
                        it,
                    )
                }
            putExtra(
                AiWorkspaceContract.EXTRA_BIND_TITLE,
                target.boundProject.orEmpty()
                    .ifBlank { target.title }
                    .ifBlank { "AI" },
            )
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }

        runCatching { app.startActivity(intent) }
            .onFailure {
                setStatus(windowId, "无法打开 YagaYHub 绑定选择器")
            }
    }

    fun unbindWindow(windowId: String) {
        val target = windows.firstOrNull { it.id == windowId } ?: return
        val url = target.boundUrl ?: target.url ?: return

        notifyBindingRemoval(url)

        val nextBound = windows.firstOrNull {
            it.id != windowId && !it.boundUrl.isNullOrBlank()
        }

        updateWindow(windowId) {
            it.copy(
                boundUrl = null,
                boundRepo = null,
                boundProject = null,
            )
        }
        aiTabCacheStore.markUnbound(windowId)

        if (activeWindowId == windowId && nextBound != null) {
            switchWindow(nextBound.id)
        }

        DiagnosticLogger.i(
            "WORKSPACE",
            "window_unbound id=" + windowId.take(12) +
                " url=" + url.take(160)
        )
    }

    fun deleteChat(
        windowId: String,
        runtime: WindowWebRuntime,
    ) {
        val target = windows.firstOrNull {
            it.id == windowId
        } ?: return

        target.boundUrl
            ?.takeIf { it.isNotBlank() }
            ?.let(::notifyBindingRemoval)

        closeWindow(windowId, runtime)

        DiagnosticLogger.i(
            "WORKSPACE",
            "chat_deleted id=" + windowId.take(12) +
                " bound=" +
                (!target.boundUrl.isNullOrBlank())
        )
    }

    private fun notifyBindingRemoval(url: String) {
        val app = getApplication<Application>()

        runCatching {
            app.sendBroadcast(
                Intent(
                    AiWorkspaceContract.ACTION_LOCAL_BINDING_REMOVE
                ).apply {
                    setPackage(app.packageName)
                    putExtra(
                        AiWorkspaceContract.EXTRA_BIND_URL,
                        url,
                    )
                }
            )
        }
        runCatching {
            app.sendBroadcast(
                Intent(
                    AiWorkspaceContract.ACTION_NOTIFY_BINDING_REMOVE
                ).apply {
                    setPackage(
                        AiWorkspaceContract.YAGAYHUB_PACKAGE
                    )
                    putExtra(
                        AiWorkspaceContract.EXTRA_BIND_URL,
                        url,
                    )
                }
            )
        }
    }

    fun onWorkspaceExit() {
        aiTabCacheStore.cleanupOnWorkspaceExit(windows)
        DiagnosticLogger.i(
            "WORKSPACE",
            "tab_cache_exit_cleanup bound=" +
                windows.count { !it.boundUrl.isNullOrBlank() } +
                " total=" + windows.size
        )
    }

    fun updateDraft(value: String) {
        drafts[activeWindowId] = value
    }

    fun newWindow(providerId: String = activeWindow.providerId) {
        val window = createWindowModel(providerId)
        windows = windows + window
        activeWindowId = window.id
        messages.clear()
        pendingAttachments[window.id] = emptyList()
        windowStore.saveActiveId(window.id)
        persist()
        DiagnosticLogger.i(
            "WORKSPACE",
            "window_created provider=$providerId id=${window.id.take(12)}"
        )
    }

    fun switchWindow(id: String) {
        if (id == activeWindowId || windows.none { it.id == id }) return
        updateWindow(id) {
            it.copy(lastActiveAt = System.currentTimeMillis(), unread = false)
        }
        activeWindowId = id
        windowStore.saveActiveId(id)
        reloadConversation()
        DiagnosticLogger.i("WORKSPACE", "window_selected id=${id.take(12)}")
    }

    fun closeWindow(id: String, runtime: WindowWebRuntime) {
        val target = windows.firstOrNull { it.id == id } ?: return
        generationJobs.remove(id)?.cancel()
        syncJobs.remove(id)?.cancel()
        networkHistoryReady.remove(id)
        runtime.destroyWindow(id, ProviderCatalog.byId(target.providerId))
        aiTabCacheStore.delete(id)
        conversationStore.clear(session(target))
        pendingAttachmentStore.clear(session(target))
        pendingAttachments.remove(id)
        drafts.remove(id)
        statuses.remove(id)

        var remaining = windows.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            remaining = listOf(createWindowModel(target.providerId))
        }
        windows = remaining

        if (activeWindowId == id) {
            activeWindowId = remaining.maxByOrNull { it.lastActiveAt }?.id ?: remaining.first().id
            windowStore.saveActiveId(activeWindowId)
            reloadConversation()
        }

        persist()
        DiagnosticLogger.i(
            "WORKSPACE",
            "window_closed id=${id.take(12)} remaining=${windows.size}"
        )
    }

    fun setViewMode(mode: WindowViewMode) {
        updateWindow(activeWindowId) {
            it.copy(viewMode = mode, lastActiveAt = System.currentTimeMillis())
        }
    }

    fun onPageChanged(windowId: String, provider: ProviderSpec, url: String) {
        val target = windows.firstOrNull { it.id == windowId } ?: return
        if (target.providerId != provider.id) return
        val oldPage = pageIdentity(target.url)
        val newPage = pageIdentity(url)
        if (oldPage != null && newPage != null && oldPage != newPage) {
            networkHistoryReady.remove(windowId)
        }
        updateWindow(windowId) {
            it.copy(url = url, lastActiveAt = System.currentTimeMillis())
        }
    }

    fun onConversationSnapshot(
        windowId: String,
        provider: ProviderSpec,
        snapshot: WebRuntime.ConversationSnapshot,
    ) {
        val target = windows.firstOrNull { it.id == windowId }
        if (target == null) {
            DiagnosticLogger.recordBridgeTrace(
                stage = "native-drop-no-window",
                provider = provider.id,
                windowId = windowId,
                url = snapshot.url,
                detail = "window not found",
                candidateCount = snapshot.candidateCount,
                messageCount = snapshot.messages.size,
            )
            return
        }
        if (target.providerId != provider.id) {
            DiagnosticLogger.recordBridgeTrace(
                stage = "native-drop-provider-mismatch",
                provider = provider.id,
                windowId = windowId,
                url = snapshot.url,
                detail = "expected=${target.providerId}",
                candidateCount = snapshot.candidateCount,
                messageCount = snapshot.messages.size,
            )
            return
        }
        if (!providerOwnsPage(snapshot.url, provider)) {
            DiagnosticLogger.recordBridgeTrace(
                stage = "native-drop-origin",
                provider = provider.id,
                windowId = windowId,
                url = snapshot.url,
                detail = "provider home=${provider.homeUrl}",
                candidateCount = snapshot.candidateCount,
                messageCount = snapshot.messages.size,
            )
            return
        }

        if (
            provider.id == "chatgpt" &&
            !target.boundUrl.isNullOrBlank() &&
            !sameBoundPage(
                target.boundUrl,
                snapshot.url,
            )
        ) {
            DiagnosticLogger.recordBridgeTrace(
                stage = "native-drop-unbound-web-history",
                provider = provider.id,
                windowId = windowId,
                url = snapshot.url,
                detail =
                    "bound=" +
                        target.boundUrl.orEmpty().take(180),
                candidateCount = snapshot.candidateCount,
                messageCount = snapshot.messages.size,
            )
            return
        }

        if (snapshot.source == "network-history" && snapshot.complete) {
            networkHistoryReady += windowId
        }

        val imported = importSnapshotMessages(snapshot)

        val previous = conversationStore.load(session(target))
        val stored = mergeSnapshot(
            window = target,
            snapshot = snapshot,
            previous = previous,
            incoming = imported,
        )
        conversationStore.save(session(target), stored)

        snapshot.url
            .takeIf { it.isNotBlank() }
            ?.let { currentUrl ->
                updateWindow(windowId) {
                    it.copy(
                        url = currentUrl,
                        lastActiveAt = System.currentTimeMillis(),
                    )
                }
            }

        if (
            snapshot.title.isNotBlank() &&
            target.title == "新对话"
        ) {
            updateWindow(windowId) {
                it.copy(title = snapshot.title.take(48))
            }
        }

        if (windowId == activeWindowId) {
            messages.clear()
            messages.addAll(stored)
            setStatus(windowId, null)
        } else if (stored != previous) {
            updateWindow(windowId) { it.copy(unread = true) }
        }

        val userCount = stored.count { it.role == MessageRole.USER }
        val assistantCount = stored.count { it.role == MessageRole.ASSISTANT }
        DiagnosticLogger.recordBridgeTrace(
            stage = "native-applied",
            provider = provider.id,
            windowId = windowId,
            url = snapshot.url,
            detail = "source=${snapshot.source} complete=${snapshot.complete} ${snapshot.error}".trim(),
            candidateCount = snapshot.candidateCount,
            messageCount = stored.size,
            userCount = userCount,
            assistantCount = assistantCount,
        )
        DiagnosticLogger.d(
            "WORKSPACE",
            "page_push provider=${provider.id} " +
                "window=${windowId.take(12)} messages=${imported.size}"
        )
    }

    fun onAttachments(windowId: String, attachments: List<AttachmentMeta>) {
        pendingAttachments[windowId] = attachments
    }

    fun send(runtime: WindowWebRuntime) {
        val target = activeWindow
        val provider = ProviderCatalog.byId(target.providerId)
        val prompt = activeDraft.trim()
        val attachments = pendingAttachments[target.id].orEmpty()

        if ((prompt.isBlank() && attachments.isEmpty()) || target.generating) return

        drafts[target.id] = ""

        val visibleText = when {
            attachments.isNotEmpty() && prompt.isBlank() ->
                "📎 " + attachments.joinToString(", ") { it.name }
            attachments.isNotEmpty() ->
                prompt + "\n\n📎 " + attachments.joinToString(", ") { it.name }
            else -> prompt
        }

        val optimisticUser = ChatMessage(
            id = "local-user-" + System.nanoTime(),
            role = MessageRole.USER,
            text = visibleText,
            attachments = attachments,
        )
        val targetMessages =
            conversationStore.load(session(target)).toMutableList()
        targetMessages += optimisticUser
        conversationStore.save(session(target), targetMessages)

        if (target.id == activeWindowId) {
            messages.clear()
            messages.addAll(targetMessages)
        }

        if (target.title == "新对话") {
            val title = prompt.lineSequence().firstOrNull()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.take(28)
                ?: attachments.firstOrNull()?.name?.take(28)
                ?: provider.name
            updateWindow(target.id) { it.copy(title = title) }
        }

        generationJobs[target.id]?.cancel()
        generationJobs[target.id] = viewModelScope.launch {
            setGenerating(target.id, true)
            setStatus(target.id, "正在连接 ${provider.name}…")
            try {
                val baseline = runCatching {
                    runtime.responseSnapshot(target.id, provider)
                }.getOrDefault(WebRuntime.ResponseSnapshot())

                val sent = runCatching {
                    runtime.send(target.id, provider, prompt)
                }.onFailure {
                    DiagnosticLogger.e(
                        "WORKSPACE",
                        "send_exception provider=${provider.id} window=${target.id.take(12)}",
                        it
                    )
                }.getOrDefault(false)

                if (!sent) {
                    val reverted =
                        conversationStore
                            .load(session(target))
                            .filterNot {
                                it.id == optimisticUser.id
                            }
                    conversationStore.save(
                        session(target),
                        reverted,
                    )
                    if (target.id == activeWindowId) {
                        messages.clear()
                        messages.addAll(reverted)
                    }
                    setStatus(
                        target.id,
                        "消息没有被官网确认提交，可切到网页检查。",
                    )
                    return@launch
                }

                if (attachments.isNotEmpty()) {
                    runtime.markAttachmentsSubmitted(target.id, provider)
                    pendingAttachments[target.id] = emptyList()
                }

                runtime.currentUrl(target.id, provider)?.let { url ->
                    updateWindow(target.id) { it.copy(url = url) }
                }

                setStatus(target.id, "等待 ${provider.name} 回复…")
                awaitResponse(runtime, target.id, provider, baseline)
            } finally {
                setGenerating(target.id, false)
                generationJobs.remove(target.id)
            }
        }
    }

    fun stop(runtime: WindowWebRuntime) {
        val target = activeWindow
        val provider = activeProvider
        generationJobs.remove(target.id)?.cancel()
        viewModelScope.launch {
            runCatching { runtime.stop(target.id, provider) }
            setGenerating(target.id, false)
            setStatus(target.id, "已请求停止生成")
        }
    }

    private suspend fun awaitResponse(
        runtime: WindowWebRuntime,
        windowId: String,
        provider: ProviderSpec,
        baseline: WebRuntime.ResponseSnapshot
    ) {
        var last = WebRuntime.ResponseSnapshot()
        var stableCount = 0
        var sawGenerating = false

        for (poll in 0 until 180) {
            delay(700)

            val snap = runCatching {
                runtime.responseSnapshot(windowId, provider)
            }.getOrDefault(WebRuntime.ResponseSnapshot())

            if (snap.isGenerating) sawGenerating = true

            val structuralChange =
                (snap.key.isNotBlank() && snap.key != baseline.key) ||
                    snap.responseCount > baseline.responseCount ||
                    snap.turnCount > baseline.turnCount ||
                    (
                        baseline.path.isNotBlank() &&
                            snap.path.isNotBlank() &&
                            snap.path != baseline.path
                    )

            val textChange = snap.text.isNotBlank() && snap.text != baseline.text
            val fresh = snap.text.isNotBlank() && (structuralChange || textChange || sawGenerating)

            if (snap.state == "error") {
                setStatus(
                    windowId,
                    "官网没有完成消息提交，可切到网页检查。",
                )
                return
            }

            if (fresh) {
                val same = snap.key == last.key && snap.text == last.text
                stableCount = if (same) stableCount + 1 else 0
                last = snap

                if (!snap.isGenerating && stableCount >= 2) {
                    commitAssistant(windowId, snap.text)

                    runtime.currentUrl(windowId, provider)?.let { url ->
                        updateWindow(windowId) { it.copy(url = url) }
                    }

                    setStatus(windowId, null)
                    DiagnosticLogger.i(
                        "WORKSPACE",
                        "response_completed provider=${provider.id} " +
                            "window=${windowId.take(12)} polls=${poll + 1}"
                    )
                    return
                }
            } else {
                stableCount = 0
            }

            if (poll == 0 || poll == 10 || poll == 40) {
                val probe = runCatching {
                    runtime.probeSummary(windowId, provider)
                }.getOrDefault("")

                DiagnosticLogger.d(
                    "WORKSPACE",
                    "response_poll provider=${provider.id} window=${windowId.take(12)} " +
                        "poll=$poll state=${snap.state} chars=${snap.text.length} " +
                        "probe=${DiagnosticLogger.scrub(probe).take(500)}"
                )
            }
        }

        if (last.text.isNotBlank()) {
            commitAssistant(windowId, last.text)
            setStatus(windowId, null)
        } else {
            setStatus(windowId, "没有读取到新的回复，可切到网页视图检查。")
        }
    }

    private fun commitAssistant(windowId: String, text: String) {
        val window =
            windows.firstOrNull {
                it.id == windowId
            } ?: return
        if (text.isBlank()) return

        val list =
            conversationStore
                .load(session(window))
                .toMutableList()
        val duplicate = list.any {
            it.role == MessageRole.ASSISTANT &&
                it.text.trim() == text.trim()
        }
        if (!duplicate) {
            list += ChatMessage(
                id =
                    "local-assistant-" +
                        System.nanoTime(),
                role = MessageRole.ASSISTANT,
                text = text,
            )
            conversationStore.save(
                session(window),
                list,
            )
        }

        if (windowId == activeWindowId) {
            messages.clear()
            messages.addAll(list)
            updateWindow(windowId) {
                it.copy(unread = false)
            }
        } else {
            updateWindow(windowId) {
                it.copy(unread = true)
            }
        }
    }

    private fun setGenerating(windowId: String, value: Boolean) {
        updateWindow(windowId) { it.copy(generating = value) }
    }

    private fun setViewModeFor(windowId: String, mode: WindowViewMode) {
        updateWindow(windowId) { it.copy(viewMode = mode) }
    }

    private fun setStatus(windowId: String, value: String?) {
        if (value == null) {
            statuses.remove(windowId)
        } else {
            statuses[windowId] = value
        }
    }

    private fun reloadConversation() {
        val target = activeWindow
        messages.clear()
        messages.addAll(
            conversationStore.load(
                session(target)
            )
        )
        pendingAttachments[target.id] =
            pendingAttachmentStore.load(session(target))
        updateWindow(target.id) {
            it.copy(unread = false)
        }
    }

    private fun updateWindow(
        id: String,
        transform: (ChatWindow) -> ChatWindow
    ) {
        windows = windows.map { window ->
            if (window.id == id) transform(window) else window
        }
        persist()
    }

    private fun persist() {
        windowStore.save(
            windows.map { it.copy(generating = false, unread = false) }
        )
        if (activeWindowId.isNotBlank()) {
            windowStore.saveActiveId(activeWindowId)
        }
    }

    private fun createWindowModel(providerId: String): ChatWindow =
        ChatWindow(providerId = providerId, title = "新对话")

    private fun session(window: ChatWindow): WindowSessionKey =
        WindowSessionKey(
            providerId = window.providerId,
            windowId = window.id
        )

    class Factory(
        private val application: Application
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return WorkspaceViewModel(application) as T
        }
    }
}
