package com.yagay.ybrowser.ai.model

import java.util.UUID

enum class WindowViewMode {
    CHAT,
    WEB
}

/**
 * One AI UI tab.
 *
 * Project identity and web-page identity are intentionally separate:
 * - boundRepo/boundProject own the persistent project tab.
 * - boundUrl is only the project's current web-conversation binding.
 * - url is the live browser location and may change independently.
 *
 * A project tab must keep the same id/history while boundUrl changes.
 */
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
) {
    val projectKey: String?
        get() =
            boundRepo
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: boundProject
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

    val currentWebUrl: String?
        get() =
            boundUrl
                ?.trim()
                ?.takeIf { it.isNotBlank() }
}
