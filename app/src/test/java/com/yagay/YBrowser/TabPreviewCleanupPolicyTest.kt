package com.yagay.YBrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabPreviewCleanupPolicyTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 10L * day

    @Test fun protectsInProgressAndRecentFiles() {
        assertFalse(TabPreviewCleanupPolicy.shouldDelete("4.webp.new", now - 2 * day, now, emptySet()))
        assertFalse(TabPreviewCleanupPolicy.shouldDelete("4.webp", now - 1000, now, emptySet()))
        assertFalse(TabPreviewCleanupPolicy.shouldDelete("4.webp.bak", now - 2 * day, now, setOf(4L)))
    }

    @Test fun deletesOnlyOldOrphanPreviews() {
        assertTrue(TabPreviewCleanupPolicy.shouldDelete("4.webp", now - 2 * day, now, emptySet()))
        assertTrue(TabPreviewCleanupPolicy.shouldDelete("4.webp.bak", now - 2 * day, now, emptySet()))
        assertFalse(TabPreviewCleanupPolicy.shouldDelete("5.webp", now - 2 * day, now, setOf(5L)))
        assertTrue(TabPreviewCleanupPolicy.shouldDelete("bad.tmp", now - 2 * day, now, emptySet()))
    }
}
