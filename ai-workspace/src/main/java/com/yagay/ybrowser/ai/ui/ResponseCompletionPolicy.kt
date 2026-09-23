package com.yagay.ybrowser.ai.ui

import com.yagay.ybrowser.ai.web.WebRuntime

internal object ResponseCompletionPolicy {
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
