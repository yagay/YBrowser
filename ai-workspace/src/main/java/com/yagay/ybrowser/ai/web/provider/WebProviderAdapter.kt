package com.yagay.ybrowser.ai.web.provider

import com.yagay.ybrowser.ai.model.ProviderSpec
import com.yagay.ybrowser.ai.web.CapturedNetworkPayload
import com.yagay.ybrowser.ai.web.WebRuntime

internal interface WebProviderAdapter : ProviderProtocolAdapter {
    override val providerId: String
    override val protocolCapabilities: ProviderProtocolCapabilities
        get() = ProviderProtocolCapabilities()
    val captureUrlHints: List<String>

    fun parseNetwork(
        provider: ProviderSpec,
        capture: CapturedNetworkPayload,
        pageUrl: String,
    ): WebRuntime.ConversationSnapshot?
}

internal object WebProviderAdapterRegistry {
    private val adapters: Map<String, WebProviderAdapter> = listOf(
        ChatGptWebProviderAdapter,
        ClaudeWebProviderAdapter,
        GeminiWebProviderAdapter,
        GrokWebProviderAdapter,
        DeepSeekWebProviderAdapter,
        QwenWebProviderAdapter,
    ).associateBy { it.providerId }

    fun forProvider(provider: ProviderSpec): WebProviderAdapter =
        adapters[provider.id] ?: GenericWebProviderAdapter(
            providerId = provider.id,
            captureUrlHints = emptyList(),
        )

    fun captureUrlHints(provider: ProviderSpec): List<String> =
        forProvider(provider).captureUrlHints
}
