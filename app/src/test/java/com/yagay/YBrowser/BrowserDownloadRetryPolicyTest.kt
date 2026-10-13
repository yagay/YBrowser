package com.yagay.YBrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDownloadRetryPolicyTest {
    @Test fun onlyTerminallyFailedSystemJobsCanBeReplaced() {
        assertTrue(BrowserDownloadRetryPolicy.canRetrySystem(BrowserDownloadStatus.FAILED))
        for (status in listOf(
            BrowserDownloadStatus.PAUSED,
            BrowserDownloadStatus.UNKNOWN,
            BrowserDownloadStatus.SUCCESS,
            BrowserDownloadStatus.RUNNING,
            BrowserDownloadStatus.PENDING,
        )) assertFalse("Unexpected retry state: $status", BrowserDownloadRetryPolicy.canRetrySystem(status))
    }
}
