package com.yagay.ybrowser.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserAddressTest {
    @Test fun emptyGoesHome() = assertEquals(BrowserAddress.HOME, BrowserAddress.resolve("   "))
    @Test fun hostDefaultsToHttps() = assertEquals("https://example.org/path", BrowserAddress.resolve("example.org/path"))
    @Test fun existingHttpSurvives() = assertEquals("http://example.org", BrowserAddress.resolve(" http://example.org "))
    @Test fun queryEncoded() = assertEquals("https://duckduckgo.com/?q=hello+world", BrowserAddress.resolve("hello world"))
    @Test fun scriptIsSearch() = assertEquals("https://duckduckgo.com/?q=javascript%3Aalert%281%29", BrowserAddress.resolve("javascript:alert(1)"))
}
