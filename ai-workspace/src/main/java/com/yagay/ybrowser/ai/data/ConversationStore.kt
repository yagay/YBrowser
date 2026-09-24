package com.yagay.ybrowser.ai.data

import android.content.Context
import com.yagay.ybrowser.ai.diagnostics.DiagnosticLogger
import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ChatMessage
import com.yagay.ybrowser.ai.model.MessageRole
import com.yagay.ybrowser.ai.model.WindowSessionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ConversationStore(context: Context) {
    private val appContext = context.applicationContext
    private val legacyPrefs =
        appContext.getSharedPreferences("aihub_conversations", Context.MODE_PRIVATE)
    private val dao by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED
    ) {
        ConversationDatabase.get(appContext)
            .conversationDao()
    }

    suspend fun load(
        session: WindowSessionKey,
    ): List<ChatMessage> =
        runCatching {
            withContext(Dispatchers.IO) {
                val stored =
                    dao.loadMessages(session.storageKey)
                if (stored.isNotEmpty()) {
                    return@withContext stored.map(::toModel)
                }

                val legacy = loadLegacy(session)
                if (legacy.isNotEmpty()) {
                    saveInternal(session, legacy)
                    legacyPrefs.edit()
                        .remove(session.storageKey)
                        .apply()
                }
                legacy
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            logFailure(
                operation = "load",
                session = session,
                error = it,
            )
            emptyList()
        }

    suspend fun save(
        session: WindowSessionKey,
        messages: List<ChatMessage>,
    ) {
        runCatching {
            withContext(Dispatchers.IO) {
                saveInternal(session, messages)
                if (legacyPrefs.contains(session.storageKey)) {
                    legacyPrefs.edit()
                        .remove(session.storageKey)
                        .apply()
                }
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            logFailure(
                operation = "save",
                session = session,
                error = it,
            )
        }
    }

    suspend fun clear(
        session: WindowSessionKey,
    ) {
        runCatching {
            withContext(Dispatchers.IO) {
                dao.clearSession(session.storageKey)
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            logFailure(
                operation = "clear",
                session = session,
                error = it,
            )
        }

        legacyPrefs.edit()
            .remove(session.storageKey)
            .apply()
    }

    private fun saveInternal(
        session: WindowSessionKey,
        messages: List<ChatMessage>,
    ) {
        val conversation = StoredConversationEntity(
            sessionKey = session.storageKey,
            providerId = session.providerId,
            windowId = session.windowId,
            updatedAt = System.currentTimeMillis(),
        )
        val entities = messages.mapIndexed { index, message ->
            StoredMessageEntity(
                sessionKey = session.storageKey,
                messageId = message.id,
                sequence = index,
                role = message.role.name,
                text = message.text,
                timestamp = message.timestamp,
                attachmentsJson = encodeAttachments(message.attachments),
            )
        }
        dao.saveIncremental(conversation, entities)
    }

    private fun toModel(entity: StoredMessageEntity): ChatMessage =
        ChatMessage(
            id = entity.messageId,
            role = runCatching { MessageRole.valueOf(entity.role) }
                .getOrDefault(MessageRole.SYSTEM),
            text = entity.text,
            timestamp = entity.timestamp,
            attachments = decodeAttachments(entity.attachmentsJson),
        )

    private fun loadLegacy(session: WindowSessionKey): List<ChatMessage> = runCatching {
        val array = JSONArray(legacyPrefs.getString(session.storageKey, "[]") ?: "[]")
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
                                name = item.optString("name")
                                    .ifBlank { "attachment-${j + 1}" },
                                mimeType = item.optString(
                                    "mimeType",
                                    "application/octet-stream",
                                ),
                                sizeBytes = item.optLong("sizeBytes", 0L),
                                uri = item.optString("uri")
                                    .takeIf { it.isNotBlank() },
                            )
                        )
                    }
                }
                add(
                    ChatMessage(
                        id = o.optString("id").ifBlank { "legacy-$i" },
                        role = runCatching {
                            MessageRole.valueOf(o.optString("role"))
                        }.getOrDefault(MessageRole.SYSTEM),
                        text = o.optString("text"),
                        timestamp = o.optLong(
                            "timestamp",
                            System.currentTimeMillis(),
                        ),
                        attachments = attachments,
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun encodeAttachments(items: List<AttachmentMeta>): String {
        val array = JSONArray()
        items.forEach { attachment ->
            array.put(
                JSONObject()
                    .put("id", attachment.id)
                    .put("name", attachment.name)
                    .put("mimeType", attachment.mimeType)
                    .put("sizeBytes", attachment.sizeBytes)
                    .put("uri", attachment.uri.orEmpty())
            )
        }
        return array.toString()
    }

    private fun decodeAttachments(raw: String): List<AttachmentMeta> = runCatching {
        val array = JSONArray(raw.ifBlank { "[]" })
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    AttachmentMeta(
                        id = item.optString("id")
                            .ifBlank { "db-attachment-$index" },
                        name = item.optString("name")
                            .ifBlank { "attachment-${index + 1}" },
                        mimeType = item.optString(
                            "mimeType",
                            "application/octet-stream",
                        ),
                        sizeBytes = item.optLong("sizeBytes", 0L),
                        uri = item.optString("uri")
                            .takeIf { it.isNotBlank() },
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun logFailure(
        operation: String,
        session: WindowSessionKey,
        error: Throwable,
    ) {
        DiagnosticLogger.e(
            "CONVERSATION_STORE",
            operation +
                " failed provider=" +
                session.providerId +
                " window=" +
                session.windowId.take(12),
            error,
        )
    }

}
