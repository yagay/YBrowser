package com.yagay.ybrowser.ai.web.provider

import com.yagay.ybrowser.ai.model.ProviderSpec
import com.yagay.ybrowser.ai.web.CapturedNetworkPayload
import com.yagay.ybrowser.ai.web.WebRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * ChatGPT web protocol decoder.
 *
 * This follows the same conservative principle used by mature ChatGPT-web
 * clients such as gpt4free: understand known protocol fields and ignore the
 * rest. Never recursively scrape arbitrary role/text pairs.
 */
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
        val history = LinkedHashMap<String, WebRuntime.PageConversationMessage>()
        val live = LinkedHashMap<String, WebRuntime.PageConversationMessage>()

        documents.forEach { value ->
            val root = value as? JSONObject
            if (root != null && title.isBlank()) {
                title = JsonNetworkParsing.findTitle(root)
            }

            extractHistory(value).forEach { put(history, it) }
            extractLiveMessage(value)?.let { put(live, it) }
        }

        val hasHistory = history.isNotEmpty()
        val messages = if (hasHistory) history.values.toList() else live.values.toList()
        if (messages.isEmpty()) return null

        val source = when {
            hasHistory -> "network-history"
            capture.stream || capture.contentType.contains("text/event-stream", ignoreCase = true) ->
                "network-stream"
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

    /**
     * Accept history only when ChatGPT itself supplied a mapping tree.
     * Walk exactly current_node -> parent; ignore side branches and tool nodes.
     */
    private fun extractHistory(root: Any): List<WebRuntime.PageConversationMessage> {
        val conversation = findConversationObject(root) ?: return emptyList()
        val mapping = conversation.optJSONObject("mapping") ?: return emptyList()
        var current = conversation.optString("current_node")

        if (current.isBlank() || mapping.optJSONObject(current) == null) {
            return emptyList()
        }

        val reversed = mutableListOf<WebRuntime.PageConversationMessage>()
        val visited = mutableSetOf<String>()
        while (current.isNotBlank() && visited.add(current)) {
            val node = mapping.optJSONObject(current) ?: break
            node.optJSONObject("message")
                ?.let(::parseVisibleMessage)
                ?.let(reversed::add)
            current = node.optString("parent")
        }
        return reversed.asReversed()
    }

    /**
     * Live SSE/delta handling is deliberately strict. Only known ChatGPT
     * message envelopes are accepted. Patch-only p/v/o events, metadata,
     * tools, reasoning and references are not guessed into visible text.
     */
    private fun extractLiveMessage(value: Any?): WebRuntime.PageConversationMessage? {
        val obj = value as? JSONObject ?: return null

        obj.optJSONObject("message")
            ?.let(::parseVisibleMessage)
            ?.let { return it }

        val payload = obj.optJSONObject("payload")
        payload?.optJSONObject("message")
            ?.let(::parseVisibleMessage)
            ?.let { return it }

        payload?.optJSONObject("update_content")
            ?.optJSONObject("message")
            ?.let(::parseVisibleMessage)
            ?.let { return it }

        obj.optJSONObject("data")
            ?.optJSONObject("message")
            ?.let(::parseVisibleMessage)
            ?.let { return it }

        return null
    }

    private fun parseVisibleMessage(
        message: JSONObject,
    ): WebRuntime.PageConversationMessage? {
        val role = message.optJSONObject("author")
            ?.optString("role")
            .orEmpty()
            .lowercase()
            .ifBlank { message.optString("role").lowercase() }

        if (role != "user" && role != "assistant") return null

        val recipient = message.optString("recipient")
        if (role == "assistant" && recipient.isNotBlank() && recipient != "all") {
            return null
        }

        val metadata = message.optJSONObject("metadata")
        if (
            metadata?.optBoolean("is_visually_hidden_from_conversation", false) == true ||
            metadata?.optBoolean("is_visually_hidden", false) == true
        ) {
            return null
        }

        val content = message.optJSONObject("content") ?: return null
        val contentType = content.optString("content_type").lowercase()

        val rawText = when (contentType) {
            "text" -> stringParts(content.optJSONArray("parts"))
            "code" -> content.optString("text")
            "multimodal_text" -> stringParts(content.optJSONArray("parts"))
            else -> ""
        }

        val text = sanitizeVisibleText(rawText)
        if (text.isBlank()) return null

        val id = message.optString("id")
            .ifBlank { message.optString("message_id") }
            .ifBlank { role + "-" + text.hashCode() }

        return WebRuntime.PageConversationMessage(
            id = id,
            role = role,
            text = text,
        )
    }

    private fun stringParts(parts: JSONArray?): String {
        if (parts == null) return ""
        return buildList {
            for (index in 0 until parts.length()) {
                val part = parts.opt(index)
                if (part is String && part.isNotBlank()) add(part)
                // Objects such as image_asset_pointer are intentionally not
                // stringified into chat text. Media is handled separately.
            }
        }.joinToString("\n")
    }

    /**
     * ChatGPT embeds internal citation/reference sequences in the Unicode
     * private-use area. gpt4free explicitly interprets/removes these markers.
     * Until AIHub has a dedicated reference renderer, strip those protocol
     * markers instead of displaying them as garbage characters.
     */
    private fun sanitizeVisibleText(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(Regex("\\uE200[\\s\\S]*?\\uE201"), "")
            .replace("\uE203", "")
            .replace("\uE204", "")
            .replace("\uE206", "")
            .replace(Regex("\\u3010\\d+\\u2020source\\u3011"), "")
            .replace(Regex("[\\uE000-\\uF8FF]"), "")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun put(
        target: LinkedHashMap<String, WebRuntime.PageConversationMessage>,
        message: WebRuntime.PageConversationMessage,
    ) {
        val existing = target[message.id]
        if (existing == null || message.text.length >= existing.text.length) {
            target[message.id] = message
        }
    }

    private fun findConversationObject(
        value: Any?,
        depth: Int = 0,
    ): JSONObject? {
        if (value == null || depth > 12) return null

        when (value) {
            is JSONObject -> {
                if (
                    value.optJSONObject("mapping") != null &&
                    value.optString("current_node").isNotBlank()
                ) {
                    return value
                }

                listOf("conversation", "data", "result", "payload", "response").forEach { key ->
                    val child = value.opt(key)
                    findConversationObject(child, depth + 1)?.let { return it }
                }
            }

            is JSONArray -> {
                for (index in 0 until value.length()) {
                    findConversationObject(value.opt(index), depth + 1)?.let { return it }
                }
            }

            is String -> parseEmbeddedJson(value)?.let {
                return findConversationObject(it, depth + 1)
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
}
