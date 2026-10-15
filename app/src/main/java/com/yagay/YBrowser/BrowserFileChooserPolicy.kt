package com.yagay.YBrowser

import android.net.Uri
import java.util.concurrent.atomic.AtomicBoolean

/** Identity is an engine document generation, not the mutable address bar URL. */
internal data class BrowserFileChooserIdentity(
    val tabId: Long,
    val documentGeneration: Long,
)

internal data class BrowserFileChooserState(
    val selectedTabId: Long,
    val documentGeneration: Long?,
    val tabExists: Boolean,
    val isActivityStarted: Boolean,
)

internal object BrowserFileChooserPolicy {
    const val MAX_SELECTED_FILES = 100

    fun isCurrent(
        identity: BrowserFileChooserIdentity,
        state: BrowserFileChooserState,
    ): Boolean = state.tabExists && state.isActivityStarted &&
        state.selectedTabId == identity.tabId &&
        state.documentGeneration != null &&
        state.documentGeneration == identity.documentGeneration

    fun <T> distinctSelection(values: List<T>?, allowMultiple: Boolean): List<T>? {
        val limit = if (allowMultiple) MAX_SELECTED_FILES else 1
        return values.orEmpty().distinct().take(limit).takeIf { it.isNotEmpty() }
    }

    /** Mirrors Candy's MIME filtering, including common extension-only HTML accept values. */
    fun acceptsMimeType(
        actualType: String?,
        acceptTypes: List<String>,
        displayName: String? = null,
    ): Boolean {
        val accepted = acceptTypes.flatMap { it.split(',') }
            .map { it.trim().lowercase().substringBefore(';') }
            .filter { it.isNotBlank() }
        if (accepted.isEmpty() || "*" in accepted || "*/*" in accepted) return true
        val actual = actualType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        val name = displayName.orEmpty().lowercase()
        return accepted.any { expected ->
            when {
                expected.startsWith(".") ->
                    name.endsWith(expected) && name.length > expected.length
                expected.endsWith("/*") ->
                    actual.startsWith(expected.substringBefore('/') + "/")
                else -> actual == expected
            }
        }
    }

    fun safeUriString(raw: String): Boolean {
        if (raw.length !in 1..8192 || raw.any { it.isWhitespace() || it.isISOControl() }) {
            return false
        }
        return runCatching {
            val uri = java.net.URI(raw)
            uri.scheme.equals("content", ignoreCase = true) &&
                !uri.rawAuthority.isNullOrBlank() && uri.rawUserInfo == null
        }.getOrDefault(false)
    }
}

/** Both Android and Gecko can invoke error/close after a chooser result. */
internal class BrowserFileChooserDelivery<T>(
    private val callback: (List<T>?) -> Unit,
) {
    private val completed = AtomicBoolean(false)
    fun complete(values: List<T>?): Boolean {
        if (!completed.compareAndSet(false, true)) return false
        callback(values)
        return true
    }
}

internal class PendingBrowserFilePrompt(
    val identity: BrowserFileChooserIdentity,
    val request: BrowserFilePromptRequest,
) {
    val tabId: Long get() = identity.tabId
    private val delivery = BrowserFileChooserDelivery<Uri>(request.complete)

    fun cancel() { delivery.complete(null) }

    fun complete(
        values: List<Uri>?,
        state: BrowserFileChooserState,
    ): Boolean {
        val valid = BrowserFileChooserPolicy.isCurrent(identity, state)
        delivery.complete(
            if (valid) BrowserFileChooserPolicy.distinctSelection(
                values, request.allowMultiple,
            ) else null,
        )
        return valid
    }
}
