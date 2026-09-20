package com.yagay.YBrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class SearchEngine(val label: String, val template: String) {
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s"),
}

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

enum class ToolbarPosition(val label: String) {
    TOP("顶部"),
    BOTTOM("底部"),
}

data class BrowserSettings(
    val defaultEngine: BrowserEngineKind = BrowserEngineKind.GECKO,
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    val homepage: String = "https://www.google.com/",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val toolbarPosition: ToolbarPosition = ToolbarPosition.BOTTOM,
    val restoreTabs: Boolean = true,
    val javaScriptEnabled: Boolean = true,
    val cookiesEnabled: Boolean = true,
    val desktopModeByDefault: Boolean = false,
    val textScale: Int = 100,
)

data class BrowserTab(
    val id: Long,
    val url: String,
    val title: String,
    val privateMode: Boolean = false,
    val desktopMode: Boolean = false,
)

data class BookmarkEntry(
    val url: String,
    val title: String,
    val createdAt: Long,
)

data class HistoryEntry(
    val url: String,
    val title: String,
    val visitedAt: Long,
)

class BrowserStore(context: Context) {
    private val prefs = context.getSharedPreferences("ybrowser_store", Context.MODE_PRIVATE)

    fun loadSettings(): BrowserSettings = BrowserSettings(
        defaultEngine = enumValueOrDefault(
            prefs.getString(KEY_ENGINE, null),
            BrowserEngineKind.GECKO,
        ),
        searchEngine = enumValueOrDefault(
            prefs.getString(KEY_SEARCH, null),
            SearchEngine.GOOGLE,
        ),
        homepage = prefs.getString(KEY_HOME, null)
            ?.takeIf { it.isNotBlank() }
            ?: "https://www.google.com/",
        themeMode = enumValueOrDefault(
            prefs.getString(KEY_THEME, null),
            ThemeMode.SYSTEM,
        ),
        toolbarPosition = enumValueOrDefault(
            prefs.getString(KEY_TOOLBAR, null),
            ToolbarPosition.BOTTOM,
        ),
        restoreTabs = prefs.getBoolean(KEY_RESTORE, true),
        javaScriptEnabled = prefs.getBoolean(KEY_JS, true),
        cookiesEnabled = prefs.getBoolean(KEY_COOKIES, true),
        desktopModeByDefault = prefs.getBoolean(KEY_DESKTOP, false),
        textScale = prefs.getInt(KEY_TEXT_SCALE, 100).coerceIn(50, 200),
    )

    fun saveSettings(settings: BrowserSettings) {
        prefs.edit()
            .putString(KEY_ENGINE, settings.defaultEngine.name)
            .putString(KEY_SEARCH, settings.searchEngine.name)
            .putString(KEY_HOME, settings.homepage)
            .putString(KEY_THEME, settings.themeMode.name)
            .putString(KEY_TOOLBAR, settings.toolbarPosition.name)
            .putBoolean(KEY_RESTORE, settings.restoreTabs)
            .putBoolean(KEY_JS, settings.javaScriptEnabled)
            .putBoolean(KEY_COOKIES, settings.cookiesEnabled)
            .putBoolean(KEY_DESKTOP, settings.desktopModeByDefault)
            .putInt(KEY_TEXT_SCALE, settings.textScale.coerceIn(50, 200))
            .apply()
    }

    fun loadTabs(homepage: String): Pair<List<BrowserTab>, Long> {
        val array = parseArray(prefs.getString(KEY_TABS, null))
        val tabs = buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optLong("id", 0L)
                val url = obj.optString("url")
                if (id <= 0L || url.isBlank()) continue
                add(
                    BrowserTab(
                        id = id,
                        url = url,
                        title = obj.optString("title").ifBlank { url },
                        privateMode = false,
                        desktopMode = obj.optBoolean("desktopMode", false),
                    ),
                )
            }
        }
        val valid = tabs.ifEmpty {
            listOf(
                BrowserTab(
                    id = 1L,
                    url = homepage,
                    title = "YBrowser",
                ),
            )
        }
        val storedSelected = prefs.getLong(KEY_SELECTED_TAB, valid.first().id)
        val selected = valid.firstOrNull { it.id == storedSelected }?.id ?: valid.first().id
        return valid to selected
    }

    fun saveTabs(tabs: List<BrowserTab>, selectedTabId: Long) {
        val array = JSONArray()
        tabs.filterNot { it.privateMode }.forEach { tab ->
            array.put(
                JSONObject()
                    .put("id", tab.id)
                    .put("url", tab.url)
                    .put("title", tab.title)
                    .put("desktopMode", tab.desktopMode),
            )
        }
        prefs.edit()
            .putString(KEY_TABS, array.toString())
            .putLong(KEY_SELECTED_TAB, selectedTabId)
            .apply()
    }

    fun loadBookmarks(): List<BookmarkEntry> {
        val array = parseArray(prefs.getString(KEY_BOOKMARKS, null))
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val url = obj.optString("url")
                if (url.isBlank()) continue
                add(
                    BookmarkEntry(
                        url = url,
                        title = obj.optString("title").ifBlank { url },
                        createdAt = obj.optLong("createdAt", 0L),
                    ),
                )
            }
        }.sortedByDescending { it.createdAt }
    }

    fun saveBookmarks(entries: List<BookmarkEntry>) {
        val array = JSONArray()
        entries.distinctBy { it.url }.forEach { entry ->
            array.put(
                JSONObject()
                    .put("url", entry.url)
                    .put("title", entry.title)
                    .put("createdAt", entry.createdAt),
            )
        }
        prefs.edit().putString(KEY_BOOKMARKS, array.toString()).apply()
    }

    fun loadHistory(): List<HistoryEntry> {
        val array = parseArray(prefs.getString(KEY_HISTORY, null))
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val url = obj.optString("url")
                if (url.isBlank()) continue
                add(
                    HistoryEntry(
                        url = url,
                        title = obj.optString("title").ifBlank { url },
                        visitedAt = obj.optLong("visitedAt", 0L),
                    ),
                )
            }
        }.sortedByDescending { it.visitedAt }
    }

    fun addHistory(url: String, title: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val now = System.currentTimeMillis()
        val merged = buildList {
            add(HistoryEntry(url, title.ifBlank { url }, now))
            loadHistory()
                .asSequence()
                .filterNot { it.url == url }
                .take(MAX_HISTORY - 1)
                .forEach(::add)
        }
        saveHistory(merged)
    }

    fun saveHistory(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.take(MAX_HISTORY).forEach { entry ->
            array.put(
                JSONObject()
                    .put("url", entry.url)
                    .put("title", entry.title)
                    .put("visitedAt", entry.visitedAt),
            )
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_TABS)
            .remove(KEY_SELECTED_TAB)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        raw: String?,
        fallback: T,
    ): T = runCatching { enumValueOf<T>(raw.orEmpty()) }.getOrDefault(fallback)

    private fun parseArray(raw: String?): JSONArray = runCatching {
        JSONArray(raw ?: "[]")
    }.getOrElse { JSONArray() }

    companion object {
        private const val KEY_ENGINE = "engine"
        private const val KEY_SEARCH = "search"
        private const val KEY_HOME = "home"
        private const val KEY_THEME = "theme"
        private const val KEY_TOOLBAR = "toolbar"
        private const val KEY_RESTORE = "restore"
        private const val KEY_JS = "js"
        private const val KEY_COOKIES = "cookies"
        private const val KEY_DESKTOP = "desktop"
        private const val KEY_TEXT_SCALE = "text_scale"
        private const val KEY_TABS = "tabs"
        private const val KEY_SELECTED_TAB = "selected_tab"
        private const val KEY_BOOKMARKS = "bookmarks"
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY = 500
    }
}
