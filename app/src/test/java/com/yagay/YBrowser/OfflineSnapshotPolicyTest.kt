package com.yagay.YBrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSnapshotPolicyTest {
    @Test fun acceptsOrdinaryWebPages() {
        assertTrue(OfflineSnapshotPolicy.canSave("https://example.org/news?q=1", false))
        assertTrue(OfflineSnapshotPolicy.canSave("http://example.org/a", false))
    }

    @Test fun refusesPrivateAndNonWebDocuments() {
        assertFalse(OfflineSnapshotPolicy.canSave("https://example.org/", true))
        assertFalse(OfflineSnapshotPolicy.canSave("file:///sdcard/secret.html", false))
        assertFalse(OfflineSnapshotPolicy.canSave("javascript:alert(1)", false))
        assertFalse(OfflineSnapshotPolicy.canSave("about:blank", false))
    }

    @Test fun refusesCredentialsAndMalformedUrls() {
        assertFalse(OfflineSnapshotPolicy.canSave("https://user:pass@example.org", false))
        assertFalse(OfflineSnapshotPolicy.canSave("https://", false))
        assertFalse(OfflineSnapshotPolicy.canSave("not a url", false))
    }
}
