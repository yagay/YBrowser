package com.yagay.YBrowser

/**
 * Only the foreground tab may expose its native rendering Surface.
 *
 * Compose alpha/zIndex alone cannot safely hide GeckoView's SurfaceView:
 * native surfaces may composite outside the Compose drawing layer.
 *
 * The actual View is kept attached but assigned View.INVISIBLE when false.
 */
internal object BrowserTabSurfacePolicy {
    fun isVisible(
        tabId: Long,
        selectedTabId: Long,
        nativeHomeVisible: Boolean,
    ): Boolean = tabId == selectedTabId && !nativeHomeVisible
}
