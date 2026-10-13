package com.yagay.YBrowser

/** Never replace a completed or still-running download during retry. */
internal object BrowserDownloadRetryPolicy {
    fun canRetrySystem(status: BrowserDownloadStatus): Boolean =
        status == BrowserDownloadStatus.FAILED ||
            status == BrowserDownloadStatus.PAUSED ||
            status == BrowserDownloadStatus.UNKNOWN
}
