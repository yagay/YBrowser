package com.yagay.ybrowser.ai.model

import java.util.UUID

enum class WindowViewMode {
    CHAT,
    WEB
}

data class ChatWindow(
    val id: String = UUID.randomUUID().toString(),
    val providerId: String,
    val title: String = "新对话",
    val url: String? = null,
    // Current active web conversation for this project tab.
    val boundUrl: String? = null,
    // All web conversations that have contributed history to this project tab.
    // The tab identity is project/repository based, not URL based.
    val conversationUrls: List<String> = emptyList(),
    val boundRepo: String? = null,
    val boundProject: String? = null,
    val viewMode: WindowViewMode = WindowViewMode.CHAT,
    val createdAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long = System.currentTimeMillis(),
    val unread: Boolean = false,
    val generating: Boolean = false
)
