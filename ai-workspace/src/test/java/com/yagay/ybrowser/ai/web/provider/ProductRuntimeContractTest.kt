package com.yagay.ybrowser.ai.web.provider

import com.yagay.ybrowser.ai.model.ProviderSpec
import com.yagay.ybrowser.ai.web.CapturedNetworkPayload
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

        assertEquals(2, contract.schema)
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
        assertEquals(
            "product-conversation-id",
            contract.conversationIdentityAuthority,
        )
        assertEquals(
            "paginated-chunked-sha256-v2",
            contract.canonicalReadProtocol,
        )
        assertEquals(
            2,
            contract.canonicalReadTimeoutAttempts,
        )
        assertEquals(
            250L,
            contract.canonicalReadTimeoutRetryDelayMs,
        )
        assertTrue(contract.retainedConversationRuntime)
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
            "1d449bc22614c5bc27f1e1cb4bfaeed3794d5921",
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
            "abc",
            snapshot?.conversationId,
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
    @Test
    fun canonicalIdentityMismatchFailsClosed() {
        val provider = ProviderSpec(
            id = "chatgpt",
            name = "ChatGPT",
            shortName = "ChatGPT",
            homeUrl = "https://chatgpt.com/",
            scriptAsset = "providers/chatgpt.js",
        )
        val body =
            """
            {
              "conversation_id": "other",
              "current_node": "a",
              "mapping": {
                "a": {
                  "id": "a",
                  "parent": null,
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

        assertNull(snapshot)
    }

    @Test
    fun streamHandoffOwnsConversationIdentityOverTransientRoute() {
        val provider = ProviderSpec(
            id = "chatgpt",
            name = "ChatGPT",
            shortName = "ChatGPT",
            homeUrl = "https://chatgpt.com/",
            scriptAsset = "providers/chatgpt.js",
        )
        val body =
            """
            data: {"type":"stream_handoff","conversation_id":"real-conversation"}

            data: {"message":{"id":"assistant-1","author":{"role":"assistant"},"recipient":"all","content":{"content_type":"text","parts":["hello"]}}}
            """.trimIndent()

        val snapshot =
            ChatGptProductProvider.parseNetwork(
                provider = provider,
                capture = CapturedNetworkPayload(
                    requestId = "stream-1",
                    url =
                        "https://chatgpt.com/backend-api/f/conversation",
                    method = "POST",
                    statusCode = 200,
                    contentType = "text/event-stream",
                    body = body,
                    stream = true,
                    complete = false,
                    truncated = false,
                    capturedAt = 1L,
                ),
                pageUrl =
                    "https://chatgpt.com/c/WEB:temporary",
            )

        assertEquals(
            "real-conversation",
            snapshot?.conversationId,
        )
        assertEquals(
            listOf("hello"),
            snapshot?.messages?.map { it.text },
        )
    }
}
