package com.yagay.YBrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedHostMatcherTest {
    @Test fun blocksDomainAndSubdomains() {
        val blocked = setOf("example.com", "other.test")
        assertTrue(BlockedHostMatcher.matches("example.com", blocked))
        assertTrue(BlockedHostMatcher.matches("one.two.example.com", blocked))
        assertTrue(BlockedHostMatcher.matches("ONE.EXAMPLE.COM.", blocked))
        assertFalse(BlockedHostMatcher.matches("notexample.com", blocked))
        assertFalse(BlockedHostMatcher.matches("example.com.bad.test", blocked))
    }

    @Test fun worksWithListAndEmptyRules() {
        assertTrue(BlockedHostMatcher.matches("a.example.com", listOf("example.com")))
        assertFalse(BlockedHostMatcher.matches("example.com", emptySet()))
    }
}
