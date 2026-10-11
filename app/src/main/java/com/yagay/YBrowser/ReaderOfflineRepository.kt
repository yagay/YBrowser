package com.yagay.YBrowser

import android.content.Context
import android.util.AtomicFile
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
    private const val MAX_SAVE_BYTES = 2 * 1024 * 1024
    private const val MAX_READ_BYTES = 2 * 1024 * 1024
    private const val MAX_SAVED_DOCUMENTS = 500

    /** Write a bounded, crash-safe reader snapshot; invoke from Dispatchers.IO. */
    fun save(context: Context, document: ReaderDocument): Boolean = runCatching {
        if (document.blocks.isEmpty() ||
            !OfflineSnapshotPolicy.canSave(document.sourceUrl, privateMode = false)
        ) return false
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val file = File(dir, fileName(document.sourceUrl))
        if (!file.exists() &&
            dir.listFiles()?.count { it.isFile && it.extension == "json" }
                ?.let { it >= MAX_SAVED_DOCUMENTS } == true
        ) return false
        val bytes = JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("document", document.toJson())
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_SAVE_BYTES) return false

        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
        true
    }.getOrDefault(false)

    fun remove(context: Context, sourceUrl: String): Boolean = runCatching {
        val file = File(File(context.filesDir, DIR_NAME), fileName(sourceUrl))
        val atomic = AtomicFile(file)
        if (!atomic.exists()) return false
        // Remove AtomicFile's backup/new siblings too; otherwise a later
        // crash-recovery read could resurrect a deleted offline article.
        atomic.delete()
        true
    }.getOrDefault(false)

    fun contains(context: Context, sourceUrl: String): Boolean =
        AtomicFile(File(File(context.filesDir, DIR_NAME), fileName(sourceUrl))).exists()

    /** Opens one offline document without reading/scanning the entire library. */
    fun load(context: Context, sourceUrl: String): SavedReaderDocument? =
        read(File(File(context.filesDir, DIR_NAME), fileName(sourceUrl)))

    private fun read(file: File): SavedReaderDocument? = runCatching {
        if (!file.isFile || file.length() !in 1L..MAX_READ_BYTES.toLong()) {
            return@runCatching null
        }
        val root = AtomicFile(file).openRead().use { input ->
            val bytes = input.readBytes()
            if (bytes.isEmpty() || bytes.size > MAX_READ_BYTES) {
                return@runCatching null
            }
            JSONObject(String(bytes, Charsets.UTF_8))
        }
        val document = root.optJSONObject("document")?.toReaderDocument()
            ?: return@runCatching null
        SavedReaderDocument(
            document = document,
            savedAt = root.optLong("savedAt", file.lastModified()),
        )
    }.getOrNull()

    fun list(context: Context): List<SavedReaderDocument> {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension == "json" }
            .take(MAX_SAVED_DOCUMENTS)
            .mapNotNull(::read)
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
