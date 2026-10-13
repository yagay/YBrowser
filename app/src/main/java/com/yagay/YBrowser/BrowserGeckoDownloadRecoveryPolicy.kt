package com.yagay.YBrowser

/** A Gecko response stream cannot survive an Android process restart. */
internal object BrowserGeckoDownloadRecoveryPolicy {
    const val LOST_STREAM_REASON = -9
    private const val STARTUP_GRACE_MS = 30_000L

    fun isAbandoned(
        status: BrowserDownloadStatus?,
        createdAtMs: Long,
        nowMs: Long,
        streamRegistered: Boolean,
    ): Boolean =
        !streamRegistered &&
            (status == BrowserDownloadStatus.PENDING ||
                status == BrowserDownloadStatus.RUNNING) &&
            createdAtMs >= 0L &&
            nowMs >= createdAtMs &&
            nowMs - createdAtMs >= STARTUP_GRACE_MS
}
