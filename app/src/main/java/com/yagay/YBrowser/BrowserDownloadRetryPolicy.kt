package com.yagay.YBrowser

/**
 * Android DownloadManager may resume PAUSED jobs automatically once network
 * conditions change. UNKNOWN can mean a temporary query failure. Only a
 * terminal FAILED state is safe to replace with another system download.
 */
internal object BrowserDownloadRetryPolicy {
    fun canRetrySystem(status: BrowserDownloadStatus): Boolean =
        status == BrowserDownloadStatus.FAILED
}
