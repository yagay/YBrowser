package com.yagay.ybrowser.ai.ui

import android.app.Application
import android.content.Intent
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
                    val exists = merged.any {
                        normalizeUrl(it.url) == normalizeUrl(url)
                    }
                    if (!exists) {
                        merged = merged + ChatWindow(
                            providerId = provider.id,
                            title = item.optString("title")
                                .ifBlank { item.optString("project") }
                                .ifBlank { provider.name },
                            url = url,
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

        when {
            requestedWindowId != null -> switchWindow(requestedWindowId)

            requestedUrl != null -> {
                val existing = windows.firstOrNull {
                    normalizeUrl(it.url) == normalizeUrl(requestedUrl)
                }
                if (existing != null) {
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
        updateWindow(windowId) {
            it.copy(url = url, lastActiveAt = System.currentTimeMillis())
        }
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
