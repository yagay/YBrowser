package com.yagay.ybrowser.ai.data

import android.content.Context
import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.WindowSessionKey
import org.json.JSONArray
import org.json.JSONObject

class PendingAttachmentStore(context: Context) {
    private val prefs = context.getSharedPreferences("aihub_pending_attachments", Context.MODE_PRIVATE)

    fun save(session: WindowSessionKey, attachments: List<AttachmentMeta>) {
        val array = JSONArray()
        attachments.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("mimeType", item.mimeType)
                    .put("sizeBytes", item.sizeBytes)
            )
        }
        prefs.edit().putString(session.storageKey, array.toString()).apply()
    }

    fun load(session: WindowSessionKey): List<AttachmentMeta> = runCatching {
        val array = JSONArray(prefs.getString(session.storageKey, "[]") ?: "[]")
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    AttachmentMeta(
                        id = item.optString("id").ifBlank { "pending-$i" },
                        name = item.optString("name").ifBlank { "attachment-${i + 1}" },
                        mimeType = item.optString("mimeType", "application/octet-stream"),
                        sizeBytes = item.optLong("sizeBytes", 0L)
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun consume(session: WindowSessionKey): List<AttachmentMeta> {
        val result = load(session)
        clear(session)
        return result
    }

    fun clear(session: WindowSessionKey) {
        prefs.edit().remove(session.storageKey).apply()
    }
}
