package com.yagay.ybrowser.ai.web.provider

import com.yagay.ybrowser.ai.model.ProviderSpec
import com.yagay.ybrowser.ai.web.CapturedNetworkPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptWebProviderAdapterTest {
    private val provider =
        ProviderSpec(
            id = "chatgpt",
            name = "ChatGPT",
            shortName = "GPT",
            homeUrl = "https://chatgpt.com/",
            scriptAsset = "providers/chatgpt.js",
        )

    @Test
    fun pageConversationId_ignoresQueryAndNestedGptPath() {
        assertEquals(
            "abc-123",
            ChatGptWebProviderAdapter.pageConversationId(
                "https://chatgpt.com/c/abc-123?model=auto#anchor"
            ),
        )
        assertEquals(
            "nested-456",
            ChatGptWebProviderAdapter.pageConversationId(
                "https://chatgpt.com/g/g-demo/c/nested-456"
            ),
        )
    }

    @Test
    fun canonicalPluralHistory_isCompleteAndKeepsVisibleBranchOnly() {
        val body =
            """
            {
              "title":"Demo",
              "messages":[
                {
                  "id":"u1",
                  "message":{
                    "id":"u1",
                    "author":{"role":"user"},
                    "recipient":"all",
                    "content":{
                      "content_type":"text",
                      "parts":["hello"]
                    }
                  }
                },
                {
                  "id":"reasoning",
                  "message":{
                    "id":"reasoning",
                    "author":{"role":"assistant"},
                    "recipient":"all",
                    "channel":"analysis",
                    "content":{
                      "content_type":"text",
                      "parts":["hidden reasoning"]
                    }
                  }
                },
                {
                  "id":"a1",
                  "message":{
                    "id":"a1",
                    "author":{"role":"assistant"},
                    "recipient":"all",
                    "channel":"final",
                    "content":{
                      "content_type":"text",
                      "parts":["world"]
                    }
                  }
                },
                {
                  "id":"after-current",
                  "message":{
                    "id":"after-current",
                    "author":{"role":"assistant"},
                    "recipient":"all",
                    "channel":"final",
                    "content":{
                      "content_type":"text",
                      "parts":["must not leak"]
                    }
                  }
                }
              ],
              "current_node":"a1",
              "page_info":{
                "has_previous_page":false,
                "has_next_page":false
              }
            }
            """.trimIndent()

        val snapshot =
            ChatGptWebProviderAdapter.parseNetwork(
                provider = provider,
                capture =
                    CapturedNetworkPayload(
                        requestId = "canonical-1",
                        url =
                            "https://chatgpt.com/backend-api/conversations/abc-123?include_has_versions=true&num_turns=20",
                        method = "GET",
                        statusCode = 200,
                        contentType = "application/json",
                        body = body,
                        stream = false,
                        complete = true,
                        truncated = false,
                        canonical = true,
                        capturedAt = 1L,
                    ),
                pageUrl =
                    "https://chatgpt.com/c/abc-123",
            )

        assertNotNull(snapshot)
        snapshot!!
        assertTrue(snapshot.canonical)
        assertTrue(snapshot.complete)
        assertEquals("network-history", snapshot.source)
        assertEquals(
            listOf("u1", "a1"),
            snapshot.messages.map { it.id },
        )
        assertEquals(
            listOf("hello", "world"),
            snapshot.messages.map { it.text },
        )
    }

    @Test
    fun mismatchedConversationHistory_isRejected() {
        val snapshot =
            ChatGptWebProviderAdapter.parseNetwork(
                provider = provider,
                capture =
                    CapturedNetworkPayload(
                        requestId = "wrong-chat",
                        url =
                            "https://chatgpt.com/backend-api/conversations/other",
                        method = "GET",
                        statusCode = 200,
                        contentType = "application/json",
                        body =
                            """
                            {
                              "messages":[],
                              "current_node":"x",
                              "page_info":{
                                "has_previous_page":false,
                                "has_next_page":false
                              }
                            }
                            """.trimIndent(),
                        stream = false,
                        complete = true,
                        truncated = false,
                        canonical = true,
                        capturedAt = 1L,
                    ),
                pageUrl =
                    "https://chatgpt.com/c/abc-123",
            )

        assertEquals(null, snapshot)
    }
}
