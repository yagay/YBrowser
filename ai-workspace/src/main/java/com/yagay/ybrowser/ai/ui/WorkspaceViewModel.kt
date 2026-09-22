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
    private val windowStore = WindowStore(application)
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
        val restored = windowStore.load()
        windows = if (restored.isEmpty()) {
            listOf(createWindowModel(ProviderCatalog.all.first().id))
        } else {
            restored.map { it.copy(generating = false, unread = false) }
        }
        activeWindowId = windowStore.loadActiveId()
            ?.takeIf { id -> windows.any { it.id == id } }
            ?: windows.first().id
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
                    val existingIndex = merged.indexOfFirst {
                        sameBoundPage(it.boundUrl ?: it.url, url)
                    }
                    if (existingIndex >= 0) {
                        merged = merged.mapIndexed { windowIndex, window ->
                            if (windowIndex == existingIndex) {
                                window.copy(
                                    providerId = provider.id,
                                    title = item.optString("title")
                                        .ifBlank { item.optString("project") }
                                        .ifBlank { window.title },
                                    url = window.url ?: url,
                                    boundUrl = url,
                                )
                            } else {
                                window
                            }
                        }
                    } else {
                        merged = merged + ChatWindow(
                            providerId = provider.id,
                            title = item.optString("title")
                                .ifBlank { item.optString("project") }
                                .ifBlank { provider.name },
                            url = url,
                            boundUrl = url,
                            viewMode = WindowViewMode.CHAT,
                            createdAt = item.optLong("addedAt", System.currentTimeMillis()),
                            lastActiveAt = item.optLong("addedAt", System.currentTimeMillis()),
                        )
                    }
                }
                windows = merged
            }
        }

        val requestedWindowId = intent
            .getStringExtra(AiWorkspaceContract.EXTRA_WINDOW_ID)
            ?.takeIf { id -> windows.any { it.id == id } }
        val requestedUrl = intent
            .getStringExtra(AiWorkspaceContract.EXTRA_URL)
            ?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val requestedProviderId = intent
            .getStringExtra(AiWorkspaceContract.EXTRA_PROVIDER_ID)
            ?.takeIf { id -> providers.any { it.id == id } }
        val requestedIsBinding =
            !intent.getStringExtra(AiWorkspaceContract.EXTRA_BIND_REPO).isNullOrBlank() ||
                !intent.getStringExtra(AiWorkspaceContract.EXTRA_BIND_PROJECT).isNullOrBlank() ||
                !intent.getStringExtra(AiWorkspaceContract.EXTRA_BIND_TITLE).isNullOrBlank()

        when {
            requestedWindowId != null -> switchWindow(requestedWindowId)

            requestedUrl != null -> {
                val existing = windows.firstOrNull {
                    sameBoundPage(it.boundUrl ?: it.url, requestedUrl)
                }
                if (existing != null) {
                    if (requestedIsBinding && existing.boundUrl == null) {
                        updateWindow(existing.id) {
                            it.copy(boundUrl = requestedUrl)
                        }
                    }
                    switchWindow(existing.id)
                } else {
                    val provider = ProviderCatalog.fromUrl(requestedUrl)
                    if (provider != null) {
                        val title = intent
                            .getStringExtra(AiWorkspaceContract.EXTRA_BIND_TITLE)
                            ?.takeIf { it.isNotBlank() }
                            ?: intent.getStringExtra(AiWorkspaceContract.EXTRA_BIND_PROJECT)
                                ?.takeIf { it.isNotBlank() }
                            ?: provider.name
                        val window = ChatWindow(
                            providerId = provider.id,
                            title = title,
                            url = requestedUrl,
                            boundUrl = requestedUrl.takeIf { requestedIsBinding },
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

    private fun importSnapshotMessages(
        snapshot: WebRuntime.ConversationSnapshot,
    ): List<ChatMessage> {
        val prefix = if (snapshot.source.startsWith("network")) {
            "network"
        } else {
            "page"
        }
        return snapshot.messages.mapNotNull { pageMessage ->
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
            val sameId = merged.indexOfFirst { it.id == message.id }
            if (sameId >= 0) {
                val old = merged[sameId]
                merged[sameId] = message.copy(
                    timestamp = old.timestamp,
                    attachments = if (old.attachments.isNotEmpty()) {
                        old.attachments
                    } else {
                        message.attachments
                    },
                )
                return@forEach
            }

            val sameContent = merged.indexOfFirst {
                normalizedMessageKey(it) == normalizedMessageKey(message)
            }
            if (sameContent >= 0) return@forEach

            val lastIndex = merged.lastIndex
            val last = merged.lastOrNull()
            if (
                last != null &&
                last.role == message.role &&
                (
                    message.text.startsWith(last.text) ||
                        last.text.startsWith(message.text)
                    )
            ) {
                if (message.text.length >= last.text.length) {
                    merged[lastIndex] = message.copy(
                        timestamp = last.timestamp,
                        attachments = if (last.attachments.isNotEmpty()) {
                            last.attachments
                        } else {
                            message.attachments
                        },
                    )
                }
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

        snapshot.source == "network-history" && snapshot.complete -> {
            incoming.map { message ->
                val old = previous.firstOrNull {
                    normalizedMessageKey(it) == normalizedMessageKey(message)
                }
                if (old != null && old.attachments.isNotEmpty()) {
                    message.copy(
                        timestamp = old.timestamp,
                        attachments = old.attachments,
                    )
                } else {
                    message
                }
            }
        }

        snapshot.source.startsWith("network") ->
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
            val hadLocalMessages = conversationStore.load(session(target)).isNotEmpty()
            if (!hadLocalMessages && windowId == activeWindowId) {
                setStatus(windowId, "正在同步网页已加载内容…")
            }

            try {
                // Passive only: wait briefly for the provider's own render.
                // Never call startConversationHydration() and never scroll the
                // provider page to force virtualized history to materialize.
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
                    setStatus(windowId, "网页当前没有已加载的对话内容")
                } else if (windowId == activeWindowId) {
                    setStatus(windowId, null)
                }
            } finally {
                syncJobs.remove(windowId)
            }
        }
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

        val targetMessages = conversationStore.load(session(target)).toMutableList()
        targetMessages += ChatMessage(
            role = MessageRole.USER,
            text = visibleText,
            attachments = attachments
        )
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
                    setStatus(target.id, "消息没有被官网确认提交，请切到网页视图检查。")
                    setViewModeFor(target.id, WindowViewMode.WEB)
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
                setStatus(windowId, "官网没有完成消息提交，请切到网页视图检查。")
                setViewModeFor(windowId, WindowViewMode.WEB)
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
        val window = windows.firstOrNull { it.id == windowId } ?: return
        val list = conversationStore.load(session(window)).toMutableList()
        list += ChatMessage(role = MessageRole.ASSISTANT, text = text)
        conversationStore.save(session(window), list)

        if (windowId == activeWindowId) {
            messages.clear()
            messages.addAll(list)
            updateWindow(windowId) { it.copy(unread = false) }
        } else {
            updateWindow(windowId) { it.copy(unread = true) }
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
        messages.addAll(conversationStore.load(session(target)))
        pendingAttachments[target.id] = pendingAttachmentStore.load(session(target))
        updateWindow(target.id) { it.copy(unread = false) }
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
