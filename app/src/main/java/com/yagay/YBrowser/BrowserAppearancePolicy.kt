package com.yagay.YBrowser

/** A single source of truth for chrome and the two native web engines. */
enum class BrowserWebColorScheme {
    SYSTEM, LIGHT, DARK
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
