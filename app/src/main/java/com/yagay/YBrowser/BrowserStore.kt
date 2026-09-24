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
    CHATGPT("ChatGPT", "https://chatgpt.com/?q=%s"),
    SEARXNG("SearXNG", ""),
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

enum class DownloadManagerMode(val label: String) {
    SYSTEM("系统下载器"),
    ASK_EVERY_TIME("每次询问"),
    EXTERNAL("固定外部下载器"),
}

enum class ExternalAppLinkHandling(val label: String) {
    AUTOMATIC("自动打开"),
    ASK_EVERY_TIME("每次询问"),
    NEVER("始终留在浏览器"),
}

enum class DnsOverHttpsProvider(
    val label: String,
    val endpoint: String?,
) {
    SYSTEM("系统 DNS", null),
    CLOUDFLARE(
        "Cloudflare",
        "https://cloudflare-dns.com/dns-query",
    ),
    GOOGLE(
        "Google",
        "https://dns.google/dns-query",
    ),
    QUAD9(
        "Quad9",
        "https://dns.quad9.net/dns-query",
    ),
    CUSTOM("自定义", null),
}

enum class TranslationProvider(
    val label: String,
) {
    GOOGLE("Google 翻译"),
    YANDEX("Yandex 翻译"),
    KAGI("Kagi Translate"),
}

enum class WebRtcProtectionMode(
    val label: String,
) {
    STANDARD("标准"),
    HIDE_LOCAL_IP("隐藏本地 IP"),
    DISABLE_NON_PROXIED_UDP("禁用非代理 UDP"),
    PROTECT_IP("最大限度保护 IP"),
    BLOCK("完全阻止 WebRTC"),
}

enum class ToolbarPosition(val label: String) {
    TOP("顶部"),
    BOTTOM("底部"),
}

enum class TabSwitcherLayout(val label: String) {
    GRID("网格"),
    LIST("列表"),
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
    QR_SCAN("扫码"),
    BOOKMARK("收藏"),
    DESKTOP_MODE("桌面版"),
    READER("阅读模式"),
    OFFLINE_READER("离线阅读"),
    SNOOZED_TABS("休眠标签"),
    TRANSLATE("翻译"),
    VIEW_SOURCE("源代码"),
    PRINT("打印 / PDF"),
    OPEN_EXTERNAL("外部打开"),
    ADD_TO_HOME("添加到主屏幕"),
    SITE_SETTINGS("网站设置"),
    PRIVACY_REPORT("隐私报告"),
    USER_SCRIPTS("用户脚本"),
    CUSTOM_FILTERS("自定义过滤"),
    PROFILES("Profiles"),
    EXTENSIONS("扩展"),
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
    val searxngBaseUrl: String = "",
    val homepage: String = "https://www.google.com/",
    val nativeNewTabPage: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val forceDarkWebView: Boolean = false,
    val toolbarPosition: ToolbarPosition = ToolbarPosition.BOTTOM,
    val tabSwitcherLayout: TabSwitcherLayout = TabSwitcherLayout.GRID,
    val activeProfileId: String = DEFAULT_BROWSER_PROFILE_ID,
    val restoreTabs: Boolean = true,
    val autoCloseTabsDays: Int = 0,
    val javaScriptEnabled: Boolean = true,
    val cookiesEnabled: Boolean = true,
    val desktopModeByDefault: Boolean = false,
    val textScale: Int = 100,
    val trackingProtection: TrackingProtection = TrackingProtection.STANDARD,
    val blockAutoplay: Boolean = false,
    val blockThirdPartyCookies: Boolean = true,
    val downloadManagerMode: DownloadManagerMode = DownloadManagerMode.SYSTEM,
    val externalDownloadManagerId: String? = null,
    val shareDownloadSessionData: Boolean = false,
    val externalAppLinkHandling: ExternalAppLinkHandling =
        ExternalAppLinkHandling.ASK_EVERY_TIME,
    val historySuggestionsEnabled: Boolean = true,
    val bookmarkSuggestionsEnabled: Boolean = true,
    val onlineSearchSuggestionsEnabled: Boolean = true,
    val translationProvider: TranslationProvider =
        TranslationProvider.GOOGLE,
    val pullToRefreshEnabled: Boolean = true,
    val pullToRefreshThresholdDp: Int = 88,
    val dnsOverHttpsProvider: DnsOverHttpsProvider =
        DnsOverHttpsProvider.SYSTEM,
    val customDnsOverHttpsUrl: String = "",
    val httpsOnlyMode: Boolean = false,
    val doNotTrackEnabled: Boolean = true,
    val globalPrivacyControlEnabled: Boolean = true,
    val webRtcProtectionMode: WebRtcProtectionMode =
        WebRtcProtectionMode.STANDARD,
    val clearHistoryOnExit: Boolean = false,
    val clearSiteDataOnExit: Boolean = false,
    val menuShortcuts: List<BrowserMenuShortcut> = BrowserMenuShortcut.DEFAULT,
)

data class BrowserTab(
    val id: Long,
    val url: String,
    val title: String,
    val privateMode: Boolean = false,
    val desktopMode: Boolean = false,
    val pinned: Boolean = false,
    val groupName: String? = null,
    val lastAccessedAt: Long = System.currentTimeMillis(),
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


enum class SitePermissionDecision(val label: String) {
    ASK("每次询问"),
    ALLOW("允许"),
    BLOCK("阻止"),
}

data class SiteSettings(
    val host: String,
    val javaScriptEnabled: Boolean? = null,
    val cookiesEnabled: Boolean? = null,
    val trackingProtection: TrackingProtection? = null,
    val textScale: Int? = null,
    val muted: Boolean? = null,
) {
    val isDefault: Boolean
        get() = javaScriptEnabled == null &&
            cookiesEnabled == null &&
            trackingProtection == null &&
            textScale == null &&
            muted == null
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
        searxngBaseUrl = prefs.getString(KEY_SEARXNG, "").orEmpty(),
        homepage = prefs.getString(KEY_HOME, null)
            ?.takeIf { it.isNotBlank() }
            ?: "https://www.google.com/",
        nativeNewTabPage = prefs.getBoolean(KEY_NATIVE_NEW_TAB, true),
        themeMode = enumValueOrDefault(
            prefs.getString(KEY_THEME, null),
            ThemeMode.SYSTEM,
        ),
        forceDarkWebView = prefs.getBoolean(
            KEY_FORCE_DARK_WEBVIEW,
            false,
        ),
        toolbarPosition = enumValueOrDefault(
            prefs.getString(KEY_TOOLBAR, null),
            ToolbarPosition.BOTTOM,
        ),
        tabSwitcherLayout = enumValueOrDefault(
            prefs.getString(KEY_TAB_LAYOUT, null),
            TabSwitcherLayout.GRID,
        ),
        activeProfileId = prefs.getString(KEY_ACTIVE_PROFILE, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_BROWSER_PROFILE_ID,
        restoreTabs = prefs.getBoolean(KEY_RESTORE, true),
        autoCloseTabsDays = prefs.getInt(KEY_AUTO_CLOSE_TABS_DAYS, 0)
            .takeIf { it in setOf(0, 1, 7, 30) }
            ?: 0,
        javaScriptEnabled = prefs.getBoolean(KEY_JS, true),
        cookiesEnabled = prefs.getBoolean(KEY_COOKIES, true),
        desktopModeByDefault = prefs.getBoolean(KEY_DESKTOP, false),
        textScale = prefs.getInt(KEY_TEXT_SCALE, 100).coerceIn(50, 200),
        trackingProtection = enumValueOrDefault(
            prefs.getString(KEY_TRACKING, null),
            TrackingProtection.STANDARD,
        ),
        blockAutoplay = prefs.getBoolean(KEY_BLOCK_AUTOPLAY, false),
        blockThirdPartyCookies = prefs.getBoolean(
            KEY_BLOCK_THIRD_PARTY_COOKIES,
            true,
        ),
        downloadManagerMode = enumValueOrDefault(
            prefs.getString(KEY_DOWNLOAD_MANAGER_MODE, null),
            DownloadManagerMode.SYSTEM,
        ),
        externalDownloadManagerId = prefs.getString(
            KEY_EXTERNAL_DOWNLOAD_MANAGER,
            null,
        )?.takeIf { it.isNotBlank() },
        shareDownloadSessionData = prefs.getBoolean(
            KEY_SHARE_DOWNLOAD_SESSION_DATA,
            false,
        ),
        externalAppLinkHandling = enumValueOrDefault(
            prefs.getString(KEY_EXTERNAL_APP_LINKS, null),
            ExternalAppLinkHandling.ASK_EVERY_TIME,
        ),
        historySuggestionsEnabled = prefs.getBoolean(
            KEY_HISTORY_SUGGESTIONS,
            true,
        ),
        bookmarkSuggestionsEnabled = prefs.getBoolean(
            KEY_BOOKMARK_SUGGESTIONS,
            true,
        ),
        onlineSearchSuggestionsEnabled = prefs.getBoolean(
            KEY_ONLINE_SEARCH_SUGGESTIONS,
            true,
        ),
        translationProvider = enumValueOrDefault(
            prefs.getString(KEY_TRANSLATION_PROVIDER, null),
            TranslationProvider.GOOGLE,
        ),
        pullToRefreshEnabled = prefs.getBoolean(
            KEY_PULL_TO_REFRESH,
            true,
        ),
        pullToRefreshThresholdDp = prefs.getInt(
            KEY_PULL_TO_REFRESH_THRESHOLD,
            88,
        ).coerceIn(60, 160),
        dnsOverHttpsProvider = enumValueOrDefault(
            prefs.getString(KEY_DOH_PROVIDER, null),
            DnsOverHttpsProvider.SYSTEM,
        ),
        customDnsOverHttpsUrl = prefs.getString(
            KEY_DOH_CUSTOM_URL,
            "",
        ).orEmpty(),
        httpsOnlyMode = prefs.getBoolean(
            KEY_HTTPS_ONLY,
            false,
        ),
        doNotTrackEnabled = prefs.getBoolean(
            KEY_DO_NOT_TRACK,
            true,
        ),
        globalPrivacyControlEnabled = prefs.getBoolean(
            KEY_GPC,
            true,
        ),
        webRtcProtectionMode = enumValueOrDefault(
            prefs.getString(KEY_WEBRTC_PROTECTION, null),
            WebRtcProtectionMode.STANDARD,
        ),
        clearHistoryOnExit = prefs.getBoolean(
            KEY_CLEAR_HISTORY_ON_EXIT,
            false,
        ),
        clearSiteDataOnExit = prefs.getBoolean(
            KEY_CLEAR_SITE_DATA_ON_EXIT,
            false,
        ),
        menuShortcuts = loadMenuShortcuts(),
    )

    fun saveSettings(settings: BrowserSettings) {
        prefs.edit()
            .putString(KEY_ENGINE, settings.defaultEngine.name)
            .putString(KEY_SEARCH, settings.searchEngine.name)
            .putString(KEY_SEARXNG, settings.searxngBaseUrl)
            .putString(KEY_HOME, settings.homepage)
            .putBoolean(KEY_NATIVE_NEW_TAB, settings.nativeNewTabPage)
            .putString(KEY_THEME, settings.themeMode.name)
            .putBoolean(
                KEY_FORCE_DARK_WEBVIEW,
                settings.forceDarkWebView,
            )
            .putString(KEY_TOOLBAR, settings.toolbarPosition.name)
            .putString(KEY_TAB_LAYOUT, settings.tabSwitcherLayout.name)
            .putString(KEY_ACTIVE_PROFILE, settings.activeProfileId)
            .putBoolean(KEY_RESTORE, settings.restoreTabs)
            .putInt(KEY_AUTO_CLOSE_TABS_DAYS, settings.autoCloseTabsDays)
            .putBoolean(KEY_JS, settings.javaScriptEnabled)
            .putBoolean(KEY_COOKIES, settings.cookiesEnabled)
            .putBoolean(KEY_DESKTOP, settings.desktopModeByDefault)
            .putInt(KEY_TEXT_SCALE, settings.textScale.coerceIn(50, 200))
            .putString(KEY_TRACKING, settings.trackingProtection.name)
            .putBoolean(KEY_BLOCK_AUTOPLAY, settings.blockAutoplay)
            .putBoolean(
                KEY_BLOCK_THIRD_PARTY_COOKIES,
                settings.blockThirdPartyCookies,
            )
            .putString(
                KEY_DOWNLOAD_MANAGER_MODE,
                settings.downloadManagerMode.name,
            )
            .putString(
                KEY_EXTERNAL_DOWNLOAD_MANAGER,
                settings.externalDownloadManagerId,
            )
            .putBoolean(
                KEY_SHARE_DOWNLOAD_SESSION_DATA,
                settings.shareDownloadSessionData,
            )
            .putString(
                KEY_EXTERNAL_APP_LINKS,
                settings.externalAppLinkHandling.name,
            )
            .putBoolean(
                KEY_HISTORY_SUGGESTIONS,
                settings.historySuggestionsEnabled,
            )
            .putBoolean(
                KEY_BOOKMARK_SUGGESTIONS,
                settings.bookmarkSuggestionsEnabled,
            )
            .putBoolean(
                KEY_ONLINE_SEARCH_SUGGESTIONS,
                settings.onlineSearchSuggestionsEnabled,
            )
            .putString(
                KEY_TRANSLATION_PROVIDER,
                settings.translationProvider.name,
            )
            .putBoolean(
                KEY_PULL_TO_REFRESH,
                settings.pullToRefreshEnabled,
            )
            .putInt(
                KEY_PULL_TO_REFRESH_THRESHOLD,
                settings.pullToRefreshThresholdDp.coerceIn(60, 160),
            )
            .putString(
                KEY_DOH_PROVIDER,
                settings.dnsOverHttpsProvider.name,
            )
            .putString(
                KEY_DOH_CUSTOM_URL,
                settings.customDnsOverHttpsUrl.trim(),
            )
            .putBoolean(
                KEY_HTTPS_ONLY,
                settings.httpsOnlyMode,
            )
            .putBoolean(
                KEY_DO_NOT_TRACK,
                settings.doNotTrackEnabled,
            )
            .putBoolean(
                KEY_GPC,
                settings.globalPrivacyControlEnabled,
            )
            .putString(
                KEY_WEBRTC_PROTECTION,
                settings.webRtcProtectionMode.name,
            )
            .putBoolean(
                KEY_CLEAR_HISTORY_ON_EXIT,
                settings.clearHistoryOnExit,
            )
            .putBoolean(
                KEY_CLEAR_SITE_DATA_ON_EXIT,
                settings.clearSiteDataOnExit,
            )
            .putString(
                KEY_MENU_SHORTCUTS,
                settings.menuShortcuts
                    .distinct()
                    .take(BrowserMenuShortcut.MAX_COUNT)
                    .joinToString(",") { it.name },
            )
            .apply()
    }

    fun loadTabs(
        homepage: String,
        profileId: String = DEFAULT_BROWSER_PROFILE_ID,
    ): Pair<List<BrowserTab>, Long> {
        val tabsKey = scopedKey(KEY_TABS, profileId)
        val selectedKey = scopedKey(KEY_SELECTED_TAB, profileId)
        val array = parseArray(prefs.getString(tabsKey, null))
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
                        pinned = obj.optBoolean("pinned", false),
                        groupName = obj.optString("groupName")
                            .takeIf { it.isNotBlank() },
                        lastAccessedAt = obj.optLong(
                            "lastAccessedAt",
                            System.currentTimeMillis(),
                        ),
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
        val storedSelected = prefs.getLong(selectedKey, valid.first().id)
        val selected = valid.firstOrNull { it.id == storedSelected }?.id ?: valid.first().id
        return valid to selected
    }

    fun saveTabs(
        tabs: List<BrowserTab>,
        selectedTabId: Long,
        profileId: String = DEFAULT_BROWSER_PROFILE_ID,
    ) {
        val array = JSONArray()
        tabs.filterNot { it.privateMode }.forEach { tab ->
            array.put(
                JSONObject()
                    .put("id", tab.id)
                    .put("url", tab.url)
                    .put("title", tab.title)
                    .put("desktopMode", tab.desktopMode)
                    .put("pinned", tab.pinned)
                    .apply {
                        tab.groupName?.takeIf { it.isNotBlank() }
                            ?.let { put("groupName", it) }
                        put("lastAccessedAt", tab.lastAccessedAt)
                    },
            )
        }
        prefs.edit()
            .putString(scopedKey(KEY_TABS, profileId), array.toString())
            .putLong(scopedKey(KEY_SELECTED_TAB, profileId), selectedTabId)
            .apply()
    }

    fun loadBookmarks(
        profileId: String = DEFAULT_BROWSER_PROFILE_ID,
    ): List<BookmarkEntry> {
        val array = parseArray(
            prefs.getString(scopedKey(KEY_BOOKMARKS, profileId), null),
        )
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

    fun saveBookmarks(
        entries: List<BookmarkEntry>,
        profileId: String = DEFAULT_BROWSER_PROFILE_ID,
    ) {
        val array = JSONArray()
        entries.distinctBy { it.url }.forEach { entry ->
            array.put(
                JSONObject()
                    .put("url", entry.url)
                    .put("title", entry.title)
                    .put("createdAt", entry.createdAt),
            )
        }
        prefs.edit()
            .putString(scopedKey(KEY_BOOKMARKS, profileId), array.toString())
            .apply()
    }

    fun loadHistory(
        profileId: String = DEFAULT_BROWSER_PROFILE_ID,
    ): List<HistoryEntry> {
        val array = parseArray(
            prefs.getString(scopedKey(KEY_HISTORY, profileId), null),
        )
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

    fun addHistory(
        url: String,
        title: String,
        profileId: String = DEFAULT_BROWSER_PROFILE_ID,
    ) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val now = System.currentTimeMillis()
        val merged = buildList {
            add(HistoryEntry(url, title.ifBlank { url }, now))
            loadHistory(profileId)
                .asSequence()
                .filterNot { it.url == url }
                .take(MAX_HISTORY - 1)
                .forEach(::add)
        }
        saveHistory(merged, profileId)
    }

    fun saveHistory(
        entries: List<HistoryEntry>,
        profileId: String = DEFAULT_BROWSER_PROFILE_ID,
    ) {
        val array = JSONArray()
        entries.take(MAX_HISTORY).forEach { entry ->
            array.put(
                JSONObject()
                    .put("url", entry.url)
                    .put("title", entry.title)
                    .put("visitedAt", entry.visitedAt),
            )
        }
        prefs.edit()
            .putString(scopedKey(KEY_HISTORY, profileId), array.toString())
            .apply()
    }

    fun clearHistory(profileId: String = DEFAULT_BROWSER_PROFILE_ID) {
        prefs.edit().remove(scopedKey(KEY_HISTORY, profileId)).apply()
    }

    fun clearSession(profileId: String = DEFAULT_BROWSER_PROFILE_ID) {
        prefs.edit()
            .remove(scopedKey(KEY_TABS, profileId))
            .remove(scopedKey(KEY_SELECTED_TAB, profileId))
            .apply()
    }

    fun clearAllSiteSettings(
        profileId: String = DEFAULT_BROWSER_PROFILE_ID,
    ) {
        prefs.edit()
            .remove(
                scopedKey(
                    KEY_SITE_SETTINGS,
                    profileId,
                )
            )
            .apply()
    }

    fun clearAllSitePermissionDecisions(
        profileId: String = DEFAULT_BROWSER_PROFILE_ID,
    ) {
        prefs.edit()
            .remove(
                scopedKey(
                    KEY_SITE_PERMISSIONS,
                    profileId,
                )
            )
            .apply()
    }

    fun loadSiteSettings(
        host: String,
        profileId: String = DEFAULT_BROWSER_PROFILE_ID,
    ): SiteSettings? {
        val normalized = host.lowercase().trim().trimEnd('.')
        if (normalized.isBlank()) return null
        val root = parseObject(
            prefs.getString(scopedKey(KEY_SITE_SETTINGS, profileId), null),
        )
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
            muted = obj.optNullableBoolean("muted"),
        )
    }

    fun saveSiteSettings(
        settings: SiteSettings,
        profileId: String = DEFAULT_BROWSER_PROFILE_ID,
    ) {
        val normalized = settings.host.lowercase().trim().trimEnd('.')
        if (normalized.isBlank()) return
        val root = parseObject(prefs.getString(scopedKey(KEY_SITE_SETTINGS, profileId), null))
        if (settings.isDefault) {
            root.remove(normalized)
        } else {
            val obj = JSONObject()
            settings.javaScriptEnabled?.let { obj.put("js", it) }
            settings.cookiesEnabled?.let { obj.put("cookies", it) }
            settings.trackingProtection?.let { obj.put("tracking", it.name) }
            settings.textScale?.let { obj.put("textScale", it.coerceIn(50, 200)) }
            settings.muted?.let { obj.put("muted", it) }
            root.put(normalized, obj)
        }
        prefs.edit()
            .putString(scopedKey(KEY_SITE_SETTINGS, profileId), root.toString())
            .apply()
    }

    fun clearSiteSettings(
        host: String,
        profileId: String = DEFAULT_BROWSER_PROFILE_ID,
    ) {
        val normalized = host.lowercase().trim().trimEnd('.')
        val root = parseObject(prefs.getString(scopedKey(KEY_SITE_SETTINGS, profileId), null))
        root.remove(normalized)
        prefs.edit()
            .putString(scopedKey(KEY_SITE_SETTINGS, profileId), root.toString())
            .apply()
    }

    fun loadAllSiteSettings(
        profileId: String = DEFAULT_BROWSER_PROFILE_ID,
    ): List<SiteSettings> {
        val root = parseObject(
            prefs.getString(scopedKey(KEY_SITE_SETTINGS, profileId), null),
        )
        return buildList {
            val keys = root.keys()
            while (keys.hasNext()) {
                val host = keys.next()
                loadSiteSettings(host, profileId)?.let(::add)
            }
        }.sortedBy { it.host }
    }


    fun loadSitePermissionDecision(
        host: String,
        permission: BrowserSitePermission,
        profileId: String = DEFAULT_BROWSER_PROFILE_ID,
    ): SitePermissionDecision {
        val normalized = host.lowercase().trim().trimEnd('.')
        if (normalized.isBlank()) return SitePermissionDecision.ASK
        val root = parseObject(prefs.getString(scopedKey(KEY_SITE_PERMISSIONS, profileId), null))
        val site = root.optJSONObject(normalized) ?: return SitePermissionDecision.ASK
        val raw = site.optString(permission.name)
        return runCatching { SitePermissionDecision.valueOf(raw) }
            .getOrDefault(SitePermissionDecision.ASK)
    }

    fun saveSitePermissionDecision(
        host: String,
        permission: BrowserSitePermission,
        decision: SitePermissionDecision,
        profileId: String = DEFAULT_BROWSER_PROFILE_ID,
    ) {
        val normalized = host.lowercase().trim().trimEnd('.')
        if (normalized.isBlank()) return
        val root = parseObject(prefs.getString(scopedKey(KEY_SITE_PERMISSIONS, profileId), null))
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
        prefs.edit()
            .putString(scopedKey(KEY_SITE_PERMISSIONS, profileId), root.toString())
            .apply()
    }

    fun clearSitePermissionDecisions(
        host: String,
        profileId: String = DEFAULT_BROWSER_PROFILE_ID,
    ) {
        val normalized = host.lowercase().trim().trimEnd('.')
        val root = parseObject(prefs.getString(scopedKey(KEY_SITE_PERMISSIONS, profileId), null))
        root.remove(normalized)
        prefs.edit()
            .putString(scopedKey(KEY_SITE_PERMISSIONS, profileId), root.toString())
            .apply()
    }

    fun deleteProfileData(profileId: String) {
        if (profileId == DEFAULT_BROWSER_PROFILE_ID) return
        val keys = listOf(
            KEY_TABS,
            KEY_SELECTED_TAB,
            KEY_BOOKMARKS,
            KEY_HISTORY,
            KEY_SITE_SETTINGS,
            KEY_SITE_PERMISSIONS,
        )
        val editor = prefs.edit()
        keys.forEach { key ->
            editor.remove(scopedKey(key, profileId))
        }
        editor.apply()
    }

    private fun scopedKey(base: String, profileId: String): String =
        if (profileId == DEFAULT_BROWSER_PROFILE_ID) {
            base
        } else {
            base + "__profile_" + profileId
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
        private const val KEY_SEARXNG = "searxng_base_url"
        private const val KEY_HOME = "home"
        private const val KEY_NATIVE_NEW_TAB = "native_new_tab"
        private const val KEY_THEME = "theme"
        private const val KEY_FORCE_DARK_WEBVIEW = "force_dark_webview"
        private const val KEY_TOOLBAR = "toolbar"
        private const val KEY_TAB_LAYOUT = "tab_layout"
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        private const val KEY_RESTORE = "restore"
        private const val KEY_AUTO_CLOSE_TABS_DAYS = "auto_close_tabs_days"
        private const val KEY_JS = "js"
        private const val KEY_COOKIES = "cookies"
        private const val KEY_DESKTOP = "desktop"
        private const val KEY_TEXT_SCALE = "text_scale"
        private const val KEY_TRACKING = "tracking"
        private const val KEY_BLOCK_AUTOPLAY = "block_autoplay"
        private const val KEY_BLOCK_THIRD_PARTY_COOKIES =
            "block_third_party_cookies"
        private const val KEY_DOWNLOAD_MANAGER_MODE = "download_manager_mode"
        private const val KEY_EXTERNAL_DOWNLOAD_MANAGER = "external_download_manager"
        private const val KEY_SHARE_DOWNLOAD_SESSION_DATA = "share_download_session_data"
        private const val KEY_EXTERNAL_APP_LINKS = "external_app_links"
        private const val KEY_HISTORY_SUGGESTIONS = "history_suggestions"
        private const val KEY_BOOKMARK_SUGGESTIONS = "bookmark_suggestions"
        private const val KEY_ONLINE_SEARCH_SUGGESTIONS =
            "online_search_suggestions"
        private const val KEY_TRANSLATION_PROVIDER = "translation_provider"
        private const val KEY_PULL_TO_REFRESH = "pull_to_refresh"
        private const val KEY_PULL_TO_REFRESH_THRESHOLD =
            "pull_to_refresh_threshold"
        private const val KEY_DOH_PROVIDER = "doh_provider"
        private const val KEY_DOH_CUSTOM_URL = "doh_custom_url"
        private const val KEY_HTTPS_ONLY = "https_only"
        private const val KEY_DO_NOT_TRACK = "do_not_track"
        private const val KEY_GPC = "global_privacy_control"
        private const val KEY_WEBRTC_PROTECTION = "webrtc_protection"
        private const val KEY_CLEAR_HISTORY_ON_EXIT = "clear_history_on_exit"
        private const val KEY_CLEAR_SITE_DATA_ON_EXIT = "clear_site_data_on_exit"
        private const val KEY_MENU_SHORTCUTS = "menu_shortcuts"
        private const val KEY_TABS = "tabs"
        private const val KEY_SELECTED_TAB = "selected_tab"
        private const val KEY_BOOKMARKS = "bookmarks"
        private const val KEY_HISTORY = "history"
        private const val KEY_SITE_SETTINGS = "site_settings"
        private const val KEY_SITE_PERMISSIONS = "site_permissions"
        private const val MAX_HISTORY = 500
    }
}
