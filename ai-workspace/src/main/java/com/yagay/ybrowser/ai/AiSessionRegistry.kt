package com.yagay.ybrowser.ai

import android.content.Context
import com.yagay.browsercore.GeckoCoreCallbacks
import com.yagay.browsercore.GeckoCoreSession
import com.yagay.browsercore.GeckoCoreSessionPool

object AiSessionRegistry {
    @Volatile
    private var pool: GeckoCoreSessionPool? = null

    private fun pool(context: Context): GeckoCoreSessionPool {
        pool?.let { return it }
        return synchronized(this) {
            pool ?: GeckoCoreSessionPool(context.applicationContext).also {
                pool = it
            }
        }
    }

    fun acquire(
        context: Context,
        window: AiWorkspaceWindow,
        callbacks: GeckoCoreCallbacks,
    ): GeckoCoreSession =
        pool(context).acquire(
            key = sessionKey(window.id),
            hostContext = context,
            initialUrl = window.currentUrl.ifBlank { window.entryUrl },
            callbacks = callbacks,
        )

    fun get(
        context: Context,
        windowId: String,
    ): GeckoCoreSession? =
        pool(context).get(sessionKey(windowId))

    fun detach(
        context: Context,
        windowId: String,
    ) {
        pool(context).detach(sessionKey(windowId))
    }

    fun close(
        context: Context,
        windowId: String,
    ) {
        pool(context).close(sessionKey(windowId))
    }

    fun activeCount(context: Context): Int =
        pool(context).activeCount()

    private fun sessionKey(windowId: String): String =
        "ai-workspace:" + windowId
}
