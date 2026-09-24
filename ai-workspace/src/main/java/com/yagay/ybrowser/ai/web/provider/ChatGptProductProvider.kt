package com.yagay.ybrowser.ai.web.provider

import com.yagay.ybrowser.ai.model.ProviderSpec
import com.yagay.ybrowser.ai.web.CapturedNetworkPayload
import com.yagay.ybrowser.ai.web.WebRuntime
import org.json.JSONObject
import org.json.JSONTokener

/**
 * ChatGPT product provider aligned with chatgpt-web-adapter's public runtime
 * contract. Gecko/network details stay below this layer.
 */
internal object ChatGptProductProvider : WebProviderAdapter {
    override val providerId: String =
        ChatGptWireDecoder.providerId

    override val captureUrlHints: List<String> =
        ChatGptWireDecoder.captureUrlHints

    override fun parseNetwork(
        provider: ProviderSpec,
        capture: CapturedNetworkPayload,
        pageUrl: String,
    ): WebRuntime.ConversationSnapshot? =
        ChatGptWireDecoder
            .parseNetwork(
                provider = provider,
                capture = capture,
                pageUrl = pageUrl,
            )
            ?.copy(
                // Passive network/SSE traffic is intentionally provisional.
                // It may drive responsive UI, but never canonical finality.
                authority =
                    ProductObservationAuthority.PROVISIONAL,
                finality =
                    ProductFinality.PROVISIONAL,
            )

    fun parseActiveStream(
        provider: ProviderSpec,
        capture: CapturedNetworkPayload,
        pageUrl: String,
    ): WebRuntime.ConversationSnapshot? {
        if (
            provider.id != providerId ||
            !capture.stream ||
            !capture.method.equals("POST", ignoreCase = true) ||
            capture.statusCode !in 200..299
        ) {
            return null
        }

        val path =
            runCatching {
                android.net.Uri.parse(capture.url)
                    .path
                    .orEmpty()
            }.getOrDefault("")
        if (
            !Regex(
                """^/backend-api/(?:f/)?conversation/?$"""
            ).matches(path)
        ) {
            return null
        }

        val snapshot =
            ChatGptWireDecoder.parseNetwork(
                provider = provider,
                capture = capture,
                pageUrl = pageUrl,
            ) ?: return null

        val activeConversationId =
            sequenceOf(
                Regex(
                    """"conversation_id"\s*:\s*"([^"]+)""""
                ),
                Regex(
                    """"conversationId"\s*:\s*"([^"]+)""""
                ),
            ).mapNotNull { pattern ->
                pattern.find(capture.body)
                    ?.groupValues
                    ?.getOrNull(1)
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
            }.firstOrNull()

        val lifecycleComplete =
            capture.body.contains(
                "\"type\":\"message_stream_complete\""
            ) ||
                capture.body.contains(
                    "data: [DONE]"
                )

        return snapshot.copy(
            conversationId =
                activeConversationId
                    ?: snapshot.conversationId,
            source = "network-active-stream",
            complete = lifecycleComplete,
            authority =
                ProductObservationAuthority.PROVISIONAL,
            finality =
                ProductFinality.PROVISIONAL,
        )
    }

    fun parseCanonicalRead(
        provider: ProviderSpec,
        body: String,
        endpoint: String,
        pageUrl: String,
    ): WebRuntime.ConversationSnapshot? {
        val expectedConversationId =
            Regex(
                """/backend-api/conversations?/([^/?#]+)(?:[?#]|$)"""
            ).find(endpoint)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?: return null

        val payload =
            runCatching {
                JSONTokener(body).nextValue()
                    as? JSONObject
            }.getOrNull()
                ?: return null

        val payloadConversationId =
            payload.optString(
                "conversation_id"
            ).trim()
        if (
            payloadConversationId.isNotBlank() &&
            payloadConversationId !=
                expectedConversationId
        ) {
            return null
        }

        val flatMessages =
            payload.optJSONArray("messages")
        val legacyMapping =
            payload.optJSONObject("mapping")
        if (
            flatMessages == null &&
            legacyMapping == null
        ) {
            return null
        }

        if (flatMessages != null) {
            val seen = mutableSetOf<String>()
            for (
                index in
                0 until flatMessages.length()
            ) {
                val item =
                    flatMessages
                        .optJSONObject(index)
                        ?: return null
                val message =
                    item.optJSONObject("message")
                        ?: item
                val messageId =
                    message.optString("id")
                        .trim()
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?: return null
                if (!seen.add(messageId)) {
                    return null
                }
            }
        }

        return ChatGptWireDecoder
            .parseNetwork(
                provider = provider,
                capture =
                    CapturedNetworkPayload(
                        requestId =
                            "canonical-" +
                                expectedConversationId +
                                "-" +
                                body.hashCode(),
                        url = endpoint,
                        method = "GET",
                        statusCode = 200,
                        contentType =
                            "application/json",
                        body = body,
                        stream = false,
                        complete = true,
                        truncated = false,
                        capturedAt =
                            System.currentTimeMillis(),
                    ),
                pageUrl = pageUrl,
            )
            ?.copy(
                conversationId =
                    expectedConversationId,
                source = "canonical-read",
                authority =
                    ProductObservationAuthority.CANONICAL,
                finality =
                    ProductFinality.CANONICAL_COMPLETE,
            )
    }
}
