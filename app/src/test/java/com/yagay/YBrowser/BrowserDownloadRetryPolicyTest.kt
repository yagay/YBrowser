package com.yagay.YBrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDownloadRetryPolicyTest {
    @Test fun onlyStoppedOrFailedSystemJobsCanBeReplaced() {
        assertTrue(BrowserDownloadRetryPolicy.canRetrySystem(BrowserDownloadStatus.FAILED))
        assertTrue(BrowserDownloadRetryPolicy.canRetrySystem(BrowserDownloadStatus.PAUSED))
        assertTrue(BrowserDownloadRetryPolicy.canRetrySystem(BrowserDownloadStatus.UNKNOWN))
        assertFalse(BrowserDownloadRetryPolicy.canRetrySystem(BrowserDownloadStatus.SUCCESS))
        assertFalse(BrowserDownloadRetryPolicy.canRetrySystem(BrowserDownloadStatus.RUNNING))
        assertFalse(BrowserDownloadRetryPolicy.canRetrySystem(BrowserDownloadStatus.PENDING))
    }
}
