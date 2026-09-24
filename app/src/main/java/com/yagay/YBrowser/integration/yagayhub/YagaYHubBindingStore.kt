package com.yagay.YBrowser.integration.yagayhub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class YagaYHubBindingRecord(
    val repoKey: String,
    val project: String,
    val url: String,
    val title: String,
    val addedAt: Long = System.currentTimeMillis(),
)

/**
 * YagaYHub-owned persistence. It deliberately keeps the historical
 * ybrowser_store/chat_bindings location so existing users keep all bindings.
 *
 * Binding identity is project-first:
 * - one repository/project owns one AI tag;
 * - that tag has one current web page;
 * - rebinding the same project to another page replaces only the current page,
 *   not the project tag or its native conversation history.
 */
class YagaYHubBindingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE,
    )

    fun load(): List<YagaYHubBindingRecord> {
        val array = runCatching {
            JSONArray(prefs.getString(KEY_BINDINGS, null) ?: "[]")
        }.getOrElse { JSONArray() }

        val decoded = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val repoKey = item.optString("repoKey").trim().lowercase()
                val url = normalize(item.optString("url"))
                if (repoKey.isBlank() || url.isBlank()) continue
                add(
                    YagaYHubBindingRecord(
                        repoKey = repoKey,
                        project = item.optString("project").ifBlank {
                            repoKey.substringAfterLast('/')
                        },
                        url = url,
                        title = item.optString("title").ifBlank { "AI" },
                        addedAt = item.optLong("addedAt", 0L),
                    ),
                )
            }
        }.sortedByDescending { it.addedAt }

        // Migrate historical many-URLs-per-project data on read. Newest wins.
        // Also keep one owner per URL so page identity can never merge two
        // unrelated project tags.
        val seenRepos = mutableSetOf<String>()
        val seenUrls = mutableSetOf<String>()
        val compacted = decoded.filter { record ->
            val repo = record.repoKey.lowercase()
            val url = normalize(record.url)
            val keep = repo !in seenRepos && url !in seenUrls
            if (keep) {
                seenRepos += repo
                seenUrls += url
            }
            keep
        }

        if (compacted != decoded) {
            persist(compacted)
        }
        return compacted
    }

    fun find(url: String): YagaYHubBindingRecord? {
        val normalized = normalize(url)
        if (normalized.isBlank()) return null
        return load().firstOrNull {
            normalize(it.url) == normalized
        }
    }

    fun save(record: YagaYHubBindingRecord) {
        val repo = record.repoKey.trim().lowercase()
        val url = normalize(record.url)
        if (repo.isBlank() || url.isBlank()) return

        val current = load()
        val normalized = record.copy(
            repoKey = repo,
            url = url,
            project = record.project.ifBlank {
                repo.substringAfterLast('/')
            },
            title = record.title.ifBlank { "AI" },
            // This field is retained for schema compatibility but now also
            // acts as the binding's last-update timestamp.
            addedAt = System.currentTimeMillis(),
        )

        val merged = buildList {
            add(normalized)
            current
                .filterNot {
                    it.repoKey.equals(repo, ignoreCase = true) ||
                        normalize(it.url) == url
                }
                .forEach(::add)
        }.sortedByDescending { it.addedAt }

        persist(merged)
    }

    fun remove(url: String) {
        val normalized = normalize(url)
        if (normalized.isBlank()) return
        persist(
            load().filterNot {
                normalize(it.url) == normalized
            },
        )
        if (normalize(lastCompactUrl().orEmpty()) == normalized) {
            prefs.edit().remove(KEY_LAST_COMPACT_URL).apply()
        }
    }

    fun lastCompactUrl(): String? =
        prefs.getString(KEY_LAST_COMPACT_URL, null)
            ?.takeIf { it.isNotBlank() }

    fun saveLastCompactUrl(url: String) {
        val normalized = normalize(url)
        if (normalized.isBlank()) return
        prefs.edit()
            .putString(KEY_LAST_COMPACT_URL, normalized)
            .apply()
    }

    private fun persist(bindings: List<YagaYHubBindingRecord>) {
        val array = JSONArray()
        bindings.forEach { item ->
            array.put(
                JSONObject()
                    .put("repoKey", item.repoKey)
                    .put("project", item.project)
                    .put("url", item.url)
                    .put("title", item.title)
                    .put("addedAt", item.addedAt),
            )
        }
        prefs.edit().putString(KEY_BINDINGS, array.toString()).apply()
    }

    private fun normalize(url: String): String =
        url.trim().trimEnd('/')

    private companion object {
        const val PREFS = "ybrowser_store"
        const val KEY_BINDINGS = "chat_bindings"
        const val KEY_LAST_COMPACT_URL = "yagayhub_last_compact_url"
    }
}
