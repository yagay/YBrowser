package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDownloadIntegrityTest {
    private fun inspect(
        status: Int, offset: Long = 0, range: String? = null,
        length: Long = -1, previous: String? = null, current: String? = null,
    ) = BrowserDownloadIntegrity.inspect(
        status, offset, range, length, previous, current, null, null,
    )

    @Test fun acceptsMatchingPartialContent() {
        assertEquals(
            BrowserDownloadIntegrity.Decision.Accept(true, 1000),
            inspect(206, 500, "bytes 500-999/1000", 500, "\"v1\"", "\"v1\""),
        )
    }

    @Test fun refusesWrongStartAndMalformedRanges() {
        assertTrue(inspect(206, 500, "bytes 0-999/1000") is BrowserDownloadIntegrity.Decision.Reject)
        assertTrue(inspect(206, 500, "broken") is BrowserDownloadIntegrity.Decision.Reject)
        assertTrue(inspect(206, 500, "bytes 500-1000/1000") is BrowserDownloadIntegrity.Decision.Reject)
        assertTrue(inspect(206, 500, "bytes 500-999/1000", 200) is BrowserDownloadIntegrity.Decision.Reject)
    }

    @Test fun refusesChangedResource() {
        assertTrue(
            inspect(206, 500, "bytes 500-999/1000", 500, "\"old\"", "\"new\"")
                is BrowserDownloadIntegrity.Decision.Reject,
        )
    }

    @Test fun restartsWhenServerIgnoresRange() {
        assertEquals(
            BrowserDownloadIntegrity.Decision.Accept(false, 1000),
            inspect(200, offset = 500, length = 1000),
        )
    }

    @Test fun refusesOverflowingOrNegativeTransferLengths() {
        assertTrue(inspect(200, offset = -1, length = 100) is BrowserDownloadIntegrity.Decision.Reject)
        assertTrue(inspect(206, offset = 0, range = "bytes 0-9223372036854775807/*")
            is BrowserDownloadIntegrity.Decision.Reject)
        assertTrue(inspect(200, length = -2) is BrowserDownloadIntegrity.Decision.Reject)
    }

    @Test fun weakEtagUsesLastModified() {
        assertEquals(
            "Wed, 21 Oct 2015 07:28:00 GMT",
            BrowserDownloadIntegrity.resumeValidator(
                "W/\"weak\"", "Wed, 21 Oct 2015 07:28:00 GMT",
            ),
        )
    }
}
