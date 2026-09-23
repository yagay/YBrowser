package com.yagay.ybrowser.ai.web

import com.yagay.ybrowser.ai.model.ProviderSpec
import com.yagay.ybrowser.ai.web.provider.WebProviderAdapterRegistry

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
    val canonical: Boolean = false,
    val capturedAt: Long,
)

internal object ProviderNetworkParser {
    fun captureUrlHints(provider: ProviderSpec): List<String> =
        WebProviderAdapterRegistry.captureUrlHints(provider)

    fun parse(
        provider: ProviderSpec,
        capture: CapturedNetworkPayload,
        pageUrl: String,
    ): WebRuntime.ConversationSnapshot? =
        WebProviderAdapterRegistry
            .forProvider(provider)
            .parseNetwork(
                provider = provider,
                capture = capture,
                pageUrl = pageUrl,
            )
}
