package com.yagay.YBrowser

import kotlin.math.roundToInt

/** Size policy for persisted tab thumbnails; pure so it can be unit tested. */
internal data class TabPreviewSize(val width: Int, val height: Int)

internal object TabPreviewSizePolicy {
    private const val MAX_WIDTH = 480
    private const val MAX_HEIGHT = 720

    fun fit(width: Int, height: Int): TabPreviewSize? {
        if (width <= 0 || height <= 0) return null
        val factor = minOf(
            1.0,
            MAX_WIDTH.toDouble() / width,
            MAX_HEIGHT.toDouble() / height,
        )
        return TabPreviewSize(
            (width.toDouble() * factor).roundToInt().coerceIn(1, MAX_WIDTH),
            (height.toDouble() * factor).roundToInt().coerceIn(1, MAX_HEIGHT),
        )
    }
}
