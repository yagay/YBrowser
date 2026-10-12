package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserChromeMotionPolicyTest {
    @Test fun fullyVisibleReservesEntireChromeHeight() {
        assertEquals(BrowserChromeMotion(0, 96), BrowserChromeMotionPolicy.calculate(96, 0f))
    }

    @Test fun fullyHiddenReleasesChromeInset() {
        assertEquals(BrowserChromeMotion(96, 0), BrowserChromeMotionPolicy.calculate(96, 1f))
    }

    @Test fun slideAndViewportInsetRemainSynchronized() {
        for (height in listOf(1, 37, 83, 112)) {
            for (step in 0..100) {
                val motion = BrowserChromeMotionPolicy.calculate(height, step / 100f)
                assertEquals(height, motion.translationPx + motion.reservedSpacePx)
            }
        }
        assertEquals(BrowserChromeMotion(48, 48), BrowserChromeMotionPolicy.calculate(96, 0.5f))
    }

    @Test fun invalidOrOvershootingAnimationValuesStayInBounds() {
        assertEquals(BrowserChromeMotion(0, 80), BrowserChromeMotionPolicy.calculate(80, -1f))
        assertEquals(BrowserChromeMotion(80, 0), BrowserChromeMotionPolicy.calculate(80, 2f))
        assertEquals(BrowserChromeMotion(0, 80), BrowserChromeMotionPolicy.calculate(80, Float.NaN))
        assertEquals(BrowserChromeMotion(0, 0), BrowserChromeMotionPolicy.calculate(-20, 0.5f))
    }
}
