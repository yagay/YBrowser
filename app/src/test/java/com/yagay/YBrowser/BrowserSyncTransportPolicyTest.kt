package com.yagay.YBrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSyncTransportPolicyTest {
    @Test fun acceptsCleanHttpsEndpoint() {
        assertTrue(
            BrowserSyncTransportPolicy.isSecureEndpoint(
                "https://example.org/backups/YBrowser.json",
            ),
        )
    }

    @Test fun blocksHttpAndEmbeddedCredentials() {
        assertFalse(BrowserSyncTransportPolicy.isSecureEndpoint("http://nas.lan/backup"))
        assertFalse(BrowserSyncTransportPolicy.isSecureEndpoint("https://user:pass@example.org/backup"))
        assertFalse(BrowserSyncTransportPolicy.isSecureEndpoint("ftp://example.org/backup"))
        assertFalse(BrowserSyncTransportPolicy.isSecureEndpoint("javascript:alert(1)"))
    }
}
