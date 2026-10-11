package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BrowserWebNotificationIdsTest {
    @Test fun distinctUntaggedNotificationsDoNotOverwrite() {
        val ids = BrowserWebNotificationIds()
        val first = Any()
        val second = Any()
        assertNotEquals(ids.id("https://site.test", null, first),
            ids.id("https://site.test", null, second))
        assertEquals(ids.id("https://site.test", null, first),
            ids.id("https://site.test", null, first))
    }

    @Test fun taggedNotificationsDeliberatelyReplace() {
        val ids = BrowserWebNotificationIds()
        assertEquals(
            ids.id("https://site.test", "status", Any()),
            ids.id("https://site.test", "status", Any()),
        )
        assertNotEquals(
            ids.id("https://site.test", "status", Any()),
            ids.id("https://other.test", "status", Any()),
        )
    }
}
