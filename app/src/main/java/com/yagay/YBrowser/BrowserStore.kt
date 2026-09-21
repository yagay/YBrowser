package com.yagay.YBrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class SearchEngine(val label: String, val template: String) {
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s"),
    BRAVE("Brave", "https://search.brave.com/search?q=%s"),
    ECOSIA("Ecosia", "https://www.ecosia.org/search?q=%s"),
    STARTPAGE("Startpage", "https://www.startpage.com/sp/search?query=%s"),
    QWANT("Qwant", "https://www.qwant.com/?q=%s"),
    KAGI("Kagi", "https://kagi.com/search?q=%s"),
    PERPLEXITY("Perplexity", "https://www.perplexity.ai/search?q=%s"),
}

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
    AMOLED("AMOLED"),
}

enum class TrackingProtection(val label: String) {
    OFF("关闭"),
    STANDARD("标准"),
    STRICT("严格"),
}

enum class ToolbarPosition(val label: String) {
    TOP("顶部"),
    BOTTOM("底部"),
}

enum class BrowserMenuShortcut(val label: String) {
    NEW_TAB("新标签"),
    PRIVATE_TAB("隐私标签"),
    SHARE("分享"),
    COPY_LINK("复制链接"),
    BOOKMARKS("收藏夹"),
    HISTORY("历史"),
    DOWNLOADS("下载"),
    FIND_IN_PAGE("页内查找"),
    HOME("主页"),
    BOOKMARK("收藏"),
    DESKTOP_MODE("桌面版"),
    READER("阅读模式"),
    TRANSLATE("翻译"),
    VIEW_SOURCE("源代码"),
    PRINT("打印 / PDF"),
    OPEN_EXTERNAL("外部打开"),
    SITE_SETTINGS("网站设置"),
    SETTINGS("设置");

    companion object {
        val DEFAULT = listOf(
            NEW_TAB,
            PRIVATE_TAB,
            SHARE,
            COPY_LINK,
            BOOKMARKS,
            HISTORY,
            DOWNLOADS,
            FIND_IN_PAGE,
        )
        const val MAX_COUNT = 12
    }
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
    val trackingProtection: TrackingProtection = TrackingProtection.STANDARD,
    val menuShortcuts: List<BrowserMenuShortcut> = BrowserMenuShortcut.DEFAULT,
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

data class ChatBindingRecord(
    val repoKey: String,
    val project: String,
    val url: String,
    val title: String,
    val addedAt: Long = System.currentTimeMillis(),
)


enum class SitePermissionDecision {
    ASK,
    ALLOW,
    BLOCK,
}

data class SiteSettings(
    val host: String,
    val javaScriptEnabled: Boolean? = null,
    val cookiesEnabled: Boolean? = null,
    val trackingProtection: TrackingProtection? = null,
    val textScale: Int? = null,
) {
    val isDefault: Boolean
        get() = javaScriptEnabled == null &&
            cookiesEnabled == null &&
            trackingProtection == null &&
            textScale == null
}

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
        trackingProtection = enumValueOrDefault(
            prefs.getString(KEY_TRACKING, null),
            TrackingProtection.STANDARD,
        ),
        menuShortcuts = loadMenuShortcuts(),
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
            .putString(KEY_TRACKING, settings.trackingProtection.name)
            .putString(
                KEY_MENU_SHORTCUTS,
                settings.menuShortcuts
                    .distinct()
                    .take(BrowserMenuShortcut.MAX_COUNT)
                    .joinToString(",") { it.name },
            )
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

    fun loadChatBindings(): List<ChatBindingRecord> {
        val array = parseArray(prefs.getString(KEY_CHAT_BINDINGS, null))
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val repoKey = obj.optString("repoKey")
                val url = obj.optString("url")
                if (repoKey.isBlank() || url.isBlank()) continue
                add(
                    ChatBindingRecord(
                        repoKey = repoKey,
                        project = obj.optString("project").ifBlank {
                            repoKey.substringAfterLast('/')
                        },
                        url = url,
                        title = obj.optString("title").ifBlank { "AI" },
                        addedAt = obj.optLong("addedAt", 0L),
                    ),
                )
            }
        }.sortedByDescending { it.addedAt }
    }

    fun findChatBinding(url: String): ChatBindingRecord? {
        val normalized = normalizeBindingUrl(url)
        if (normalized.isBlank()) return null
        return loadChatBindings().firstOrNull {
            normalizeBindingUrl(it.url) == normalized
        }
    }

    fun saveChatBinding(record: ChatBindingRecord) {
        val normalizedRepo = record.repoKey.trim().lowercase()
        val normalizedUrl = normalizeBindingUrl(record.url)
        if (normalizedRepo.isBlank() || normalizedUrl.isBlank()) return

        val existing = loadChatBindings().firstOrNull {
            it.repoKey.equals(normalizedRepo, ignoreCase = true) &&
                normalizeBindingUrl(it.url) == normalizedUrl
        }
        val normalizedRecord = record.copy(
            repoKey = normalizedRepo,
            url = normalizedUrl,
            project = record.project.ifBlank {
                normalizedRepo.substringAfterLast('/')
            },
            title = record.title.ifBlank { "AI" },
            addedAt = if (existing != null && existing.addedAt > 0L) {
                existing.addedAt
            } else {
                record.addedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            },
        )
        val merged = buildList {
            add(normalizedRecord)
            loadChatBindings()
                .filterNot { normalizeBindingUrl(it.url) == normalizedUrl }
                .forEach(::add)
        }.sortedByDescending { it.addedAt }

        saveChatBindings(merged)
    }

    fun removeChatBinding(url: String) {
        val normalizedUrl = normalizeBindingUrl(url)
        if (normalizedUrl.isBlank()) return
        saveChatBindings(
            loadChatBindings().filterNot {
                normalizeBindingUrl(it.url) == normalizedUrl
            },
        )
    }

    private fun saveChatBindings(bindings: List<ChatBindingRecord>) {
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
        prefs.edit().putString(KEY_CHAT_BINDINGS, array.toString()).apply()
    }

    private fun normalizeBindingUrl(url: String): String =
        url.trim().trimEnd('/')


    fun loadSiteSettings(host: String): SiteSettings? {
        val normalized = host.lowercase().trim().trimEnd('.')
        if (normalized.isBlank()) return null
        val root = parseObject(prefs.getString(KEY_SITE_SETTINGS, null))
        val obj = root.optJSONObject(normalized) ?: return null
        return SiteSettings(
            host = normalized,
            javaScriptEnabled = obj.optNullableBoolean("js"),
            cookiesEnabled = obj.optNullableBoolean("cookies"),
            trackingProtection = obj.optString("tracking")
                .takeIf { it.isNotBlank() }
                ?.let { raw ->
                    runCatching { TrackingProtection.valueOf(raw) }.getOrNull()
                },
            textScale = if (obj.has("textScale") && !obj.isNull("textScale")) {
                obj.optInt("textScale", 100).coerceIn(50, 200)
            } else {
                null
            },
        )
    }

    fun saveSiteSettings(settings: SiteSettings) {
        val normalized = settings.host.lowercase().trim().trimEnd('.')
        if (normalized.isBlank()) return
        val root = parseObject(prefs.getString(KEY_SITE_SETTINGS, null))
        if (settings.isDefault) {
            root.remove(normalized)
        } else {
            val obj = JSONObject()
            settings.javaScriptEnabled?.let { obj.put("js", it) }
            settings.cookiesEnabled?.let { obj.put("cookies", it) }
            settings.trackingProtection?.let { obj.put("tracking", it.name) }
            settings.textScale?.let { obj.put("textScale", it.coerceIn(50, 200)) }
            root.put(normalized, obj)
        }
        prefs.edit().putString(KEY_SITE_SETTINGS, root.toString()).apply()
    }

    fun clearSiteSettings(host: String) {
        val normalized = host.lowercase().trim().trimEnd('.')
        val root = parseObject(prefs.getString(KEY_SITE_SETTINGS, null))
        root.remove(normalized)
        prefs.edit().putString(KEY_SITE_SETTINGS, root.toString()).apply()
    }

    fun loadAllSiteSettings(): List<SiteSettings> {
        val root = parseObject(prefs.getString(KEY_SITE_SETTINGS, null))
        return buildList {
            val keys = root.keys()
            while (keys.hasNext()) {
                val host = keys.next()
                loadSiteSettings(host)?.let(::add)
            }
        }.sortedBy { it.host }
    }


    fun loadSitePermissionDecision(
        host: String,
        permission: BrowserSitePermission,
    ): SitePermissionDecision {
        val normalized = host.lowercase().trim().trimEnd('.')
        if (normalized.isBlank()) return SitePermissionDecision.ASK
        val root = parseObject(prefs.getString(KEY_SITE_PERMISSIONS, null))
        val site = root.optJSONObject(normalized) ?: return SitePermissionDecision.ASK
        val raw = site.optString(permission.name)
        return runCatching { SitePermissionDecision.valueOf(raw) }
            .getOrDefault(SitePermissionDecision.ASK)
    }

    fun saveSitePermissionDecision(
        host: String,
        permission: BrowserSitePermission,
        decision: SitePermissionDecision,
    ) {
        val normalized = host.lowercase().trim().trimEnd('.')
        if (normalized.isBlank()) return
        val root = parseObject(prefs.getString(KEY_SITE_PERMISSIONS, null))
        val site = root.optJSONObject(normalized) ?: JSONObject()
        if (decision == SitePermissionDecision.ASK) {
            site.remove(permission.name)
        } else {
            site.put(permission.name, decision.name)
        }
        if (site.length() == 0) {
            root.remove(normalized)
        } else {
            root.put(normalized, site)
        }
        prefs.edit().putString(KEY_SITE_PERMISSIONS, root.toString()).apply()
    }

    fun clearSitePermissionDecisions(host: String) {
        val normalized = host.lowercase().trim().trimEnd('.')
        val root = parseObject(prefs.getString(KEY_SITE_PERMISSIONS, null))
        root.remove(normalized)
        prefs.edit().putString(KEY_SITE_PERMISSIONS, root.toString()).apply()
    }

    private fun loadMenuShortcuts(): List<BrowserMenuShortcut> {
        if (!prefs.contains(KEY_MENU_SHORTCUTS)) {
            return BrowserMenuShortcut.DEFAULT
        }
        return prefs.getString(KEY_MENU_SHORTCUTS, "")
            .orEmpty()
            .split(',')
            .mapNotNull { raw ->
                raw.takeIf { it.isNotBlank() }
                    ?.let { runCatching { BrowserMenuShortcut.valueOf(it) }.getOrNull() }
            }
            .distinct()
            .take(BrowserMenuShortcut.MAX_COUNT)
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        raw: String?,
        fallback: T,
    ): T = runCatching { enumValueOf<T>(raw.orEmpty()) }.getOrDefault(fallback)

    private fun parseArray(raw: String?): JSONArray = runCatching {
        JSONArray(raw ?: "[]")
    }.getOrElse { JSONArray() }


    private fun parseObject(raw: String?): JSONObject = runCatching {
        JSONObject(raw ?: "{}")
    }.getOrElse { JSONObject() }

    private fun JSONObject.optNullableBoolean(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        return optBoolean(key)
    }

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
        private const val KEY_TRACKING = "tracking"
        private const val KEY_MENU_SHORTCUTS = "menu_shortcuts"
        private const val KEY_TABS = "tabs"
        private const val KEY_SELECTED_TAB = "selected_tab"
        private const val KEY_BOOKMARKS = "bookmarks"
        private const val KEY_HISTORY = "history"
        private const val KEY_CHAT_BINDINGS = "chat_bindings"
        private const val KEY_SITE_SETTINGS = "site_settings"
        private const val KEY_SITE_PERMISSIONS = "site_permissions"
        private const val MAX_HISTORY = 500
    }
}
