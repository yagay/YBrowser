package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserDownloadNamePolicyTest {
    @Test fun sanitizesServerProvidedFileNamesAndAvoidsExcessivelyLongNames() {
        assertEquals("safe.zip", BrowserDownloadNamePolicy.choose("../safe.zip", emptyList()))
        assertEquals("safe.zip", BrowserDownloadNamePolicy.choose("folder\\safe.zip", emptyList()))
        assertEquals("download", BrowserDownloadNamePolicy.choose("..", emptyList()))
        assertEquals("new_ file.txt", BrowserDownloadNamePolicy.choose("new: file.txt", emptyList()))
        assertEquals("safe_name", BrowserDownloadNamePolicy.choose("safe\nname", emptyList()))
        val long = BrowserDownloadNamePolicy.choose("报告".repeat(150) + ".pdf", emptyList())
        org.junit.Assert.assertTrue(long.toByteArray(Charsets.UTF_8).size <= 180)
        org.junit.Assert.assertTrue(long.endsWith(".pdf"))
    }

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
