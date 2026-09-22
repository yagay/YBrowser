package com.yagay.ybrowser.ai

import android.content.Context
import android.net.Uri
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class AiWorkspaceWindow(
    val id: String,
    val providerId: String,
    val title: String,
    val entryUrl: String,
    val currentUrl: String,
    val project: String? = null,
    val repoKey: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long = System.currentTimeMillis(),
)

class AiWorkspaceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE,
    )

    fun loadWindows(): List<AiWorkspaceWindow> {
        val array = runCatching {
            JSONArray(prefs.getString(KEY_WINDOWS, "[]"))
        }.getOrElse { JSONArray() }

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val entryUrl = normalizeWebUrl(item.optString("entryUrl"))
                    ?: normalizeWebUrl(item.optString("url"))
                    ?: continue
                val currentUrl = normalizeWebUrl(item.optString("currentUrl"))
                    ?: entryUrl
                val providerId = item.optString("providerId")
                    .ifBlank {
                        AiProviderCatalog.fromUrl(currentUrl)?.id
                            ?: AiProviderCatalog.fromUrl(entryUrl)?.id
                            ?: "web"
                    }
                add(
                    AiWorkspaceWindow(
                        id = item.optString("id")
                            .ifBlank { stableIdForUrl(entryUrl) },
                        providerId = providerId,
                        title = item.optString("title")
                            .ifBlank {
                                AiProviderCatalog.byId(providerId)?.name
                                    ?: Uri.parse(entryUrl).host.orEmpty().ifBlank { "AI" }
                            },
                        entryUrl = entryUrl,
                        currentUrl = currentUrl,
                        project = item.optString("project")
                            .takeIf { it.isNotBlank() },
                        repoKey = item.optString("repoKey")
                            .takeIf { it.isNotBlank() },
                        createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                        lastActiveAt = item.optLong("lastActiveAt", 0L),
                    ),
                )
            }
        }.distinctBy { it.id }
    }

    fun loadActiveId(): String? =
        prefs.getString(KEY_ACTIVE_ID, null)
            ?.takeIf { it.isNotBlank() }

    fun save(
        windows: List<AiWorkspaceWindow>,
        activeId: String?,
    ) {
        val array = JSONArray()
        windows.forEach { window ->
            array.put(
                JSONObject()
                    .put("id", window.id)
                    .put("providerId", window.providerId)
                    .put("title", window.title)
                    .put("entryUrl", window.entryUrl)
                    .put("currentUrl", window.currentUrl)
                    .put("project", window.project)
                    .put("repoKey", window.repoKey)
                    .put("createdAt", window.createdAt)
                    .put("lastActiveAt", window.lastActiveAt),
            )
        }
        prefs.edit()
            .putString(KEY_WINDOWS, array.toString())
            .putString(KEY_ACTIVE_ID, activeId)
            .apply()
    }

    fun mergeTargets(
        existing: List<AiWorkspaceWindow>,
        rawJson: String?,
    ): List<AiWorkspaceWindow> {
        if (rawJson.isNullOrBlank()) return existing

        val merged = existing.toMutableList()
        val array = runCatching { JSONArray(rawJson) }
            .getOrElse { return existing }

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = normalizeWebUrl(item.optString("url")) ?: continue
            val repoKey = item.optString("repoKey").takeIf { it.isNotBlank() }
            val project = item.optString("project").takeIf { it.isNotBlank() }
            val title = item.optString("title")
                .ifBlank { project.orEmpty() }
                .ifBlank {
                    AiProviderCatalog.fromUrl(url)?.name ?: "AI"
                }
            val providerId = AiProviderCatalog.fromUrl(url)?.id ?: "web"
            val stableId = stableIdForUrl(url)
            val existingIndex = merged.indexOfFirst {
                it.id == stableId
            }
            if (existingIndex >= 0) {
                val previous = merged[existingIndex]
                merged[existingIndex] = previous.copy(
                    providerId = providerId,
                    title = title,
                    entryUrl = url,
                    project = project ?: previous.project,
                    repoKey = repoKey ?: previous.repoKey,
                )
            } else {
                merged += AiWorkspaceWindow(
                    id = stableId,
                    providerId = providerId,
                    title = title,
                    entryUrl = url,
                    currentUrl = url,
                    project = project,
                    repoKey = repoKey,
                    createdAt = item.optLong("addedAt", 0L)
                        .takeIf { it > 0L }
                        ?: System.currentTimeMillis(),
                )
            }
        }
        return merged.distinctBy { it.id }
    }

    companion object {
        private const val PREFS = "ybrowser_ai_workspace"
        private const val KEY_WINDOWS = "windows_v1"
        private const val KEY_ACTIVE_ID = "active_window_id"

        fun newProviderWindow(provider: AiProvider): AiWorkspaceWindow {
            val now = System.currentTimeMillis()
            return AiWorkspaceWindow(
                id = "provider-" + provider.id + "-" + now.toString(36),
                providerId = provider.id,
                title = provider.name,
                entryUrl = provider.homeUrl,
                currentUrl = provider.homeUrl,
                createdAt = now,
                lastActiveAt = now,
            )
        }

        fun newWebWindow(
            url: String,
            title: String? = null,
            project: String? = null,
            repoKey: String? = null,
            stable: Boolean = false,
        ): AiWorkspaceWindow? {
            val normalized = normalizeWebUrl(url) ?: return null
            val provider = AiProviderCatalog.fromUrl(normalized)
            val now = System.currentTimeMillis()
            return AiWorkspaceWindow(
                id = if (stable) {
                    stableIdForUrl(normalized)
                } else {
                    "web-" + now.toString(36) + "-" +
                        stableIdForUrl(normalized).takeLast(8)
                },
                providerId = provider?.id ?: "web",
                title = title.orEmpty()
                    .ifBlank { project.orEmpty() }
                    .ifBlank {
                        provider?.name
                            ?: Uri.parse(normalized).host.orEmpty().ifBlank { "AI" }
                    },
                entryUrl = normalized,
                currentUrl = normalized,
                project = project,
                repoKey = repoKey,
                createdAt = now,
                lastActiveAt = now,
            )
        }

        fun normalizeWebUrl(value: String?): String? {
            val raw = value.orEmpty().trim()
            if (raw.isBlank()) return null
            val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrBlank()) return null
            return raw
        }

        fun sameUrl(left: String?, right: String?): Boolean {
            val a = normalizeWebUrl(left)?.trimEnd('/') ?: return false
            val b = normalizeWebUrl(right)?.trimEnd('/') ?: return false
            return a == b
        }

        fun stableIdForUrl(url: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(url.trim().trimEnd('/').toByteArray(Charsets.UTF_8))
            return "bound-" + digest
                .take(10)
                .joinToString("") { "%02x".format(it) }
        }
    }
}
