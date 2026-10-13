package com.yagay.YBrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCredentialOriginPolicyTest {
    @Test fun onlyMatchesExactHost() {
        assertTrue(BrowserCredentialOriginPolicy.matches("https://example.com", "example.com"))
        assertFalse(BrowserCredentialOriginPolicy.matches("https://example.com", "login.example.com"))
        assertFalse(BrowserCredentialOriginPolicy.matches("https://login.example.com", "example.com"))
        assertFalse(BrowserCredentialOriginPolicy.matches("https://example.com", "www.example.com"))
        assertFalse(BrowserCredentialOriginPolicy.matches("https://foo.example.com", "bar.example.com"))
    }

    @Test fun respectsOriginAndPortsWhenProvided() {
        assertTrue(BrowserCredentialOriginPolicy.matches("https://example.com", "https://example.com"))
        assertFalse(BrowserCredentialOriginPolicy.matches("http://example.com", "https://example.com"))
        assertFalse(BrowserCredentialOriginPolicy.matches("https://example.com:8443", "https://example.com"))
        assertTrue(BrowserCredentialOriginPolicy.matches("https://example.com:8443", "https://example.com:8443"))
    }

    @Test fun failsClosedOnMalformedAndOpaqueUris() {
        assertFalse(BrowserCredentialOriginPolicy.matches("javascript:evil", "example.com"))
        assertFalse(BrowserCredentialOriginPolicy.matches("https://example.com", ""))
        assertFalse(BrowserCredentialOriginPolicy.matches("https://example.com", "example.com.attacker.org"))
    }
}
