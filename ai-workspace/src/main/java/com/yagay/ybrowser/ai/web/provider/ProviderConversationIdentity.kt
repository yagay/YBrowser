package com.yagay.ybrowser.ai.web.provider

import android.net.Uri
import com.yagay.ybrowser.ai.model.ProviderSpec

/**
 * Provider-owned conversation identity.
 *
 * Workspace/project code should never infer provider conversation identity
 * from raw URL shapes. Each provider owns its own conversation/page mapping so
 * rebind, history merge and source namespacing stay stable when routes change.
 */
internal interface ProviderConversationIdentity {
    fun pageConversationId(rawUrl: String?): String?

    fun historyConversationId(rawUrl: String?): String? = null

    fun sourceKey(rawUrl: String?): String? =
        pageConversationId(rawUrl)
            ?.takeIf { it.isNotBlank() }
            ?.let { "conversation:$it" }

    fun sameConversation(
        leftUrl: String?,
        rightUrl: String?,
    ): Boolean {
        val left = pageConversationId(leftUrl)
            ?: return false
        val right = pageConversationId(rightUrl)
            ?: return false
        return left == right
    }
}

internal object ChatGptConversationIdentity :
    ProviderConversationIdentity {

    private val pagePattern =
        Regex(
            """/c/([^/?#]+)(?:[/?#]|$)""",
            RegexOption.IGNORE_CASE,
        )

    private val historyPattern =
        Regex(
            """/backend-api/conversations?/([^/?#]+)(?:[/?#]|$)""",
            RegexOption.IGNORE_CASE,
        )

    override fun pageConversationId(
        rawUrl: String?,
    ): String? =
        rawUrl
            ?.let(pagePattern::find)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)

    override fun historyConversationId(
        rawUrl: String?,
    ): String? =
        rawUrl
            ?.let(historyPattern::find)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)

    override fun sourceKey(
        rawUrl: String?,
    ): String? {
        pageConversationId(rawUrl)
            ?.let {
                return "conversation:$it"
            }

        val uri =
            runCatching {
                Uri.parse(rawUrl.orEmpty())
            }.getOrNull()
                ?: return null
        val scheme =
            uri.scheme
                ?.lowercase()
                .orEmpty()
        val host =
            uri.host
                ?.lowercase()
                .orEmpty()
        if (
            scheme !in setOf("http", "https") ||
            (
                host != "chatgpt.com" &&
                    !host.endsWith(".chatgpt.com")
            )
        ) {
            return null
        }

        val path =
            uri.path
                .orEmpty()
                .trimEnd('/')
                .ifBlank { "/" }
        return "page:" +
            Integer.toHexString(
                "$scheme://$host$path".hashCode()
            )
    }
}

internal object ProviderConversationIdentityRegistry {
    fun forProvider(
        provider: ProviderSpec,
    ): ProviderConversationIdentity? =
        when (provider.id) {
            "chatgpt" ->
                ChatGptConversationIdentity
            else -> null
        }

    fun forUrl(
        provider: ProviderSpec,
        rawUrl: String?,
    ): ProviderConversationIdentity? =
        forProvider(provider)
            ?.takeIf {
                it.sourceKey(rawUrl) != null
            }
}
