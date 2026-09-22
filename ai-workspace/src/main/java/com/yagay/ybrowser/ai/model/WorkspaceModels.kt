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
    val boundUrl: String? = null,
    val boundRepo: String? = null,
    val boundProject: String? = null,
    val viewMode: WindowViewMode = WindowViewMode.CHAT,
    val createdAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long = System.currentTimeMillis(),
    val unread: Boolean = false,
    val generating: Boolean = false
)
