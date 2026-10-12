package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAddressBarSizingTest {
    @Test fun defaultsMatchExistingAddressChrome() {
        assertEquals(48, BrowserAddressBarSizing.height(48))
        assertEquals(48, BrowserAddressBarSizing.effectiveButton(48, 48))
        assertEquals(20, BrowserAddressBarSizing.effectiveIcon(48, 48, 20))
        assertEquals(44, BrowserAddressBarSizing.fieldHeight(48))
    }

    @Test fun smallBarsActuallyShrinkTheirChildButtons() {
        assertEquals(28, BrowserAddressBarSizing.height(12))
        assertEquals(28, BrowserAddressBarSizing.effectiveButton(28, 48))
        assertEquals(24, BrowserAddressBarSizing.effectiveIcon(28, 48, 32))
        assertEquals(24, BrowserAddressBarSizing.fieldHeight(28))
    }

    @Test fun allControlsHaveSafeLimitsAndIndependentSizes() {
        assertEquals(88, BrowserAddressBarSizing.height(100))
        assertEquals(24, BrowserAddressBarSizing.button(1))
        assertEquals(80, BrowserAddressBarSizing.button(200))
        assertEquals(12, BrowserAddressBarSizing.icon(1))
        assertEquals(32, BrowserAddressBarSizing.icon(200))
        assertEquals(0, BrowserAddressBarSizing.corner(-5))
        assertEquals(24, BrowserAddressBarSizing.corner(100))
        assertEquals(0, BrowserAddressBarSizing.spacing(-1))
        assertEquals(16, BrowserAddressBarSizing.spacing(200))
        assertTrue(BrowserAddressBarSizing.effectiveIcon(28, 24, 32) <
            BrowserAddressBarSizing.effectiveButton(28, 24))
    }
}
