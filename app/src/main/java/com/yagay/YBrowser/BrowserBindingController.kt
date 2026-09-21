package com.yagay.YBrowser

/**
 * Optional browser-shell integration for binding the current web page to an
 * external host. BrowserApp only knows this generic contract; product-specific
 * integrations live outside the browser core.
 */
data class BrowserPageBinding(
    val label: String,
)

class BrowserBindingController(
    val revision: Int = 0,
    val targetLabel: String? = null,
    val findBinding: (String) -> BrowserPageBinding? = { null },
    val bindToTarget: (String, String) -> Unit = { _, _ -> },
    val unbind: (String) -> Unit = {},
    val requestBinding: (String, String) -> Unit = { _, _ -> },
)
