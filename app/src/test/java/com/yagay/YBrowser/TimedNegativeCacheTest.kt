package com.yagay.YBrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimedNegativeCacheTest {
    @Test fun retriesAfterExpiryAndRemovesExpiredFailure() {
        val cache = TimedNegativeCache(2, 1000L)
        cache.recordFailure("a", 10L)
        assertTrue(cache.isSuppressed("a", 1009L))
        assertFalse(cache.isSuppressed("a", 1010L))
        assertFalse(cache.isSuppressed("a", 1011L))
    }

    @Test fun capsTheNumberOfFailedOrigins() {
        val cache = TimedNegativeCache(2, 1000L)
        cache.recordFailure("a", 0L)
        cache.recordFailure("b", 1L)
        cache.recordFailure("c", 2L)
        assertFalse(cache.isSuppressed("a", 3L))
        assertTrue(cache.isSuppressed("b", 3L))
        assertTrue(cache.isSuppressed("c", 3L))
        cache.clear("b")
        assertFalse(cache.isSuppressed("b", 3L))
    }

    @Test fun handlesNearOverflowTimestamps() {
        val cache = TimedNegativeCache(1, 1000L)
        cache.recordFailure("a", Long.MAX_VALUE - 100)
        assertTrue(cache.isSuppressed("a", Long.MAX_VALUE - 1))
        assertFalse(cache.isSuppressed("a", Long.MAX_VALUE))
    }
}
