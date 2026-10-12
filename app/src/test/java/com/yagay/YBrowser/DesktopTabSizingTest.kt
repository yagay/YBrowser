package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopTabSizingTest {
    @Test fun allowsSmallerThanPreviousMinimum() {
        assertEquals(36, DesktopTabSizing.normalWidth(20))
        assertEquals(320, DesktopTabSizing.normalWidth(400))
        assertEquals(28, DesktopTabSizing.height(10))
        assertEquals(88, DesktopTabSizing.height(100))
        assertEquals(28, DesktopTabSizing.compactWidth(10))
        assertEquals(160, DesktopTabSizing.compactWidth(200))
    }

    @Test fun bothForegroundAndBackgroundShrink() {
        assertEquals(36, DesktopTabSizing.width(36, 56, false, true))
        assertEquals(28, DesktopTabSizing.width(36, 56, false, false))
        assertEquals(56, DesktopTabSizing.width(144, 56, true, true))
        assertEquals(32, DesktopTabSizing.width(144, 32, true, false))
        assertEquals(144, DesktopTabSizing.width(144, 56, false, true))
    }

    @Test fun narrowTabHidesActionsRatherThanGrowing() {
        assertFalse(DesktopTabSizing.showTitle(36, false, true))
        assertFalse(DesktopTabSizing.showMore(36, true, false))
        assertFalse(DesktopTabSizing.showClose(36, false, true, false))
        assertFalse(DesktopTabSizing.showPinned(36, false, true))
        assertTrue(DesktopTabSizing.showTitle(144, false, true))
        assertTrue(DesktopTabSizing.showMore(144, true, false))
        assertTrue(DesktopTabSizing.showClose(144, false, true, false))
        assertTrue(DesktopTabSizing.showPinned(144, false, true))
        assertFalse(DesktopTabSizing.showClose(144, false, true, true))
    }
}
