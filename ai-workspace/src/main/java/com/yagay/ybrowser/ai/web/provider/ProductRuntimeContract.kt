package com.yagay.ybrowser.ai.web.provider

/**
 * YBrowser's ChatGPT provider boundary follows the public architecture of
 * kymuco/chatgpt-web-adapter (CWA), pinned for compatibility review at:
 *
 *   repository: https://github.com/kymuco/chatgpt-web-adapter
 *   release:    v0.3.0
 *   main:       83a99e79817db2bba656944e0eedcfe1c661929c
 *
 * CWA is MIT licensed. YBrowser does not embed its Python/Chromium runtime;
 * Android keeps Gecko as the browser-owned transport while preserving the same
 * product-level authority split:
 *
 *   canonical observation != incremental observation
 *   product write          != automatic retry
 *   ambiguous write        -> reconciliation
 *
 * Keeping this contract separate from Gecko/DOM details means future upstream
 * protocol changes can be adapted below this boundary without leaking them
 * throughout the UI or project-history code.
 */
internal object CwaUpstream {
    const val REPOSITORY =
        "https://github.com/kymuco/chatgpt-web-adapter"
    const val RELEASE = "v0.3.0"
    const val MAIN_COMMIT =
        "83a99e79817db2bba656944e0eedcfe1c661929c"
    const val CONTRACT_SCHEMA = 1
}

internal enum class ProductCapabilityState {
    AVAILABLE,
    UNSUPPORTED,
    UNKNOWN,
    UNIMPLEMENTED,
}

internal enum class ProductTransportSupportTier {
    PRODUCTION,
    EXPERIMENTAL,
}

internal enum class ProductObservationAuthority {
    /**
     * DOM/SSE/network deltas are useful for responsive UI but never prove
     * durable final state.
     */
    PROVISIONAL,

    /**
     * Read from the canonical conversation surface after the protected write.
     */
    CANONICAL,
}

internal enum class ProductFinality {
    PROVISIONAL,
    CANONICAL_COMPLETE,
    AMBIGUOUS,
    ERROR,
}

internal data class ProductRuntimeContract(
    val schema: Int = CwaUpstream.CONTRACT_SCHEMA,
    val runtime: String = "ChatGPTProductRuntime",
    val productSemantics: String = "ordinary-chatgpt",
    val transport: String = "gecko-browser-owned",
    val transportSupportTier: ProductTransportSupportTier =
        ProductTransportSupportTier.PRODUCTION,
    val canonicalInterface: String = "CanonicalConversationClient",
    val writeTransportInterface: String = "ProductWriteTransport",
    val automaticWriteRetry: Boolean = false,
    val fallbackTransport: String? = null,
    val legacyDirectWriteFallback: Boolean = false,
    val ambiguousWriteRequiresReconciliation: Boolean = true,
    val incrementalObservationIsCanonicalFinality: Boolean = false,
    val browserImplementationRequiredByCaller: Boolean = false,
) {
    init {
        require(schema == 1)
        require(productSemantics == "ordinary-chatgpt")
        require(canonicalInterface == "CanonicalConversationClient")
        require(writeTransportInterface == "ProductWriteTransport")
        require(!automaticWriteRetry)
        require(fallbackTransport == null)
        require(!legacyDirectWriteFallback)
        require(ambiguousWriteRequiresReconciliation)
        require(!incrementalObservationIsCanonicalFinality)
        require(!browserImplementationRequiredByCaller)
    }
}

internal object ChatGptProductContract {
    val runtime = ProductRuntimeContract()

    val capabilityDefaults:
        Map<String, ProductCapabilityState> =
        linkedMapOf(
            "text" to ProductCapabilityState.AVAILABLE,
            "canonical_conversation_read" to
                ProductCapabilityState.AVAILABLE,
            "streaming" to ProductCapabilityState.AVAILABLE,
            "attachments" to ProductCapabilityState.AVAILABLE,
            "images" to ProductCapabilityState.AVAILABLE,
            "general_files" to ProductCapabilityState.AVAILABLE,
            "multimodal_continuation" to
                ProductCapabilityState.AVAILABLE,
            "generated_artifact_download" to
                ProductCapabilityState.UNKNOWN,
            "tools_connectors" to
                ProductCapabilityState.UNKNOWN,
        )
}
