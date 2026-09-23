package com.yagay.ybrowser.ai.bridge

import android.content.Context
import com.yagay.ybrowser.ai.model.ChatWindow
import com.yagay.ybrowser.ai.model.WindowViewMode
import org.json.JSONObject

internal class AiBridgeSessionStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(
            "ai_bridge_sessions",
            Context.MODE_PRIVATE,
        )

    fun save(window: ChatWindow) {
        prefs.edit()
            .putString(
                window.id,
                JSONObject()
                    .put("providerId", window.providerId)
                    .put("title", window.title)
                    .put("url", window.url.orEmpty())
                    .put("boundUrl", window.boundUrl.orEmpty())
                    .put("boundRepo", window.boundRepo.orEmpty())
                    .put("boundProject", window.boundProject.orEmpty())
                    .put("lastActiveAt", window.lastActiveAt)
                    .toString(),
            )
            .apply()
    }

    fun load(windowId: String): ChatWindow? {
        val raw = prefs.getString(windowId, null) ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val providerId = obj.optString("providerId")
        if (providerId.isBlank()) return null

        return ChatWindow(
            id = windowId,
            providerId = providerId,
            title = obj.optString("title").ifBlank { "AI" },
            url = obj.optString("url").takeIf { it.isNotBlank() },
            boundUrl = obj.optString("boundUrl").takeIf { it.isNotBlank() },
            boundRepo = obj.optString("boundRepo").takeIf { it.isNotBlank() },
            boundProject = obj.optString("boundProject").takeIf { it.isNotBlank() },
            viewMode = WindowViewMode.CHAT,
            lastActiveAt =
                obj.optLong(
                    "lastActiveAt",
                    System.currentTimeMillis(),
                ),
        )
    }

    fun updateUrl(windowId: String, url: String) {
        val current = load(windowId) ?: return
        save(
            current.copy(
                url = url,
                lastActiveAt = System.currentTimeMillis(),
            )
        )
    }
}
