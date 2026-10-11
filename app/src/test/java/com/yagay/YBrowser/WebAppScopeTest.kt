package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAppScopeTest {
    @Test fun acceptsOnlySameOriginAndDirectoryBoundary() {
        assertTrue(WebAppScope.contains("https://example.org/app/", "https://example.org/app/page?q=1"))
        assertFalse(WebAppScope.contains("https://example.org/app/", "https://example.org/application"))
        assertFalse(WebAppScope.contains("https://example.org/app/", "https://evil.example.org/app/"))
        assertFalse(WebAppScope.contains("https://example.org/app/", "http://example.org/app/"))
    }

    @Test fun protectsAgainstLookalikePathsAndDifferentPorts() {
        assertFalse(WebAppScope.contains("https://site.test/app", "https://site.test/app2"))
        assertFalse(WebAppScope.contains("https://site.test/app", "https://site.test:8443/app"))
        assertTrue(WebAppScope.contains("https://site.test/app", "https://site.test/app/dashboard"))
    }

    @Test fun normalizesTraversalAndDerivesDefaultScope() {
        assertFalse(WebAppScope.contains(
            "https://site.test/app/",
            "https://site.test/app/../admin",
        ))
        assertEquals("https://site.test/app/", WebAppScope.defaultScope("https://site.test/app/start"))
        assertNull(WebAppScope.defaultScope("http://site.test/app/start"))
    }

    @Test fun rejectsMaliciousOrOutOfOriginScope() {
        assertEquals(
            "https://site.test/app/",
            WebAppScope.validatedScope("https://site.test/app/start", "https://evil.test/"),
        )
    }
}
