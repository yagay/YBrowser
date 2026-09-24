package com.yagay.ybrowser.ai.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import java.security.MessageDigest
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
import com.yagay.ybrowser.ai.web.AiChatRuntime
import com.yagay.ybrowser.ai.web.provider.ProductObservationAuthority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private data class SharedBinding(
        val repoKey: String,
        val project: String,
        val url: String,
        val title: String,
        val updatedAt: Long,
    )

    private data class ProjectTabMerge(
        val survivorId: String,
        val duplicate: ChatWindow,
    )

    private data class NormalizedProjectTabs(
        val windows: List<ChatWindow>,
        val redirects: Map<String, String>,
        val merges: List<ProjectTabMerge>,
    )

    private val windowStore = WindowStore(application)
    private val aiTabCacheStore = AiTabCacheStore(application)
    private val conversationStore = ConversationStore(application)
    private val pendingAttachmentStore = PendingAttachmentStore(application)
    private val historyMigrationPrefs =
        application.getSharedPreferences(
            "aihub_history_migrations",
            0,
        )

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
    private val canonicalReconcileJobs = mutableMapOf<String, Job>()
    private var conversationLoadJob: Job? = null
    private var persistJob: Job? = null
    private val conversationMutexes = mutableMapOf<String, Mutex>()
    private val responseSignals = mutableMapOf<String, Channel<Unit>>()
    private val networkHistoryReady = mutableSetOf<String>()
    private val activeStreamObserved =
        mutableSetOf<String>()
    private val activeStreamCompleted =
        mutableSetOf<String>()
    private val emptyHistoryHydrationAttempted =
        mutableSetOf<String>()

    var emptyHistoryHydrationWindowId by
        mutableStateOf<String?>(null)
        private set

    init {
        runCatching {
            aiTabCacheStore.cleanupTransientFromPreviousRun()
        }.onFailure {
            DiagnosticLogger.e(
                "WORKSPACE_BOOT",
                "transient_cache_cleanup_failed",
                it,
            )
        }

        val restored =
            runCatching {
                windowStore.load()
            }.onFailure {
                DiagnosticLogger.e(
                    "WORKSPACE_BOOT",
                    "window_store_restore_failed",
                    it,
                )
            }.getOrDefault(emptyList())

        val normalizedRestored =
            normalizeProjectTabs(restored)
        val repairedRestored =
            repairRestoredBindings(
                normalizedRestored.windows
            )

        windows =
            if (restored.isEmpty()) {
                listOf(
                    createWindowModel(
                        ProviderCatalog.all.first().id
                    )
                )
            } else {
                repairedRestored.map {
                    it.copy(
                        generating = false,
                        unread = false,
                    )
                }
            }

        val savedActiveId =
            runCatching {
                windowStore.loadActiveId()
            }.onFailure {
                DiagnosticLogger.e(
                    "WORKSPACE_BOOT",
                    "active_window_restore_failed",
                    it,
                )
            }.getOrNull()

        activeWindowId =
            savedActiveId
                ?.let { id ->
                    normalizedRestored.redirects[id] ?: id
                }
                ?.takeIf { id ->
                    windows.any { it.id == id }
                }
                ?: windows.first().id

        // Native chat is the primary presentation. Local failures must never
        // prevent the workspace UI from opening.
        runCatching {
            aiTabCacheStore.reconcile(windows)
        }.onFailure {
            DiagnosticLogger.e(
                "WORKSPACE_BOOT",
                "tab_cache_reconcile_failed",
                it,
            )
        }

        persist(immediate = true)
        reloadConversation()
        migrateLegacyProjectConversationHistory(restored)

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
        get() = windows.filter(::hasProjectBinding)

    val tabWindows: List<ChatWindow>
        get() {
            val bound = boundWindows
            val active = windows.firstOrNull { it.id == activeWindowId }
            return if (active != null && !hasProjectBinding(active)) {
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
                        bindingMatches(
                            window = it,
                            repoKey = repoKey,
                            project = project,
                            url = url,
                        )
                    }
                    if (existingIndex >= 0) {
                        merged = merged.mapIndexed {
                                windowIndex,
                                window,
                            ->
                            if (windowIndex == existingIndex) {
                                val retainedBoundUrl =
                                    window.boundUrl
                                        ?.takeIf {
                                            ProviderCatalog
                                                .fromUrl(it)
                                                ?.id ==
                                                provider.id
                                        }
                                val effectiveUrl =
                                    retainedBoundUrl ?: url

                                if (
                                    retainedBoundUrl != null &&
                                    !sameBoundPage(
                                        retainedBoundUrl,
                                        url,
                                    )
                                ) {
                                    DiagnosticLogger.i(
                                        "WORKSPACE",
                                        "launch_binding_url_preserved window=" +
                                            window.id.take(12) +
                                            " local=" +
                                            retainedBoundUrl.take(180) +
                                            " incoming=" +
                                            url.take(180),
                                    )
                                }

                                window.copy(
                                    providerId = provider.id,
                                    title = displayTitle,
                                    url = effectiveUrl,
                                    boundUrl = effectiveUrl,
                                    boundConversationId =
                                        if (
                                            provider.id ==
                                            "chatgpt"
                                        ) {
                                            window
                                                .boundConversationId
                                                ?: stableChatGptConversationId(
                                                    effectiveUrl
                                                )
                                        } else {
                                            window
                                                .boundConversationId
                                        },
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
                            boundConversationId =
                                if (
                                    provider.id ==
                                    "chatgpt"
                                ) {
                                    stableChatGptConversationId(
                                        url
                                    )
                                } else {
                                    null
                                },
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
            ?.takeIf { id -> windows.any { it.id == id } }
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
        val requestedProjectRaw = intent
            .getStringExtra(
                AiWorkspaceContract.EXTRA_BIND_PROJECT
            )
            .orEmpty()
            .trim()
        val requestedProject = requestedProjectRaw
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
        // EXTRA_BIND_URL is the only launch field that explicitly asks us to
        // replace the currently bound web conversation. Project/repo/title
        // metadata is also present on ordinary YagaYHub launches and must not
        // clobber a newer canonical ChatGPT /c/<id> learned locally.
        val explicitBindingUrlChange =
            requestedBindUrl != null &&
                !requestedWindowId.isNullOrBlank() &&
                intent.action !=
                    AiWorkspaceContract.ACTION_OPEN_AI

        val legacyTopAiPayload =
            intent.action ==
                AiWorkspaceContract.ACTION_OPEN_AI &&
                requestedWindowId == null &&
                requestedProjectRaw.isBlank() &&
                requestedBindingTitle.isBlank() &&
                normalizedProject(requestedRepo) ==
                    "yagay/ybrowser"

        val genericWorkspaceOpen =
            intent.action ==
                AiWorkspaceContract.ACTION_OPEN_AI &&
                requestedWindowId == null &&
                (
                    (
                        requestedRepo.isBlank() &&
                            requestedProject.isBlank() &&
                            requestedBindingTitle.isBlank()
                        ) ||
                        legacyTopAiPayload
                    )

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
                        explicitBindingUrlChange &&
                        !target.boundUrl.isNullOrBlank() &&
                        !sameBoundPage(
                            target.boundUrl,
                            requestedUrl,
                        )
                    ) {
                        clearBoundPageHistory(
                            window = target,
                            reason = "explicit-rebind",
                        )
                        DiagnosticLogger.i(
                            "WORKSPACE",
                            "project_rebind_clear_history window=" +
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
                            url =
                                if (explicitBindingUrlChange) {
                                    requestedUrl
                                } else {
                                    it.boundUrl
                                        ?.takeIf { value ->
                                            ProviderCatalog
                                                .fromUrl(value)
                                                ?.id ==
                                                (
                                                    ProviderCatalog
                                                        .fromUrl(requestedUrl)
                                                        ?.id
                                                        ?: it.providerId
                                                    )
                                        }
                                        ?: requestedUrl
                                },
                            boundUrl =
                                if (explicitBindingUrlChange) {
                                    requestedUrl
                                } else {
                                    it.boundUrl ?: requestedUrl
                                },
                            boundConversationId =
                                if (
                                    it.providerId ==
                                    "chatgpt"
                                ) {
                                    if (
                                        explicitBindingUrlChange
                                    ) {
                                        stableChatGptConversationId(
                                            requestedUrl
                                        )
                                    } else {
                                        it.boundConversationId
                                            ?: stableChatGptConversationId(
                                                it.boundUrl
                                                    ?: requestedUrl
                                            )
                                    }
                                } else {
                                    it.boundConversationId
                                },
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
                            viewMode = WindowViewMode.CHAT,
                        )
                    }
                    aiTabCacheStore.reconcile(windows)
                    switchWindow(target.id)
                }
            }

            requestedWindowId != null ->
                switchWindow(requestedWindowId)

            genericWorkspaceOpen -> {
                DiagnosticLogger.i(
                    "WORKSPACE",
                    "generic_launch_preserve_active id=" +
                        activeWindowId.take(12) +
                        " ignoredUrl=" +
                        requestedUrl.orEmpty().take(160),
                )
            }

            requestedUrl != null -> {
                val existing = windows.firstOrNull {
                    bindingMatches(
                        window = it,
                        repoKey = requestedRepo,
                        project = requestedProject,
                        url = requestedUrl,
                    )
                }
                if (existing != null) {
                    if (requestedIsBinding) {
                        if (
                            explicitBindingUrlChange &&
                            !existing.boundUrl.isNullOrBlank() &&
                            !sameBoundPage(
                                existing.boundUrl,
                                requestedUrl,
                            )
                        ) {
                            clearBoundPageHistory(
                                window = existing,
                                reason = "explicit-rebind",
                            )
                        }
                        updateWindow(existing.id) {
                            it.copy(
                                title = requestedProject
                                    .ifBlank { requestedBindingTitle }
                                    .ifBlank { it.title },
                                url =
                                    if (explicitBindingUrlChange) {
                                        requestedUrl
                                    } else {
                                        it.boundUrl ?: requestedUrl
                                    },
                                boundUrl =
                                    if (explicitBindingUrlChange) {
                                        requestedUrl
                                    } else {
                                        it.boundUrl ?: requestedUrl
                                    },
                                boundConversationId =
                                    if (
                                        it.providerId ==
                                        "chatgpt"
                                    ) {
                                        if (
                                            explicitBindingUrlChange
                                        ) {
                                            stableChatGptConversationId(
                                                requestedUrl
                                            )
                                        } else {
                                            it.boundConversationId
                                                ?: stableChatGptConversationId(
                                                    it.boundUrl
                                                        ?: requestedUrl
                                                )
                                        }
                                    } else {
                                        it.boundConversationId
                                    },
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
                            boundConversationId =
                                if (
                                    provider.id ==
                                        "chatgpt" &&
                                    requestedIsBinding
                                ) {
                                    stableChatGptConversationId(
                                        requestedUrl
                                    )
                                } else {
                                    null
                                },
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

        val beforeNormalize = windows
        val normalizedLive =
            normalizeProjectTabs(beforeNormalize)
        windows = normalizedLive.windows
        activeWindowId =
            normalizedLive.redirects[activeWindowId]
                ?: activeWindowId
        if (windows.none { it.id == activeWindowId }) {
            activeWindowId = windows.first().id
        }
        aiTabCacheStore.reconcile(windows)
        persist()
        reloadConversation()
        migrateLegacyProjectConversationHistory(beforeNormalize)
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

    private fun chatGptConversationId(value: String?): String? =
        runCatching {
            val path = Uri.parse(value.orEmpty()).path.orEmpty()
            Regex("""(?:^|/)c/([^/?#]+)(?:/|$)""")
                .find(path)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun isTransientChatGptConversationPage(
        value: String?,
    ): Boolean =
        chatGptConversationId(value)
            ?.startsWith("WEB:", ignoreCase = true) == true

    private fun stableChatGptConversationId(
        value: String?,
    ): String? =
        chatGptConversationId(value)
            ?.takeUnless {
                it.startsWith(
                    "WEB:",
                    ignoreCase = true,
                )
            }

    private fun isCanonicalChatGptConversationPage(
        value: String?,
    ): Boolean {
        val id = chatGptConversationId(value) ?: return false
        return !id.startsWith("WEB:", ignoreCase = true)
    }

    private fun preferSharedBindingUrl(
        window: ChatWindow,
        sharedUrl: String,
        sharedUpdatedAt: Long,
    ): Boolean {
        if (window.providerId != "chatgpt") {
            return window.boundUrl.isNullOrBlank() ||
                sameBoundPage(window.boundUrl, sharedUrl)
        }

        val localCanonical =
            isCanonicalChatGptConversationPage(window.boundUrl)
        val sharedCanonical =
            isCanonicalChatGptConversationPage(sharedUrl)

        return when {
            sharedCanonical && !localCanonical -> true
            !sharedCanonical && localCanonical -> false
            sharedCanonical && localCanonical ->
                sameBoundPage(
                    window.boundUrl,
                    sharedUrl,
                ) ||
                    (
                        sharedUpdatedAt > 0L &&
                            sharedUpdatedAt >
                            window.lastActiveAt
                        )
            else -> window.boundUrl.isNullOrBlank() ||
                sameBoundPage(window.boundUrl, sharedUrl)
        }
    }

    private fun shouldPromoteChatGptBoundPage(
        previous: String?,
        current: String?,
    ): Boolean {
        val currentId =
            chatGptConversationId(current)
                ?: return false
        if (currentId.startsWith("WEB:", ignoreCase = true)) {
            return false
        }
        val previousId = chatGptConversationId(previous)
        return previousId == null ||
            previousId.startsWith("WEB:", ignoreCase = true)
    }

    private fun normalizedProject(value: String?): String =
        value.orEmpty().trim().lowercase()

    private fun normalizeProjectTabs(
        source: List<ChatWindow>,
    ): NormalizedProjectTabs {
        if (source.isEmpty()) {
            return NormalizedProjectTabs(
                windows = emptyList(),
                redirects = emptyMap(),
                merges = emptyList(),
            )
        }

        val normalized = mutableListOf<ChatWindow>()
        val redirects = mutableMapOf<String, String>()
        val merges = mutableListOf<ProjectTabMerge>()

        source.forEach { window ->
            if (!hasProjectBinding(window)) {
                normalized += window
                return@forEach
            }

            val existingIndex =
                normalized.indexOfFirst { candidate ->
                    hasProjectBinding(candidate) &&
                        sameProjectBinding(
                            window = candidate,
                            repoKey = window.boundRepo,
                            project = window.boundProject,
                        )
                }

            if (existingIndex < 0) {
                normalized += window
                return@forEach
            }

            val survivor = normalized[existingIndex]
            val latest =
                if (window.lastActiveAt >= survivor.lastActiveAt) {
                    window
                } else {
                    survivor
                }

            normalized[existingIndex] =
                survivor.copy(
                    title = latest.title,
                    url = latest.url ?: survivor.url,
                    boundUrl =
                        latest.boundUrl ?: survivor.boundUrl,
                    boundConversationId =
                        latest.boundConversationId
                            ?: survivor.boundConversationId,
                    boundRepo =
                        latest.boundRepo ?: survivor.boundRepo,
                    boundProject =
                        latest.boundProject
                            ?: survivor.boundProject,
                    viewMode = latest.viewMode,
                    createdAt =
                        minOf(
                            survivor.createdAt,
                            window.createdAt,
                        ),
                    lastActiveAt =
                        maxOf(
                            survivor.lastActiveAt,
                            window.lastActiveAt,
                        ),
                    unread = survivor.unread || window.unread,
                    generating = false,
                )

            redirects[window.id] = survivor.id
            merges += ProjectTabMerge(
                survivorId = survivor.id,
                duplicate = window,
            )
        }

        return NormalizedProjectTabs(
            windows = normalized,
            redirects = redirects,
            merges = merges,
        )
    }

    /**
     * Project tags and conversation history are deliberately separate.
     *
     * Older builds stored one merged history per project, which allowed turns
     * from several differently bound ChatGPT pages to accumulate under one
     * tag. History is now page-owned. Remove that deprecated project bucket
     * and let the current bound page rehydrate its own history from the
     * provider.
     */
    /**
     * One-time upgrade from the historical project-scoped transcript bucket
     * to the current page/conversation-scoped bucket.
     *
     * Never delete first. If the current page bucket already has data it wins
     * and the legacy mixed bucket is discarded. If the page bucket is empty,
     * copy the legacy transcript first, verify it can be loaded back, and only
     * then remove the old bucket. A persistent per-project marker prevents
     * repeated destructive work on every launch/binding sync.
     */
    private fun migrateLegacyProjectConversationHistory(
        source: List<ChatWindow>,
    ) {
        val projectWindows =
            source.filter(::hasProjectBinding)
        if (projectWindows.isEmpty()) return

        viewModelScope.launch {
            var migrated = 0
            var pageOwned = 0
            var legacyOnly = 0

            projectWindows
                .distinctBy {
                    legacyProjectConversationSession(it)
                        .storageKey
                }
                .forEach { window ->
                    val legacy =
                        legacyProjectConversationSession(window)
                    val current =
                        conversationSession(window)

                    if (
                        legacy.storageKey ==
                        current.storageKey
                    ) {
                        return@forEach
                    }

                    val currentMessages =
                        conversationMutex(
                            current.storageKey
                        ).withLock {
                            conversationStore.load(current)
                        }

                    if (currentMessages.isNotEmpty()) {
                        pageOwned++
                        return@forEach
                    }

                    val legacyMessages =
                        conversationMutex(
                            legacy.storageKey
                        ).withLock {
                            conversationStore.load(legacy)
                        }

                    if (legacyMessages.isEmpty()) {
                        return@forEach
                    }

                    conversationMutex(
                        current.storageKey
                    ).withLock {
                        conversationStore.save(
                            current,
                            legacyMessages,
                        )
                    }
                    migrated++
                    legacyOnly++

                    DiagnosticLogger.i(
                        "WORKSPACE",
                        "legacy_project_history_migrated window=" +
                            window.id.take(12) +
                            " messages=" +
                            legacyMessages.size +
                            " target=" +
                            current.storageKey.take(40),
                    )
                }

            DiagnosticLogger.i(
                "WORKSPACE",
                "legacy_project_history_checked projects=" +
                    projectWindows.size +
                    " migrated=" + migrated +
                    " pageOwned=" + pageOwned +
                    " legacyOnly=" + legacyOnly,
            )
        }
    }

    private fun clearBoundPageHistory(
        window: ChatWindow,
        reason: String,
    ) {
        syncJobs.remove(window.id)?.cancel()
        canonicalReconcileJobs
            .remove(window.id)
            ?.cancel()
        networkHistoryReady.remove(window.id)

        val historySession =
            conversationSession(window)

        if (window.id == activeWindowId) {
            messages.clear()
            setStatus(
                window.id,
                "绑定页面已更换，正在读取新页面历史…",
            )
        }

        viewModelScope.launch {
            conversationMutex(
                historySession.storageKey
            ).withLock {
                conversationStore.clear(
                    historySession
                )
            }
            conversationMutexes.remove(
                historySession.storageKey
            )

            DiagnosticLogger.i(
                "WORKSPACE",
                "bound_page_history_cleared window=" +
                    window.id.take(12) +
                    " page=" +
                    (window.boundUrl ?: window.url)
                        .orEmpty()
                        .take(180) +
                    " reason=" +
                    reason,
            )
        }
    }


    private fun migratePromotedPageHistory(
        window: ChatWindow,
        newUrl: String,
    ) {
        val oldSession =
            conversationSession(window)
        val promotedWindow =
            window.copy(
                url = newUrl,
                boundUrl = newUrl,
                boundConversationId =
                    stableChatGptConversationId(
                        newUrl
                    ),
            )
        val newSession =
            conversationSession(promotedWindow)

        if (
            oldSession.storageKey ==
            newSession.storageKey
        ) {
            return
        }

        viewModelScope.launch {
            val oldMessages =
                conversationMutex(
                    oldSession.storageKey
                ).withLock {
                    conversationStore.load(
                        oldSession
                    )
                }

            if (oldMessages.isNotEmpty()) {
                conversationMutex(
                    newSession.storageKey
                ).withLock {
                    val existing =
                        conversationStore.load(
                            newSession
                        )
                    conversationStore.save(
                        newSession,
                        mergeNetworkDelta(
                            previous = existing,
                            incoming = oldMessages,
                        ),
                    )
                }
            }

            conversationMutex(
                oldSession.storageKey
            ).withLock {
                conversationStore.clear(
                    oldSession
                )
            }
            conversationMutexes.remove(
                oldSession.storageKey
            )

            DiagnosticLogger.i(
                "WORKSPACE",
                "promoted_page_history_migrated window=" +
                    window.id.take(12) +
                    " from=" +
                    (window.boundUrl ?: window.url)
                        .orEmpty()
                        .take(160) +
                    " to=" +
                    newUrl.take(160) +
                    " messages=" +
                    oldMessages.size,
            )
        }
    }

    private fun hasProjectBinding(window: ChatWindow): Boolean =
        !window.boundRepo.isNullOrBlank() ||
            !window.boundProject.isNullOrBlank()

    private fun sameProjectBinding(
        window: ChatWindow,
        repoKey: String?,
        project: String?,
    ): Boolean {
        val incomingRepo = normalizedProject(repoKey)
        val windowRepo = normalizedProject(window.boundRepo)

        if (incomingRepo.isNotBlank() && windowRepo.isNotBlank()) {
            return incomingRepo == windowRepo
        }

        val incomingProject = normalizedProject(
            project?.takeIf { it.isNotBlank() }
                ?: repoKey?.substringAfterLast('/'),
        )
        val windowProject = normalizedProject(
            window.boundProject?.takeIf { it.isNotBlank() }
                ?: window.boundRepo?.substringAfterLast('/'),
        )

        return incomingProject.isNotBlank() &&
            windowProject.isNotBlank() &&
            incomingProject == windowProject
    }

    /**
     * Project identity owns the tag. URL identity owns only the page history.
     * Once a project is known, a different URL is a rebind of the same tag,
     * never a second tag.
     */
    private fun bindingMatches(
        window: ChatWindow,
        repoKey: String?,
        project: String?,
        url: String?,
    ): Boolean {
        val incomingHasProject =
            !repoKey.isNullOrBlank() ||
                !project.isNullOrBlank()

        return if (incomingHasProject) {
            sameProjectBinding(window, repoKey, project)
        } else {
            !hasProjectBinding(window) &&
                sameBoundPage(
                    window.boundUrl ?: window.url,
                    url,
                )
        }
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
                id = "$prefix@${pageMessage.id}",
                role = role,
                text = pageMessage.text,
                attachments = pageMessage.attachments,
            )
        }

    private fun importSnapshotMessages(
        snapshot: WebRuntime.ConversationSnapshot,
    ): List<ChatMessage> {
        val scopeIdentity =
            snapshot.conversationId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { "conversation:$it" }
                ?: pageIdentity(snapshot.url)
                    .orEmpty()
        val pageScope =
            Integer.toHexString(
                scopeIdentity.hashCode(),
            )
        val prefix =
            when {
                snapshot.authority ==
                    ProductObservationAuthority.CANONICAL ->
                    "canonical@$pageScope"
                snapshot.source.startsWith("network") ->
                    "network@$pageScope"
                else ->
                    "page@$pageScope"
            }
        return importPageMessages(snapshot.messages, prefix)
    }

    private fun messageSourceScope(
        message: ChatMessage,
    ): String? {
        val id = message.id
        if (
            !id.startsWith("network@") &&
            !id.startsWith("page@") &&
            !id.startsWith("canonical@")
        ) {
            return null
        }
        val separator = id.lastIndexOf('@')
        if (separator <= 0) return null
        return id.substring(0, separator)
    }

    private fun normalizedMessageKey(message: ChatMessage): String =
        message.role.name + "|" +
            message.text.replace(Regex("\\s+"), " ").trim() +
            "|" +
            message.attachments.joinToString(",") {
                it.uri.orEmpty() + ":" + it.name
            }

    private fun messagePageScope(
        message: ChatMessage,
    ): String? {
        val id = message.id
        val first = id.indexOf('@')
        if (first < 0) return null
        val second = id.indexOf('@', first + 1)
        if (second <= first + 1) return null
        return id.substring(first + 1, second)
    }

    private fun messageWireId(
        message: ChatMessage,
    ): String? {
        if (messageSourceScope(message) == null) return null
        return message.id
            .substringAfterLast('@')
            .takeIf {
                it.isNotBlank()
            }
    }

    private fun mergeCanonicalSnapshot(
        snapshot: WebRuntime.ConversationSnapshot,
        previous: List<ChatMessage>,
        incoming: List<ChatMessage>,
    ): List<ChatMessage> {
        if (incoming.isEmpty()) return previous

        val canonicalScope =
            incoming.firstNotNullOfOrNull(
                ::messagePageScope
            )

        val base =
            if (
                snapshot.complete &&
                canonicalScope != null
            ) {
                previous.filterNot { old ->
                    messagePageScope(old) ==
                        canonicalScope &&
                        messageSourceScope(old) != null
                }
            } else {
                previous
            }

        val merged = base.toMutableList()

        incoming.forEach { message ->
            val pageScope =
                messagePageScope(message)
            val wireId =
                messageWireId(message)

            val sameWire =
                if (wireId != null) {
                    // ChatGPT uses stable message ids while a newly-created
                    // conversation briefly moves through /c/WEB:<id> before
                    // adopting its canonical /c/<conversation-id> URL. Match
                    // the wire id across that route transition so the
                    // provisional network turn is replaced instead of being
                    // duplicated under a second page scope.
                    merged.indexOfFirst { old ->
                        old.role == message.role &&
                            messageWireId(old) == wireId
                    }
                } else {
                    -1
                }

            if (sameWire >= 0) {
                val old = merged[sameWire]
                merged[sameWire] =
                    message.copy(
                        timestamp =
                            old.timestamp,
                        attachments =
                            if (
                                old.attachments
                                    .isNotEmpty()
                            ) {
                                old.attachments
                            } else {
                                message.attachments
                            },
                    )
                return@forEach
            }

            val sameContent =
                merged.indexOfLast { old ->
                    normalizedMessageKey(old) ==
                        normalizedMessageKey(message) &&
                        (
                            messagePageScope(old) ==
                                pageScope ||
                                old.id.startsWith("local-")
                            )
                }
            if (sameContent >= 0) {
                val old = merged[sameContent]
                merged[sameContent] =
                    message.copy(
                        timestamp =
                            old.timestamp,
                        attachments =
                            if (
                                old.attachments
                                    .isNotEmpty()
                            ) {
                                old.attachments
                            } else {
                                message.attachments
                            },
                    )
                return@forEach
            }

            val lastIndex =
                merged.lastIndex
            val last =
                merged.lastOrNull()
            val samePage =
                pageScope != null &&
                    last != null &&
                    messagePageScope(last) ==
                    pageScope

            if (
                last != null &&
                samePage &&
                last.role == message.role &&
                (
                    message.text
                        .startsWith(last.text) ||
                        last.text
                            .startsWith(
                                message.text
                            )
                    )
            ) {
                if (
                    message.text.length >=
                    last.text.length
                ) {
                    merged[lastIndex] =
                        message.copy(
                            timestamp =
                                last.timestamp,
                            attachments =
                                if (
                                    last.attachments
                                        .isNotEmpty()
                                ) {
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

            val incomingScope =
                messageSourceScope(message)
            val sameContent = merged.indexOfFirst {
                normalizedMessageKey(it) ==
                    normalizedMessageKey(message) &&
                    (
                        incomingScope == null ||
                            messageSourceScope(it) == null ||
                            messageSourceScope(it) ==
                            incomingScope
                        )
            }
            if (sameContent >= 0) return@forEach

            val lastIndex = merged.lastIndex
            val last = merged.lastOrNull()
            val sameStreamingSource =
                incomingScope == null ||
                    last == null ||
                    messageSourceScope(last) == null ||
                    messageSourceScope(last) ==
                    incomingScope
            if (
                last != null &&
                sameStreamingSource &&
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

        snapshot.authority ==
            ProductObservationAuthority.CANONICAL ->
            mergeCanonicalSnapshot(
                snapshot = snapshot,
                previous = previous,
                incoming = incoming,
            )

        snapshot.source == "dom" && window.id in networkHistoryReady ->
            previous

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

        else -> mergePassiveConversation(
            previous = previous,
            incoming = incoming,
        )
    }

    fun ensureCachedHistory(
        runtime: AiChatRuntime,
        windowId: String = activeWindowId,
    ) {
        val target =
            windows.firstOrNull {
                it.id == windowId
            } ?: return
        val provider =
            ProviderCatalog.byId(
                target.providerId
            )

        viewModelScope.launch {
            val historySession =
                conversationSession(target)
            val local =
                conversationMutex(
                    historySession.storageKey
                ).withLock {
                    conversationStore.load(
                        historySession
                    )
                }

            if (
                windowId == activeWindowId &&
                local.isNotEmpty()
            ) {
                if (messages != local) {
                    messages.clear()
                    messages.addAll(local)
                }
                setStatus(windowId, null)
            }

            DiagnosticLogger.i(
                "WORKSPACE",
                "cached_history_checked window=" +
                    windowId.take(12) +
                    " messages=" + local.size +
                    " session=" +
                    historySession.storageKey.take(40),
            )

            if (local.isNotEmpty()) {
                return@launch
            }

            if (
                provider.id == "chatgpt" &&
                !target.boundUrl.isNullOrBlank()
            ) {
                if (windowId == activeWindowId) {
                    setStatus(
                        windowId,
                        "正在首次恢复这个标签的历史…",
                    )
                }
                syncPage(
                    runtime = runtime,
                    windowId = windowId,
                )
            }
        }
    }

    fun syncPage(
        runtime: AiChatRuntime,
        windowId: String = activeWindowId,
    ) {
        val target = windows.firstOrNull { it.id == windowId } ?: return
        val provider = ProviderCatalog.byId(target.providerId)

        if (
            hasProjectBinding(target) &&
            target.boundUrl.isNullOrBlank()
        ) {
            if (windowId == activeWindowId) {
                setStatus(
                    windowId,
                    "项目历史已保留；当前没有绑定网页。",
                )
            }
            return
        }

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
                    .load(conversationSession(target))
                    .isNotEmpty()
            if (!hadLocalMessages && windowId == activeWindowId) {
                setStatus(
                    windowId,
                    "正在同步聊天历史…",
                )
            }

            try {
                if (provider.id == "chatgpt") {
                    // CWA-style canonical observation is the authority for
                    // durable ChatGPT history. Network/SSE remains a
                    // provisional live-display plane only.
                    val liveTarget =
                        windows.firstOrNull {
                            it.id == windowId
                        } ?: target

                    runtime.ensurePreferredPage(
                        liveTarget,
                        provider,
                    )

                    if (
                        hadLocalMessages &&
                        windowId == activeWindowId
                    ) {
                        setStatus(
                            windowId,
                            "正在读取官网聊天历史…",
                        )
                    }

                    repeat(6) { attempt ->
                        if (attempt > 0) {
                            delay(
                                if (attempt == 1) {
                                    250
                                } else {
                                    450
                                }
                            )
                        }

                        val canonical =
                            runCatching {
                                runtime
                                    .canonicalConversationSnapshot(
                                        liveTarget,
                                        provider,
                                        includeAllPages = true,
                                    )
                            }.onFailure {
                                DiagnosticLogger.w(
                                    "WORKSPACE",
                                    "canonical_sync_failed provider=" +
                                        provider.id +
                                        " window=" +
                                        windowId.take(12) +
                                        " attempt=" +
                                        attempt,
                                    it,
                                )
                            }.getOrNull()

                        if (
                            canonical != null &&
                            providerOwnsPage(
                                canonical.url,
                                provider,
                            ) &&
                            canonical.messages
                                .isNotEmpty()
                        ) {
                            val imported =
                                importSnapshotMessages(
                                    canonical
                                )
                            val currentWindow =
                                windows.firstOrNull {
                                    it.id == windowId
                                } ?: liveTarget
                            val stored =
                                conversationMutex(
                                    conversationSession(
                                        currentWindow
                                    ).storageKey
                                ).withLock {
                                    val previous =
                                        conversationStore
                                            .load(
                                                conversationSession(
                                                    currentWindow
                                                )
                                            )
                                    val merged =
                                        mergeSnapshot(
                                            window =
                                                currentWindow,
                                            snapshot =
                                                canonical,
                                            previous =
                                                previous,
                                            incoming =
                                                imported,
                                        )
                                    conversationStore.save(
                                        conversationSession(
                                            currentWindow
                                        ),
                                        merged,
                                    )
                                    merged
                                }

                            networkHistoryReady +=
                                windowId

                            canonical.url
                                .takeIf {
                                    it.isNotBlank()
                                }
                                ?.let { currentUrl ->
                                    updateWindow(
                                        windowId
                                    ) {
                                        it.copy(
                                            url =
                                                currentUrl,
                                            lastActiveAt =
                                                System.currentTimeMillis(),
                                        )
                                    }
                                }

                            if (
                                canonical.title
                                    .isNotBlank() &&
                                currentWindow.title ==
                                    "新对话"
                            ) {
                                updateWindow(
                                    windowId
                                ) {
                                    it.copy(
                                        title =
                                            canonical
                                                .title
                                                .take(
                                                    48
                                                )
                                    )
                                }
                            }

                            if (
                                windowId ==
                                activeWindowId
                            ) {
                                messages.clear()
                                messages.addAll(
                                    stored
                                )
                                setStatus(
                                    windowId,
                                    null,
                                )
                            } else {
                                updateWindow(
                                    windowId
                                ) {
                                    it.copy(
                                        unread = true
                                    )
                                }
                            }

                            DiagnosticLogger.i(
                                "WORKSPACE",
                                "canonical_history_synced provider=" +
                                    provider.id +
                                    " window=" +
                                    windowId.take(12) +
                                    " stored=" +
                                    stored.size,
                            )
                            return@launch
                        }
                    }

                    if (
                        windowId ==
                        activeWindowId
                    ) {
                        setStatus(
                            windowId,
                            if (
                                hadLocalMessages
                            ) {
                                "旧聊天已保留；官网历史暂未完成确认。"
                            } else {
                                "暂未读取到官网聊天历史，可切到网页检查。"
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
                            val previous = conversationStore.load(conversationSession(liveWindow))
                            val stored = mergeSnapshot(
                                window = liveWindow,
                                snapshot = snapshot,
                                previous = previous,
                                incoming = imported,
                            )
                            conversationStore.save(conversationSession(liveWindow), stored)

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
        runtime: AiChatRuntime,
        windowId: String = activeWindowId,
    ) {
        val target = windows.firstOrNull { it.id == windowId } ?: return
        val provider = ProviderCatalog.byId(target.providerId)
        if (target.generating) return

        if (
            hasProjectBinding(target) &&
            target.boundUrl.isNullOrBlank()
        ) {
            if (windowId == activeWindowId) {
                setStatus(
                    windowId,
                    "项目历史已保留；请先绑定网页再同步。",
                )
            }
            return
        }

        syncJobs.remove(windowId)?.cancel()
        networkHistoryReady.remove(windowId)

        if (windowId == activeWindowId) {
            messages.clear()
            setStatus(
                windowId,
                if (provider.id == "chatgpt") {
                    "正在重新读取当前绑定页面历史…"
                } else {
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

        viewModelScope.launch {
            conversationMutex(
                conversationSession(target).storageKey
            ).withLock {
                conversationStore.clear(
                    conversationSession(target)
                )
            }

            if (provider.id == "chatgpt") {
                runtime.clearConversationCache(windowId)
                runtime.reloadPage(target, provider)
            }
            syncPage(runtime, windowId)
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

    private fun loadSharedBindings(): List<SharedBinding> {
        val prefs =
            getApplication<Application>()
                .getSharedPreferences(
                    "ybrowser_store",
                    0,
                )
        if (!prefs.contains("chat_bindings")) return emptyList()

        val array = runCatching {
            JSONArray(
                prefs.getString(
                    "chat_bindings",
                    "[]",
                ) ?: "[]"
            )
        }.getOrElse { JSONArray() }

        return buildList {
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
                        updatedAt =
                            item.optLong("addedAt", 0L),
                    )
                )
            }
        }
            .sortedByDescending { it.updatedAt }
            .distinctBy { it.repoKey.lowercase() }
    }

    private fun repairRestoredBindings(
        source: List<ChatWindow>,
    ): List<ChatWindow> {
        val bindings = loadSharedBindings()
        if (bindings.isEmpty()) return source

        return source.map { window ->
            val match =
                bindings.firstOrNull {
                    sameProjectBinding(
                        window = window,
                        repoKey = it.repoKey,
                        project = it.project,
                    )
                } ?: return@map window

            if (
                window.providerId == "chatgpt" &&
                isCanonicalChatGptConversationPage(match.url) &&
                !isCanonicalChatGptConversationPage(window.boundUrl)
            ) {
                clearBoundPageHistory(
                    window = window,
                    reason = "startup-binding-repair",
                )
                DiagnosticLogger.i(
                    "WORKSPACE",
                    "binding_repaired_from_shared_canonical window=" +
                        window.id.take(12) +
                        " from=" +
                        window.boundUrl.orEmpty().take(160) +
                        " to=" +
                        match.url.take(160),
                )
                window.copy(
                    title = match.project.ifBlank { window.title },
                    url = match.url,
                    boundUrl = match.url,
                    boundConversationId =
                        stableChatGptConversationId(
                            match.url
                        ),
                    boundRepo = match.repoKey,
                    boundProject = match.project,
                )
            } else {
                window
            }
        }
    }

    fun refreshBindingsFromSharedStore() {
        val bindings = loadSharedBindings()
        if (bindings.isEmpty()) return

        var changed = false
        var merged = windows.map { window ->
            val page =
                window.boundUrl ?: window.url
            val match =
                bindings.firstOrNull {
                    sameProjectBinding(
                        window = window,
                        repoKey = it.repoKey,
                        project = it.project,
                    )
                }
                    ?: if (!hasProjectBinding(window)) {
                        bindings.firstOrNull {
                            sameBoundPage(it.url, page)
                        }
                    } else {
                        null
                    }

            if (match != null) {
                val sharedPageIsNewer =
                    preferSharedBindingUrl(
                        window = window,
                        sharedUrl = match.url,
                        sharedUpdatedAt = match.updatedAt,
                    ) ||
                        (
                            window.providerId != "chatgpt" &&
                                match.updatedAt > 0L &&
                                match.updatedAt >
                                window.lastActiveAt
                            )

                if (
                    sharedPageIsNewer &&
                    !window.boundUrl.isNullOrBlank() &&
                    !sameBoundPage(
                        window.boundUrl,
                        match.url,
                    )
                ) {
                    clearBoundPageHistory(
                        window = window,
                        reason = "shared-binding-rebind",
                    )
                }

                val updated = window.copy(
                    title =
                        match.project.ifBlank {
                            window.title
                        },
                    url =
                        if (sharedPageIsNewer) {
                            match.url
                        } else {
                            window.url
                        },
                    boundUrl =
                        if (sharedPageIsNewer) {
                            match.url
                        } else {
                            window.boundUrl
                        },
                    boundConversationId =
                        if (
                            window.providerId ==
                                "chatgpt" &&
                            sharedPageIsNewer
                        ) {
                            stableChatGptConversationId(
                                match.url
                            )
                        } else {
                            window.boundConversationId
                        },
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
                    boundConversationId =
                        if (
                            provider.id ==
                            "chatgpt"
                        ) {
                            stableChatGptConversationId(
                                binding.url
                            )
                        } else {
                            null
                        },
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
            migrateLegacyProjectConversationHistory(windows)
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

    /**
     * Detach only the current web conversation.
     *
     * The project identity, project tab and project-level native history stay
     * intact. Deleting the project tab is a separate explicit action.
     */
    fun unbindWindow(windowId: String) {
        val target = windows.firstOrNull { it.id == windowId } ?: return
        val url = target.boundUrl ?: return

        notifyBindingRemoval(url)

        updateWindow(windowId) {
            it.copy(
                boundUrl = null,
            )
        }
        aiTabCacheStore.markUnbound(windowId)
        setStatus(
            windowId,
            "已解除网页绑定，项目标签和聊天历史已保留。",
        )

        DiagnosticLogger.i(
            "WORKSPACE",
            "web_binding_removed_keep_project id=" +
                windowId.take(12) +
                " project=" +
                target.boundProject.orEmpty().take(80) +
                " url=" + url.take(160)
        )
    }

    fun deleteChat(
        windowId: String,
        runtime: AiChatRuntime,
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

    private fun persistProjectWebBinding(
        window: ChatWindow,
        url: String,
        title: String,
    ) {
        val repoKey =
            window.boundRepo
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return
        val normalizedUrl =
            url.trim().trimEnd('/')
                .takeIf {
                    it.startsWith("http://") ||
                        it.startsWith("https://")
                } ?: return

        if (
            window.providerId == "chatgpt" &&
            !isCanonicalChatGptConversationPage(
                normalizedUrl,
            )
        ) {
            DiagnosticLogger.i(
                "WORKSPACE",
                "binding_persist_skip_noncanonical window=" +
                    window.id.take(12) +
                    " url=" +
                    normalizedUrl.take(160),
            )
            return
        }

        val app = getApplication<Application>()
        val prefs =
            app.getSharedPreferences(
                "ybrowser_store",
                0,
            )
        val existing =
            runCatching {
                JSONArray(
                    prefs.getString(
                        "chat_bindings",
                        "[]",
                    ) ?: "[]"
                )
            }.getOrElse { JSONArray() }

        val output = JSONArray()
        output.put(
            org.json.JSONObject()
                .put("repoKey", repoKey.lowercase())
                .put(
                    "project",
                    window.boundProject.orEmpty()
                        .ifBlank {
                            repoKey.substringAfterLast('/')
                        },
                )
                .put("url", normalizedUrl)
                .put(
                    "title",
                    title.trim()
                        .ifBlank { window.title }
                        .ifBlank { "AI" },
                )
                .put(
                    "addedAt",
                    System.currentTimeMillis(),
                )
        )

        for (index in 0 until existing.length()) {
            val item =
                existing.optJSONObject(index)
                    ?: continue
            val itemRepo =
                item.optString("repoKey")
                    .trim()
            val itemUrl =
                item.optString("url")
                    .trim()
                    .trimEnd('/')
            if (
                itemRepo.equals(
                    repoKey,
                    ignoreCase = true,
                ) ||
                itemUrl == normalizedUrl
            ) {
                continue
            }
            output.put(item)
        }

        prefs.edit()
            .putString(
                "chat_bindings",
                output.toString(),
            )
            .apply()

        // Keep YagaYHub's project pointer in sync with the canonical ChatGPT
        // conversation URL learned by the live page. Otherwise YagaYHub keeps
        // launching the stale root/transient URL and can reset the restored
        // web session on the next AI UI open.
        runCatching {
            app.sendBroadcast(
                Intent(
                    AiWorkspaceContract
                        .ACTION_NOTIFY_BINDING_UPDATE
                ).apply {
                    setPackage(
                        AiWorkspaceContract
                            .YAGAYHUB_PACKAGE
                    )
                    putExtra(
                        AiWorkspaceContract
                            .EXTRA_BIND_REPO,
                        repoKey,
                    )
                    putExtra(
                        AiWorkspaceContract
                            .EXTRA_BIND_PROJECT,
                        window.boundProject.orEmpty()
                            .ifBlank {
                                repoKey.substringAfterLast('/')
                            },
                    )
                    putExtra(
                        AiWorkspaceContract
                            .EXTRA_BIND_URL,
                        normalizedUrl,
                    )
                    putExtra(
                        AiWorkspaceContract
                            .EXTRA_BIND_TITLE,
                        title.trim()
                            .ifBlank { window.title }
                            .ifBlank { "AI" },
                    )
                    putExtra(
                        AiWorkspaceContract
                            .EXTRA_BIND_SILENT_SYNC,
                        true,
                    )
                }
            )
        }.onFailure {
            DiagnosticLogger.w(
                "WORKSPACE",
                "binding_update_notify_failed repo=" +
                    repoKey,
                it,
            )
        }
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
        runCatching {
            aiTabCacheStore.cleanupOnWorkspaceExit(windows)
        }.onFailure {
            DiagnosticLogger.e(
                "WORKSPACE",
                "tab_cache_exit_cleanup_failed",
                it,
            )
        }

        DiagnosticLogger.i(
            "WORKSPACE",
            "tab_cache_exit_cleanup bound=" +
                windows.count {
                    !it.boundUrl.isNullOrBlank()
                } +
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
        persist()
        reloadConversation()
        DiagnosticLogger.i("WORKSPACE", "window_selected id=${id.take(12)}")
    }

    fun closeWindow(id: String, runtime: AiChatRuntime) {
        val target = windows.firstOrNull { it.id == id } ?: return
        generationJobs.remove(id)?.cancel()
        syncJobs.remove(id)?.cancel()
        networkHistoryReady.remove(id)
        activeStreamObserved.remove(id)
        activeStreamCompleted.remove(id)
        runtime.destroyWindow(id, ProviderCatalog.byId(target.providerId))
        aiTabCacheStore.delete(id)
        viewModelScope.launch {
            val historySession =
                conversationSession(target)
            conversationMutex(
                historySession.storageKey
            ).withLock {
                conversationStore.clear(historySession)
            }
            conversationMutexes.remove(
                historySession.storageKey
            )
        }
        pendingAttachmentStore.clear(session(target))
        pendingAttachments.remove(id)
        drafts.remove(id)
        statuses.remove(id)
        responseSignals.remove(id)?.close()

        var remaining = windows.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            remaining = listOf(createWindowModel(target.providerId))
        }
        windows = remaining

        if (activeWindowId == id) {
            activeWindowId = remaining.maxByOrNull { it.lastActiveAt }?.id ?: remaining.first().id
            persist()
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

    fun onPageChanged(
        windowId: String,
        provider: ProviderSpec,
        url: String,
    ) {
        val target =
            windows.firstOrNull {
                it.id == windowId
            } ?: return
        if (target.providerId != provider.id) return

        val oldPage = pageIdentity(target.url)
        val newPage = pageIdentity(url)
        if (
            oldPage != null &&
            newPage != null &&
            oldPage != newPage
        ) {
            networkHistoryReady.remove(windowId)
        }

        // CWA identity invariant: SPA route is navigation state only.
        // Product/network/canonical conversation_id owns binding identity.
        updateWindow(windowId) {
            it.copy(
                url = url,
                lastActiveAt =
                    System.currentTimeMillis(),
            )
        }
    }
    fun onLiveConversationSnapshot(
        runtime: AiChatRuntime,
        windowId: String,
        provider: ProviderSpec,
        snapshot: WebRuntime.ConversationSnapshot,
    ) {
        onConversationSnapshot(
            windowId = windowId,
            provider = provider,
            snapshot = snapshot,
        )

        if (
            provider.id == "chatgpt" &&
            snapshot.source ==
                "network-active-stream"
        ) {
            activeStreamObserved += windowId
            if (snapshot.complete) {
                activeStreamCompleted +=
                    windowId
            }
            responseSignal(windowId)
                .trySend(Unit)
        }

        if (
            provider.id != "chatgpt" ||
            !snapshot.source.startsWith("network")
        ) {
            return
        }

        canonicalReconcileJobs.remove(windowId)?.cancel()
        canonicalReconcileJobs[windowId] =
            viewModelScope.launch {
                // Active stream is the fast display plane. A terminal stream
                // gets a quick latest-page canonical check; non-terminal
                // network observations use the normal quiet debounce.
                delay(
                    if (
                        snapshot.source ==
                            "network-active-stream" &&
                        snapshot.complete
                    ) {
                        CHATGPT_ACTIVE_STREAM_FINAL_RECONCILE_DELAY_MS
                    } else {
                        CHATGPT_CANONICAL_RECONCILE_DELAY_MS
                    }
                )

                repeat(CHATGPT_CANONICAL_RECONCILE_ATTEMPTS) { attempt ->
                    val liveWindow =
                        windows.firstOrNull {
                            it.id == windowId &&
                                it.providerId == provider.id
                        } ?: return@launch

                    val canonical =
                        runCatching {
                            runtime.canonicalConversationSnapshot(
                                window = liveWindow,
                                provider = provider,
                                // Realtime turns only need the latest canonical
                                // page. Full pagination is reserved for an empty
                                // cache or an explicit history refresh.
                                includeAllPages = false,
                            )
                        }.onFailure {
                            if (it !is CancellationException) {
                                DiagnosticLogger.w(
                                    "WORKSPACE",
                                    "canonical_reconcile_failed provider=" +
                                        provider.id +
                                        " window=" +
                                        windowId.take(12) +
                                        " attempt=" +
                                        attempt,
                                    it,
                                )
                            }
                        }.getOrNull()

                    if (
                        canonical != null &&
                        canonical.messages.isNotEmpty()
                    ) {
                        onConversationSnapshot(
                            windowId = windowId,
                            provider = provider,
                            snapshot = canonical,
                        )
                        DiagnosticLogger.i(
                            "WORKSPACE",
                            "canonical_reconciled provider=" +
                                provider.id +
                                " window=" +
                                windowId.take(12) +
                                " messages=" +
                                canonical.messages.size +
                                " attempt=" +
                                attempt,
                        )
                        canonicalReconcileJobs.remove(windowId)
                        return@launch
                    }

                    if (
                        attempt <
                            CHATGPT_CANONICAL_RECONCILE_ATTEMPTS - 1
                    ) {
                        delay(
                            CHATGPT_CANONICAL_RECONCILE_RETRY_MS *
                                (attempt + 1L)
                        )
                    }
                }

                canonicalReconcileJobs.remove(windowId)
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

        val projectBound = hasProjectBinding(target)

        if (
            provider.id == "chatgpt" &&
            projectBound &&
            target.boundUrl.isNullOrBlank()
        ) {
            DiagnosticLogger.recordBridgeTrace(
                stage = "native-drop-project-without-web-binding",
                provider = provider.id,
                windowId = windowId,
                url = snapshot.url,
                detail = "project history only; explicit web binding required",
                candidateCount = snapshot.candidateCount,
                messageCount = snapshot.messages.size,
            )
            return
        }

        val boundConversationId =
            target.boundConversationId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: stableChatGptConversationId(
                    target.boundUrl
                )
        val observedConversationId =
            snapshot.conversationId
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        !it.startsWith(
                            "WEB:",
                            ignoreCase = true,
                        )
                }
                ?: chatGptConversationId(
                    snapshot.url,
                )?.takeUnless {
                    it.startsWith(
                        "WEB:",
                        ignoreCase = true,
                    )
                }
        val observedCanonicalUrl =
            if (
                provider.id == "chatgpt" &&
                observedConversationId != null
            ) {
                "https://chatgpt.com/c/" +
                    Uri.encode(
                        observedConversationId
                    )
            } else {
                null
            }

        if (
            provider.id == "chatgpt" &&
            !target.boundUrl.isNullOrBlank() &&
            boundConversationId != null
        ) {
            val identityMismatch =
                observedConversationId != null &&
                    observedConversationId !=
                    boundConversationId
            val unprovenDifferentPage =
                observedConversationId == null &&
                    !sameBoundPage(
                        target.boundUrl,
                        snapshot.url,
                    )

            if (identityMismatch || unprovenDifferentPage) {
                DiagnosticLogger.recordBridgeTrace(
                    stage =
                        if (identityMismatch) {
                            "native-drop-conversation-identity-mismatch"
                        } else {
                            "native-drop-unproven-route"
                        },
                    provider = provider.id,
                    windowId = windowId,
                    url = snapshot.url,
                    detail =
                        "boundConversationId=" +
                            boundConversationId.take(96) +
                            " observedConversationId=" +
                            observedConversationId
                                .orEmpty()
                                .take(96),
                    candidateCount = snapshot.candidateCount,
                    messageCount = snapshot.messages.size,
                )
                return
            }
        }

        if (
            provider.id == "chatgpt" &&
            projectBound &&
            boundConversationId == null &&
            observedConversationId != null
        ) {
            DiagnosticLogger.recordBridgeTrace(
                stage = "native-product-identity-promote",
                provider = provider.id,
                windowId = windowId,
                url = snapshot.url,
                detail =
                    "conversationId=" +
                        observedConversationId.take(96) +
                        " previous=" +
                        target.boundUrl.orEmpty().take(160),
                candidateCount = snapshot.candidateCount,
                messageCount = snapshot.messages.size,
            )
        }
        if (
            snapshot.authority ==
                ProductObservationAuthority.CANONICAL ||
            (
                snapshot.source ==
                    "network-history" &&
                    snapshot.complete
                )
        ) {
            networkHistoryReady += windowId
        }

        val imported = importSnapshotMessages(snapshot)

        viewModelScope.launch {
            val historyWindow =
                if (
                    provider.id == "chatgpt" &&
                    projectBound &&
                    imported.isNotEmpty() &&
                    observedCanonicalUrl != null &&
                    boundConversationId == null
                ) {
                    target.copy(
                        url = snapshot.url,
                        boundUrl =
                            observedCanonicalUrl,
                        boundConversationId =
                            observedConversationId,
                    )
                } else {
                    target
                }

            val sourceSession =
                conversationSession(target)
            val historySession =
                conversationSession(historyWindow)

            val carried =
                if (
                    sourceSession.storageKey !=
                    historySession.storageKey
                ) {
                    conversationMutex(
                        sourceSession.storageKey
                    ).withLock {
                        conversationStore.load(
                            sourceSession
                        )
                    }
                } else {
                    emptyList()
                }

            val (previous, stored) =
                conversationMutex(
                    historySession.storageKey
                ).withLock {
                    val existing =
                        conversationStore.load(
                            historySession
                        )
                    val previous =
                        if (carried.isNotEmpty()) {
                            mergeNetworkDelta(
                                previous = existing,
                                incoming = carried,
                            )
                        } else {
                            existing
                        }
                    val stored = mergeSnapshot(
                        window = historyWindow,
                        snapshot = snapshot,
                        previous = previous,
                        incoming = imported,
                    )
                    conversationStore.save(
                        historySession,
                        stored,
                    )
                    previous to stored
                }

            if (
                sourceSession.storageKey !=
                historySession.storageKey
            ) {
                conversationMutex(
                    sourceSession.storageKey
                ).withLock {
                    conversationStore.clear(
                        sourceSession
                    )
                }
                conversationMutexes.remove(
                    sourceSession.storageKey
                )
                DiagnosticLogger.i(
                    "WORKSPACE",
                    "snapshot_history_promoted window=" +
                        windowId.take(12) +
                        " from=" +
                        (target.boundUrl ?: target.url)
                            .orEmpty()
                            .take(160) +
                        " to=" +
                        (
                            observedCanonicalUrl
                                ?: snapshot.url
                            ).take(160) +
                        " carried=" +
                        carried.size +
                        " stored=" +
                        stored.size,
                )
            }

            snapshot.url
                .takeIf { it.isNotBlank() }
                ?.let { currentUrl ->
                    updateWindow(windowId) { liveWindow ->
                        val promotedBinding =
                            if (
                                provider.id == "chatgpt" &&
                                hasProjectBinding(liveWindow) &&
                                imported.isNotEmpty() &&
                                observedCanonicalUrl != null
                            ) {
                                observedCanonicalUrl
                            } else {
                                null
                            }
                        liveWindow.copy(
                            url = currentUrl,
                            boundUrl =
                                promotedBinding
                                    ?: liveWindow.boundUrl,
                            boundConversationId =
                                if (
                                    provider.id ==
                                        "chatgpt" &&
                                    observedConversationId !=
                                        null
                                ) {
                                    observedConversationId
                                } else {
                                    liveWindow
                                        .boundConversationId
                                },
                            lastActiveAt =
                                System.currentTimeMillis(),
                        )
                    }

                    val bindingUrl =
                        if (provider.id == "chatgpt") {
                            observedCanonicalUrl
                        } else {
                            currentUrl
                        }
                    if (
                        hasProjectBinding(target) &&
                        imported.isNotEmpty() &&
                        !bindingUrl.isNullOrBlank()
                    ) {
                        persistProjectWebBinding(
                            window = target,
                            url = bindingUrl,
                            title = snapshot.title,
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
                updateWindow(windowId) {
                    it.copy(unread = true)
                }
            }

            val userCount =
                stored.count {
                    it.role == MessageRole.USER
                }
            val assistantCount =
                stored.count {
                    it.role == MessageRole.ASSISTANT
                }
            DiagnosticLogger.recordBridgeTrace(
                stage = "native-applied",
                provider = provider.id,
                windowId = windowId,
                url = snapshot.url,
                detail =
                    "source=" + snapshot.source +
                        " complete=" + snapshot.complete +
                        " " + snapshot.error,
                candidateCount = snapshot.candidateCount,
                messageCount = stored.size,
                userCount = userCount,
                assistantCount = assistantCount,
            )
            DiagnosticLogger.d(
                "WORKSPACE",
                "page_push provider=" + provider.id +
                    " window=" + windowId.take(12) +
                    " messages=" + imported.size
            )
        }
    }
    fun onAttachments(windowId: String, attachments: List<AttachmentMeta>) {
        pendingAttachments[windowId] = attachments
    }

    fun onResponseChanged(
        windowId: String,
        provider: ProviderSpec,
    ) {
        val target =
            windows.firstOrNull {
                it.id == windowId
            } ?: return
        if (target.providerId != provider.id) return
        if (generationJobs[windowId]?.isActive != true) return

        responseSignal(windowId).trySend(Unit)
    }

    fun send(runtime: AiChatRuntime) {
        val target = activeWindow
        val provider = ProviderCatalog.byId(target.providerId)
        val prompt = activeDraft.trim()
        val attachments = pendingAttachments[target.id].orEmpty()

        if ((prompt.isBlank() && attachments.isEmpty()) || target.generating) return

        if (
            hasProjectBinding(target) &&
            target.boundUrl.isNullOrBlank()
        ) {
            setStatus(
                target.id,
                "当前项目没有绑定网页，请先绑定网页后再发送。",
            )
            return
        }

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
            messages.toMutableList().apply {
                add(optimisticUser)
            }

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
                conversationMutex(
                    conversationSession(target).storageKey
                ).withLock {
                    conversationStore.save(
                        conversationSession(target),
                        targetMessages,
                    )
                }

                activeStreamObserved.remove(
                    target.id
                )
                activeStreamCompleted.remove(
                    target.id
                )

                val responseSignal =
                    responseSignal(target.id)
                while (
                    responseSignal.tryReceive()
                        .isSuccess
                ) {
                    // Drop stale page events from before this send.
                }

                val baseline = runCatching {
                    runtime.responseSnapshot(target.id, provider)
                }.getOrDefault(WebRuntime.ResponseSnapshot())

                val sendResult = runCatching {
                    runtime.send(
                        target.id,
                        provider,
                        prompt,
                    )
                }.onFailure {
                    DiagnosticLogger.e(
                        "WORKSPACE",
                        "send_exception provider=${provider.id} window=${target.id.take(12)}",
                        it
                    )
                }.getOrElse {
                    WebRuntime.SendResult(
                        state =
                            WebRuntime.SendState.FAILED,
                        reason = "runtime-exception",
                    )
                }

                if (
                    sendResult.state ==
                    WebRuntime.SendState.FAILED
                ) {
                    val reverted =
                        conversationStore
                            .load(conversationSession(target))
                            .filterNot {
                                it.id == optimisticUser.id
                            }
                    conversationStore.save(
                        conversationSession(target),
                        reverted,
                    )
                    if (target.id == activeWindowId) {
                        messages.clear()
                        messages.addAll(reverted)
                    }
                    setStatus(
                        target.id,
                        "官网没有观察到这次提交；消息未保留为已发送。",
                    )
                    return@launch
                }

                // CONFIRMED and AMBIGUOUS both cross the one-shot write
                // boundary. Never leave attachments queued for a replay.
                if (attachments.isNotEmpty()) {
                    runtime.markAttachmentsSubmitted(
                        target.id,
                        provider,
                    )
                    pendingAttachments[target.id] =
                        emptyList()
                }

                runtime.currentUrl(
                    target.id,
                    provider,
                )?.let { url ->
                    updateWindow(target.id) {
                        it.copy(url = url)
                    }
                }

                setStatus(
                    target.id,
                    if (
                        sendResult.state ==
                        WebRuntime.SendState.AMBIGUOUS
                    ) {
                        "消息可能已提交，正在等待官网历史确认；不会自动重发。"
                    } else {
                        "等待 ${provider.name} 回复…"
                    },
                )
                awaitResponse(
                    runtime,
                    target.id,
                    provider,
                    baseline,
                )
            } finally {
                setGenerating(target.id, false)
                generationJobs.remove(target.id)
            }
        }
    }

    fun stop(runtime: AiChatRuntime) {
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
        runtime: AiChatRuntime,
        windowId: String,
        provider: ProviderSpec,
        baseline: WebRuntime.ResponseSnapshot,
    ) {
        val signal = responseSignal(windowId)
        val deadline =
            SystemClock.elapsedRealtime() +
                RESPONSE_WAIT_TIMEOUT_MS
        var last =
            WebRuntime.ResponseSnapshot()
        var sawGenerating = false
        var checks = 0
        var eventChecks = 0
        var fallbackChecks = 0

        while (
            SystemClock.elapsedRealtime() <
                deadline
        ) {
            val remaining =
                deadline -
                    SystemClock.elapsedRealtime()
            if (remaining <= 0L) break

            val signaled =
                withTimeoutOrNull(
                    minOf(
                        RESPONSE_FALLBACK_CHECK_MS,
                        remaining,
                    )
                ) {
                    signal.receive()
                    true
                } ?: false

            if (signaled) {
                eventChecks++
                delay(
                    RESPONSE_EVENT_SETTLE_MS
                )
                while (
                    signal.tryReceive()
                        .isSuccess
                ) {
                    // Coalesce streaming mutation bursts.
                }
            } else {
                fallbackChecks++
            }

            if (
                provider.id == "chatgpt" &&
                windowId in
                    activeStreamObserved
            ) {
                sawGenerating = true
            }

            if (
                provider.id == "chatgpt" &&
                activeStreamCompleted
                    .remove(windowId)
            ) {
                val liveWindow =
                    windows.firstOrNull {
                        it.id == windowId
                    }
                if (liveWindow != null) {
                    setStatus(
                        windowId,
                        "正在确认官网最终回复…",
                    )
                    val canonical =
                        runCatching {
                            runtime
                                .canonicalConversationSnapshot(
                                    liveWindow,
                                    provider,
                                    includeAllPages = false,
                                )
                        }.onFailure {
                            DiagnosticLogger.w(
                                "WORKSPACE",
                                "active_stream_finality_read_failed provider=" +
                                    provider.id +
                                    " window=" +
                                    windowId.take(12),
                                it,
                            )
                        }.getOrNull()

                    if (
                        ResponseCompletionPolicy
                            .isCanonicalFresh(
                                baseline =
                                    baseline,
                                snapshot =
                                    canonical,
                                sawGenerating =
                                    true,
                            )
                    ) {
                        onConversationSnapshot(
                            windowId =
                                windowId,
                            provider = provider,
                            snapshot =
                                canonical!!,
                        )
                        activeStreamObserved
                            .remove(windowId)
                        setStatus(
                            windowId,
                            null,
                        )
                        DiagnosticLogger.i(
                            "WORKSPACE",
                            "response_completed_active_stream provider=" +
                                provider.id +
                                " window=" +
                                windowId.take(12) +
                                " events=" +
                                eventChecks,
                        )
                        return
                    }
                }
            }

            val snap =
                runCatching {
                    runtime.responseSnapshot(
                        windowId,
                        provider,
                    )
                }.getOrDefault(
                    WebRuntime.ResponseSnapshot()
                )
            checks++

            if (snap.isGenerating) {
                sawGenerating = true
            }

            if (snap.state == "error") {
                setStatus(
                    windowId,
                    "官网没有完成消息提交，可切到网页检查。",
                )
                return
            }

            val fresh =
                ResponseCompletionPolicy.isFresh(
                    baseline = baseline,
                    current = snap,
                    sawGenerating = sawGenerating,
                )

            if (
                fresh &&
                !snap.isGenerating
            ) {
                if (provider.id == "chatgpt") {
                    // CWA invariant: incremental DOM/SSE state is not
                    // canonical finality. Read back the product-owned
                    // conversation before committing the final assistant turn.
                    val liveWindow =
                        windows.firstOrNull {
                            it.id == windowId
                        }
                    if (liveWindow != null) {
                        setStatus(
                            windowId,
                            "正在确认官网最终回复…",
                        )
                        val canonical =
                            runCatching {
                                runtime
                                    .canonicalConversationSnapshot(
                                        liveWindow,
                                        provider,
                                    )
                            }.onFailure {
                                DiagnosticLogger.w(
                                    "WORKSPACE",
                                    "canonical_finality_read_failed provider=" +
                                        provider.id +
                                        " window=" +
                                        windowId.take(12),
                                    it,
                                )
                            }.getOrNull()

                        if (
                            ResponseCompletionPolicy
                                .isCanonicalFresh(
                                    baseline =
                                        baseline,
                                    snapshot =
                                        canonical,
                                    sawGenerating =
                                        sawGenerating,
                                )
                        ) {
                            onConversationSnapshot(
                                windowId =
                                    windowId,
                                provider =
                                    provider,
                                snapshot =
                                    canonical!!,
                            )

                            runtime.currentUrl(
                                windowId,
                                provider,
                            )?.let { url ->
                                updateWindow(
                                    windowId
                                ) {
                                    it.copy(
                                        url = url
                                    )
                                }
                            }

                            DiagnosticLogger.i(
                                "WORKSPACE",
                                "response_completed_canonical provider=" +
                                    provider.id +
                                    " window=" +
                                    windowId.take(12) +
                                    " checks=" +
                                    checks +
                                    " events=" +
                                    eventChecks +
                                    " fallbacks=" +
                                    fallbackChecks,
                            )
                            return
                        }
                    }

                    last = snap
                    continue
                }

                commitAssistant(
                    windowId,
                    snap.text,
                )

                runtime.currentUrl(
                    windowId,
                    provider,
                )?.let { url ->
                    updateWindow(windowId) {
                        it.copy(url = url)
                    }
                }

                setStatus(windowId, null)
                DiagnosticLogger.i(
                    "WORKSPACE",
                    "response_completed provider=" +
                        provider.id +
                        " window=" +
                        windowId.take(12) +
                        " checks=" + checks +
                        " events=" + eventChecks +
                        " fallbacks=" +
                        fallbackChecks,
                )
                return
            }

            if (fresh) {
                last = snap
            }

            if (!signaled) {
                DiagnosticLogger.d(
                    "WORKSPACE",
                    "response_fallback_check provider=" +
                        provider.id +
                        " window=" +
                        windowId.take(12) +
                        " state=" + snap.state +
                        " chars=" +
                        snap.text.length,
                )
            }
        }

        if (provider.id == "chatgpt") {
            val liveWindow =
                windows.firstOrNull {
                    it.id == windowId
                }
            val canonical =
                if (liveWindow != null) {
                    runCatching {
                        runtime
                            .canonicalConversationSnapshot(
                                liveWindow,
                                provider,
                            )
                    }.getOrNull()
                } else {
                    null
                }

            if (
                ResponseCompletionPolicy
                    .isCanonicalFresh(
                        baseline = baseline,
                        snapshot = canonical,
                        sawGenerating =
                            sawGenerating,
                    )
            ) {
                onConversationSnapshot(
                    windowId = windowId,
                    provider = provider,
                    snapshot = canonical!!,
                )
                return
            }

            setStatus(
                windowId,
                if (last.text.isNotBlank()) {
                    "回复已显示，但官网最终状态尚未确认，可切到网页检查。"
                } else {
                    "没有读取到官网确认的新回复，可切到网页视图检查。"
                },
            )
            return
        }

        if (last.text.isNotBlank()) {
            commitAssistant(
                windowId,
                last.text,
            )
            setStatus(windowId, null)
        } else {
            setStatus(
                windowId,
                "没有读取到新的回复，可切到网页视图检查。",
            )
        }
    }

    private suspend fun commitAssistant(
        windowId: String,
        text: String,
    ) {
        val window =
            windows.firstOrNull {
                it.id == windowId
            } ?: return
        if (text.isBlank()) return

        val list =
            conversationMutex(
                conversationSession(window).storageKey
            ).withLock {
                val stored =
                    conversationStore
                        .load(conversationSession(window))
                        .toMutableList()
                val duplicate = stored.any {
                    it.role == MessageRole.ASSISTANT &&
                        it.text.trim() == text.trim()
                }
                if (!duplicate) {
                    stored += ChatMessage(
                        id =
                            "local-assistant-" +
                                System.nanoTime(),
                        role = MessageRole.ASSISTANT,
                        text = text,
                    )
                    conversationStore.save(
                        conversationSession(window),
                        stored,
                    )
                }
                stored
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
        val target =
            windows.firstOrNull {
                it.id == activeWindowId
            } ?: windows.firstOrNull()
                ?: return
        val targetId = target.id
        val historySession =
            conversationSession(target)

        conversationLoadJob?.cancel()
        emptyHistoryHydrationWindowId = null
        messages.clear()

        pendingAttachments[targetId] =
            runCatching {
                pendingAttachmentStore.load(
                    session(target)
                )
            }.onFailure {
                DiagnosticLogger.e(
                    "WORKSPACE",
                    "pending_attachment_restore_failed window=" +
                        targetId.take(12),
                    it,
                )
            }.getOrDefault(emptyList())

        conversationLoadJob =
            viewModelScope.launch {
                val stored =
                    conversationMutex(
                        historySession.storageKey
                    ).withLock {
                        conversationStore.load(
                            historySession
                        )
                    }

                if (activeWindowId != targetId) {
                    return@launch
                }

                messages.clear()
                messages.addAll(stored)
                updateWindow(targetId) {
                    it.copy(unread = false)
                }
                DiagnosticLogger.i(
                    "WORKSPACE",
                    "local_history_loaded window=" +
                        targetId.take(12) +
                        " messages=" + stored.size +
                        " session=" +
                        conversationSession(target)
                            .storageKey
                            .take(40),
                )

                DiagnosticLogger.i(
                    "WORKSPACE",
                    "conversation_restored window=" +
                        targetId.take(12) +
                        " messages=" +
                        stored.size +
                        " session=" +
                        historySession.storageKey.take(36),
                )

                val page =
                    target.boundUrl ?: target.url
                if (
                    stored.isEmpty() &&
                    target.providerId == "chatgpt" &&
                    isCanonicalChatGptConversationPage(page) &&
                    emptyHistoryHydrationAttempted.add(
                        targetId
                    )
                ) {
                    emptyHistoryHydrationWindowId =
                        targetId
                    DiagnosticLogger.i(
                        "WORKSPACE",
                        "empty_history_hydration_requested window=" +
                            targetId.take(12) +
                            " page=" +
                            page.orEmpty().take(160),
                    )
                }
            }
    }

    fun consumeEmptyHistoryHydration(
        windowId: String,
    ): Boolean {
        if (
            emptyHistoryHydrationWindowId !=
            windowId
        ) {
            return false
        }
        emptyHistoryHydrationWindowId = null
        return true
    }

    private fun responseSignal(
        windowId: String,
    ): Channel<Unit> =
        responseSignals.getOrPut(windowId) {
            Channel(Channel.CONFLATED)
        }

    private fun conversationMutex(
        windowId: String,
    ): Mutex =
        conversationMutexes.getOrPut(windowId) {
            Mutex()
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

    private fun persist(
        immediate: Boolean = false,
    ) {
        persistJob?.cancel()

        if (immediate) {
            persistNow()
            return
        }

        persistJob =
            viewModelScope.launch {
                delay(PERSIST_DEBOUNCE_MS)
                persistNow()
                persistJob = null
            }
    }

    private fun persistNow() {
        val snapshot =
            windows.map {
                it.copy(
                    generating = false,
                    unread = false,
                )
            }
        val activeId = activeWindowId

        runCatching {
            windowStore.save(snapshot)
            if (activeId.isNotBlank()) {
                windowStore.saveActiveId(activeId)
            }
        }.onFailure {
            DiagnosticLogger.e(
                "WORKSPACE",
                "workspace_persist_failed",
                it,
            )
        }
    }

    override fun onCleared() {
        persistJob?.cancel()
        persistJob = null
        responseSignals.values
            .forEach { it.close() }
        responseSignals.clear()
        canonicalReconcileJobs.values.forEach { it.cancel() }
        canonicalReconcileJobs.clear()
        activeStreamObserved.clear()
        activeStreamCompleted.clear()
        persistNow()
        super.onCleared()
    }

    private fun createWindowModel(providerId: String): ChatWindow =
        ChatWindow(providerId = providerId, title = "新对话")

    private fun session(window: ChatWindow): WindowSessionKey =
        WindowSessionKey(
            providerId = window.providerId,
            windowId = window.id
        )

    private fun conversationSession(
        window: ChatWindow,
    ): WindowSessionKey {
        val page =
            window.boundUrl
                ?.takeIf { it.isNotBlank() }
                ?: window.url
                    ?.takeIf { it.isNotBlank() }
                ?: return session(window)

        val identity =
            if (window.providerId == "chatgpt") {
                window.boundConversationId
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank() &&
                            !it.startsWith(
                                "WEB:",
                                ignoreCase = true,
                            )
                    }
                    ?.let { "chatgpt:$it" }
                    ?: chatGptConversationId(page)
                        ?.takeIf {
                            !it.startsWith(
                                "WEB:",
                                ignoreCase = true,
                            )
                        }
                        ?.let { "chatgpt:$it" }
                    ?: pageIdentity(page)
                        ?.let { "page:$it" }
            } else {
                pageIdentity(page)
                    ?.let {
                        window.providerId +
                            ":page:" +
                            it
                    }
            } ?: return session(window)

        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(
                    identity.toByteArray(
                        Charsets.UTF_8
                    )
                )
                .take(16)
                .joinToString("") {
                    "%02x".format(it)
                }

        return WindowSessionKey(
            providerId = "page",
            windowId = "page-$digest",
        )
    }

    private fun legacyProjectConversationSession(
        window: ChatWindow,
    ): WindowSessionKey {
        val identity =
            when {
                !window.boundRepo.isNullOrBlank() ->
                    "repo:" +
                        normalizedProject(
                            window.boundRepo
                        )
                !window.boundProject.isNullOrBlank() ->
                    "project:" +
                        normalizedProject(
                            window.boundProject
                        )
                else -> return session(window)
            }

        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(
                    identity.toByteArray(
                        Charsets.UTF_8
                    )
                )
                .take(16)
                .joinToString("") {
                    "%02x".format(it)
                }

        return WindowSessionKey(
            providerId = "project",
            windowId = "project-$digest",
        )
    }

    private companion object {
        const val HISTORY_PAGE_SCOPE_MIGRATION_PREFIX =
            "page-history-v2:"
        const val PERSIST_DEBOUNCE_MS = 400L
        const val RESPONSE_EVENT_SETTLE_MS = 450L
        const val RESPONSE_FALLBACK_CHECK_MS = 10_000L
        const val RESPONSE_WAIT_TIMEOUT_MS = 120_000L
        const val CHATGPT_CANONICAL_RECONCILE_DELAY_MS = 1_200L
        const val CHATGPT_ACTIVE_STREAM_FINAL_RECONCILE_DELAY_MS = 250L
        const val CHATGPT_CANONICAL_RECONCILE_RETRY_MS = 1_500L
        const val CHATGPT_CANONICAL_RECONCILE_ATTEMPTS = 3
    }

    class Factory(
        private val application: Application
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return WorkspaceViewModel(application) as T
        }
    }
}
