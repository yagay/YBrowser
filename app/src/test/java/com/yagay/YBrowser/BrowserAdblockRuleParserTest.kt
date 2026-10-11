package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAdblockRuleParserTest {
    @Test fun preservesHostRulesAndExceptions() {
        val result = BrowserAdblockRuleParser.parse(
            "||ads.example.org^\n@@||trusted.example.org^\n0.0.0.0 tracking.example.net",
            20, 20,
        )
        assertEquals(listOf("ads.example.org", "tracking.example.net"), result.blocked)
        assertEquals(listOf("trusted.example.org"), result.exceptions)
    }

    @Test fun doesNotOverblockConditionalAbpRules() {
        val result = BrowserAdblockRuleParser.parse(
            "||app.example.org^\$third-party\n" +
                "||images.example.org^\$image\n" +
                "||path.example.org/ads\n" +
                "||safe.example.org^\n",
            20, 20,
        )
        assertEquals(listOf("safe.example.org"), result.blocked)
    }

    @Test fun keepsCosmeticAndExceptionSelectors() {
        val result = BrowserAdblockRuleParser.parse(
            "##.global-ad\nexample.org##.sponsor\n" +
                "example.org#@#.sponsor\nexample.org#?#.unsupported",
            20, 20,
        )
        assertEquals(
            listOf("##.global-ad", "example.org##.sponsor", "example.org#@#.sponsor"),
            result.cosmetics,
        )
    }

    @Test fun rejectsUnsafeCssAndNegatedDomains() {
        assertFalse(BrowserAdblockRuleParser.isValidCosmeticRule("##a{color:red}"))
        assertFalse(BrowserAdblockRuleParser.isValidCosmeticRule("##.a; body"))
        assertFalse(BrowserAdblockRuleParser.isValidCosmeticRule("~example.com##.ad"))
        assertTrue(BrowserAdblockRuleParser.isValidCosmeticRule("example.org##div.banner > img"))
    }

    @Test fun boundsRuleCounts() {
        val result = BrowserAdblockRuleParser.parse(
            "||a.example.org^\n||b.example.org^\n##.one\n##.two",
            1, 1,
        )
        assertEquals(1, result.blocked.size)
        assertEquals(1, result.cosmetics.size)
    }
}
