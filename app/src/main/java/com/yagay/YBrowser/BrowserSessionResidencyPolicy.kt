package com.yagay.YBrowser

/**
 * Pure memory-residency policy. A protected tab may make the effective limit
 * temporarily higher, preventing active chats and pinned sessions from being
 * interrupted just to satisfy a numeric cache target.
 */
internal data class ResidentBrowserTab(
    val id: Long,
    val lastAccessedAt: Long,
    val protected: Boolean,
)

internal object BrowserSessionResidencyPolicy {
    fun evict(
        candidates: List<ResidentBrowserTab>,
        maxResident: Int,
    ): List<Long> {
        val excess = (candidates.size - maxResident.coerceAtLeast(1)).coerceAtLeast(0)
        if (excess == 0) return emptyList()
        return candidates.asSequence()
            .filterNot { it.protected }
            .sortedWith(compareBy<ResidentBrowserTab> { it.lastAccessedAt }.thenBy { it.id })
            .take(excess)
            .map { it.id }
            .toList()
    }
}
