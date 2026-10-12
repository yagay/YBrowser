package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTabSurfacePolicyTest {
    @Test fun switchingTabsShowsOnlyTheSelectedNativeSurface() {
        val ids = listOf(1L, 2L, 3L)
        for (selected in ids) {
            val visible = ids.filter {
                BrowserTabSurfacePolicy.isVisible(it, selected, nativeHomeVisible = false)
            }
            assertEquals(listOf(selected), visible)
        }
    }

    @Test fun nativeHomepageHidesAllWebViewsWithoutClosingTabs() {
        val ids = listOf(1L, 2L, 3L)
        assertFalse(ids.any {
            BrowserTabSurfacePolicy.isVisible(it, 2L, nativeHomeVisible = true)
        })
    }

    @Test fun inactiveTabNeverDisplaysAboveForegroundTab() {
        assertFalse(BrowserTabSurfacePolicy.isVisible(19L, 10L, false))
        assertTrue(BrowserTabSurfacePolicy.isVisible(10L, 10L, false))
        assertFalse(BrowserTabSurfacePolicy.isVisible(10L, 10L, true))
    }
}
