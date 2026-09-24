package com.yagay.ybrowser.ai.web.provider

import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ProviderSpec
import com.yagay.ybrowser.ai.web.CapturedNetworkPayload
import com.yagay.ybrowser.ai.web.WebRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Low-level ChatGPT wire decoder.
 *
 * This is intentionally below the product-runtime boundary. Application code
 * must depend on [ChatGptProductProvider], whose authority/finality semantics
 * follow the CWA contract. This decoder only converts observed ChatGPT wire
 * shapes into YBrowser message values.
 *
 * Main path follows the conservative state-machine approach used by mature
 * browser-owned ChatGPT clients:
 * - only known ChatGPT message envelopes and p/v/o patches are decoded;
 * - recipient and message identity are kept per network request;
 * - tool/reasoning/metadata/reference payloads are never guessed into chat text;
 * - history accepts the legacy mapping/current_node tree and the current
 *   messages/current_node/page_info paginated branch shape.
 *
 * The generic recursive role/text scanner is intentionally never used here.
 */
internal object ChatGptWireDecoder {
    const val providerId: String = "chatgpt"

    val captureUrlHints: List<String> = listOf(
        "/backend-api/conversations/",
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
        var visibleAssistant: Boolean = false,
    )

    private val streamStates = LinkedHashMap<String, StreamState>()

    @Synchronized
    fun parseNetwork(
        provider: ProviderSpec,
        capture: CapturedNetworkPayload,
        pageUrl: String,
    ): WebRuntime.ConversationSnapshot? {
        if (capture.body.isBlank()) return null

        val documents = JsonNetworkParsing.parseDocuments(capture.body)
        if (documents.isEmpty()) return null

        val historyConversationId =
            historyConversationId(capture.url)
        val pageConversationId =
            pageConversationId(pageUrl)
        val productConversationId =
            productConversationId(documents)
        val observedConversationId =
            historyConversationId
                ?: productConversationId

        if (
            historyConversationId != null &&
            productConversationId != null &&
            historyConversationId !=
                productConversationId
        ) {
            return null
        }

        if (
            historyConversationId != null &&
            pageConversationId != null &&
            !pageConversationId.startsWith(
                "WEB:",
                ignoreCase = true,
            ) &&
            historyConversationId !=
                pageConversationId
        ) {
            return null
        }

        val olderPaginatedPage =
            Regex(
                """/backend-api/conversations/[^/?#]+/messages(?:[?#]|$)"""
            ).containsMatchIn(capture.url)

        var title = ""
        val historyMessages =
            LinkedHashMap<String, WebRuntime.PageConversationMessage>()
        var historyComplete = false

        documents.forEach { value ->
            val root = value as? JSONObject
            if (root != null && title.isBlank()) {
                title = JsonNetworkParsing.findTitle(root)
            }

            val history = extractHistory(
                root = value,
                allowCurrentNodeMissing = olderPaginatedPage,
            )
            if (history.messages.isNotEmpty()) {
                history.messages.forEach { put(historyMessages, it) }
                historyComplete = historyComplete || history.completeChain
            }
        }

        if (historyMessages.isNotEmpty()) {
            return WebRuntime.ConversationSnapshot(
                url = pageUrl,
                conversationId =
                    observedConversationId,
                title = title,
                candidateCount = historyMessages.size,
                source = if (historyComplete) {
                    "network-history"
                } else {
                    "network-delta"
                },
                complete =
                    historyComplete &&
                        capture.complete &&
                        !capture.truncated,
                messages = historyMessages.values.toList(),
            )
        }

        val live = parseStream(
            capture = capture,
            pageUrl = pageUrl,
            documents = documents,
        )
        if (live.isEmpty()) return null

        return WebRuntime.ConversationSnapshot(
            url = pageUrl,
            conversationId =
                observedConversationId,
            title = title,
            candidateCount = live.size,
            source = if (
                capture.stream ||
                capture.contentType.contains(
                    "text/event-stream",
                    ignoreCase = true,
                )
            ) {
                "network-stream"
            } else {
                "network-delta"
            },
            complete = false,
            messages = live,
        )
    }

    /**
     * ChatGPT currently exposes two history wire shapes:
     *
     * 1) legacy `mapping + current_node`, where the active branch is walked
     *    backwards through parent links;
     * 2) current `messages[] + current_node + page_info`, returned by
     *    /backend-api/conversations/<id>. The array is already the current
     *    branch in chronological order. `page_info` decides completeness.
     *
     * Flat messages are accepted only from an object that also carries
     * current_node/page_info. We still never recursively treat arbitrary
     * role/text JSON as conversation history.
     */
    private fun extractHistory(
        root: Any,
        allowCurrentNodeMissing: Boolean = false,
    ): HistoryResult {
        findPaginatedConversationObject(
            value = root,
            allowCurrentNodeMissing =
                allowCurrentNodeMissing,
        )?.let { conversation ->
            val raw = conversation.optJSONArray("messages")
                ?: return@let
            val currentNode = conversation.optString("current_node")
                .ifBlank { conversation.optString("current_node_id") }

            val result =
                mutableListOf<WebRuntime.PageConversationMessage>()
            var currentSeen = currentNode.isBlank()

            for (index in 0 until raw.length()) {
                val item = raw.optJSONObject(index) ?: continue
                val message = item.optJSONObject("message") ?: item

                parseVisibleMessage(message)
                    ?.let(result::add)

                val messageId = message.optString("id")
                    .ifBlank { message.optString("message_id") }
                val nodeId = item.optString("id")
                if (
                    currentNode.isNotBlank() &&
                    (
                        messageId == currentNode ||
                            nodeId == currentNode
                    )
                ) {
                    currentSeen = true
                    // The plural endpoint is a linear current-branch window.
                    // Anything after current_node is not part of the selected
                    // branch and must not leak into the transcript.
                    break
                }
            }

            val pageInfo =
                conversation.optJSONObject("page_info")
                    ?: conversation.optJSONObject("pageInfo")
            val hasPrevious =
                pageInfo?.optBoolean("has_previous_page", false) == true ||
                    pageInfo?.optBoolean("hasPreviousPage", false) == true
            val hasNext =
                pageInfo?.optBoolean("has_next_page", false) == true ||
                    pageInfo?.optBoolean("hasNextPage", false) == true

            if (result.isNotEmpty()) {
                return HistoryResult(
                    messages = result,
                    completeChain =
                        !allowCurrentNodeMissing &&
                            pageInfo != null &&
                            currentSeen &&
                            !hasPrevious &&
                            !hasNext,
                )
            }
        }

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
                ?: return HistoryResult(
                    messages = reversed.asReversed(),
                    completeChain = false,
                )

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

    /**
     * Decode ChatGPT SSE / delta traffic as a state machine. This mirrors the
     * gpt4free idea of tracking current p/recipient/message instead of treating
     * every JSON object as an independent chat message.
     */
    private fun parseStream(
        capture: CapturedNetworkPayload,
        pageUrl: String,
        documents: List<Any>,
    ): List<WebRuntime.PageConversationMessage> {
        val streamKey = pageUrl + "|" + capture.requestId
        val state = if (capture.complete) {
            streamStates.remove(streamKey)
            StreamState()
        } else {
            streamStates.getOrPut(streamKey) { StreamState() }
        }

        var directMessage: WebRuntime.PageConversationMessage? = null

        documents.forEach { value ->
            val obj = value as? JSONObject ?: return@forEach

            knownMessageEnvelope(obj)?.let { message ->
                updateStateFromMessage(state, message)
                parseVisibleMessage(message)?.let { directMessage = it }
            }

            val patchPath = obj.optString("p")
            if (patchPath.isNotBlank()) {
                state.path = patchPath
            }

            when (val payload = obj.opt("v")) {
                is JSONObject -> {
                    knownMessageEnvelope(payload)?.let { message ->
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
            streamStates.remove(streamKey)
        } else {
            trimStreamStates()
        }

        directMessage?.let { visible ->
            if (visible.role == "assistant") {
                state.role = visible.role
                state.messageId = visible.id
                state.text = visible.text
            }
        }

        if (state.role.isNotBlank() && state.role != "assistant") {
            return directMessage?.let(::listOf).orEmpty()
        }
        if (state.recipient != "all") {
            return emptyList()
        }

        val text = sanitizeVisibleText(state.text)
        if (text.isBlank()) {
            return directMessage?.let(::listOf).orEmpty()
        }

        return listOf(
            WebRuntime.PageConversationMessage(
                id = state.messageId.ifBlank {
                    "stream-" + capture.requestId
                },
                role = "assistant",
                text = text,
                attachments =
                    directMessage
                        ?.attachments
                        .orEmpty(),
            )
        )
    }

    private fun knownMessageEnvelope(root: JSONObject): JSONObject? {
        root.optJSONObject("message")?.let { return it }

        root.optJSONObject("payload")?.let { payload ->
            payload.optJSONObject("message")?.let { return it }
            payload.optJSONObject("update_content")
                ?.optJSONObject("message")
                ?.let { return it }
        }

        root.optJSONObject("data")
            ?.optJSONObject("message")
            ?.let { return it }

        return null
    }

    private fun updateStateFromMessage(
        state: StreamState,
        message: JSONObject,
    ) {
        state.messageId = message.optString("id")
            .ifBlank { message.optString("message_id") }
            .ifBlank { state.messageId }

        val role = message.optJSONObject("author")
            ?.optString("role")
            .orEmpty()
            .lowercase()
            .ifBlank { message.optString("role").lowercase() }
        if (role.isNotBlank()) state.role = role

        val recipient = message.optString("recipient")
            .lowercase()
            .ifBlank { "all" }
        state.recipient = recipient
        state.visibleAssistant =
            isVisibleAssistantStreamMessage(
                message = message,
                role = role,
                recipient = recipient,
            )

        if (!state.visibleAssistant) {
            state.text = ""
            return
        }

        parseVisibleMessage(message)?.let { visible ->
            state.text = visible.text
        }
    }

    private fun isVisibleAssistantStreamMessage(
        message: JSONObject,
        role: String,
        recipient: String,
    ): Boolean {
        if (role != "assistant" || recipient != "all") {
            return false
        }

        val metadata =
            message.optJSONObject("metadata")
        if (
            metadata?.optBoolean(
                "is_visually_hidden_from_conversation",
                false,
            ) == true ||
            metadata?.optBoolean(
                "is_visually_hidden",
                false,
            ) == true
        ) {
            return false
        }

        val channel =
            sequenceOf(
                message.optString("channel"),
                metadata?.optString("channel").orEmpty(),
                metadata
                    ?.optString("output_channel")
                    .orEmpty(),
                metadata
                    ?.optString("message_channel")
                    .orEmpty(),
            ).firstOrNull {
                it.isNotBlank()
            }.orEmpty()
                .trim()
                .lowercase()

        if (
            channel.isNotBlank() &&
            channel != "final"
        ) {
            return false
        }

        val contentType =
            message.optJSONObject("content")
                ?.optString("content_type")
                .orEmpty()
                .trim()
                .lowercase()

        return contentType.isBlank() ||
            contentType == "text" ||
            contentType == "multimodal_text"
    }

    private fun applyTextPatch(
        state: StreamState,
        path: String,
        operation: String,
        value: String,
    ) {
        // Reasoning/thought summaries, references and metadata are protocol
        // state, not visible assistant content.
        if (path.startsWith("/message/content/thoughts")) return
        if (path != "/message/content/parts/0") return
        if (!state.visibleAssistant) return
        if (value.isBlank()) return

        val cleaned = sanitizeVisibleText(value)
        if (cleaned.isBlank()) return

        state.role = "assistant"
        state.text = when {
            operation.equals("replace", ignoreCase = true) -> cleaned
            state.text.isBlank() -> cleaned
            cleaned.startsWith(state.text) -> cleaned
            state.text.endsWith(cleaned) -> state.text
            else -> state.text + cleaned
        }
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
            .lowercase()
            .ifBlank { "all" }
        if (recipient != "all") return null

        val metadata = message.optJSONObject("metadata")

        // Current flat history includes assistant reasoning recaps and tool
        // traces next to the final visible answer. Keep old cohorts (no
        // channel field) compatible, but when channel is present only the
        // final assistant channel belongs in the visible transcript.
        val channel =
            sequenceOf(
                message.optString("channel"),
                metadata
                    ?.optString("channel")
                    .orEmpty(),
                metadata
                    ?.optString("output_channel")
                    .orEmpty(),
                metadata
                    ?.optString("message_channel")
                    .orEmpty(),
            ).firstOrNull {
                it.isNotBlank()
            }.orEmpty()
                .trim()
                .lowercase()
        if (
            role == "assistant" &&
            channel.isNotBlank() &&
            channel != "final"
        ) {
            return null
        }
        if (
            metadata?.optBoolean(
                "is_visually_hidden_from_conversation",
                false,
            ) == true ||
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
        val attachments =
            attachmentParts(
                content.optJSONArray("parts")
            )
        if (
            text.isBlank() &&
            attachments.isEmpty()
        ) {
            return null
        }

        val id = message.optString("id")
            .ifBlank {
                message.optString(
                    "message_id"
                )
            }
            .ifBlank {
                role + "-" +
                    (
                        text +
                            attachments
                                .joinToString {
                                    it.uri.orEmpty()
                                }
                        ).hashCode()
            }

        return WebRuntime.PageConversationMessage(
            id = id,
            role = role,
            text = text,
            attachments = attachments,
        )
    }

    private fun attachmentParts(
        parts: JSONArray?,
    ): List<AttachmentMeta> {
        if (parts == null) return emptyList()

        val result =
            mutableListOf<AttachmentMeta>()
        val seen = mutableSetOf<String>()

        fun addFromObject(
            obj: JSONObject,
            index: Int,
        ) {
            val type =
                obj.optString(
                    "content_type"
                ).lowercase()
            val candidates =
                listOf(
                    obj.optString("url"),
                    obj.optString(
                        "download_url"
                    ),
                    obj.optString(
                        "asset_url"
                    ),
                    obj.optString("src"),
                    (
                        obj.opt(
                            "image_url"
                        ) as? String
                        ).orEmpty(),
                    obj.optJSONObject(
                        "image_url"
                    )?.optString("url")
                        .orEmpty(),
                )
            val uri =
                candidates.firstOrNull {
                    it.startsWith(
                        "http://",
                        true,
                    ) ||
                        it.startsWith(
                            "https://",
                            true,
                        ) ||
                        it.startsWith(
                            "content://",
                            true,
                        )
                } ?: return
            if (!seen.add(uri)) return

            val mime =
                obj.optString(
                    "mime_type"
                ).ifBlank {
                    obj.optString(
                        "mimeType"
                    )
                }.ifBlank {
                    when {
                        type.contains(
                            "image"
                        ) ->
                            "image/*"
                        uri.substringBefore('?')
                            .lowercase()
                            .matches(
                                Regex(
                                    """.*\.(png|jpe?g|gif|webp|avif)$"""
                                )
                            ) ->
                            "image/*"
                        else ->
                            "application/octet-stream"
                    }
                }
            val name =
                obj.optString(
                    "filename"
                ).ifBlank {
                    obj.optString("name")
                }.ifBlank {
                    obj.optString("alt")
                }.ifBlank {
                    if (
                        mime.startsWith(
                            "image/"
                        )
                    ) {
                        "image-" +
                            (index + 1)
                    } else {
                        uri.substringAfterLast(
                            '/'
                        ).substringBefore('?')
                            .ifBlank {
                                "attachment-" +
                                    (index + 1)
                            }
                    }
                }
            result +=
                AttachmentMeta(
                    id =
                        obj.optString("id")
                            .ifBlank {
                                "network-" +
                                    uri.hashCode()
                            },
                    name = name,
                    mimeType = mime,
                    sizeBytes =
                        obj.optLong(
                            "size_bytes",
                            obj.optLong(
                                "sizeBytes",
                                0L,
                            ),
                        ),
                    uri = uri,
                )
        }

        for (
            index in 0 until
                parts.length()
        ) {
            val part =
                parts.optJSONObject(index)
                    ?: continue
            addFromObject(
                part,
                index,
            )
        }
        return result
    }

    private fun stringParts(parts: JSONArray?): String {
        if (parts == null) return ""
        return buildList {
            for (index in 0 until parts.length()) {
                when (val part = parts.opt(index)) {
                    is String -> if (part.isNotBlank()) add(part)
                    is JSONObject -> {
                        // Do not stringify image/file/tool objects into text.
                        if (part.optString("content_type") == "text") {
                            part.optString("text")
                                .takeIf { it.isNotBlank() }
                                ?.let(::add)
                        }
                    }
                }
            }
        }.joinToString("\n")
    }

    private fun sanitizeVisibleText(raw: String): String {
        if (raw.isBlank()) return ""

        return raw
            .replace(Regex("\uE200[\\s\\S]*?\uE201"), "")
            .replace("\uE203", "")
            .replace("\uE204", "")
            .replace("\uE206", "")
            .replace(Regex("\\u3010\\d+\\u2020source\\u3011"), "")
            .replace(Regex("[\\uE000-\\uF8FF]"), "")
            .replace("\u0000", "")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun put(
        target: LinkedHashMap<String, WebRuntime.PageConversationMessage>,
        message: WebRuntime.PageConversationMessage,
    ) {
        val existing = target[message.id]
        val nextScore =
            message.text.length +
                message.attachments.size * 1_000
        val oldScore =
            existing?.let {
                it.text.length +
                    it.attachments.size * 1_000
            } ?: -1
        if (
            existing == null ||
            nextScore >= oldScore
        ) {
            target[message.id] = message
        }
    }

    private fun findPaginatedConversationObject(
        value: Any?,
        depth: Int = 0,
        allowCurrentNodeMissing: Boolean = false,
    ): JSONObject? {
        if (value == null || depth > 16) return null

        when (value) {
            is JSONObject -> {
                val hasCurrentNode =
                    value.optString("current_node").isNotBlank() ||
                        value.optString("current_node_id").isNotBlank()
                val hasPageInfo =
                    value.optJSONObject("page_info") != null ||
                        value.optJSONObject("pageInfo") != null
                if (
                    value.optJSONArray("messages") != null &&
                    hasPageInfo &&
                    (
                        hasCurrentNode ||
                            allowCurrentNodeMissing
                    )
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
                    findPaginatedConversationObject(
                        value.opt(key),
                        depth + 1,
                        allowCurrentNodeMissing,
                    )?.let { return it }
                }

                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key in preferred) continue
                    when (val child = value.opt(key)) {
                        is JSONObject, is JSONArray ->
                            findPaginatedConversationObject(
                                child,
                                depth + 1,
                                allowCurrentNodeMissing,
                            )?.let { return it }
                        is String ->
                            parseEmbeddedJson(child)
                                ?.let {
                                    findPaginatedConversationObject(
                                        it,
                                        depth + 1,
                                        allowCurrentNodeMissing,
                                    )
                                }
                                ?.let { return it }
                    }
                }
            }

            is JSONArray -> {
                for (index in 0 until value.length()) {
                    findPaginatedConversationObject(
                        value.opt(index),
                        depth + 1,
                        allowCurrentNodeMissing,
                    )?.let { return it }
                }
            }

            is String ->
                parseEmbeddedJson(value)
                    ?.let {
                        return findPaginatedConversationObject(
                            it,
                            depth + 1,
                            allowCurrentNodeMissing,
                        )
                    }
        }
        return null
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
                            findConversationObject(child, depth + 1)
                                ?.let { return it }
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

    private fun productConversationId(
        documents: List<Any>,
    ): String? {
        val identities =
            linkedSetOf<String>()

        fun add(value: String?) {
            val normalized =
                value
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank() &&
                            !it.startsWith(
                                "WEB:",
                                ignoreCase = true,
                            ) &&
                            !it.contains('/') &&
                            !it.contains('?') &&
                            !it.contains('#')
                    }
                    ?: return
            identities += normalized
        }

        fun visit(
            value: Any?,
            depth: Int = 0,
        ) {
            if (
                value == null ||
                depth > 8 ||
                identities.size > 1
            ) {
                return
            }

            when (value) {
                is JSONObject -> {
                    val type =
                        value.optString("type")
                    if (
                        type == "stream_handoff" ||
                        value.has("conversation_id") ||
                        value.has("conversationId")
                    ) {
                        add(
                            value.optString(
                                "conversation_id"
                            )
                        )
                        add(
                            value.optString(
                                "conversationId"
                            )
                        )
                    }

                    listOf(
                        "payload",
                        "data",
                        "result",
                        "response",
                    ).forEach { key ->
                        when (
                            val child =
                                value.opt(key)
                        ) {
                            is JSONObject,
                            is JSONArray ->
                                visit(
                                    child,
                                    depth + 1,
                                )
                        }
                    }
                }

                is JSONArray -> {
                    for (
                        index in
                        0 until value.length()
                    ) {
                        visit(
                            value.opt(index),
                            depth + 1,
                        )
                        if (
                            identities.size > 1
                        ) {
                            break
                        }
                    }
                }
            }
        }

        documents.forEach {
            visit(it)
        }

        return identities.singleOrNull()
    }

    private fun historyConversationId(
        rawUrl: String,
    ): String? =
        Regex(
            """/backend-api/conversations?/([^/?#]+)(?:[?#]|$)"""
        ).find(rawUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

    private fun pageConversationId(
        rawUrl: String,
    ): String? =
        Regex(
            """/c/([^/?#]+)(?:[/?#]|$)"""
        ).find(rawUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

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

    private fun trimStreamStates() {
        while (streamStates.size > 32) {
            val first = streamStates.keys.firstOrNull() ?: break
            streamStates.remove(first)
        }
    }
}
