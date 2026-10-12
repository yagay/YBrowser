package com.yagay.YBrowser

/**
 * A browser menu can take focus away from the native HTML form. Keep the
 * last focused virtual field only for that one menu interaction, not across
 * navigations or unrelated requests.
 */
internal class BrowserAutofillFocusKeeper<T> {
    private var saved: T? = null

    fun snapshot(target: T?) {
        saved = target
    }

    fun consume(current: T?): T? {
        val target = current ?: saved
        saved = null
        return target
    }

    fun clear() {
        saved = null
    }
}
