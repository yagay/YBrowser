package com.yagay.ybrowser.ai.web

import com.yagay.ybrowser.ai.model.ProviderSpec
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class CapturedNetworkPayload(
    val requestId: String,
    val url: String,
    val method: String,
    val statusCode: Int,
    val contentType: String,
    val body: String,
    val stream: Boolean,
    val complete: Boolean,
    val truncated: Boolean,
    val capturedAt: Long,
)

internal object ProviderNetworkParser {
    fun parse(
        provider: ProviderSpec,
        capture: CapturedNetworkPayload,
        pageUrl: String,
    ): WebRuntime.ConversationSnapshot? {
        if (capture.body.isBlank()) return null

        val documents = parseDocuments(capture.body)
        if (documents.isEmpty()) return null

        var title = ""
        var history = false
        val ordered = LinkedHashMap<String, WebRuntime.PageConversationMessage>()

        documents.forEach { value ->
            if (value is JSONObject) {
                if (title.isBlank()) title = findTitle(value)

                if (provider.id == "chatgpt") {
                    val chain = extractChatGptHistory(value)
                    if (chain.isNotEmpty()) {
                        history = true
                        chain.forEach { putMessage(ordered, it) }
                    }
                }

                val explicit = extractExplicitMessageArrays(value)
                if (explicit.size >= 2) history = true
                explicit.forEach { putMessage(ordered, it) }

                collectMessages(value).forEach { putMessage(ordered, it) }
            } else if (value is JSONArray) {
                collectMessages(value).forEach { putMessage(ordered, it) }
            }
        }

        val messages = ordered.values.toList()
        if (messages.isEmpty()) return null

        val source = when {
            history -> "network-history"
            capture.stream -> "network-stream"
            else -> "network-delta"
        }

        return WebRuntime.ConversationSnapshot(
            url = pageUrl,
            title = title,
            candidateCount = messages.size,
            source = source,
            complete = capture.complete && !capture.truncated,
            messages = messages,
        )
    }

    private fun putMessage(
        target: LinkedHashMap<String, WebRuntime.PageConversationMessage>,
        message: WebRuntime.PageConversationMessage,
    ) {
        val stable = message.id.ifBlank {
            val normalized = message.text.replace(Regex("\\s+"), " ").trim()
            "${message.role}-${normalized.hashCode()}"
        }
        val existing = target[stable]
        if (existing == null || message.text.length >= existing.text.length) {
            target[stable] = message.copy(id = stable)
        }
    }

    private fun parseDocuments(raw: String): List<Any> {
        val out = mutableListOf<Any>()
        val trimmed = raw.trim()

        if (trimmed.contains("data:")) {
            trimmed.lineSequence().forEach { line ->
                val value = line.trim()
                if (!value.startsWith("data:")) return@forEach
                val data = value.removePrefix("data:").trim()
                if (data.isBlank() || data == "[DONE]") return@forEach
                parseJsonValue(data)?.let(out::add)
            }
            if (out.isNotEmpty()) return out
        }

        parseJsonValue(trimmed)?.let(out::add)
        if (out.isNotEmpty()) return out

        trimmed.lineSequence().forEach { line ->
            parseJsonValue(line.trim())?.let(out::add)
        }
        return out
    }

    private fun parseJsonValue(value: String): Any? {
        if (value.isBlank()) return null
        return runCatching {
            when (val parsed = JSONTokener(value).nextValue()) {
                is JSONObject -> parsed
                is JSONArray -> parsed
                else -> null
            }
        }.getOrNull()
    }

    private fun findTitle(root: JSONObject): String {
        root.optString("title").takeIf { it.isNotBlank() }?.let { return it }
        root.optJSONObject("conversation")
            ?.optString("title")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        root.optJSONObject("chat")
            ?.optString("title")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return ""
    }

    private fun extractChatGptHistory(root: JSONObject): List<WebRuntime.PageConversationMessage> {
        val conversation = when {
            root.optJSONObject("mapping") != null -> root
            root.optJSONObject("conversation")?.optJSONObject("mapping") != null ->
                root.optJSONObject("conversation")
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
                ?.let(::parseMessage)
                ?.let(reversed::add)
            current = node.optString("parent")
        }
        return reversed.asReversed()
    }

    private fun extractExplicitMessageArrays(
        root: JSONObject,
    ): List<WebRuntime.PageConversationMessage> {
        val keys = listOf("messages", "chat_messages", "turns")
        keys.forEach { key ->
            val array = root.optJSONArray(key) ?: return@forEach
            val messages = buildList {
                for (index in 0 until array.length()) {
                    when (val item = array.opt(index)) {
                        is JSONObject -> parseMessage(item)?.let(::add)
                    }
                }
            }
            if (messages.isNotEmpty()) return messages
        }

        val conversation = root.optJSONObject("conversation")
        if (conversation != null) {
            return extractExplicitMessageArrays(conversation)
        }
        return emptyList()
    }

    private fun collectMessages(value: Any): List<WebRuntime.PageConversationMessage> {
        val output = mutableListOf<WebRuntime.PageConversationMessage>()
        val visited = mutableSetOf<Int>()

        fun walk(node: Any?, depth: Int) {
            if (node == null || depth > 20) return
            val identity = System.identityHashCode(node)
            if (!visited.add(identity)) return

            when (node) {
                is JSONObject -> {
                    parseMessage(node)?.let(output::add)

                    node.optJSONObject("message")
                        ?.let(::parseMessage)
                        ?.let(output::add)

                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = node.opt(key)
                        if (child is JSONObject || child is JSONArray) {
                            walk(child, depth + 1)
                        }
                    }
                }
                is JSONArray -> {
                    for (index in 0 until node.length()) {
                        val child = node.opt(index)
                        if (child is JSONObject || child is JSONArray) {
                            walk(child, depth + 1)
                        }
                    }
                }
            }
        }

        walk(value, 0)
        return output
    }

    private fun parseMessage(
        obj: JSONObject,
    ): WebRuntime.PageConversationMessage? {
        val author = obj.optJSONObject("author")
        val roleRaw = sequenceOf(
            obj.optString("role"),
            author?.optString("role").orEmpty(),
            obj.optString("author"),
            obj.optString("sender"),
            obj.optString("type"),
        ).firstOrNull { it.isNotBlank() }.orEmpty().lowercase()

        val role = when {
            roleRaw in setOf("user", "human", "prompt") -> "user"
            roleRaw in setOf("assistant", "model", "bot", "ai") -> "assistant"
            roleRaw.contains("assistant") || roleRaw.contains("model") -> "assistant"
            roleRaw.contains("user") || roleRaw.contains("human") -> "user"
            else -> return null
        }

        val text = extractText(obj)
        if (text.isBlank()) return null

        val id = sequenceOf(
            obj.optString("id"),
            obj.optString("message_id"),
            obj.optString("messageId"),
            obj.optString("uuid"),
        ).firstOrNull { it.isNotBlank() }
            ?: "${role}-${text.hashCode()}"

        return WebRuntime.PageConversationMessage(
            id = id,
            role = role,
            text = text,
        )
    }

    private fun extractText(obj: JSONObject): String {
        val content = obj.opt("content")
        textFromValue(content).takeIf { it.isNotBlank() }?.let { return it }

        val candidates = listOf(
            obj.optString("text"),
            obj.optString("value"),
            obj.optString("message"),
        )
        candidates.firstOrNull { it.isNotBlank() }?.let { return it.trim() }

        obj.optJSONArray("parts")?.let { parts ->
            textFromValue(parts).takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    private fun textFromValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value.trim()
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                val text = textFromValue(value.opt(index))
                if (text.isNotBlank()) add(text)
            }
        }.joinToString("\n").trim()
        is JSONObject -> {
            sequenceOf(
                value.optString("text"),
                value.optString("value"),
            ).firstOrNull { it.isNotBlank() }?.trim()
                ?: value.optJSONArray("parts")
                    ?.let(::textFromValue)
                    .orEmpty()
        }
        else -> ""
    }
}
