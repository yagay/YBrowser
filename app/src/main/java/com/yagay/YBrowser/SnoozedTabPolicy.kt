package com.yagay.YBrowser

/** Pure scheduling rules, also exercised in JVM tests without Android. */
internal object SnoozedTabPolicy {
    const val MAX_ITEMS = 200
    fun nextId(existing: Collection<Long>, now: Long): Long {
        val candidate = now.coerceAtLeast(1L)
        val last = existing.maxOrNull() ?: 0L
        if (last < candidate && candidate !in existing) return candidate
        require(last < Long.MAX_VALUE) { "No snooze identifiers available" }
        return last + 1L
    }
}
