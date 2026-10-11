package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserSessionResidencyPolicyTest {
    @Test fun evictsOldestUnprotectedTabs() {
        val tabs = listOf(
            ResidentBrowserTab(4L, 400L, false),
            ResidentBrowserTab(2L, 200L, false),
            ResidentBrowserTab(1L, 100L, false),
            ResidentBrowserTab(3L, 300L, false),
        )
        assertEquals(listOf(1L, 2L), BrowserSessionResidencyPolicy.evict(tabs, 2))
    }

    @Test fun neverEvictsPinnedActiveOrLoadingTabs() {
        val tabs = listOf(
            ResidentBrowserTab(1L, 100L, true),
            ResidentBrowserTab(2L, 200L, true),
            ResidentBrowserTab(3L, 300L, false),
        )
        assertEquals(listOf(3L), BrowserSessionResidencyPolicy.evict(tabs, 1))
    }

    @Test fun doesNotEvictWithinLimit() {
        assertEquals(
            emptyList<Long>(),
            BrowserSessionResidencyPolicy.evict(
                listOf(ResidentBrowserTab(1L, 1L, false)), 12,
            ),
        )
    }
}
