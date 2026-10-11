package com.yagay.YBrowser

/**
 * Stable LazyColumn keys for navigation history need to be unique even when
 * separate transitions are recorded within the same millisecond.
 */
internal object NavigationTrailIdPolicy {
    fun nextId(timestamp: Long, existingIds: Collection<Long>): Long {
        val used = if (existingIds is Set<Long>) existingIds else existingIds.toHashSet()
        var candidate = timestamp.coerceAtLeast(1L)
        while (candidate in used && candidate < Long.MAX_VALUE) candidate++
        if (candidate in used) {
            candidate = 1L
            while (candidate in used && candidate < Long.MAX_VALUE) candidate++
        }
        check(candidate !in used) { "Navigation trail ID space exhausted" }
        return candidate
    }
}
