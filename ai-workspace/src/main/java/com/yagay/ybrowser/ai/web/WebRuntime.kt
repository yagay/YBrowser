package com.yagay.ybrowser.ai.web

import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.web.provider.ProductFinality
import com.yagay.ybrowser.ai.web.provider.ProductObservationAuthority

class WebRuntime {
    enum class SendState {
        CONFIRMED,
        AMBIGUOUS,
        FAILED,
    }

    data class SendResult(
        val state: SendState,
        val reason: String = "",
        val conversationId: String? = null,
        val userMessageId: String? = null,
    ) {
        val confirmed: Boolean
            get() = state == SendState.CONFIRMED

        val writeMayHaveBeenSubmitted: Boolean
            get() =
                state == SendState.CONFIRMED ||
                    state == SendState.AMBIGUOUS
    }

    data class AttachmentAttachResult(
        val attachedCount: Int,
        val names: List<String>,
        val failure: String? = null,
    )

    data class ResolvedResource(
        val uri: String,
        val mimeType: String,
        val sizeBytes: Long,
        val name: String,
    )

    data class PageConversationMessage(
        val id: String,
        val role: String,
        val text: String,
        val attachments: List<AttachmentMeta> = emptyList(),
    )

    data class ConversationSnapshot(
        val url: String = "",
        val conversationId: String? = null,
        val title: String = "",
        val candidateCount: Int = -1,
        val error: String = "",
        val source: String = "dom",
        val complete: Boolean = false,
        val authority: ProductObservationAuthority =
            ProductObservationAuthority.PROVISIONAL,
        val finality: ProductFinality =
            ProductFinality.PROVISIONAL,
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
