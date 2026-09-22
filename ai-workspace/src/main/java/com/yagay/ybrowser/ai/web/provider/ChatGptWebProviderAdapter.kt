package com.yagay.ybrowser.ai.web.provider

import com.yagay.ybrowser.ai.model.ProviderSpec
import com.yagay.ybrowser.ai.web.CapturedNetworkPayload
import com.yagay.ybrowser.ai.web.WebRuntime
import org.json.JSONObject

internal object ChatGptWebProviderAdapter : WebProviderAdapter {
    override val providerId: String = "chatgpt"

    override val captureUrlHints: List<String> = listOf(
        "/backend-api/conversation",
        "/backend-api/f/conversation",
    )

    override fun parseNetwork(
        provider: ProviderSpec,
        capture: CapturedNetworkPayload,
        pageUrl: String,
    ): WebRuntime.ConversationSnapshot? {
        if (capture.body.isBlank()) return null

        val documents = JsonNetworkParsing.parseDocuments(capture.body)
        if (documents.isEmpty()) return null

        var title = ""
        var hasFullHistory = false
        val ordered = LinkedHashMap<String, WebRuntime.PageConversationMessage>()

        documents.forEach { value ->
            if (value is JSONObject) {
                if (title.isBlank()) title = JsonNetworkParsing.findTitle(value)

                val history = extractHistory(value)
                if (history.isNotEmpty()) {
                    hasFullHistory = true
                    history.forEach { JsonNetworkParsing.putMessage(ordered, it) }
                }

                JsonNetworkParsing.extractExplicitMessageArrays(value)
                    .forEach { JsonNetworkParsing.putMessage(ordered, it) }

                JsonNetworkParsing.collectMessages(value)
                    .forEach { JsonNetworkParsing.putMessage(ordered, it) }
            }
        }

        val messages = ordered.values.toList()
        if (messages.isEmpty()) return null

        val source = when {
            hasFullHistory -> "network-history"
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
            complete =
                source == "network-history" &&
                    capture.complete &&
                    !capture.truncated,
            messages = messages,
        )
    }

    private fun extractHistory(
        root: JSONObject,
    ): List<WebRuntime.PageConversationMessage> {
        val conversation = when {
            root.optJSONObject("mapping") != null -> root
            root.optJSONObject("conversation")
                ?.optJSONObject("mapping") != null ->
                root.optJSONObject("conversation")

            root.optJSONObject("data")
                ?.optJSONObject("mapping") != null ->
                root.optJSONObject("data")

            else -> null
        } ?: return emptyList()

        val mapping = conversation.optJSONObject("mapping") ?: return emptyList()
        var current = conversation.optString("current_node")

        if (current.isBlank() || mapping.optJSONObject(current) == null) {
            val parentIds = mutableSetOf<String>()
            val ids = mutableListOf<String>()
            val keys = mapping.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                ids += id
                mapping.optJSONObject(id)
                    ?.optString("parent")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(parentIds::add)
            }
            current = ids.lastOrNull { it !in parentIds }.orEmpty()
        }

        if (current.isBlank()) return emptyList()

        val reversed = mutableListOf<WebRuntime.PageConversationMessage>()
        val visited = mutableSetOf<String>()
        while (current.isNotBlank() && visited.add(current)) {
            val node = mapping.optJSONObject(current) ?: break
            node.optJSONObject("message")
                ?.let(JsonNetworkParsing::parseMessage)
                ?.let(reversed::add)
            current = node.optString("parent")
        }
        return reversed.asReversed()
    }
}
