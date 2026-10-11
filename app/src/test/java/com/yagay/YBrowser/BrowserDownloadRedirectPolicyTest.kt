package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDownloadRedirectPolicyTest {
    @Test fun followsRelativeHttpsRedirectButNotDowngrade() {
        assertEquals(
            "https://example.com/files/result.zip",
            BrowserDownloadRedirectPolicy.resolve(
                "https://example.com/files/start.zip", "result.zip",
            ),
        )
        assertNull(BrowserDownloadRedirectPolicy.resolve(
            "https://example.com/start", "http://example.com/insecure",
        ))
        assertNull(BrowserDownloadRedirectPolicy.resolve(
            "https://example.com/start", "file:///sdcard/test",
        ))
    }

    @Test fun refusesCredentialUrlsAndInvalidPorts() {
        assertNull(BrowserDownloadRedirectPolicy.resolve(
            "https://example.com/start", "https://user:pass@example.com/path",
        ))
        assertNull(BrowserDownloadRedirectPolicy.resolve(
            "https://example.com/start", "https://example.com:65536/path",
        ))
    }

    @Test fun onlyForwardsCookiesToSameOrigin() {
        assertTrue(BrowserDownloadRedirectPolicy.canForwardCookie(
            "https://EXAMPLE.com/download", "https://example.com:443/file",
        ))
        assertFalse(BrowserDownloadRedirectPolicy.canForwardCookie(
            "https://example.com/file", "https://cdn.example.com/file",
        ))
        assertFalse(BrowserDownloadRedirectPolicy.canForwardCookie(
            "http://example.com/file", "https://example.com/file",
        ))
        assertFalse(BrowserDownloadRedirectPolicy.canForwardCookie(
            "https://example.com/file", "https://example.com:8443/file",
        ))
    }
}
