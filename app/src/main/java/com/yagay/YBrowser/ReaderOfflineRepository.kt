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

    /** Serialize operations against concurrent save/delete/read to avoid AtomicFile races.
     * Call disk-heavy operations from Dispatchers.IO.
     */
    @Synchronized
    fun save(context: Context, document: ReaderDocument): Boolean = runCatching {
        if (document.blocks.isEmpty() ||
            !OfflineSnapshotPolicy.canSave(document.sourceUrl, privateMode = false)
        ) return false
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val file = File(dir, fileName(document.sourceUrl))
        if (!hasCommittedFile(file) &&
            snapshotFiles(dir).size >= MAX_SAVED_DOCUMENTS
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

    @Synchronized
    fun remove(context: Context, sourceUrl: String): Boolean = runCatching {
        val file = File(File(context.filesDir, DIR_NAME), fileName(sourceUrl))
        val atomic = AtomicFile(file)
        if (!hasCommittedFile(file)) return false
        // Remove AtomicFile's backup/new siblings too; otherwise a later
        // crash-recovery read could resurrect a deleted offline article.
        atomic.delete()
        true
    }.getOrDefault(false)

    @Synchronized
    fun contains(context: Context, sourceUrl: String): Boolean {
        val file = File(File(context.filesDir, DIR_NAME), fileName(sourceUrl))
        return hasCommittedFile(file)
    }

    /** Opens one offline document without reading/scanning the entire library. */
    @Synchronized
    fun load(context: Context, sourceUrl: String): SavedReaderDocument? =
        read(File(File(context.filesDir, DIR_NAME), fileName(sourceUrl)))

    private fun read(file: File): SavedReaderDocument? = runCatching {
        // A failed AtomicFile write may leave only .bak; openRead restores it.
        if (!hasCommittedFile(file)) return@runCatching null
        val root = AtomicFile(file).openRead().use { input ->
            val bytes = ByteArray(8 * 1024).let { buffer ->
                val output = java.io.ByteArrayOutputStream()
                while (output.size() <= MAX_READ_BYTES) {
                    val read = input.read(
                        buffer, 0, minOf(buffer.size, MAX_READ_BYTES + 1 - output.size()),
                    )
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            if (bytes.isEmpty() || bytes.size > MAX_READ_BYTES) {
                return@runCatching null
            }
            JSONObject(String(bytes, Charsets.UTF_8))
        }
        val document = root.optJSONObject("document")?.toReaderDocument()
            ?: return@runCatching null
        if (!OfflineSnapshotPolicy.canSave(document.sourceUrl, privateMode = false) ||
            file.name != fileName(document.sourceUrl)
        ) return@runCatching null
        SavedReaderDocument(
            document = document,
            savedAt = root.optLong("savedAt", file.lastModified()),
        )
    }.getOrNull()

    private fun hasCommittedFile(file: File): Boolean =
        file.isFile || File(file.path + ".bak").isFile

    /** Deduplicate main/backup pairs, excluding unfinished .new files. */
    private fun snapshotFiles(dir: File): List<File> =
        dir.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".json.bak")) }
            .map { file ->
                if (file.name.endsWith(".bak")) File(file.path.removeSuffix(".bak")) else file
            }
            .distinctBy { it.name }
            .toList()

    @Synchronized
    fun list(context: Context): List<SavedReaderDocument> {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.isDirectory) return emptyList()
        return snapshotFiles(dir)
            .asSequence()
            // Never truncate unsorted directory iteration: newly saved files
            // can appear after older or corrupt entries on some filesystems.
            .mapNotNull(::read)
            .sortedByDescending { it.savedAt }
            .take(MAX_SAVED_DOCUMENTS)
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
