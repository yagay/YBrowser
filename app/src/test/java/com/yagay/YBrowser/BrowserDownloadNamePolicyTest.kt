package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserDownloadNamePolicyTest {
    @Test fun leavesUnusedNameUnchanged() {
        assertEquals("file.zip", BrowserDownloadNamePolicy.choose("file.zip", listOf("other.zip")))
    }

    @Test fun retainsOriginalAndFindsNextAvailableName() {
        assertEquals(
            "file (3).zip",
            BrowserDownloadNamePolicy.choose("file.zip", listOf("file.zip", "FILE (2).ZIP")),
        )
    }

    @Test fun supportsNamesWithoutExtensionsAndHiddenFiles() {
        assertEquals("README (2)", BrowserDownloadNamePolicy.choose("README", listOf("README")))
        assertEquals(".config (2)", BrowserDownloadNamePolicy.choose(".config", listOf(".config")))
    }
}
