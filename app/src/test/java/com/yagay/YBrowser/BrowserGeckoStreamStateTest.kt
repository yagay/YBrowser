package com.yagay.YBrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserGeckoStreamStateTest {
    private fun record(stream: Boolean) = BrowserDownloadRecord(
        id = -123L,
        url = "https://example.org/authenticated.zip",
        fileName = "authenticated.zip",
        mimeType = "application/zip",
        createdAt = 1L,
        backend = BrowserDownloadBackend.NATIVE,
        geckoStream = stream,
    )

    @Test fun streamedResponsesCannotAdvertiseUnsupportedPauseAndResume() {
        val native = record(true)
        assertFalse(
            BrowserDownloadState(native, BrowserDownloadStatus.RUNNING, 10, 100, 0, null).pausable
        )
        assertFalse(
            BrowserDownloadState(native, BrowserDownloadStatus.FAILED, 10, 100, 0, null).resumable
        )
    }

    @Test fun rangedHttpDownloadsRetainPauseAndResume() {
        val normal = record(false)
        assertTrue(
            BrowserDownloadState(normal, BrowserDownloadStatus.RUNNING, 10, 100, 0, null).pausable
        )
        assertTrue(
            BrowserDownloadState(normal, BrowserDownloadStatus.FAILED, 10, 100, 0, null).resumable
        )
    }
}
