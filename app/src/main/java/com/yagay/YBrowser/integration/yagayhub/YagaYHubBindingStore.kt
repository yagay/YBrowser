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

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val repoKey = item.optString("repoKey")
                val url = item.optString("url")
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

        val existing = load().firstOrNull {
            it.repoKey.equals(repo, ignoreCase = true) &&
                normalize(it.url) == url
        }
        val normalized = record.copy(
            repoKey = repo,
            url = url,
            project = record.project.ifBlank {
                repo.substringAfterLast('/')
            },
            title = record.title.ifBlank { "AI" },
            addedAt = existing?.addedAt?.takeIf { it > 0L }
                ?: record.addedAt.takeIf { it > 0L }
                ?: System.currentTimeMillis(),
        )
        val merged = buildList {
            add(normalized)
            load()
                .filterNot { normalize(it.url) == url }
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

    fun removeProject(
        repoKey: String,
    ): List<YagaYHubBindingRecord> {
        val normalizedRepo =
            repoKey.trim().lowercase()
        if (normalizedRepo.isBlank()) {
            return emptyList()
        }

        val all = load()
        val removed =
            all.filter {
                it.repoKey.equals(
                    normalizedRepo,
                    ignoreCase = true,
                )
            }
        if (removed.isEmpty()) {
            return emptyList()
        }

        persist(
            all.filterNot {
                it.repoKey.equals(
                    normalizedRepo,
                    ignoreCase = true,
                )
            },
        )

        val compact =
            normalize(
                lastCompactUrl().orEmpty()
            )
        if (
            compact.isNotBlank() &&
            removed.any {
                normalize(it.url) == compact
            }
        ) {
            prefs.edit()
                .remove(KEY_LAST_COMPACT_URL)
                .apply()
        }

        return removed
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
