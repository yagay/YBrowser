package com.yagay.ybrowser.ai.web

import com.yagay.ybrowser.ai.model.AttachmentMeta

class WebRuntime {
    data class AttachmentAttachResult(
        val attachedCount: Int,
        val names: List<String>,
        val failure: String? = null,
    )

    data class PageConversationMessage(
        val id: String,
        val role: String,
        val text: String,
        val attachments: List<AttachmentMeta> = emptyList(),
    )

    data class ConversationSnapshot(
        val url: String = "",
        val title: String = "",
        val candidateCount: Int = -1,
        val error: String = "",
        val source: String = "dom",
        val complete: Boolean = false,
        val messages: List<PageConversationMessage> = emptyList(),
        val visibleMessages: List<PageConversationMessage> = emptyList(),
    )

    data class ResponseSnapshot(
        val text: String = "",
        val key: String = "",
        val source: String = "none",
        val responseCount: Int = 0,
        val turnCount: Int = 0,
        val state: String = "idle",
        val reason: String = "",
        val path: String = "",
        val quietMs: Long = -1L,
    ) {
        val isGenerating: Boolean
            get() = state == "generating" || state == "queued" || state == "uploading"
    }
}
