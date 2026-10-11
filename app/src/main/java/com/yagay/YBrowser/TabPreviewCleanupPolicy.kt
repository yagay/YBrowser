package com.yagay.YBrowser

/** Prevent a snapshot load from racing an AtomicFile preview writer. */
internal object TabPreviewCleanupPolicy {
    private const val MIN_ORPHAN_AGE_MS = 24L * 60 * 60 * 1000

    fun shouldDelete(
        filename: String,
        lastModified: Long,
        now: Long,
        allowedTabIds: Set<Long>,
    ): Boolean {
        // .new files belong to an unfinished AtomicFile transaction.
        if (filename.endsWith(".new")) return false
        if (lastModified <= 0 || lastModified > now ||
            now - lastModified < MIN_ORPHAN_AGE_MS
        ) return false
        val base = filename.removeSuffix(".bak")
        val id = base.removeSuffix(".webp").toLongOrNull()
        return !base.endsWith(".webp") || id == null || id !in allowedTabIds
    }
}
