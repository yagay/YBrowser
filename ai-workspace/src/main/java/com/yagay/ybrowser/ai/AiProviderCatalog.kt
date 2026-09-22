package com.yagay.ybrowser.ai

import android.net.Uri

data class AiProvider(
    val id: String,
    val name: String,
    val homeUrl: String,
)

object AiProviderCatalog {
    val all = listOf(
        AiProvider("chatgpt", "ChatGPT", "https://chatgpt.com/"),
        AiProvider("gemini", "Gemini", "https://gemini.google.com/app"),
        AiProvider("claude", "Claude", "https://claude.ai/new"),
        AiProvider("grok", "Grok", "https://grok.com/"),
        AiProvider("deepseek", "DeepSeek", "https://chat.deepseek.com/"),
        AiProvider("qwen", "Qwen", "https://chat.qwen.ai/"),
    )

    fun byId(id: String?): AiProvider? =
        all.firstOrNull { it.id == id }

    fun fromUrl(url: String): AiProvider? {
        val host = runCatching { Uri.parse(url).host.orEmpty().lowercase() }
            .getOrDefault("")
        return when {
            host == "chatgpt.com" || host.endsWith(".chatgpt.com") -> byId("chatgpt")
            host == "gemini.google.com" -> byId("gemini")
            host == "claude.ai" || host.endsWith(".claude.ai") -> byId("claude")
            host == "grok.com" || host.endsWith(".grok.com") -> byId("grok")
            host == "chat.deepseek.com" -> byId("deepseek")
            host == "chat.qwen.ai" -> byId("qwen")
            else -> null
        }
    }
}
