package com.yagay.ybrowser.ai.web.provider

/**
 * UI-independent provider contract inspired by mature multi-provider clients:
 * every provider exposes capabilities and emits the same small set of
 * lifecycle events. Provider-specific HTTP/SSE/DOM details stay behind the
 * adapter and never leak into AIHub.
 */
internal data class ProviderProtocolCapabilities(
    val text: Boolean = true,
    val attachments: Boolean = false,
    val stop: Boolean = true,
    val history: Boolean = true,
    val streaming: Boolean = true,
)

internal sealed interface ProviderProtocolEvent {
    data class History(
        val complete: Boolean,
        val messageCount: Int,
    ) : ProviderProtocolEvent

    data class Update(
        val messageCount: Int,
    ) : ProviderProtocolEvent

    data object Done : ProviderProtocolEvent

    data class Error(
        val reason: String,
    ) : ProviderProtocolEvent
}

internal interface ProviderProtocolAdapter {
    val providerId: String
    val protocolCapabilities: ProviderProtocolCapabilities
}
