package com.yagay.ybrowser.ai.web.provider

import com.yagay.ybrowser.ai.model.ProviderSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductRuntimeContractTest {
    @Test
    fun chatGptContractKeepsCwaFinalityAndRetryInvariants() {
        val contract =
            ChatGptProductContract.runtime

        assertEquals(1, contract.schema)
        assertEquals(
            "ordinary-chatgpt",
            contract.productSemantics,
        )
        assertEquals(
            "gecko-browser-owned",
            contract.transport,
        )
        assertEquals(
            ProductTransportSupportTier.PRODUCTION,
            contract.transportSupportTier,
        )
        assertFalse(contract.automaticWriteRetry)
        assertNull(contract.fallbackTransport)
        assertFalse(contract.legacyDirectWriteFallback)
        assertTrue(
            contract.ambiguousWriteRequiresReconciliation
        )
        assertFalse(
            contract.incrementalObservationIsCanonicalFinality
        )
    }

    @Test
    fun upstreamPinIsExplicit() {
        assertEquals("v0.3.0", CwaUpstream.RELEASE)
        assertEquals(
            "83a99e79817db2bba656944e0eedcfe1c661929c",
            CwaUpstream.MAIN_COMMIT,
        )
    }

    @Test
    fun canonicalReadIsPromotedOnlyAtProductBoundary() {
        val provider =
            ProviderSpec(
                id = "chatgpt",
                name = "ChatGPT",
                shortName = "ChatGPT",
                homeUrl = "https://chatgpt.com/",
                scriptAsset = "providers/chatgpt.js",
            )
        val body =
            """
            {
              "title": "Test",
              "current_node": "a",
              "mapping": {
                "u": {
                  "id": "u",
                  "parent": null,
                  "message": {
                    "id": "um",
                    "author": {"role": "user"},
                    "recipient": "all",
                    "content": {
                      "content_type": "text",
                      "parts": ["hello"]
                    }
                  }
                },
                "a": {
                  "id": "a",
                  "parent": "u",
                  "message": {
                    "id": "am",
                    "author": {"role": "assistant"},
                    "recipient": "all",
                    "content": {
                      "content_type": "text",
                      "parts": ["world"]
                    }
                  }
                }
              }
            }
            """.trimIndent()

        val snapshot =
            ChatGptProductProvider.parseCanonicalRead(
                provider = provider,
                body = body,
                endpoint =
                    "https://chatgpt.com/backend-api/conversation/abc",
                pageUrl =
                    "https://chatgpt.com/c/abc",
            )

        assertEquals(
            ProductObservationAuthority.CANONICAL,
            snapshot?.authority,
        )
        assertEquals(
            ProductFinality.CANONICAL_COMPLETE,
            snapshot?.finality,
        )
        assertEquals(
            listOf("hello", "world"),
            snapshot?.messages?.map {
                it.text
            },
        )
    }
}
