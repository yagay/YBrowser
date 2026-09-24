package com.yagay.browsercore

import android.content.Context

class GeckoCoreSessionPool(context: Context) {
    private val appContext = context.applicationContext
    private val sessions = linkedMapOf<String, GeckoCoreSession>()

    @Synchronized
    fun acquire(
        key: String,
        initialUrl: String?,
        initialSessionState: String? = null,
        callbacks: GeckoCoreCallbacks,
        waitForRpcBeforeInitialLoad: Boolean = true,
    ): GeckoCoreSession {
        val current = sessions[key]
        if (current != null) {
            current.updateCallbacks(callbacks)
            return current
        }

        val created = GeckoCoreSession(
            context = appContext,
            initialUrl = initialUrl,
            initialSessionState = initialSessionState,
            waitForRpcBeforeInitialLoad =
                waitForRpcBeforeInitialLoad,
            callbacks = callbacks,
        )
        sessions[key] = created
        return created
    }

    @Synchronized
    fun get(key: String): GeckoCoreSession? = sessions[key]

    @Synchronized
    fun setAllActive(active: Boolean) {
        sessions.values.forEach { it.setActive(active) }
    }

    @Synchronized
    fun flushAllSessionStates() {
        sessions.values.forEach { it.flushSessionState() }
    }

    @Synchronized
    fun close(key: String) {
        sessions.remove(key)?.destroy()
    }

    @Synchronized
    fun closeAll() {
        sessions.values.forEach { it.destroy() }
        sessions.clear()
    }

    @Synchronized
    fun activeCount(): Int = sessions.size
}
