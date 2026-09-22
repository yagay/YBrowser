package com.yagay.YBrowser

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.UiThread
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime

internal class GeckoWebAuthnActivityDelegate(
    private val launch: (PendingIntent) -> Unit,
) : GeckoRuntime.ActivityDelegate {
    private var pendingResult: GeckoResult<Intent>? = null
    private var pendingActivityResult: Pair<Int, Intent?>? = null
    private var hostResumed = false
    private var closed = false

    @UiThread
    override fun onStartActivityForResult(intent: PendingIntent): GeckoResult<Intent> {
        if (closed) {
            return GeckoResult.fromException(
                IllegalStateException("WebAuthn activity host is closed"),
            )
        }
        if (pendingResult != null) {
            return GeckoResult.fromException(
                IllegalStateException("WebAuthn activity is already pending"),
            )
        }

        val result = GeckoResult<Intent>()
        pendingResult = result
        runCatching { launch(intent) }
            .onFailure { failure ->
                if (pendingResult === result) {
                    pendingResult = null
                    pendingActivityResult = null
                    result.completeExceptionally(failure)
                }
            }
        return result
    }

    @UiThread
    fun onActivityResult(resultCode: Int, data: Intent?) {
        if (closed || pendingResult == null) return
        pendingActivityResult = resultCode to data
        deliverIfReady()
    }

    @UiThread
    fun onHostResumed() {
        if (closed) return
        hostResumed = true
        deliverIfReady()
    }

    @UiThread
    fun onHostPaused() {
        hostResumed = false
    }

    @UiThread
    fun close() {
        if (closed) return
        closed = true
        hostResumed = false
        pendingActivityResult = null
        pendingResult?.let { result ->
            pendingResult = null
            result.completeExceptionally(
                IllegalStateException("WebAuthn activity host was closed"),
            )
        }
    }

    private fun deliverIfReady() {
        if (!hostResumed || closed) return
        val result = pendingResult ?: return
        val activityResult = pendingActivityResult ?: return

        pendingResult = null
        pendingActivityResult = null
        if (activityResult.first == Activity.RESULT_OK) {
            result.complete(activityResult.second)
        } else {
            result.completeExceptionally(
                IllegalStateException("WebAuthn provider did not return success"),
            )
        }
    }
}
