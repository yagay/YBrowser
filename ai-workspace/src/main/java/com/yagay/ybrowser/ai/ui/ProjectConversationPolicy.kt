package com.yagay.ybrowser.ai.ui

import android.net.Uri
import com.yagay.ybrowser.ai.model.ChatWindow
import com.yagay.ybrowser.ai.web.provider.ChatGptWebProviderAdapter

/**
 * Project-tab identity and conversation-source policy.
 *
 * A project tab is not a URL. It may accumulate multiple ChatGPT
 * conversations, while boundUrl is only the currently active conversation.
 */
internal object ProjectConversationPolicy {
    fun pageIdentity(
        value: String?,
    ): String? =
        runCatching {
            val uri =
                Uri.parse(
                    value.orEmpty().trim()
                )
            val scheme =
                uri.scheme
                    ?.lowercase()
                    .orEmpty()
            val host =
                uri.host
                    ?.lowercase()
                    .orEmpty()

            if (
                scheme !in setOf(
                    "http",
                    "https",
                ) ||
                host.isBlank()
            ) {
                return@runCatching null
            }

            val path =
                uri.path
                    .orEmpty()
                    .ifBlank { "/" }
                    .trimEnd('/')
                    .ifBlank { "/" }

            "$scheme://$host$path"
        }.getOrNull()

    fun sameBoundPage(
        left: String?,
        right: String?,
    ): Boolean {
        val leftChat =
            ChatGptWebProviderAdapter
                .pageConversationId(left)
        val rightChat =
            ChatGptWebProviderAdapter
                .pageConversationId(right)

        if (
            leftChat != null &&
            rightChat != null
        ) {
            return leftChat == rightChat
        }

        val a =
            pageIdentity(left)
                ?: return false
        val b =
            pageIdentity(right)
                ?: return false

        return a == b
    }

    fun canonicalSourceKey(
        url: String?,
    ): String? =
        ChatGptWebProviderAdapter
            .pageConversationId(url)
            ?.let { "chatgpt:$it" }
            ?: pageIdentity(url)

    fun projectConversationSources(
        window: ChatWindow,
    ): List<String> {
        val current =
            (window.boundUrl ?: window.url)
                ?.trim()
                ?.takeIf(String::isNotBlank)

        val unique =
            (
                window.conversationUrls +
                    listOfNotNull(current)
                )
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinctBy {
                    canonicalSourceKey(it)
                        ?: it
                }

        if (current == null) {
            return unique
        }

        return unique.filterNot {
            sameBoundPage(
                it,
                current,
            )
        } + current
    }

    fun windowOwnsConversationSource(
        window: ChatWindow,
        url: String?,
    ): Boolean =
        !url.isNullOrBlank() &&
            projectConversationSources(window)
                .any {
                    sameBoundPage(
                        it,
                        url,
                    )
                }

    fun sameProjectBinding(
        window: ChatWindow,
        repoKey: String,
        project: String,
    ): Boolean {
        if (
            repoKey.isNotBlank() &&
            !window.boundRepo.isNullOrBlank()
        ) {
            return window.boundRepo.equals(
                repoKey,
                ignoreCase = true,
            )
        }

        return repoKey.isBlank() &&
            project.isNotBlank() &&
            window.boundRepo.isNullOrBlank() &&
            window.boundProject.equals(
                project,
                ignoreCase = true,
            )
    }

    fun mergeConversationUrls(
        window: ChatWindow,
        newUrl: String?,
    ): List<String> {
        val ordered =
            buildList {
                window.conversationUrls
                    .forEach(::add)
                window.boundUrl
                    ?.let(::add)
                newUrl
                    ?.let(::add)
            }

        val seen =
            mutableSetOf<String>()

        return ordered
            .map(String::trim)
            .filter(String::isNotBlank)
            .filter { raw ->
                seen.add(
                    canonicalSourceKey(raw)
                        ?: raw
                )
            }
    }

    fun conversationSourceKey(
        url: String?,
    ): String {
        ChatGptWebProviderAdapter
            .pageConversationId(url)
            ?.let {
                return "chatgpt-$it"
            }

        val identity =
            pageIdentity(url)
                ?: url.orEmpty()
                    .trim()
                    .trimEnd('/')
                    .ifBlank {
                        "unknown"
                    }

        return Integer.toHexString(
            identity.hashCode()
        )
    }
}
