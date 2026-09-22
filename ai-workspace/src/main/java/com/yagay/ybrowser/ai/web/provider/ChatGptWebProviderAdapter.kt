package com.yagay.ybrowser.ai.web.provider

import com.yagay.ybrowser.ai.model.ProviderSpec
import com.yagay.ybrowser.ai.web.CapturedNetworkPayload
import com.yagay.ybrowser.ai.web.WebRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

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
            val root = when (value) {
                is JSONObject -> value
                else -> null
            }
            if (root != null && title.isBlank()) {
                title = JsonNetworkParsing.findTitle(root)
            }

            val history = extractHistory(value)
            if (history.isNotEmpty()) {
                hasFullHistory = true
                history.forEach { JsonNetworkParsing.putMessage(ordered, it) }
            }

            if (root != null) {
                JsonNetworkParsing.extractExplicitMessageArrays(root)
                    .forEach { JsonNetworkParsing.putMessage(ordered, it) }
            }

            JsonNetworkParsing.collectMessages(value)
                .forEach { JsonNetworkParsing.putMessage(ordered, it) }
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

    private fun findConversationObject(
        value: Any?,
        depth: Int = 0,
    ): JSONObject? {
        if (value == null || depth > 18) return null

        when (value) {
            is JSONObject -> {
                if (value.optJSONObject("mapping") != null) {
                    return value
                }

                val preferredKeys = listOf(
                    "conversation",
                    "data",
                    "result",
                    "payload",
                    "response",
                )
                preferredKeys.forEach { key ->
                    val child = value.opt(key)
                    findConversationObject(child, depth + 1)?.let { return it }
                }

                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key in preferredKeys) continue
                    val child = value.opt(key)
                    if (child is JSONObject || child is JSONArray) {
                        findConversationObject(child, depth + 1)?.let { return it }
                    } else if (child is String) {
                        parseEmbeddedJson(child)?.let { embedded ->
                            findConversationObject(embedded, depth + 1)?.let { return it }
                        }
                    }
                }
            }

            is JSONArray -> {
                for (index in 0 until value.length()) {
                    findConversationObject(value.opt(index), depth + 1)?.let { return it }
                }
            }

            is String -> {
                parseEmbeddedJson(value)?.let { embedded ->
                    return findConversationObject(embedded, depth + 1)
                }
            }
        }
        return null
    }

    private fun parseEmbeddedJson(raw: String): Any? {
        val value = raw.trim()
        if (
            value.length < 2 ||
            (value.first() != '{' && value.first() != '[')
        ) {
            return null
        }
        return runCatching {
            when (val parsed = JSONTokener(value).nextValue()) {
                is JSONObject -> parsed
                is JSONArray -> parsed
                else -> null
            }
        }.getOrNull()
    }

    private fun extractHistory(
        root: Any,
    ): List<WebRuntime.PageConversationMessage> {
        val conversation = findConversationObject(root) ?: return emptyList()
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
