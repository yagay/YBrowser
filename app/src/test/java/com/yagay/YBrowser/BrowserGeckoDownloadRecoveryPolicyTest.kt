package com.yagay.YBrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserGeckoDownloadRecoveryPolicyTest {
    @Test fun runningStreamIsNeverMarkedLostWhileRegistered() {
        assertFalse(BrowserGeckoDownloadRecoveryPolicy.isAbandoned(
            BrowserDownloadStatus.RUNNING, 1_000, 1_000_000, true,
        ))
    }

    @Test fun pendingEnqueueHasGracePeriod() {
        assertFalse(BrowserGeckoDownloadRecoveryPolicy.isAbandoned(
            BrowserDownloadStatus.PENDING, 1_000, 30_999, false,
        ))
        assertTrue(BrowserGeckoDownloadRecoveryPolicy.isAbandoned(
            BrowserDownloadStatus.PENDING, 1_000, 31_000, false,
        ))
    }

    @Test fun staleRunningDownloadBecomesFailedAfterProcessDeath() {
        assertTrue(BrowserGeckoDownloadRecoveryPolicy.isAbandoned(
            BrowserDownloadStatus.RUNNING, 1_000, 31_001, false,
        ))
    }

    @Test fun completedPausedAndFailedJobsAreUntouched() {
        for (state in listOf(
            BrowserDownloadStatus.SUCCESS, BrowserDownloadStatus.PAUSED,
            BrowserDownloadStatus.FAILED, BrowserDownloadStatus.UNKNOWN,
        )) {
            assertFalse(BrowserGeckoDownloadRecoveryPolicy.isAbandoned(
                state, 1_000, 1_000_000, false,
            ))
        }
    }
}
