package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserProfileIdPolicyTest {
    @Test fun generatesSafeDistinctIdentifiers() {
        val first = BrowserProfileIdPolicy.next(emptySet())
        val next = BrowserProfileIdPolicy.next(setOf(first))
        assertTrue(Regex("p_[a-f0-9]{32}").matches(first))
        assertNotEquals(first, next)
    }

    @Test fun retriesRejectedAndDuplicateCandidates() {
        val used = "p_" + "1".repeat(32)
        val available = "p_" + "2".repeat(32)
        val values = listOf("bad", used, available).iterator()
        assertEquals(available, BrowserProfileIdPolicy.next(setOf(used)) { values.next() })
    }

    @Test fun cannotReuseUnavailableId() {
        val fixed = "p_" + "0".repeat(32)
        assertThrows(IllegalStateException::class.java) {
            BrowserProfileIdPolicy.next(setOf(fixed)) { fixed }
        }
    }
}
