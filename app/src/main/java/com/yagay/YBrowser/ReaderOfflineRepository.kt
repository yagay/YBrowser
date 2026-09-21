package com.yagay.YBrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class SavedReaderDocument(
    val document: ReaderDocument,
    val savedAt: Long,
)

object ReaderOfflineRepository {
    private const val DIR_NAME = "reader_saves"

    fun save(context: Context, document: ReaderDocument): Boolean = runCatching {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val file = File(dir, fileName(document.sourceUrl))
        file.writeText(
            JSONObject()
                .put("savedAt", System.currentTimeMillis())
                .put("document", document.toJson())
                .toString(),
            Charsets.UTF_8,
        )
        true
    }.getOrDefault(false)

    fun remove(context: Context, sourceUrl: String): Boolean = runCatching {
        File(File(context.filesDir, DIR_NAME), fileName(sourceUrl)).delete()
    }.getOrDefault(false)

    fun contains(context: Context, sourceUrl: String): Boolean =
        File(File(context.filesDir, DIR_NAME), fileName(sourceUrl)).exists()

    fun list(context: Context): List<SavedReaderDocument> {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension == "json" }
            .mapNotNull { file ->
                runCatching {
                    val root = JSONObject(file.readText(Charsets.UTF_8))
                    val document = root.optJSONObject("document")?.toReaderDocument()
                        ?: return@runCatching null
                    SavedReaderDocument(
                        document = document,
                        savedAt = root.optLong("savedAt", file.lastModified()),
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.savedAt }
            .toList()
    }

    private fun ReaderDocument.toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("siteName", siteName)
        .put("sourceUrl", sourceUrl)
        .put(
            "blocks",
            JSONArray().apply {
                blocks.forEach { block ->
                    put(
                        JSONObject()
                            .put("kind", block.kind.name)
                            .put("level", block.level)
                            .put("text", block.text),
                    )
                }
            },
        )

    private fun JSONObject.toReaderDocument(): ReaderDocument? {
        val blocksArray = optJSONArray("blocks") ?: return null
        val blocks = buildList {
            for (index in 0 until blocksArray.length()) {
                val item = blocksArray.optJSONObject(index) ?: continue
                val kind = runCatching {
                    ReaderBlockKind.valueOf(item.optString("kind"))
                }.getOrDefault(ReaderBlockKind.PARAGRAPH)
                val text = item.optString("text").trim()
                if (text.isBlank()) continue
                add(
                    ReaderBlock(
                        kind = kind,
                        level = item.optInt("level", 0),
                        text = text,
                    ),
                )
            }
        }
        if (blocks.isEmpty()) return null
        return ReaderDocument(
            title = optString("title").ifBlank { "离线文章" },
            siteName = optString("siteName"),
            sourceUrl = optString("sourceUrl"),
            blocks = blocks,
        )
    }

    private fun fileName(sourceUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourceUrl.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return digest + ".json"
    }
}
