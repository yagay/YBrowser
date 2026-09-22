package com.yagay.ybrowser.ai.web

import android.content.Context

class ScriptLoader(private val context: Context) {
    private val cache = mutableMapOf<String, String>()

    fun conversationScript(asset: String): String = buildString {
        append(load(asset))
        append('\n')
        append(load("providers/conversation-sync.js"))
    }

    fun chatPresentationScript(asset: String): String = buildString {
        append(load(asset))
        append('\n')
        append(load("providers/chat-presentation.js"))
    }

    fun providerScript(asset: String): String = buildString {
        append(load(asset))
        append('\n')
        append(load("providers/common.js"))
        append('\n')
        append(load("providers/attachment.js"))
        append('\n')
        append(load("providers/send-queue.js"))
        append('\n')
        append(load("providers/response-state.js"))
        append('\n')
        append(load("providers/conversation-sync.js"))
        append('\n')
        append(load("providers/capabilities.js"))
        append('\n')
        append(load("providers/network-diagnostics.js"))
        append('\n')
        append(load("providers/diagnostics.js"))
        append('\n')
        append(load("providers/diagnostics-augment.js"))
        append('\n')
        append(load("providers/diagnostic-hooks.js"))
    }

    private fun load(path: String): String = cache.getOrPut(path) {
        context.assets.open(path).bufferedReader().use { it.readText() }
    }
}
