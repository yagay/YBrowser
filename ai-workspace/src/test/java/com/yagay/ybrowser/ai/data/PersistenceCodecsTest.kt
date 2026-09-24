package com.yagay.ybrowser.ai.data

import com.yagay.ybrowser.ai.model.AttachmentMeta
import com.yagay.ybrowser.ai.model.ChatWindow
import com.yagay.ybrowser.ai.model.WindowViewMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistenceCodecsTest {
    @Test
    fun emptyWorkspaceRoundTripIsStable() {
        val decoded =
            WorkspaceStateCodec.decode(
                WorkspaceStateCodec.encode(
                    emptyList()
                )
            )

        assertEquals(
            WorkspaceStateCodec.CURRENT_SCHEMA,
            decoded.sourceSchema,
        )
        assertTrue(decoded.windows.isEmpty())
        assertFalse(decoded.repaired)
    }

    @Test
    fun legacyWorkspaceMigratesAndKeepsValidRows() {
        val legacy =
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", "good")
                        .put(
                            "providerId",
                            "chatgpt",
                        )
                        .put(
                            "title",
                            "Project A",
                        )
                        .put(
                            "boundUrl",
                            "https://chatgpt.com/c/1",
                        )
                        .put(
                            "boundRepo",
                            "owner/repo",
                        )
                        .put(
                            "boundProject",
                            "Repo",
                        )
                        .put(
                            "viewMode",
                            "CHAT",
                        )
                )
                .put(
                    JSONObject()
                        .put("id", "")
                        .put(
                            "providerId",
                            "",
                        )
                )
                .toString()

        val decoded =
            WorkspaceStateCodec.decode(
                legacy
            )

        assertEquals(1, decoded.sourceSchema)
        assertTrue(decoded.repaired)
        assertEquals(1, decoded.windows.size)
        assertEquals(
            "owner/repo",
            decoded.windows.single()
                .boundRepo,
        )
        assertEquals(
            "1",
            decoded.windows.single()
                .boundConversationId,
        )
    }

    @Test
    fun workspaceV3PreservesBindingIdentityAndMode() {
        val window =
            ChatWindow(
                id = "w1",
                providerId = "chatgpt",
                title = "Project",
                url =
                    "https://chatgpt.com/c/1",
                boundUrl =
                    "https://chatgpt.com/c/1",
                boundConversationId = "1",
                boundRepo = "o/r",
                boundProject = "R",
                viewMode =
                    WindowViewMode.WEB,
                createdAt = 10L,
                lastActiveAt = 20L,
            )

        val decoded =
            WorkspaceStateCodec.decode(
                WorkspaceStateCodec.encode(
                    listOf(window)
                )
            )

        assertFalse(decoded.repaired)
        assertEquals(
            window,
            decoded.windows.single(),
        )
    }

    @Test
    fun malformedWorkspaceFailsLocally() {
        val decoded =
            WorkspaceStateCodec.decode(
                "{not-json"
            )

        assertTrue(decoded.repaired)
        assertEquals(0, decoded.sourceSchema)
        assertTrue(decoded.windows.isEmpty())
    }

    @Test
    fun legacyAttachmentsMigrate() {
        val legacy =
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", "a1")
                        .put("name", "a.txt")
                        .put(
                            "mimeType",
                            "text/plain",
                        )
                        .put(
                            "sizeBytes",
                            42L,
                        )
                )
                .toString()

        val decoded =
            PendingAttachmentCodec.decode(
                legacy
            )

        assertEquals(1, decoded.sourceSchema)
        assertTrue(decoded.repaired)
        assertEquals(
            AttachmentMeta(
                id = "a1",
                name = "a.txt",
                mimeType = "text/plain",
                sizeBytes = 42L,
            ),
            decoded.attachments.single(),
        )
    }

    @Test
    fun attachmentUriRoundTripIsStable() {
        val items =
            listOf(
                AttachmentMeta(
                    id = "img",
                    name = "photo.png",
                    mimeType = "image/png",
                    sizeBytes = 128L,
                    uri =
                        "content://example/photo.png",
                )
            )

        val decoded =
            PendingAttachmentCodec.decode(
                PendingAttachmentCodec
                    .encode(items)
            )

        assertEquals(
            items,
            decoded.attachments,
        )
    }

    @Test
    fun attachmentV2RoundTripIsStable() {
        val items =
            listOf(
                AttachmentMeta(
                    id = "x",
                    name = "image.png",
                    mimeType = "image/png",
                    sizeBytes = 100L,
                )
            )
        val decoded =
            PendingAttachmentCodec.decode(
                PendingAttachmentCodec
                    .encode(items)
            )

        assertFalse(decoded.repaired)
        assertEquals(
            items,
            decoded.attachments,
        )
    }
}
