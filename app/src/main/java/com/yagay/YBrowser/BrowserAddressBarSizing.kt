package com.yagay.YBrowser

/**
 * Shared, persisted dimensions for the browser's native address chrome.
 * All controls use the same limits and the available bar height; a small bar
 * must not be expanded by an oversized icon or action button.
 */
internal object BrowserAddressBarSizing {
    const val MIN_HEIGHT = 28
    const val MAX_HEIGHT = 88
    const val DEFAULT_HEIGHT = 48

    const val MIN_BUTTON = 24
    const val MAX_BUTTON = 80
    const val DEFAULT_BUTTON = 48

    const val MIN_ICON = 12
    const val MAX_ICON = 32
    const val DEFAULT_ICON = 20

    const val MIN_CORNER = 0
    const val MAX_CORNER = 24
    const val DEFAULT_CORNER = 8

    const val MIN_SPACING = 0
    const val MAX_SPACING = 16
    const val DEFAULT_SPACING = 2

    fun height(value: Int): Int = value.coerceIn(MIN_HEIGHT, MAX_HEIGHT)
    fun button(value: Int): Int = value.coerceIn(MIN_BUTTON, MAX_BUTTON)
    fun icon(value: Int): Int = value.coerceIn(MIN_ICON, MAX_ICON)
    fun corner(value: Int): Int = value.coerceIn(MIN_CORNER, MAX_CORNER)
    fun spacing(value: Int): Int = value.coerceIn(MIN_SPACING, MAX_SPACING)

    fun effectiveButton(heightDp: Int, buttonDp: Int): Int =
        button(buttonDp).coerceAtMost(height(heightDp))

    fun effectiveIcon(heightDp: Int, buttonDp: Int, iconDp: Int): Int =
        icon(iconDp).coerceAtMost(
            (effectiveButton(heightDp, buttonDp) - 4).coerceAtLeast(MIN_ICON)
        )

    fun fieldHeight(heightDp: Int): Int = (height(heightDp) - 4).coerceAtLeast(24)
}
