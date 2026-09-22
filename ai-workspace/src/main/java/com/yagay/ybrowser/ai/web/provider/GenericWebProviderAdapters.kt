package com.yagay.ybrowser.ai.web.provider

import com.yagay.ybrowser.ai.model.ProviderSpec
import com.yagay.ybrowser.ai.web.CapturedNetworkPayload
import com.yagay.ybrowser.ai.web.WebRuntime
import org.json.JSONArray
import org.json.JSONObject

internal open class GenericWebProviderAdapter(
    override val providerId: String,
    override val captureUrlHints: List<String>,
) : WebProviderAdapter {
    override fun parseNetwork(
        provider: ProviderSpec,
        capture: CapturedNetworkPayload,
        pageUrl: String,
    ): WebRuntime.ConversationSnapshot? {
        if (capture.body.isBlank()) return null

        val documents = JsonNetworkParsing.parseDocuments(capture.body)
        if (documents.isEmpty()) return null

        var title = ""
        val ordered = LinkedHashMap<String, WebRuntime.PageConversationMessage>()

        documents.forEach { value ->
            when (value) {
                is JSONObject -> {
                    if (title.isBlank()) title = JsonNetworkParsing.findTitle(value)

                    val explicit = JsonNetworkParsing.extractExplicitMessageArrays(value)
                    explicit.forEach { JsonNetworkParsing.putMessage(ordered, it) }

                    JsonNetworkParsing.collectMessages(value)
                        .forEach { JsonNetworkParsing.putMessage(ordered, it) }
                }

                is JSONArray -> {
                    val collected = JsonNetworkParsing.collectMessages(value)
                    collected.forEach { JsonNetworkParsing.putMessage(ordered, it) }
                }
            }
        }

        val messages = ordered.values.toList()
        if (messages.isEmpty()) return null

        val source = when {
            capture.stream || capture.contentType.contains(
                "text/event-stream",
                ignoreCase = true,
            ) -> "network-stream"
            else -> "network-delta"
        }

        return WebRuntime.ConversationSnapshot(
            url = pageUrl,
            title = title,
            candidateCount = messages.size,
            source = source,
            complete = false,
            messages = messages,
        )
    }
}

internal object ClaudeWebProviderAdapter : GenericWebProviderAdapter(
    providerId = "claude",
    captureUrlHints = listOf(
        "/api/",
        "/chat_conversations",
        "/append_message",
    ),
)

internal object GeminiWebProviderAdapter : GenericWebProviderAdapter(
    providerId = "gemini",
    captureUrlHints = listOf(
        "/_/BardChatUi/",
        "/batchexecute",
    ),
)

internal object GrokWebProviderAdapter : GenericWebProviderAdapter(
    providerId = "grok",
    captureUrlHints = listOf(
        "/rest/app-chat/",
        "/api/",
    ),
)

internal object DeepSeekWebProviderAdapter : GenericWebProviderAdapter(
    providerId = "deepseek",
    captureUrlHints = listOf(
        "/api/v0/chat",
        "/api/v0/chat_session",
    ),
)

internal object QwenWebProviderAdapter : GenericWebProviderAdapter(
    providerId = "qwen",
    captureUrlHints = listOf(
        "/api/v2/chats",
        "/api/v1/",
        "/api/chat",
    ),
)
