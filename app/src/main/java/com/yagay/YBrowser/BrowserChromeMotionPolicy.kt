package com.yagay.YBrowser

import kotlin.math.roundToInt

/** Synchronized chrome translation and viewport inset, both measured in pixels. */
internal data class BrowserChromeMotion(
    val translationPx: Int,
    val reservedSpacePx: Int,
)

internal object BrowserChromeMotionPolicy {
    fun calculate(barHeightPx: Int, hiddenFraction: Float): BrowserChromeMotion {
        val height = barHeightPx.coerceAtLeast(0)
        // Clamp state restored from animation and guard against non-finite input.
        val fraction = if (hiddenFraction.isFinite()) {
            hiddenFraction.coerceIn(0f, 1f)
        } else {
            0f
        }
        val translation = (height * fraction).roundToInt().coerceIn(0, height)
        // The two values always sum to the original height. Independent rounding
        // can leave a one-pixel gap between the page and moving browser chrome.
        return BrowserChromeMotion(
            translationPx = translation,
            reservedSpacePx = height - translation,
        )
    }
}
