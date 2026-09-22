package com.yagay.ybrowser.ai.web

import org.json.JSONObject

data class ProviderCapabilities(
    val text: Boolean = false,
    val attachments: Boolean = false,
    val stop: Boolean = false,
    val newChat: Boolean = false,
    val model: Boolean = false,
    val search: Boolean = false,
    val reasoning: Boolean = false,
    val deepResearch: Boolean = false,
    val imageGeneration: Boolean = false,
    val tools: Boolean = false,
    val retry: Boolean = false,
    val continueGeneration: Boolean = false,
    val copy: Boolean = false,
    val edit: Boolean = false,
    val conversationMenu: Boolean = false,
    val rename: Boolean = false,
    val deleteConversation: Boolean = false,
    val history: Boolean = false,
    val voice: Boolean = false,
    val currentModel: String = "",
    val title: String = "",
    val path: String = "",
    val error: String = ""
) {
    val hasAdvancedFeatures: Boolean
        get() = model || search || reasoning || deepResearch || imageGeneration || tools ||
            retry || continueGeneration || copy || edit || rename || deleteConversation || history || voice

    companion object {
        fun fromJson(raw: String?): ProviderCapabilities {
            if (raw.isNullOrBlank()) return ProviderCapabilities()
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return ProviderCapabilities()
            return ProviderCapabilities(
                text = obj.optBoolean("text"),
                attachments = obj.optBoolean("attachments"),
                stop = obj.optBoolean("stop"),
                newChat = obj.optBoolean("newChat"),
                model = obj.optBoolean("model"),
                search = obj.optBoolean("search"),
                reasoning = obj.optBoolean("reasoning"),
                deepResearch = obj.optBoolean("deepResearch"),
                imageGeneration = obj.optBoolean("imageGeneration"),
                tools = obj.optBoolean("tools"),
                retry = obj.optBoolean("retry"),
                continueGeneration = obj.optBoolean("continue"),
                copy = obj.optBoolean("copy"),
                edit = obj.optBoolean("edit"),
                conversationMenu = obj.optBoolean("conversationMenu"),
                rename = obj.optBoolean("rename"),
                deleteConversation = obj.optBoolean("deleteConversation"),
                history = obj.optBoolean("history"),
                voice = obj.optBoolean("voice"),
                currentModel = obj.optString("currentModel"),
                title = obj.optString("title"),
                path = obj.optString("path"),
                error = obj.optString("error")
            )
        }
    }
}
