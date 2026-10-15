package com.yagay.YBrowser

/**
 * One in-flight Android file picker belongs to exactly one tab and document.
 * Pure rules are separated from Android launchers for deterministic testing.
 */
internal object BrowserFileChooserPolicy {
    const val MAX_SELECTED_FILES = 100

    /**
     * The WebView/Gecko engine owns the exact callback to the file-input
     * document. A URL is not a document identifier: single-page applications
     * may change their route or query while the Android picker is open.
     * Likewise changing the active tab must not steal the original callback.
     */
    fun isCurrent(tabExists: Boolean): Boolean = tabExists

    fun <T> distinctSelection(values: List<T>?, allowMultiple: Boolean): List<T>? {
        val limit = if (allowMultiple) MAX_SELECTED_FILES else 1
        return values.orEmpty().distinct().take(limit).takeIf { it.isNotEmpty() }
    }
}

internal class BrowserFileChooserDelivery<T>(
    private val callback: (List<T>?) -> Unit,
) {
    private var completed = false
    fun complete(values: List<T>?): Boolean {
        if (completed) return false
        completed = true
        callback(values)
        return true
    }
}

internal class PendingBrowserFilePrompt(
    val tabId: Long,
    val request: BrowserFilePromptRequest,
) {
    private val delivery = BrowserFileChooserDelivery<android.net.Uri> {
        request.complete(it)
    }

    fun cancel() { delivery.complete(null) }

    fun complete(
        values: List<android.net.Uri>?,
        tabExists: Boolean,
    ): Boolean {
        val valid = BrowserFileChooserPolicy.isCurrent(tabExists)
        delivery.complete(
            if (valid) BrowserFileChooserPolicy.distinctSelection(
                values, request.allowMultiple,
            ) else null,
        )
        return valid
    }
}
