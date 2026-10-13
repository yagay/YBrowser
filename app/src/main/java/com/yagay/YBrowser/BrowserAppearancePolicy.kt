package com.yagay.YBrowser

/** A single source of truth for chrome and the two native web engines. */
enum class BrowserWebColorScheme {
    SYSTEM, LIGHT, DARK
}

/** Visual fallback for pages that ignore a browser's preferred dark color scheme. */
enum class BrowserPageDarkMode(val label: String) {
    OFF("关闭"),
    AUTO("自动适配"),
    FORCE("强制深色"),
}

internal object BrowserAppearancePolicy {
    fun webScheme(mode: ThemeMode): BrowserWebColorScheme = when (mode) {
        ThemeMode.SYSTEM -> BrowserWebColorScheme.SYSTEM
        ThemeMode.LIGHT -> BrowserWebColorScheme.LIGHT
        ThemeMode.DARK, ThemeMode.AMOLED -> BrowserWebColorScheme.DARK
    }

    fun isDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }

    fun pageDarkMode(
        scheme: BrowserWebColorScheme,
        systemDark: Boolean,
        forceDarkPages: Boolean,
        override: BrowserPageDarkMode? = null,
    ): BrowserPageDarkMode {
        if (override != null) return override
        if (forceDarkPages) return BrowserPageDarkMode.FORCE
        return if (scheme == BrowserWebColorScheme.DARK ||
            (scheme == BrowserWebColorScheme.SYSTEM && systemDark)
        ) BrowserPageDarkMode.AUTO else BrowserPageDarkMode.OFF
    }

    /** WebView's "force dark" is separate from the app chrome preference. */
    fun webViewDark(
        scheme: BrowserWebColorScheme,
        systemDark: Boolean,
        forceDarkPages: Boolean,
    ): Boolean = forceDarkPages || when (scheme) {
        BrowserWebColorScheme.SYSTEM -> systemDark
        BrowserWebColorScheme.LIGHT -> false
        BrowserWebColorScheme.DARK -> true
    }
}
