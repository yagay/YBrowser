package com.yagay.ybrowser.ai.data

import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ChatWindow
import com.yagay.ybrowser.ai.model.WindowViewMode
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal object WorkspaceStateCodec {
    const val CURRENT_SCHEMA = 2

    data class DecodeResult(
        val windows: List<ChatWindow>,
        val sourceSchema: Int,
        val repaired: Boolean,
    )

    fun encode(
        windows: List<ChatWindow>,
    ): String {
        val items = JSONArray()
        windows.forEach { window ->
            items.put(
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

        return JSONObject()
            .put("schemaVersion", CURRENT_SCHEMA)
            .put("windows", items)
            .toString()
    }

    fun decode(
        raw: String,
    ): DecodeResult {
        if (raw.isBlank()) {
            return DecodeResult(
                windows = emptyList(),
                sourceSchema = CURRENT_SCHEMA,
                repaired = false,
            )
        }

        val root =
            runCatching {
                JSONTokener(raw).nextValue()
            }.getOrNull()
                ?: return DecodeResult(
                    windows = emptyList(),
                    sourceSchema = 0,
                    repaired = true,
                )

        val sourceSchema: Int
        val array: JSONArray
        when (root) {
            is JSONArray -> {
                sourceSchema = 1
                array = root
            }
            is JSONObject -> {
                sourceSchema =
                    root.optInt(
                        "schemaVersion",
                        1,
                    )
                array =
                    root.optJSONArray("windows")
                        ?: JSONArray()
            }
            else ->
                return DecodeResult(
                    windows = emptyList(),
                    sourceSchema = 0,
                    repaired = true,
                )
        }

        var repaired = false
        val windows =
            buildList {
                for (
                    index in 0 until
                        array.length()
                ) {
                    val item =
                        array.optJSONObject(index)
                    if (item == null) {
                        repaired = true
                        continue
                    }

                    val id =
                        item.optString("id")
                            .trim()
                    val providerId =
                        item.optString(
                            "providerId"
                        ).trim()
                    if (
                        id.isBlank() ||
                        providerId.isBlank()
                    ) {
                        repaired = true
                        continue
                    }

                    val window =
                        runCatching {
                            ChatWindow(
                                id = id,
                                providerId =
                                    providerId,
                                title =
                                    item.optString(
                                        "title"
                                    ).ifBlank {
                                        "新对话"
                                    },
                                url =
                                    item.optString(
                                        "url"
                                    ).takeIf {
                                        it.isNotBlank()
                                    },
                                boundUrl =
                                    item.optString(
                                        "boundUrl"
                                    ).takeIf {
                                        it.isNotBlank()
                                    },
                                boundRepo =
                                    item.optString(
                                        "boundRepo"
                                    ).takeIf {
                                        it.isNotBlank()
                                    },
                                boundProject =
                                    item.optString(
                                        "boundProject"
                                    ).takeIf {
                                        it.isNotBlank()
                                    },
                                viewMode =
                                    runCatching {
                                        WindowViewMode
                                            .valueOf(
                                                item.optString(
                                                    "viewMode",
                                                    WindowViewMode
                                                        .CHAT
                                                        .name,
                                                )
                                            )
                                    }.getOrDefault(
                                        WindowViewMode
                                            .CHAT
                                    ),
                                createdAt =
                                    item.optLong(
                                        "createdAt",
                                        System
                                            .currentTimeMillis(),
                                    ),
                                lastActiveAt =
                                    item.optLong(
                                        "lastActiveAt",
                                        System
                                            .currentTimeMillis(),
                                    ),
                            )
                        }.getOrNull()

                    if (window == null) {
                        repaired = true
                    } else {
                        add(window)
                    }
                }
            }

        return DecodeResult(
            windows = windows,
            sourceSchema = sourceSchema,
            repaired =
                repaired ||
                    sourceSchema !=
                        CURRENT_SCHEMA,
        )
    }
}

internal object PendingAttachmentCodec {
    const val CURRENT_SCHEMA = 2

    data class DecodeResult(
        val attachments:
            List<AttachmentMeta>,
        val sourceSchema: Int,
        val repaired: Boolean,
    )

    fun encode(
        attachments: List<AttachmentMeta>,
    ): String {
        val items = JSONArray()
        attachments.forEach { item ->
            items.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put(
                        "mimeType",
                        item.mimeType,
                    )
                    .put(
                        "sizeBytes",
                        item.sizeBytes,
                    )
                    .put(
                        "uri",
                        item.uri.orEmpty(),
                    )
            )
        }
        return JSONObject()
            .put(
                "schemaVersion",
                CURRENT_SCHEMA,
            )
            .put("attachments", items)
            .toString()
    }

    fun decode(
        raw: String,
    ): DecodeResult {
        if (raw.isBlank()) {
            return DecodeResult(
                attachments = emptyList(),
                sourceSchema =
                    CURRENT_SCHEMA,
                repaired = false,
            )
        }

        val root =
            runCatching {
                JSONTokener(raw).nextValue()
            }.getOrNull()
                ?: return DecodeResult(
                    attachments = emptyList(),
                    sourceSchema = 0,
                    repaired = true,
                )

        val sourceSchema: Int
        val array: JSONArray
        when (root) {
            is JSONArray -> {
                sourceSchema = 1
                array = root
            }
            is JSONObject -> {
                sourceSchema =
                    root.optInt(
                        "schemaVersion",
                        1,
                    )
                array =
                    root.optJSONArray(
                        "attachments"
                    ) ?: JSONArray()
            }
            else ->
                return DecodeResult(
                    attachments = emptyList(),
                    sourceSchema = 0,
                    repaired = true,
                )
        }

        var repaired = false
        val attachments =
            buildList {
                for (
                    index in 0 until
                        array.length()
                ) {
                    val item =
                        array.optJSONObject(index)
                    if (item == null) {
                        repaired = true
                        continue
                    }

                    val name =
                        item.optString("name")
                            .ifBlank {
                                "attachment-" +
                                    (index + 1)
                            }
                    val attachment =
                        runCatching {
                            AttachmentMeta(
                                id =
                                    item.optString(
                                        "id"
                                    ).ifBlank {
                                        "pending-$index"
                                    },
                                name = name,
                                mimeType =
                                    item.optString(
                                        "mimeType",
                                        "application/octet-stream",
                                    ),
                                sizeBytes =
                                    item.optLong(
                                        "sizeBytes",
                                        0L,
                                    ),
                                uri =
                                    item.optString(
                                        "uri"
                                    ).takeIf {
                                        it.isNotBlank()
                                    },
                            )
                        }.getOrNull()
                    if (attachment == null) {
                        repaired = true
                    } else {
                        add(attachment)
                    }
                }
            }

        return DecodeResult(
            attachments = attachments,
            sourceSchema = sourceSchema,
            repaired =
                repaired ||
                    sourceSchema !=
                        CURRENT_SCHEMA,
        )
    }
}
