package com.yagay.YBrowser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.view.autofill.AutofillManager

/** Uses the user's selected Android Autofill provider; never reads credentials. */
internal object BrowserSystemAutofill {
    fun openSettings(context: Context): Boolean {
        // Android exposes a public intent to select *this app as an Autofill
        // provider*, not to manage another provider selected by the user.
        // Many OEM Settings apps support this direct, undocumented action,
        // so try it first and fall back to standard Android Settings.
        val actions = listOf(
            "android.settings.AUTOFILL_SETTINGS",
            Settings.ACTION_SETTINGS,
        )
        for (action in actions) {
            val opened = runCatching {
                val intent = Intent(action).apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (opened) return true
        }
        return false
    }

    /**
     * Request Android autofill for the active web view. Individual HTML fields
     * are virtual children managed by WebView/GeckoView, not by this app.
     */
    fun request(view: View): Boolean {
        if (!view.isAttachedToWindow ||
            view.importantForAutofill != View.IMPORTANT_FOR_AUTOFILL_YES
        ) return false
        val manager = view.context.getSystemService(AutofillManager::class.java)
            ?: return false
        if (!manager.isEnabled) return false
        return runCatching {
            view.post {
                if (view.isAttachedToWindow &&
                    view.importantForAutofill == View.IMPORTANT_FOR_AUTOFILL_YES
                ) {
                    runCatching { manager.requestAutofill(view) }
                }
            }
            true
        }.getOrDefault(false)
    }
}
