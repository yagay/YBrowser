package com.yagay.YBrowser

/**
 * One in-flight Android file picker belongs to exactly one tab and document.
 * Pure rules are separated from Android launchers for deterministic testing.
 */
internal object BrowserFileChooserPolicy {
    const val MAX_SELECTED_FILES = 100

    fun isCurrent(
        sourceTabId: Long,
        sourceUrl: String,
        selectedTabId: Long,
        tabExists: Boolean,
        currentUrl: String?,
    ): Boolean {
        if (!tabExists || selectedTabId != sourceTabId) return false
        val current = currentUrl.orEmpty()
        // Fragment-only navigation does not replace the file input document.
        return sourceUrl.isBlank() || current.isBlank() ||
            sourceUrl.substringBefore('#') == current.substringBefore('#')
    }

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
    val pageUrl: String,
    val request: BrowserFilePromptRequest,
) {
    private val delivery = BrowserFileChooserDelivery<android.net.Uri> {
        request.complete(it)
    }

    fun cancel() { delivery.complete(null) }

    fun complete(
        values: List<android.net.Uri>?,
        selectedTabId: Long,
        tabExists: Boolean,
        currentUrl: String?,
    ): Boolean {
        val valid = BrowserFileChooserPolicy.isCurrent(
            tabId, pageUrl, selectedTabId, tabExists, currentUrl,
        )
        delivery.complete(
            if (valid) BrowserFileChooserPolicy.distinctSelection(
                values, request.allowMultiple,
            ) else null,
        )
        return valid
    }
}
