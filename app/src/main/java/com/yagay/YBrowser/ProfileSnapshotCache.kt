package com.yagay.YBrowser

/**
 * Retains parsed, immutable snapshots for a small number of browser profiles.
 * Comparing the original preference string invalidates the cache even when
 * data changes via backup restore or another BrowserStore instance.
 */
internal class ProfileSnapshotCache<T>(private val maxProfiles: Int = 3) {
    init { require(maxProfiles > 0) }

    private data class Snapshot<T>(val raw: String?, val value: T)

    private val items = object : LinkedHashMap<String, Snapshot<T>>(4, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Snapshot<T>>?,
        ): Boolean = size > maxProfiles
    }

    @Synchronized
    fun get(profileId: String, raw: String?): T? {
        val cached = items[profileId] ?: return null
        if (cached.raw == raw) return cached.value
        items.remove(profileId)
        return null
    }

    @Synchronized
    fun put(profileId: String, raw: String?, value: T) {
        items[profileId] = Snapshot(raw, value)
    }

    @Synchronized
    fun clear(profileId: String) {
        items.remove(profileId)
    }
}
