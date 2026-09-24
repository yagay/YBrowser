package com.yagay.ybrowser.ai.web.provider

import com.yagay.ybrowser.ai.model.ProviderSpec
import com.yagay.ybrowser.ai.web.CapturedNetworkPayload
import com.yagay.ybrowser.ai.web.WebRuntime

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

    fun parseCanonicalRead(
        provider: ProviderSpec,
        body: String,
        endpoint: String,
        pageUrl: String,
    ): WebRuntime.ConversationSnapshot? =
        ChatGptWireDecoder
            .parseNetwork(
                provider = provider,
                capture =
                    CapturedNetworkPayload(
                        requestId =
                            "canonical-" +
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
                source = "canonical-read",
                authority =
                    ProductObservationAuthority.CANONICAL,
                finality =
                    ProductFinality.CANONICAL_COMPLETE,
            )
}
