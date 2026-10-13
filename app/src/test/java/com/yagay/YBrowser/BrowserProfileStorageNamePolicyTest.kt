package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserProfileStorageNamePolicyTest {
    @Test fun retainsExistingSafeNames() {
        assertEquals("p_" + "1".repeat(32), BrowserProfileStorageNamePolicy.name("p_" + "1".repeat(32)))
        assertEquals("existing-profile", BrowserProfileStorageNamePolicy.name("existing-profile"))
    }

    @Test fun distinctUnsafeNamesNeverCollapseThroughReplacementOrTruncation() {
        assertNotEquals(
            BrowserProfileStorageNamePolicy.name("work/a"),
            BrowserProfileStorageNamePolicy.name("work_a"),
        )
        assertNotEquals(
            BrowserProfileStorageNamePolicy.name("a".repeat(65) + "1"),
            BrowserProfileStorageNamePolicy.name("a".repeat(65) + "2"),
        )
        assertNotEquals(
            BrowserProfileStorageNamePolicy.name("__hashed_abc"),
            BrowserProfileStorageNamePolicy.name("other"),
        )
    }

    @Test fun rejectsDotDirectoriesAndPathSeparators() {
        for (value in listOf(".", "..", "/", "../", "\\")) {
            val result = BrowserProfileStorageNamePolicy.name(value)
            assertTrue(result.startsWith("__hashed_"))
            assertTrue('/' !in result && '\\' !in result)
        }
    }
}
