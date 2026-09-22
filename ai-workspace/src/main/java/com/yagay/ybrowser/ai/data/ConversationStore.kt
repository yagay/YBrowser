package com.yagay.ybrowser.ai.data

import android.content.Context
import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ChatMessage
import com.yagay.ybrowser.ai.model.MessageRole
import com.yagay.ybrowser.ai.model.WindowSessionKey
import org.json.JSONArray
import org.json.JSONObject

class ConversationStore(context: Context) {
    private val prefs = context.getSharedPreferences("aihub_conversations", Context.MODE_PRIVATE)

    fun load(session: WindowSessionKey): List<ChatMessage> = runCatching {
        val array = JSONArray(prefs.getString(session.storageKey, "[]") ?: "[]")
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val attachments = buildList {
                    val items = o.optJSONArray("attachments") ?: JSONArray()
                    for (j in 0 until items.length()) {
                        val item = items.optJSONObject(j) ?: continue
                        add(
                            AttachmentMeta(
                                id = item.optString("id").ifBlank { "legacy-$i-$j" },
                                name = item.optString("name").ifBlank { "attachment-${j + 1}" },
                                mimeType = item.optString("mimeType", "application/octet-stream"),
                                sizeBytes = item.optLong("sizeBytes", 0L)
                            )
                        )
                    }
                }
                add(
                    ChatMessage(
                        id = o.getString("id"),
                        role = MessageRole.valueOf(o.getString("role")),
                        text = o.getString("text"),
                        timestamp = o.getLong("timestamp"),
                        attachments = attachments
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun save(session: WindowSessionKey, messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.forEach { message ->
            val attachments = JSONArray()
            message.attachments.forEach { attachment ->
                attachments.put(
                    JSONObject()
                        .put("id", attachment.id)
                        .put("name", attachment.name)
                        .put("mimeType", attachment.mimeType)
                        .put("sizeBytes", attachment.sizeBytes)
                )
            }
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("role", message.role.name)
                    .put("text", message.text)
                    .put("timestamp", message.timestamp)
                    .put("attachments", attachments)
            )
        }
        prefs.edit().putString(session.storageKey, array.toString()).apply()
    }

    fun clear(session: WindowSessionKey) {
        prefs.edit().remove(session.storageKey).apply()
    }
}
