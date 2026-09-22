package com.yagay.ybrowser.ai.web.provider

import com.yagay.ybrowser.ai.web.WebRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal object JsonNetworkParsing {
    fun parseDocuments(raw: String): List<Any> {
        val out = mutableListOf<Any>()
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return out

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

    fun findTitle(root: JSONObject): String {
        sequenceOf(
            root.optString("title"),
            root.optJSONObject("conversation")?.optString("title").orEmpty(),
            root.optJSONObject("chat")?.optString("title").orEmpty(),
            root.optJSONObject("data")?.optString("title").orEmpty(),
        ).firstOrNull { it.isNotBlank() }?.let { return it }
        return ""
    }

    fun extractExplicitMessageArrays(
        root: JSONObject,
    ): List<WebRuntime.PageConversationMessage> {
        val keys = listOf(
            "messages",
            "chat_messages",
            "chatMessages",
            "turns",
            "conversation",
            "history",
        )
        for (key in keys) {
            val array = root.optJSONArray(key) ?: continue
            val messages = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    parseMessage(item)?.let(::add)
                }
            }
            if (messages.isNotEmpty()) return messages
        }

        listOf("conversation", "chat", "data", "result").forEach { key ->
            val nested = root.optJSONObject(key) ?: return@forEach
            val nestedMessages = extractExplicitMessageArrays(nested)
            if (nestedMessages.isNotEmpty()) return nestedMessages
        }
        return emptyList()
    }

    fun collectMessages(value: Any): List<WebRuntime.PageConversationMessage> {
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

    fun parseMessage(
        obj: JSONObject,
    ): WebRuntime.PageConversationMessage? {
        val author = obj.optJSONObject("author")
        val roleRaw = sequenceOf(
            obj.optString("role"),
            author?.optString("role").orEmpty(),
            obj.optString("sender"),
            obj.optString("type"),
            obj.optString("speaker"),
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
            obj.optString("turn_id"),
            obj.optString("turnId"),
        ).firstOrNull { it.isNotBlank() }
            ?: "${role}-${text.hashCode()}"

        return WebRuntime.PageConversationMessage(
            id = id,
            role = role,
            text = text,
        )
    }

    fun putMessage(
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

    private fun extractText(obj: JSONObject): String {
        textFromValue(obj.opt("content"))
            .takeIf { it.isNotBlank() }
            ?.let { return it }

        sequenceOf(
            obj.optString("text"),
            obj.optString("value"),
            obj.optString("completion"),
            obj.optString("response"),
        ).firstOrNull { it.isNotBlank() }?.let { return it.trim() }

        obj.optJSONObject("delta")?.let { delta ->
            sequenceOf(
                delta.optString("text"),
                delta.optString("content"),
            ).firstOrNull { it.isNotBlank() }?.let { return it.trim() }
        }

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
