package com.yagay.YBrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class BrowserNavigationTrailEntry(
    val id: Long,
    val tabId: Long,
    val fromUrl: String,
    val toUrl: String,
    val title: String,
    val visitedAt: Long,
)

object BrowserNavigationTrails {
    private const val PREFS = "ybrowser_navigation_trails"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 1200
    private const val MAX_PER_TAB = 160

    fun record(
        context: Context,
        profileId: String,
        tabId: Long,
        fromUrl: String,
        toUrl: String,
        title: String,
    ) {
        val from = fromUrl.trim()
        val to = toUrl.trim()
        if (from.isBlank() || to.isBlank() || from == to) return
        if (!to.startsWith("http://") && !to.startsWith("https://")) return

        val current = loadAll(context, profileId).toMutableList()
        val now = System.currentTimeMillis()
        val duplicate = current.lastOrNull()?.let {
            it.tabId == tabId && it.fromUrl == from && it.toUrl == to
        } == true
        if (!duplicate) {
            current += BrowserNavigationTrailEntry(
                id = now,
                tabId = tabId,
                fromUrl = from,
                toUrl = to,
                title = title.ifBlank { to },
                visitedAt = now,
            )
        }

        val perTabCounts = mutableMapOf<Long, Int>()
        val pruned = current.asReversed().filter { entry ->
            val count = perTabCounts.getOrDefault(entry.tabId, 0)
            if (count >= MAX_PER_TAB) {
                false
            } else {
                perTabCounts[entry.tabId] = count + 1
                true
            }
        }.asReversed().takeLast(MAX_ENTRIES)

        persist(context, profileId, pruned)
    }

    fun forTab(
        context: Context,
        profileId: String,
        tabId: Long,
    ): List<BrowserNavigationTrailEntry> =
        loadAll(context, profileId)
            .filter { it.tabId == tabId }
            .sortedByDescending { it.visitedAt }

    fun clearTab(
        context: Context,
        profileId: String,
        tabId: Long,
    ) {
        persist(
            context,
            profileId,
            loadAll(context, profileId).filterNot { it.tabId == tabId },
        )
    }

    fun clearProfile(
        context: Context,
        profileId: String,
    ) {
        prefs(context).edit().remove(key(profileId)).apply()
    }

    private fun loadAll(
        context: Context,
        profileId: String,
    ): List<BrowserNavigationTrailEntry> {
        val raw = prefs(context).getString(key(profileId), null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val tabId = item.optLong("tabId", Long.MIN_VALUE)
                val from = item.optString("fromUrl")
                val to = item.optString("toUrl")
                if (tabId == Long.MIN_VALUE || from.isBlank() || to.isBlank()) continue
                add(
                    BrowserNavigationTrailEntry(
                        id = item.optLong("id", index.toLong()),
                        tabId = tabId,
                        fromUrl = from,
                        toUrl = to,
                        title = item.optString("title").ifBlank { to },
                        visitedAt = item.optLong("visitedAt", 0L),
                    )
                )
            }
        }
    }

    private fun persist(
        context: Context,
        profileId: String,
        entries: List<BrowserNavigationTrailEntry>,
    ) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("tabId", entry.tabId)
                    .put("fromUrl", entry.fromUrl)
                    .put("toUrl", entry.toUrl)
                    .put("title", entry.title)
                    .put("visitedAt", entry.visitedAt)
            )
        }
        prefs(context).edit().putString(key(profileId), array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(profileId: String) = KEY_ENTRIES + "_" + profileId
}
