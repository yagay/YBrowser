package com.yagay.ybrowser.ai.model

import java.util.UUID

enum class MessageRole { USER, ASSISTANT, SYSTEM }

data class AttachmentMeta(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long = 0L,
    val uri: String? = null,
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<AttachmentMeta> = emptyList()
)

data class ProviderSpec(
    val id: String,
    val name: String,
    val shortName: String,
    val homeUrl: String,
    val scriptAsset: String
)

data class WindowSessionKey(
    val providerId: String,
    val windowId: String
) {
    val storageKey: String get() = "${providerId}_${windowId}"
    val webProfileName: String
        get() = "aihub_${providerId}_${windowId}"
            .lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
}
