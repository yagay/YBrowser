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
    ): WebRuntime.ConversationSnapshot? =
        ChatGptActiveStreamProvider.parse(
            provider = provider,
            capture = capture,
            pageUrl = pageUrl,
        )

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
