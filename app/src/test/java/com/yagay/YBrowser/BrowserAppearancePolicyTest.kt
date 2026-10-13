package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAppearancePolicyTest {
    @Test fun systemThemeFollowsAndroid() {
        assertFalse(BrowserAppearancePolicy.isDark(ThemeMode.SYSTEM, false))
        assertTrue(BrowserAppearancePolicy.isDark(ThemeMode.SYSTEM, true))
        assertEquals(BrowserWebColorScheme.SYSTEM, BrowserAppearancePolicy.webScheme(ThemeMode.SYSTEM))
    }

    @Test fun explicitThemeOverridesAndroidAppearance() {
        assertFalse(BrowserAppearancePolicy.isDark(ThemeMode.LIGHT, true))
        assertTrue(BrowserAppearancePolicy.isDark(ThemeMode.DARK, false))
        assertTrue(BrowserAppearancePolicy.isDark(ThemeMode.AMOLED, false))
        assertEquals(BrowserWebColorScheme.LIGHT, BrowserAppearancePolicy.webScheme(ThemeMode.LIGHT))
        assertEquals(BrowserWebColorScheme.DARK, BrowserAppearancePolicy.webScheme(ThemeMode.AMOLED))
    }

    @Test fun autoModeUsesSitePreferenceAndSystemAppearance() {
        assertEquals(BrowserPageDarkMode.OFF,
            BrowserAppearancePolicy.pageDarkMode(BrowserWebColorScheme.LIGHT, false, false))
        assertEquals(BrowserPageDarkMode.AUTO,
            BrowserAppearancePolicy.pageDarkMode(BrowserWebColorScheme.DARK, false, false))
        assertEquals(BrowserPageDarkMode.AUTO,
            BrowserAppearancePolicy.pageDarkMode(BrowserWebColorScheme.SYSTEM, true, false))
        assertEquals(BrowserPageDarkMode.FORCE,
            BrowserAppearancePolicy.pageDarkMode(BrowserWebColorScheme.LIGHT, false, true))
        assertEquals(BrowserPageDarkMode.OFF,
            BrowserAppearancePolicy.pageDarkMode(
                BrowserWebColorScheme.DARK, true, true, BrowserPageDarkMode.OFF))
    }

    @Test fun nativeDarkeningNeverRunsAlongsideInjectedDarkScript() {
        assertFalse(BrowserAppearancePolicy.webViewNeedsNativeDarkening(
            BrowserPageDarkMode.AUTO, true))
        assertFalse(BrowserAppearancePolicy.webViewNeedsNativeDarkening(
            BrowserPageDarkMode.FORCE, true))
        assertTrue(BrowserAppearancePolicy.webViewNeedsNativeDarkening(
            BrowserPageDarkMode.FORCE, false))
        assertFalse(BrowserAppearancePolicy.webViewNeedsNativeDarkening(
            BrowserPageDarkMode.OFF, false))
    }

    @Test fun forceDarkWebPagesWorksIndependentlyOfChrome() {
        assertFalse(BrowserAppearancePolicy.webViewDark(BrowserWebColorScheme.LIGHT, false, false))
        assertTrue(BrowserAppearancePolicy.webViewDark(BrowserWebColorScheme.LIGHT, false, true))
        assertTrue(BrowserAppearancePolicy.webViewDark(BrowserWebColorScheme.DARK, false, false))
        assertTrue(BrowserAppearancePolicy.webViewDark(BrowserWebColorScheme.SYSTEM, true, false))
        assertFalse(BrowserAppearancePolicy.webViewDark(BrowserWebColorScheme.SYSTEM, false, false))
    }
}
