package com.yagay.browsercore

import android.content.Context

class GeckoCoreSessionPool(context: Context) {
    private val appContext = context.applicationContext
    private val sessions = linkedMapOf<String, GeckoCoreSession>()

    @Synchronized
    fun acquire(
        key: String,
        hostContext: Context,
        initialUrl: String?,
        initialSessionState: String? = null,
        callbacks: GeckoCoreCallbacks,
    ): GeckoCoreSession {
        val current = sessions[key]
        if (current != null) {
            current.attachHostContext(hostContext)
            current.updateCallbacks(callbacks)
            if (current.currentState.url.isBlank() && !initialUrl.isNullOrBlank()) {
                current.load(initialUrl)
            }
            return current
        }

        val created = GeckoCoreSession(
            context = hostContext,
            initialUrl = initialUrl,
            initialSessionState = initialSessionState,
            callbacks = callbacks,
        )
        sessions[key] = created
        return created
    }

    @Synchronized
    fun get(key: String): GeckoCoreSession? = sessions[key]

    @Synchronized
    fun detach(key: String) {
        sessions[key]?.detachHostContext()
    }

    @Synchronized
    fun setAllActive(active: Boolean) {
        sessions.values.forEach { it.setActive(active) }
    }

    @Synchronized
    fun flushAllSessionStates() {
        sessions.values.forEach { it.flushSessionState() }
    }

    @Synchronized
    fun detachAll() {
        sessions.values.forEach { it.detachHostContext() }
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
