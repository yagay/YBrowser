package com.yagay.ybrowser.ai.data

import android.content.Context
import com.yagay.ybrowser.ai.model.ChatWindow
import com.yagay.ybrowser.ai.model.WindowViewMode
import org.json.JSONArray
import org.json.JSONObject

class WindowStore(context: Context) {
    private val prefs = context.getSharedPreferences("aihub_workspace", Context.MODE_PRIVATE)

    fun load(): List<ChatWindow> = runCatching {
        val array = JSONArray(prefs.getString(KEY_WINDOWS, "[]") ?: "[]")
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val providerId = item.optString("providerId")
                if (id.isBlank() || providerId.isBlank()) continue
                add(
                    ChatWindow(
                        id = id,
                        providerId = providerId,
                        title = item.optString("title").ifBlank { "新对话" },
                        url = item.optString("url").takeIf { it.isNotBlank() },
                        boundUrl = item.optString("boundUrl").takeIf { it.isNotBlank() },
                        boundRepo = item.optString("boundRepo").takeIf { it.isNotBlank() },
                        boundProject = item.optString("boundProject").takeIf { it.isNotBlank() },
                        viewMode = runCatching {
                            WindowViewMode.valueOf(item.optString("viewMode", WindowViewMode.CHAT.name))
                        }.getOrDefault(WindowViewMode.CHAT),
                        createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                        lastActiveAt = item.optLong("lastActiveAt", System.currentTimeMillis())
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun save(windows: List<ChatWindow>) {
        val array = JSONArray()
        windows.forEach { window ->
            array.put(
                JSONObject()
                    .put("id", window.id)
                    .put("providerId", window.providerId)
                    .put("title", window.title)
                    .put("url", window.url.orEmpty())
                    .put("boundUrl", window.boundUrl.orEmpty())
                    .put("boundRepo", window.boundRepo.orEmpty())
                    .put("boundProject", window.boundProject.orEmpty())
                    .put("viewMode", window.viewMode.name)
                    .put("createdAt", window.createdAt)
                    .put("lastActiveAt", window.lastActiveAt)
            )
        }
        prefs.edit().putString(KEY_WINDOWS, array.toString()).apply()
    }

    fun loadActiveId(): String? = prefs.getString(KEY_ACTIVE, null)

    fun saveActiveId(id: String) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    companion object {
        private const val KEY_WINDOWS = "windows"
        private const val KEY_ACTIVE = "active_window"
    }
}
