package com.yagay.YBrowser

/**
 * All desktop tab size limits in one place. Smaller widths hide optional
 * actions instead of forcing a larger tab through child layout constraints.
 */
internal object DesktopTabSizing {
    const val MIN_WIDTH = 36
    const val MAX_WIDTH = 320
    const val MIN_COMPACT_WIDTH = 28
    const val MAX_COMPACT_WIDTH = 160
    const val MIN_HEIGHT = 28
    const val MAX_HEIGHT = 88
    const val DEFAULT_COMPACT_WIDTH = 56
    private const val BACKGROUND_FRACTION = 0.78f

    fun normalWidth(width: Int): Int = width.coerceIn(MIN_WIDTH, MAX_WIDTH)
    fun compactWidth(width: Int): Int = width.coerceIn(MIN_COMPACT_WIDTH, MAX_COMPACT_WIDTH)
    fun height(height: Int): Int = height.coerceIn(MIN_HEIGHT, MAX_HEIGHT)

    fun width(configured: Int, compactConfigured: Int, compact: Boolean, selected: Boolean): Int {
        if (compact) return compactWidth(compactConfigured)
        val full = normalWidth(configured)
        return if (selected) full else
            (full * BACKGROUND_FRACTION).toInt().coerceIn(MIN_COMPACT_WIDTH, MAX_WIDTH)
    }

    fun showTitle(width: Int, compact: Boolean, selected: Boolean): Boolean =
        !compact && width >= (if (selected) 96 else 72)

    fun showMore(width: Int, selected: Boolean, compact: Boolean): Boolean =
        (selected || compact) && width >= 56

    fun showClose(width: Int, compact: Boolean, selected: Boolean, pinned: Boolean): Boolean =
        selected && !compact && !pinned && width >= 136

    fun showPinned(width: Int, compact: Boolean, pinned: Boolean): Boolean =
        pinned && !compact && width >= 126
}
