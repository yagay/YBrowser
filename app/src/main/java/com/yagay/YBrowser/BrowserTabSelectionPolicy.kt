package com.yagay.YBrowser

/**
 * Private tabs are intentionally not persisted. Keep the selected-tab id
 * consistent with the persisted list, rather than storing a private id that
 * cannot be restored after process recreation.
 */
internal object BrowserTabSelectionPolicy {
    fun persistedSelection(persistedTabIds: List<Long>, selectedId: Long): Long {
        if (selectedId in persistedTabIds) return selectedId
        return persistedTabIds.firstOrNull() ?: 1L
    }
}
