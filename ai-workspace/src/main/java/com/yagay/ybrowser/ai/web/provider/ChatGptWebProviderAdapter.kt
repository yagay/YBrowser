package com.yagay.ybrowser.ai.web.provider

import com.yagay.ybrowser.ai.model.ProviderSpec
import com.yagay.ybrowser.ai.web.CapturedNetworkPayload
import com.yagay.ybrowser.ai.web.WebRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * ChatGPT Web protocol decoder.
 *
 * This intentionally follows the same high-level strategy used by mature
 * ChatGPT-Web clients such as gpt4free:
 * - decode only known ChatGPT message/patch shapes;
 * - keep per-request stream state;
 * - only surface user/assistant content addressed to "all";
 * - treat metadata, tools, reasoning, references and unknown payloads as
 *   protocol data rather than chat messages.
 *
 * The generic recursive JSON message scanner must never be used for ChatGPT.
 */
internal object ChatGptWebProviderAdapter : WebProviderAdapter {
    override val providerId: String = "chatgpt"

    override val captureUrlHints: List<String> = listOf(
        "/backend-api/conversation",
        "/backend-api/f/conversation",
    )

    private data class HistoryResult(
        val messages: List<WebRuntime.PageConversationMessage>,
        val completeChain: Boolean,
    )

    private data class StreamState(
        var recipient: String = "all",
        var role: String = "",
        var messageId: String = "",
        var path: String = "",
        var text: String = "",
    )

    private val streamStates = LinkedHashMap<String, StreamState>()

    @Synchronized
    override fun parseNetwork(
        provider: ProviderSpec,
        capture: CapturedNetworkPayload,
        pageUrl: String,
    ): WebRuntime.ConversationSnapshot? {
        if (capture.body.isBlank()) return null

        val documents = JsonNetworkParsing.parseDocuments(capture.body)
        if (documents.isEmpty()) return null

        var title = ""
        var historySeen = false
        var historyComplete = false
        val ordered = LinkedHashMap<String, WebRuntime.PageConversationMessage>()

        documents.forEach { value ->
            val root = value as? JSONObject
            if (root != null && title.isBlank()) {
                title = JsonNetworkParsing.findTitle(root)
            }

            val history = extractHistory(value)
            if (history.messages.isNotEmpty()) {
                historySeen = true
                historyComplete = historyComplete || history.completeChain
                history.messages.forEach { putMessage(ordered, it) }
            }
        }

        if (ordered.isNotEmpty()) {
            val source = if (historyComplete) "network-history" else "network-delta"
            return WebRuntime.ConversationSnapshot(
                url = pageUrl,
                title = title,
                candidateCount = ordered.size,
                source = source,
                complete =
                    source == "network-history" &&
                        capture.complete &&
                        !capture.truncated,
                messages = ordered.values.toList(),
            )
        }

        val streamMessages = parseStream(capture, documents)
        if (streamMessages.isEmpty()) return null

        return WebRuntime.ConversationSnapshot(
            url = pageUrl,
            title = title,
            candidateCount = streamMessages.size,
            source = if (
                capture.stream ||
                capture.contentType.contains("text/event-stream", ignoreCase = true)
            ) {
                "network-stream"
            } else {
                "network-delta"
            },
            complete = false,
            messages = streamMessages,
        )
    }

    private fun extractHistory(root: Any): HistoryResult {
        val conversation = findConversationObject(root)
            ?: return HistoryResult(emptyList(), false)
        val mapping = conversation.optJSONObject("mapping")
            ?: return HistoryResult(emptyList(), false)
        var current = conversation.optString("current_node")
        if (current.isBlank()) return HistoryResult(emptyList(), false)

        val reversed = mutableListOf<WebRuntime.PageConversationMessage>()
        val visited = mutableSetOf<String>()
        var completeChain = false

        while (current.isNotBlank() && visited.add(current)) {
            val node = mapping.optJSONObject(current)
                ?: return HistoryResult(reversed.asReversed(), false)

            node.optJSONObject("message")
                ?.let(::parseVisibleMessage)
                ?.let(reversed::add)

            val parent = node.optString("parent")
            if (parent.isBlank() || parent == "null") {
                completeChain = true
                break
            }
            current = parent
        }

        return HistoryResult(
            messages = reversed.asReversed(),
            completeChain = completeChain,
        )
    }

    private fun findConversationObject(
        value: Any?,
        depth: Int = 0,
    ): JSONObject? {
        if (value == null || depth > 16) return null
        when (value) {
            is JSONObject -> {
                if (
                    value.optJSONObject("mapping") != null &&
                    value.optString("current_node").isNotBlank()
                ) {
                    return value
                }

                val preferred = listOf(
                    "conversation",
                    "data",
                    "result",
                    "payload",
                    "response",
                )
                preferred.forEach { key ->
                    findConversationObject(value.opt(key), depth + 1)
                        ?.let { return it }
                }

                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key in preferred) continue
                    when (val child = value.opt(key)) {
                        is JSONObject, is JSONArray ->
                            findConversationObject(child, depth + 1)?.let { return it }
                        is String ->
                            parseEmbeddedJson(child)
                                ?.let { findConversationObject(it, depth + 1) }
                                ?.let { return it }
                    }
                }
            }

            is JSONArray -> {
                for (index in 0 until value.length()) {
                    findConversationObject(value.opt(index), depth + 1)
                        ?.let { return it }
                }
            }

            is String ->
                parseEmbeddedJson(value)
                    ?.let { return findConversationObject(it, depth + 1) }
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

    private fun parseVisibleMessage(
        message: JSONObject,
    ): WebRuntime.PageConversationMessage? {
        val role = message.optJSONObject("author")
            ?.optString("role")
            ?.lowercase()
            .orEmpty()
        if (role !in setOf("user", "assistant")) return null

        val recipient = message.optString("recipient", "all")
            .lowercase()
            .ifBlank { "all" }
        if (recipient != "all") return null

        val metadata = message.optJSONObject("metadata")
        if (metadata?.optBoolean("is_visually_hidden_from_conversation", false) == true) {
            return null
        }

        val content = message.optJSONObject("content") ?: return null
        val contentType = content.optString("content_type").lowercase()
        if (contentType !in setOf("text", "multimodal_text")) return null

        val text = sanitizeVisibleText(extractVisibleText(content))
        if (text.isBlank()) return null

        val id = message.optString("id").ifBlank {
            "${role}-${text.hashCode()}"
        }
        return WebRuntime.PageConversationMessage(
            id = id,
            role = role,
            text = text,
        )
    }

    private fun extractVisibleText(content: JSONObject): String {
        val parts = content.optJSONArray("parts")
        if (parts != null) {
            val textParts = buildList {
                for (index in 0 until parts.length()) {
                    when (val part = parts.opt(index)) {
                        is String -> if (part.isNotBlank()) add(part)
                        is JSONObject -> {
                            val type = part.optString("content_type").lowercase()
                            if (type == "text") {
                                part.optString("text")
                                    .takeIf { it.isNotBlank() }
                                    ?.let(::add)
                            }
                        }
                    }
                }
            }
            if (textParts.isNotEmpty()) return textParts.joinToString("\n")
        }

        return content.optString("text")
    }

    private fun parseStream(
        capture: CapturedNetworkPayload,
        documents: List<Any>,
    ): List<WebRuntime.PageConversationMessage> {
        val state = if (capture.complete) {
            StreamState().also { streamStates.remove(capture.requestId) }
        } else {
            streamStates.getOrPut(capture.requestId) { StreamState() }
        }

        var directMessage: WebRuntime.PageConversationMessage? = null

        documents.forEach { value ->
            val obj = value as? JSONObject ?: return@forEach

            obj.optJSONObject("message")?.let { message ->
                updateStateFromMessage(state, message)
                parseVisibleMessage(message)?.let { directMessage = it }
            }

            val patchPath = obj.optString("p")
            if (patchPath.isNotBlank()) state.path = patchPath

            when (val payload = obj.opt("v")) {
                is JSONObject -> {
                    payload.optJSONObject("message")?.let { message ->
                        updateStateFromMessage(state, message)
                        parseVisibleMessage(message)?.let { directMessage = it }
                    }
                }

                is String -> {
                    applyTextPatch(
                        state = state,
                        path = patchPath.ifBlank { state.path },
                        operation = obj.optString("o"),
                        value = payload,
                    )
                }

                is JSONArray -> {
                    for (index in 0 until payload.length()) {
                        val patch = payload.optJSONObject(index) ?: continue
                        applyTextPatch(
                            state = state,
                            path = patch.optString("p"),
                            operation = patch.optString("o"),
                            value = patch.optString("v"),
                        )
                    }
                }
            }
        }

        if (capture.complete) {
            streamStates.remove(capture.requestId)
        } else {
            trimStreamStates()
        }

        directMessage?.let { return listOf(it) }

        if (state.role != "assistant" || state.recipient != "all") return emptyList()
        val text = sanitizeVisibleText(state.text)
        if (text.isBlank()) return emptyList()

        return listOf(
            WebRuntime.PageConversationMessage(
                id = state.messageId.ifBlank { "stream-${capture.requestId}" },
                role = "assistant",
                text = text,
            )
        )
    }

    private fun updateStateFromMessage(
        state: StreamState,
        message: JSONObject,
    ) {
        state.messageId = message.optString("id").ifBlank { state.messageId }
        state.role = message.optJSONObject("author")
            ?.optString("role")
            ?.lowercase()
            ?.ifBlank { state.role }
            ?: state.role
        state.recipient = message.optString("recipient")
            .lowercase()
            .ifBlank { state.recipient.ifBlank { "all" } }

        parseVisibleMessage(message)?.let { visible ->
            if (visible.role == "assistant") {
                state.text = visible.text
            }
        }
    }

    private fun applyTextPatch(
        state: StreamState,
        path: String,
        operation: String,
        value: String,
    ) {
        if (state.recipient != "all") return
        if (state.role.isNotBlank() && state.role != "assistant") return
        if (path != "/message/content/parts/0") return
        if (value.isBlank()) return

        val cleaned = sanitizeVisibleText(value)
        if (cleaned.isBlank()) return

        state.role = "assistant"
        state.text = when {
            operation.equals("replace", ignoreCase = true) -> cleaned
            cleaned.startsWith(state.text) -> cleaned
            state.text.endsWith(cleaned) -> state.text
            else -> state.text + cleaned
        }
    }

    private fun sanitizeVisibleText(raw: String): String {
        if (raw.isBlank()) return ""

        var text = raw
            .replace("\uE203", "")
            .replace("\uE204", "")
            .replace("\uE206", "")

        // ChatGPT wraps citations/references and other protocol-only sequences in
        // private-use delimiters. If a sequence cannot be rendered safely, omit it
        // rather than surfacing protocol symbols in the chat UI.
        text = text.replace(Regex("\\uE200[\\s\\S]*?\\uE201"), "")
        text = text.replace(Regex("[\\uE000-\\uF8FF]"), "")

        return text
            .replace("\u0000", "")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun putMessage(
        target: LinkedHashMap<String, WebRuntime.PageConversationMessage>,
        message: WebRuntime.PageConversationMessage,
    ) {
        val existing = target[message.id]
        if (existing == null || message.text.length >= existing.text.length) {
            target[message.id] = message
        }
    }

    private fun trimStreamStates() {
        while (streamStates.size > 24) {
            val first = streamStates.keys.firstOrNull() ?: break
            streamStates.remove(first)
        }
    }
}
