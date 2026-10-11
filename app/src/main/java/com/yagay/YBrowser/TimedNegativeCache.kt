package com.yagay.YBrowser

/**
 * Bounded time-based failure cache. A transient DNS/network outage must not
 * permanently suppress the favicon for every site visited during the outage.
 */
internal class TimedNegativeCache(
    private val maxEntries: Int,
    private val ttlMillis: Long,
) {
    init {
        require(maxEntries > 0)
        require(ttlMillis > 0)
    }

    // In insertion order: recently failing hosts evict the oldest failures.
    private val expiry = LinkedHashMap<String, Long>()

    @Synchronized
    fun isSuppressed(key: String, now: Long): Boolean {
        val until = expiry[key] ?: return false
        if (now < until) return true
        expiry.remove(key)
        return false
    }

    @Synchronized
    fun recordFailure(key: String, now: Long) {
        val until = if (now > Long.MAX_VALUE - ttlMillis) Long.MAX_VALUE
                    else now + ttlMillis
        expiry.remove(key)
        expiry[key] = until
        while (expiry.size > maxEntries) {
            val oldest = expiry.keys.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    @Synchronized
    fun clear(key: String) {
        expiry.remove(key)
    }
}
