package com.yagay.YBrowser

/**
 * Gives each native renderer a unique callback generation.
 *
 * Evicted sessions may still flush Gecko navigation state, but a closed or
 * replaced renderer must never publish page state into its successor.
 */
internal class TabCallbackOwnership {
    private val active = mutableMapOf<Long, Any>()

    @Synchronized
    fun begin(tabId: Long): Any = Any().also { active[tabId] = it }

    @Synchronized
    fun isCurrent(tabId: Long, token: Any): Boolean = active[tabId] === token

    /**
     * Only a current, resident renderer owned by a live host can request UI.
     * An evicted renderer may still flush Gecko state, never launch prompts.
     */
    @Synchronized
    fun mayDispatchUi(
        tabId: Long,
        token: Any,
        residentToken: Any?,
        initializing: Boolean,
        hostAttached: Boolean,
    ): Boolean =
        hostAttached && active[tabId] === token &&
            (initializing || residentToken === token)

    @Synchronized
    fun retire(tabId: Long, preserveSessionState: Boolean) {
        if (!preserveSessionState) active.remove(tabId)
    }

    @Synchronized
    fun clear() = active.clear()
}
