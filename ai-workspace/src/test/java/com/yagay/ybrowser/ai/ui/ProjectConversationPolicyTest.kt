package com.yagay.ybrowser.ai.ui

import com.yagay.ybrowser.ai.model.ChatWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectConversationPolicyTest {
    @Test
    fun projectSources_keepOldChatsAndPutCurrentLast() {
        val window =
            ChatWindow(
                providerId = "chatgpt",
                url =
                    "https://chatgpt.com/c/current?model=auto",
                boundUrl =
                    "https://chatgpt.com/c/current?model=auto",
                conversationUrls =
                    listOf(
                        "https://chatgpt.com/c/old",
                        "https://chatgpt.com/c/current",
                        "https://chatgpt.com/c/old?foo=bar",
                    ),
                boundRepo = "yagay/YBrowser",
                boundProject = "YBrowser",
            )

        assertEquals(
            listOf(
                "https://chatgpt.com/c/old",
                "https://chatgpt.com/c/current?model=auto",
            ),
            ProjectConversationPolicy
                .projectConversationSources(
                    window
                ),
        )
    }

    @Test
    fun mergeConversationUrls_dedupesSameChatAcrossQueryChanges() {
        val window =
            ChatWindow(
                providerId = "chatgpt",
                boundUrl =
                    "https://chatgpt.com/c/abc?model=auto",
                conversationUrls =
                    listOf(
                        "https://chatgpt.com/c/old"
                    ),
            )

        val merged =
            ProjectConversationPolicy
                .mergeConversationUrls(
                    window,
                    "https://chatgpt.com/c/abc?temporary-chat=false",
                )

        assertEquals(2, merged.size)
        assertTrue(
            merged.any {
                it.contains("/c/old")
            }
        )
        assertTrue(
            merged.any {
                it.contains("/c/abc")
            }
        )
    }

    @Test
    fun sourceOwnership_isConversationBasedNotExactUrlBased() {
        val window =
            ChatWindow(
                providerId = "chatgpt",
                boundUrl =
                    "https://chatgpt.com/c/current",
                conversationUrls =
                    listOf(
                        "https://chatgpt.com/c/old"
                    ),
            )

        assertTrue(
            ProjectConversationPolicy
                .windowOwnsConversationSource(
                    window,
                    "https://chatgpt.com/c/old?model=auto",
                ),
        )
        assertFalse(
            ProjectConversationPolicy
                .windowOwnsConversationSource(
                    window,
                    "https://chatgpt.com/c/foreign",
                ),
        )
    }
}
