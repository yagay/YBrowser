package com.yagay.ybrowser.ai.ui

import com.yagay.ybrowser.ai.web.WebRuntime
import com.yagay.ybrowser.ai.web.provider.ProductFinality
import com.yagay.ybrowser.ai.web.provider.ProductObservationAuthority

internal object ResponseCompletionPolicy {
    fun canonicalAssistantText(
        snapshot: WebRuntime.ConversationSnapshot?,
    ): String? {
        if (snapshot == null) return null
        if (
            snapshot.authority !=
                ProductObservationAuthority.CANONICAL ||
            snapshot.finality !=
                ProductFinality.CANONICAL_COMPLETE
        ) {
            return null
        }
        return snapshot.messages
            .lastOrNull {
                it.role == "assistant" &&
                    it.text.isNotBlank()
            }
            ?.text
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
    }

    fun isCanonicalFresh(
        baseline: WebRuntime.ResponseSnapshot,
        snapshot: WebRuntime.ConversationSnapshot?,
        sawGenerating: Boolean,
    ): Boolean {
        val text =
            canonicalAssistantText(snapshot)
                ?: return false
        return text != baseline.text.trim() ||
            sawGenerating
    }

    fun isFresh(
        baseline: WebRuntime.ResponseSnapshot,
        current: WebRuntime.ResponseSnapshot,
        sawGenerating: Boolean,
    ): Boolean {
        if (current.text.isBlank()) return false

        val structuralChange =
            (current.key.isNotBlank() && current.key != baseline.key) ||
                current.responseCount > baseline.responseCount ||
                current.turnCount > baseline.turnCount ||
                (
                    baseline.path.isNotBlank() &&
                        current.path.isNotBlank() &&
                        current.path != baseline.path
                    )

        val textChange =
            current.text.isNotBlank() &&
                current.text != baseline.text

        return structuralChange || textChange || sawGenerating
    }
}
