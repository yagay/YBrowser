package com.yagay.ybrowser.ai.ui

import com.yagay.ybrowser.ai.web.WebRuntime
import com.yagay.ybrowser.ai.web.provider.ProductFinality
import com.yagay.ybrowser.ai.web.provider.ProductObservationAuthority
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseCompletionPolicyTest {
    @Test
    fun unchangedSnapshotIsNotFresh() {
        val baseline =
            WebRuntime.ResponseSnapshot(
                text = "old",
                key = "a",
                responseCount = 1,
                turnCount = 2,
                path = "/c/1",
            )

        assertFalse(
            ResponseCompletionPolicy.isFresh(
                baseline = baseline,
                current = baseline,
                sawGenerating = false,
            )
        )
    }

    @Test
    fun changedTextIsFresh() {
        val baseline =
            WebRuntime.ResponseSnapshot(
                text = "old",
                key = "a",
                responseCount = 1,
                turnCount = 2,
            )
        val current =
            baseline.copy(
                text = "new",
                key = "b",
                responseCount = 2,
                turnCount = 4,
            )

        assertTrue(
            ResponseCompletionPolicy.isFresh(
                baseline = baseline,
                current = current,
                sawGenerating = false,
            )
        )
    }

    @Test
    fun generationTransitionMakesFinalTextFresh() {
        val baseline =
            WebRuntime.ResponseSnapshot(
                text = "answer",
                key = "same",
            )
        val current =
            baseline.copy(
                state = "idle",
            )

        assertTrue(
            ResponseCompletionPolicy.isFresh(
                baseline = baseline,
                current = current,
                sawGenerating = true,
            )
        )
    }

    @Test
    fun blankCurrentTextIsNeverFresh() {
        assertFalse(
            ResponseCompletionPolicy.isFresh(
                baseline =
                    WebRuntime.ResponseSnapshot(
                        text = "old"
                    ),
                current =
                    WebRuntime.ResponseSnapshot(
                        text = ""
                    ),
                sawGenerating = true,
            )
        )
    }

    @Test
    fun provisionalConversationNeverCountsAsCanonicalFinality() {
        val snapshot =
            WebRuntime.ConversationSnapshot(
                authority =
                    ProductObservationAuthority.PROVISIONAL,
                finality =
                    ProductFinality.PROVISIONAL,
                messages =
                    listOf(
                        WebRuntime.PageConversationMessage(
                            id = "a",
                            role = "assistant",
                            text = "new",
                        )
                    ),
            )

        assertFalse(
            ResponseCompletionPolicy.isCanonicalFresh(
                baseline =
                    WebRuntime.ResponseSnapshot(
                        text = "old"
                    ),
                snapshot = snapshot,
                sawGenerating = true,
            )
        )
    }

    @Test
    fun canonicalReadbackCanFinalizeFreshAssistant() {
        val snapshot =
            WebRuntime.ConversationSnapshot(
                authority =
                    ProductObservationAuthority.CANONICAL,
                finality =
                    ProductFinality.CANONICAL_COMPLETE,
                messages =
                    listOf(
                        WebRuntime.PageConversationMessage(
                            id = "a",
                            role = "assistant",
                            text = "new",
                        )
                    ),
            )

        assertTrue(
            ResponseCompletionPolicy.isCanonicalFresh(
                baseline =
                    WebRuntime.ResponseSnapshot(
                        text = "old"
                    ),
                snapshot = snapshot,
                sawGenerating = false,
            )
        )
    }
}
