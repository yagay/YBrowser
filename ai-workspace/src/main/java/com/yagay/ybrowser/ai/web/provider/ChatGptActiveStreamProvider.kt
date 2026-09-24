package com.yagay.ybrowser.ai.web.provider

import java.net.URI
import com.yagay.ybrowser.ai.model.ProviderSpec
import com.yagay.ybrowser.ai.web.CapturedNetworkPayload
import com.yagay.ybrowser.ai.web.WebRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Realtime ChatGPT response provider.
 *
 * This is intentionally independent from history/canonical parsing. It consumes
 * the browser-owned SSE response as it arrives and owns only the provisional
 * live-display plane. Canonical history remains the durable final authority.
 */
internal object ChatGptActiveStreamProvider {
    private data class State(
        var conversationId: String? = null,
        var currentMessageId: String? = null,
        var currentVisibleAssistant: Boolean = false,
        var currentPath: String = "",
        var visibleMessageId: String? = null,
        var visibleText: String = "",
        var complete: Boolean = false,
    )

    private val states =
        LinkedHashMap<String, State>()

    @Synchronized
    fun parse(
        provider: ProviderSpec,
        capture: CapturedNetworkPayload,
        pageUrl: String,
    ): WebRuntime.ConversationSnapshot? {
        if (
            provider.id != "chatgpt" ||
            !capture.stream ||
            !capture.method.equals(
                "POST",
                ignoreCase = true,
            ) ||
            capture.statusCode !in 200..299
        ) {
            return null
        }

        val path =
            runCatching {
                URI(capture.url)
                    .path
                    .orEmpty()
            }.getOrDefault("")
        if (
            !Regex(
                """^/backend-api/(?:f/)?conversation(?:/resume)?/?$"""
            ).matches(path)
        ) {
            return null
        }

        val observedConversationId =
            extractConversationId(
                capture.body
            )
                ?: pageConversationId(
                    pageUrl
                )
        val stateKey =
            observedConversationId
                ?.let {
                    "conversation:" + it
                }
                ?: (
                    "request:" +
                        capture.requestId
                    )
        val state =
            states.getOrPut(
                stateKey
            ) {
                State(
                    conversationId =
                        observedConversationId
                )
            }

        observedConversationId
            ?.let {
                state.conversationId = it
            }

        val events =
            decodeEvents(capture.body)
        if (events.isEmpty()) {
            return null
        }

        events.forEach { event ->
            applyEvent(
                state = state,
                event = event,
            )
        }

        val text =
            sanitizeVisibleText(
                state.visibleText
            )
        val conversationId =
            state.conversationId
                ?.takeIf(::validConversationId)
                ?: pageConversationId(pageUrl)

        if (
            state.complete ||
            capture.complete
        ) {
            states.remove(
                stateKey
            )
        } else {
            trimStates()
        }

        if (text.isBlank()) {
            return null
        }

        return WebRuntime.ConversationSnapshot(
            url = pageUrl,
            conversationId =
                conversationId,
            candidateCount = 1,
            source =
                "network-active-stream",
            complete =
                state.complete ||
                    capture.complete,
            authority =
                ProductObservationAuthority.PROVISIONAL,
            finality =
                ProductFinality.PROVISIONAL,
            messages =
                listOf(
                    WebRuntime.PageConversationMessage(
                        id =
                            state.visibleMessageId
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                                ?: (
                                    "stream-" +
                                        capture.requestId
                                    ),
                        role = "assistant",
                        text = text,
                    )
                ),
        )
    }

    private fun decodeEvents(
        raw: String,
    ): List<Any> {
        val text = raw.trim()
        if (text.isBlank()) return emptyList()

        if (!text.contains("data:")) {
            return listOfNotNull(
                parseJson(text)
            )
        }

        val out = mutableListOf<Any>()
        val blocks =
            text.split(
                Regex("""\r?\n\r?\n""")
            )

        blocks.forEach { block ->
            val dataLines =
                block
                    .lineSequence()
                    .map { it.trim() }
                    .filter {
                        it.startsWith("data:")
                    }
                    .map {
                        it.removePrefix("data:")
                            .trimStart()
                    }
                    .toList()

            if (dataLines.isEmpty()) {
                return@forEach
            }

            val joined =
                dataLines.joinToString("\n")
                    .trim()

            when {
                joined.isBlank() -> Unit
                joined == "[DONE]" ->
                    out +=
                        JSONObject()
                            .put(
                                "type",
                                "__ybrowser_done__",
                            )
                else -> {
                    val parsed =
                        parseJson(joined)
                    if (parsed != null) {
                        out += parsed
                    } else {
                        // Some captured chunks contain several complete SSE
                        // data lines without a blank separator. Parse each
                        // line independently instead of dropping the chunk.
                        dataLines.forEach { data ->
                            when {
                                data == "[DONE]" ->
                                    out +=
                                        JSONObject()
                                            .put(
                                                "type",
                                                "__ybrowser_done__",
                                            )
                                else ->
                                    parseJson(data)
                                        ?.let(out::add)
                            }
                        }
                    }
                }
            }
        }

        if (out.isNotEmpty()) return out

        // Final fail-open for odd buffering: individual data lines are still
        // product-owned SSE records and can be safely decoded independently.
        text.lineSequence()
            .map { it.trim() }
            .filter {
                it.startsWith("data:")
            }
            .forEach { line ->
                val data =
                    line.removePrefix("data:")
                        .trim()
                when {
                    data.isBlank() -> Unit
                    data == "[DONE]" ->
                        out +=
                            JSONObject()
                                .put(
                                    "type",
                                    "__ybrowser_done__",
                                )
                    else ->
                        parseJson(data)
                            ?.let(out::add)
                }
            }

        return out
    }

    private fun extractConversationId(
        raw: String,
    ): String? =
        sequenceOf(
            Regex(
                """"conversation_id"\s*:\s*"([^"]+)""""
            ),
            Regex(
                """"conversationId"\s*:\s*"([^"]+)""""
            ),
        ).mapNotNull { pattern ->
            pattern.find(raw)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf(
                    ::validConversationId
                )
        }.firstOrNull()

    private fun parseJson(
        raw: String,
    ): Any? =
        runCatching {
            when (
                val value =
                    JSONTokener(raw)
                        .nextValue()
            ) {
                is JSONObject -> value
                is JSONArray -> value
                else -> null
            }
        }.getOrNull()

    private fun applyEvent(
        state: State,
        event: Any?,
    ) {
        when (event) {
            is JSONArray -> {
                for (
                    index in
                    0 until event.length()
                ) {
                    applyEvent(
                        state,
                        event.opt(index),
                    )
                }
            }

            is JSONObject ->
                applyObject(
                    state,
                    event,
                )
        }
    }

    private fun applyObject(
        state: State,
        event: JSONObject,
    ) {
        selectNestedMessages(
            state = state,
            value = event,
        )

        adoptConversationId(
            state,
            event.optString(
                "conversation_id"
            ),
        )
        adoptConversationId(
            state,
            event.optString(
                "conversationId"
            ),
        )

        when (
            event.optString("type")
        ) {
            "__ybrowser_done__" -> {
                state.complete = true
                return
            }

            "stream_handoff" -> {
                return
            }

            "message_stream_complete" -> {
                state.complete = true
                return
            }
        }

        event.optJSONObject("message")
            ?.let {
                selectMessage(
                    state,
                    it,
                )
            }

        event.optJSONObject("payload")
            ?.let { payload ->
                adoptConversationId(
                    state,
                    payload.optString(
                        "conversation_id"
                    ),
                )
                payload
                    .optJSONObject("message")
                    ?.let {
                        selectMessage(
                            state,
                            it,
                        )
                    }
            }

        val path =
            event.optString("p")
                .takeIf {
                    it.isNotBlank()
                }
        if (path != null) {
            state.currentPath = path
        }

        when (
            val value = event.opt("v")
        ) {
            is JSONObject -> {
                adoptConversationId(
                    state,
                    value.optString(
                        "conversation_id"
                    ),
                )
                if (looksLikeMessage(value)) {
                    selectMessage(
                        state,
                        value,
                    )
                }
                value.optJSONObject("message")
                    ?.let {
                        selectMessage(
                            state,
                            it,
                        )
                    }
                applyPatchValue(
                    state = state,
                    path =
                        path
                            ?: state.currentPath,
                    operation =
                        event.optString("o"),
                    value = value,
                )
            }

            is JSONArray -> {
                for (
                    index in
                    0 until value.length()
                ) {
                    val patch =
                        value.optJSONObject(
                            index
                        ) ?: continue
                    applyPatch(
                        state,
                        patch,
                    )
                }
            }

            is String ->
                applyTextPatch(
                    state = state,
                    path =
                        path
                            ?: state.currentPath,
                    operation =
                        event.optString("o"),
                    value = value,
                )

            is Boolean -> {
                val effectivePath =
                    path
                        ?: state.currentPath
                if (
                    effectivePath ==
                        "/message/end_turn" &&
                    value
                ) {
                    state.complete = true
                }
            }
        }
    }

    private fun applyPatch(
        state: State,
        patch: JSONObject,
    ) {
        val path =
            patch.optString("p")
                .takeIf {
                    it.isNotBlank()
                }
                ?: state.currentPath
        if (path.isNotBlank()) {
            state.currentPath = path
        }

        when (
            val value = patch.opt("v")
        ) {
            is String ->
                applyTextPatch(
                    state = state,
                    path = path,
                    operation =
                        patch.optString("o"),
                    value = value,
                )

            is JSONObject ->
                applyPatchValue(
                    state = state,
                    path = path,
                    operation =
                        patch.optString("o"),
                    value = value,
                )

            is Boolean -> {
                if (
                    path ==
                        "/message/end_turn" &&
                    value
                ) {
                    state.complete = true
                }
            }
        }
    }

    private fun applyPatchValue(
        state: State,
        path: String,
        operation: String,
        value: JSONObject,
    ) {
        if (
            value.has("message")
        ) {
            value.optJSONObject("message")
                ?.let {
                    selectMessage(
                        state,
                        it,
                    )
                }
            return
        }

        if (
            path ==
                "/message/content"
        ) {
            val message =
                JSONObject()
                    .put(
                        "id",
                        state.currentMessageId
                            .orEmpty(),
                    )
                    .put(
                        "author",
                        JSONObject()
                            .put(
                                "role",
                                "assistant",
                            )
                    )
                    .put(
                        "recipient",
                        "all",
                    )
                    .put(
                        "channel",
                        "final",
                    )
                    .put(
                        "content",
                        value,
                    )
            selectMessage(
                state,
                message,
            )
            return
        }

        if (
            path ==
                "/message/status" &&
            operation.equals(
                "replace",
                ignoreCase = true,
            )
        ) {
            val status =
                value.optString("value")
            if (
                status ==
                    "finished_successfully"
            ) {
                state.complete = true
            }
        }
    }

    private fun applyTextPatch(
        state: State,
        path: String,
        operation: String,
        value: String,
    ) {
        if (
            path ==
                "/message/status"
        ) {
            if (
                value ==
                    "finished_successfully"
            ) {
                state.complete = true
            }
            return
        }

        if (
            path ==
                "/message/end_turn"
        ) {
            if (
                value.equals(
                    "true",
                    ignoreCase = true,
                )
            ) {
                state.complete = true
            }
            return
        }

        if (!state.currentVisibleAssistant) {
            return
        }

        // ChatGPT's patch protocol can omit `p` on a string delta. In that
        // form the value applies to the currently selected message target.
        // Reject only an explicit non-content path.
        if (
            path.isNotBlank() &&
            !Regex(
                """^/message/content/parts/\d+$"""
            ).matches(path)
        ) {
            return
        }

        val clean =
            sanitizeVisibleText(value)
        if (clean.isBlank()) return

        state.visibleText =
            when {
                operation.equals(
                    "replace",
                    ignoreCase = true,
                ) -> clean

                state.visibleText
                    .isBlank() -> clean

                clean.startsWith(
                    state.visibleText
                ) -> clean

                state.visibleText
                    .endsWith(clean) ->
                    state.visibleText

                else ->
                    state.visibleText +
                        clean
            }
    }

    private fun looksLikeMessage(
        value: JSONObject,
    ): Boolean =
        (
            value.optJSONObject("author") !=
                null ||
                value.optString("role")
                    .isNotBlank()
            ) &&
            (
                value.optJSONObject("content") !=
                    null ||
                    value.has("status") ||
                    value.has("end_turn")
                )

    private fun selectNestedMessages(
        state: State,
        value: Any?,
        depth: Int = 0,
    ) {
        if (
            value == null ||
            depth > 7
        ) {
            return
        }

        when (value) {
            is JSONObject -> {
                if (looksLikeMessage(value)) {
                    selectMessage(
                        state,
                        value,
                    )
                }

                listOf(
                    "message",
                    "messages",
                    "data",
                    "result",
                    "payload",
                    "turn",
                    "v",
                    "value",
                ).forEach { key ->
                    when (
                        val child =
                            value.opt(key)
                    ) {
                        is JSONObject,
                        is JSONArray ->
                            selectNestedMessages(
                                state,
                                child,
                                depth + 1,
                            )
                    }
                }
            }

            is JSONArray -> {
                for (
                    index in
                    0 until minOf(
                        value.length(),
                        128,
                    )
                ) {
                    selectNestedMessages(
                        state,
                        value.opt(index),
                        depth + 1,
                    )
                }
            }
        }
    }

    private fun selectMessage(
        state: State,
        message: JSONObject,
    ) {
        val messageId =
            message.optString("id")
                .ifBlank {
                    message.optString(
                        "message_id"
                    )
                }
                .takeIf {
                    it.isNotBlank()
                }

        if (messageId != null) {
            state.currentMessageId =
                messageId
        }

        val role =
            message
                .optJSONObject("author")
                ?.optString("role")
                .orEmpty()
                .ifBlank {
                    message.optString(
                        "role"
                    )
                }
                .trim()
                .lowercase()
        val recipient =
            message.optString(
                "recipient"
            ).ifBlank {
                "all"
            }.trim().lowercase()

        val metadata =
            message.optJSONObject(
                "metadata"
            )
        val hidden =
            metadata?.optBoolean(
                "is_visually_hidden_from_conversation",
                false,
            ) == true ||
                metadata?.optBoolean(
                    "is_visually_hidden",
                    false,
                ) == true

        val channel =
            sequenceOf(
                message.optString(
                    "channel"
                ),
                metadata
                    ?.optString("channel")
                    .orEmpty(),
                metadata
                    ?.optString(
                        "output_channel"
                    )
                    .orEmpty(),
                metadata
                    ?.optString(
                        "message_channel"
                    )
                    .orEmpty(),
            ).firstOrNull {
                it.isNotBlank()
            }.orEmpty()
                .trim()
                .lowercase()

        val content =
            message.optJSONObject(
                "content"
            )
        val contentType =
            content
                ?.optString(
                    "content_type"
                )
                .orEmpty()
                .trim()
                .lowercase()

        val visible =
            role == "assistant" &&
                recipient == "all" &&
                !hidden &&
                (
                    channel.isBlank() ||
                        channel == "final"
                    ) &&
                (
                    contentType.isBlank() ||
                        contentType == "text" ||
                        contentType ==
                        "multimodal_text"
                    )

        state.currentVisibleAssistant =
            visible

        if (!visible) return

        val newMessage =
            messageId != null &&
                messageId !=
                state.visibleMessageId
        if (newMessage) {
            state.visibleMessageId =
                messageId
            state.visibleText = ""
        }

        val fullText =
            content
                ?.optJSONArray("parts")
                ?.let(::stringParts)
                .orEmpty()
                .ifBlank {
                    content
                        ?.optString("text")
                        .orEmpty()
                }

        val clean =
            sanitizeVisibleText(
                fullText
            )
        if (clean.isNotBlank()) {
            // A full message envelope is a snapshot, not an append. Accept a
            // shorter/different value as a legitimate server revision.
            state.visibleText = clean
        }

        if (
            message.optBoolean(
                "end_turn",
                false,
            ) ||
            message.optString("status") ==
                "finished_successfully" ||
            metadata?.optBoolean(
                "is_complete",
                false,
            ) == true
        ) {
            state.complete = true
        }
    }

    private fun adoptConversationId(
        state: State,
        value: String?,
    ) {
        val id =
            value
                ?.trim()
                ?.takeIf(
                    ::validConversationId
                )
                ?: return
        state.conversationId = id
    }

    private fun validConversationId(
        value: String,
    ): Boolean =
        value.isNotBlank() &&
            !value.startsWith(
                "WEB:",
                ignoreCase = true,
            ) &&
            !value.contains('/') &&
            !value.contains('?') &&
            !value.contains('#')

    private fun pageConversationId(
        pageUrl: String,
    ): String? =
        runCatching {
            val path =
                URI(pageUrl)
                    .path
                    .orEmpty()
            Regex(
                """(?:^|/)c/([^/?#]+)(?:/|$)"""
            ).find(path)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf(
                    ::validConversationId
                )
        }.getOrNull()

    private fun stringParts(
        parts: JSONArray,
    ): String =
        buildString {
            for (
                index in
                0 until parts.length()
            ) {
                when (
                    val part =
                        parts.opt(index)
                ) {
                    is String ->
                        append(part)
                    is JSONObject ->
                        part.optString("text")
                            .takeIf {
                                it.isNotBlank()
                            }
                            ?.let(::append)
                }
            }
        }

    private fun sanitizeVisibleText(
        raw: String,
    ): String =
        raw
            .replace(
                Regex(
                    "\uE200[\\s\\S]*?\uE201"
                ),
                "",
            )
            .replace(
                Regex(
                    "[\\uE000-\\uF8FF]"
                ),
                "",
            )
            .replace("\u0000", "")
            .trim()

    private fun trimStates() {
        while (
            states.size > 32
        ) {
            states.keys.firstOrNull()
                ?.let(states::remove)
                ?: break
        }
    }
}
