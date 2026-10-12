package com.yagay.YBrowser

/** Unified privacy guard for Android Autofill on both native web engines. */
internal object BrowserAutofillPolicy {
    fun enabled(settingEnabled: Boolean, privateMode: Boolean): Boolean =
        settingEnabled && !privateMode
}
