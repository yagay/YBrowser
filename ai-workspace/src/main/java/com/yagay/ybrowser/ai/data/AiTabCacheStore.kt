package com.yagay.ybrowser.ai.data

import android.content.Context
import android.net.Uri
import com.yagay.ybrowser.ai.model.ChatWindow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Owns all cold-start artifacts that belong to one AI workspace tab.
 *
 * Bound project tabs are persistent. A project tab may accumulate multiple
 * conversation URLs over time; changing the active bound URL must not erase
 * earlier project history. Unbound tabs are transient and are removed when
 * the workspace UI exits.
 */
class AiTabCacheStore(context: Context) {
    private val root = File(
        context.applicationContext.filesDir,
        "aihub_tab_cache",
    ).apply { mkdirs() }

    data class Files(
        val directory: File,
        val legacySnapshotHtml: File,
        val conversationArchive: File,
        val stylesCss: File,
        val sessionState: File,
        val metadata: File,
    )

    data class ArchiveStatus(
        val persistent: Boolean,
        val kind: String,
        val hasUsableArchive: Boolean,
        val turnCount: Int,
        val archiveBytes: Long,
        val stylesBytes: Long,
        val sessionStateBytes: Long,
        val legacySnapshotBytes: Long,
    )

    data class ArchiveTurn(
        val key: String,
        val html: String,
    )

    data class ArchiveCapture(
        val url: String,
        val title: String,
        val scrollTop: Double,
        val anchorKey: String,
        val anchorOffset: Double,
        val htmlClass: String,
        val bodyClass: String,
        val htmlStyle: String,
        val bodyStyle: String,
        val threadClass: String,
        val threadStyle: String,
        val turns: List<ArchiveTurn>,
        val css: String,
    )

    fun files(windowId: String): Files {
        val directory = File(root, safe(windowId)).apply { mkdirs() }
        return Files(
            directory = directory,
            legacySnapshotHtml = File(directory, "snapshot.html"),
            conversationArchive = File(
                directory,
                "conversation-archive.json.gz",
            ),
            stylesCss = File(directory, "styles.css.gz"),
            sessionState = File(directory, "session-state.json"),
            metadata = File(directory, "meta.json"),
        )
    }

    fun isPersistent(windowId: String): Boolean {
        val directory = File(root, safe(windowId))
        val metadata = File(directory, "meta.json")
        return readMetadata(metadata)
            ?.optBoolean("persistent", false)
            ?: false
    }

    fun writeConversationArchive(
        windowId: String,
        capture: ArchiveCapture,
    ): Int {
        if (
            !isPersistent(windowId) ||
            capture.turns.isEmpty()
        ) {
            return 0
        }

        return runCatching {
            val target = files(windowId)
            val metadata = readMetadata(target.metadata)
                ?: return@runCatching 0
            val expectedIdentity =
                metadata.optString("boundIdentity")
            val capturedIdentity =
                pageIdentity(capture.url)
            if (
                expectedIdentity.isBlank() ||
                capturedIdentity == null ||
                expectedIdentity != capturedIdentity
            ) {
                return@runCatching 0
            }

            val previous = readArchive(target.conversationArchive)

            val order = mutableListOf<String>()
            val htmlByKey = linkedMapOf<String, String>()

            previous?.optJSONArray("turns")?.let { array ->
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val key = item.optString("key").trim()
                    val html = item.optString("html")
                    if (
                        key.isBlank() ||
                        html.isBlank() ||
                        key in htmlByKey
                    ) {
                        continue
                    }
                    order += key
                    htmlByKey[key] = html
                }
            }

            val incoming = capture.turns
                .filter {
                    it.key.isNotBlank() &&
                        it.html.isNotBlank()
                }
                .map { turn ->
                    val sourceKey =
                        capturedIdentity.hashCode()
                            .let(Integer::toHexString)
                    ArchiveTurn(
                        key =
                            sourceKey +
                                "::" +
                                turn.key,
                        html = turn.html,
                    )
                }
                .distinctBy { it.key }

            incoming.forEachIndexed { index, turn ->
                if (turn.key in htmlByKey) {
                    htmlByKey[turn.key] = turn.html
                    return@forEachIndexed
                }

                val previousKnown = incoming
                    .subList(0, index)
                    .asReversed()
                    .firstOrNull { it.key in htmlByKey }
                    ?.key

                val nextKnown = incoming
                    .subList(index + 1, incoming.size)
                    .firstOrNull { it.key in htmlByKey }
                    ?.key

                val insertAt = when {
                    previousKnown != null -> {
                        (order.indexOf(previousKnown) + 1)
                            .coerceIn(0, order.size)
                    }
                    nextKnown != null -> {
                        order.indexOf(nextKnown)
                            .takeIf { it >= 0 }
                            ?: order.size
                    }
                    else -> order.size
                }

                order.add(insertAt, turn.key)
                htmlByKey[turn.key] = turn.html
            }

            val numericTurnPattern =
                Regex("""^dom:conversation-turn-(\d+)$""")
            if (
                order.isNotEmpty() &&
                order.all { numericTurnPattern.matches(it) }
            ) {
                order.sortBy { key ->
                    numericTurnPattern
                        .matchEntire(key)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toLongOrNull()
                        ?: Long.MAX_VALUE
                }
            }

            val turns = JSONArray()
            order.forEach { key ->
                val html = htmlByKey[key].orEmpty()
                if (html.isBlank()) return@forEach
                turns.put(
                    JSONObject()
                        .put("key", key)
                        .put("html", html)
                )
            }

            val archive = JSONObject()
                .put("version", 2)
                .put("url", capture.url)
                .put("title", capture.title)
                .put("scrollTop", capture.scrollTop)
                .put("anchorKey", capture.anchorKey)
                .put("anchorOffset", capture.anchorOffset)
                .put("htmlClass", capture.htmlClass)
                .put("bodyClass", capture.bodyClass)
                .put("htmlStyle", capture.htmlStyle)
                .put("bodyStyle", capture.bodyStyle)
                .put("threadClass", capture.threadClass)
                .put("threadStyle", capture.threadStyle)
                .put("updatedAt", System.currentTimeMillis())
                .put("turns", turns)

            writeGzipAtomic(
                target.conversationArchive,
                archive.toString(),
            )

            if (capture.css.isNotBlank()) {
                writeGzipAtomic(
                    target.stylesCss,
                    capture.css,
                )
            }

            // New archive supersedes the old whole-document snapshot.
            target.legacySnapshotHtml.delete()

            val meta = readMetadata(target.metadata) ?: JSONObject()
            writeMetadata(
                target.metadata,
                meta
                    .put("archiveVersion", 2)
                    .put("archiveTurns", turns.length())
                    .put(
                        "archiveBytes",
                        target.conversationArchive.length(),
                    )
                    .put(
                        "stylesBytes",
                        target.stylesCss.length(),
                    )
                    .put("updatedAt", System.currentTimeMillis()),
            )

            turns.length()
        }.getOrDefault(0)
    }

    /**
     * Kept under the old method name so older UI/runtime call sites can migrate
     * without ever reintroducing Room-backed message rendering.
     */
    fun readSnapshotHtml(windowId: String): String? = runCatching {
        val directory = File(root, safe(windowId))
        if (!directory.exists()) return@runCatching null
        val target = Files(
            directory = directory,
            legacySnapshotHtml = File(directory, "snapshot.html"),
            conversationArchive = File(
                directory,
                "conversation-archive.json.gz",
            ),
            stylesCss = File(directory, "styles.css.gz"),
            sessionState = File(directory, "session-state.json"),
            metadata = File(directory, "meta.json"),
        )
        val archive = readArchive(target.conversationArchive)
        if (archive != null) {
            return@runCatching buildFrozenHtml(
                archive = archive,
                css = readGzip(target.stylesCss).orEmpty(),
            )
        }

        // Legacy whole-page snapshots are diagnostic/migration input only.
        // They must never masquerade as a valid conversation archive because
        // old snapshots may contain stale markup or corrupted presentation.
        // A cold tab with only snapshot.html will therefore go online and the
        // next successful archive capture will delete the legacy file.
        null
    }.getOrNull()

    fun archiveStatus(windowId: String): ArchiveStatus {
        val directory = File(root, safe(windowId))
        val metadataFile = File(directory, "meta.json")
        val metadata = readMetadata(metadataFile)
        val turnCount =
            metadata?.optInt("archiveTurns", 0)
                ?: 0
        val archiveBytes =
            File(
                directory,
                "conversation-archive.json.gz",
            ).takeIf(File::exists)?.length() ?: 0L
        val stylesBytes =
            File(
                directory,
                "styles.css.gz",
            ).takeIf(File::exists)?.length() ?: 0L
        val sessionStateBytes =
            File(
                directory,
                "session-state.json",
            ).takeIf(File::exists)?.length() ?: 0L
        val legacySnapshotBytes =
            File(
                directory,
                "snapshot.html",
            ).takeIf(File::exists)?.length() ?: 0L
        val hasUsableArchive =
            turnCount > 0 && archiveBytes > 0L
        val kind = when {
            hasUsableArchive -> "archive"
            legacySnapshotBytes > 0L -> "legacy-only"
            else -> "empty"
        }

        return ArchiveStatus(
            persistent =
                metadata?.optBoolean("persistent", false)
                    ?: false,
            kind = kind,
            hasUsableArchive = hasUsableArchive,
            turnCount = turnCount,
            archiveBytes = archiveBytes,
            stylesBytes = stylesBytes,
            sessionStateBytes = sessionStateBytes,
            legacySnapshotBytes = legacySnapshotBytes,
        )
    }

    /**
     * Remove all rebuildable conversation artifacts while preserving binding
     * metadata (project/repository/conversation identity and persistence).
     *
     * This is intentionally different from delete(windowId): a user refresh
     * must not unbind the tab.
     */
    fun clearConversationContent(windowId: String) {
        val target = files(windowId)
        target.legacySnapshotHtml.delete()
        target.conversationArchive.delete()
        target.stylesCss.delete()
        target.sessionState.delete()

        val metadata = readMetadata(target.metadata) ?: return
        metadata.remove("archiveVersion")
        metadata.remove("archiveTurns")
        metadata.remove("archiveBytes")
        metadata.remove("stylesBytes")
        metadata.put("updatedAt", System.currentTimeMillis())
        writeMetadata(target.metadata, metadata)
    }

    fun hasStyles(windowId: String): Boolean {
        val directory = File(root, safe(windowId))
        return File(directory, "styles.css.gz").exists()
    }

    fun archiveTurnCount(windowId: String): Int =
        readMetadata(files(windowId).metadata)
            ?.optInt("archiveTurns", 0)
            ?: 0

    fun writeSessionState(
        windowId: String,
        value: String,
    ) {
        if (!isPersistent(windowId) || value.isBlank()) return
        runCatching {
            val target = files(windowId)
            target.sessionState.writeText(value)
            touch(target.metadata)
        }
    }

    fun readSessionState(windowId: String): String? = runCatching {
        val target = files(windowId)
        if (!target.sessionState.exists()) return@runCatching null
        target.sessionState.readText().takeIf { it.isNotBlank() }
    }.getOrNull()

    fun markBound(window: ChatWindow) {
        val boundUrl = window.boundUrl?.takeIf { it.isNotBlank() } ?: return
        val identity = pageIdentity(boundUrl) ?: return
        val target = files(window.id)
        val previous = readMetadata(target.metadata)
        val previousIdentity = previous?.optString("boundIdentity").orEmpty()

        if (
            previousIdentity.isNotBlank() &&
            previousIdentity != identity
        ) {
            // A project tab may be rebound to a newer conversation. Keep its
            // accumulated conversation archive; only page/session state is
            // tied to the old active URL and must be discarded.
            target.sessionState.delete()
            target.legacySnapshotHtml.delete()
        }

        val carry = readMetadata(target.metadata) ?: JSONObject()
        writeMetadata(
            target.metadata,
            carry
                .put("windowId", window.id)
                .put("providerId", window.providerId)
                .put("boundIdentity", identity)
                .put("boundUrl", boundUrl)
                .put(
                    "conversationUrls",
                    JSONArray().apply {
                        window.conversationUrls
                            .plus(boundUrl)
                            .map(String::trim)
                            .filter(String::isNotBlank)
                            .distinct()
                            .forEach(::put)
                    },
                )
                .put("boundRepo", window.boundRepo.orEmpty())
                .put("boundProject", window.boundProject.orEmpty())
                .put("persistent", true)
                .put("updatedAt", System.currentTimeMillis()),
        )
    }

    fun markUnbound(windowId: String) {
        val target = files(windowId)
        val previous = readMetadata(target.metadata) ?: JSONObject()
        writeMetadata(
            target.metadata,
            previous
                .put("windowId", windowId)
                .put("persistent", false)
                .put("updatedAt", System.currentTimeMillis()),
        )
    }

    fun cleanupTransientFromPreviousRun() {
        root.listFiles()?.forEach { directory ->
            if (!directory.isDirectory) return@forEach
            val metadata = readMetadata(
                File(directory, "meta.json")
            )
            if (
                metadata == null ||
                !metadata.optBoolean("persistent", false)
            ) {
                deleteRecursively(directory)
            }
        }
    }

    fun reconcile(windows: List<ChatWindow>) {
        val liveIds = windows
            .mapTo(mutableSetOf()) { safe(it.id) }

        windows.forEach { window ->
            if (window.boundUrl.isNullOrBlank()) {
                markUnbound(window.id)
            } else {
                markBound(window)
            }
        }

        root.listFiles()
            ?.filter {
                it.isDirectory &&
                    it.name !in liveIds
            }
            ?.forEach(::deleteRecursively)
    }

    fun cleanupOnWorkspaceExit(windows: List<ChatWindow>) {
        val persistentIds = windows
            .filter { !it.boundUrl.isNullOrBlank() }
            .mapTo(mutableSetOf()) { safe(it.id) }

        root.listFiles()?.forEach { directory ->
            if (!directory.isDirectory) return@forEach
            if (directory.name !in persistentIds) {
                deleteRecursively(directory)
            }
        }

        windows
            .filter { !it.boundUrl.isNullOrBlank() }
            .forEach(::markBound)
    }

    fun delete(windowId: String) {
        deleteRecursively(File(root, safe(windowId)))
    }

    fun clearAll() {
        root.listFiles()?.forEach(::deleteRecursively)
    }

    private fun buildFrozenHtml(
        archive: JSONObject,
        css: String,
    ): String {
        val turns = archive.optJSONArray("turns") ?: JSONArray()
        val body = buildString {
            for (index in 0 until turns.length()) {
                val item = turns.optJSONObject(index) ?: continue
                val html =
                    sanitizeArchivedHtml(
                        item.optString("html")
                    )
                if (html.isNotBlank()) {
                    append(html)
                }
            }
        }

        if (body.isBlank()) return ""

        val baseUrl = escapeAttribute(archive.optString("url"))
        val title = escapeText(
            archive.optString("title").ifBlank { "ChatGPT" }
        )
        val htmlClass = escapeAttribute(archive.optString("htmlClass"))
        val bodyClass = escapeAttribute(archive.optString("bodyClass"))
        val htmlStyle = escapeAttribute(archive.optString("htmlStyle"))
        val bodyStyle = escapeAttribute(archive.optString("bodyStyle"))
        val threadClass = escapeAttribute(
            archive.optString("threadClass")
        )
        val threadStyle = escapeAttribute(
            archive.optString("threadStyle")
        )
        val anchorKey = escapeAttribute(
            archive.optString("anchorKey")
        )
        val anchorOffset = archive.optDouble("anchorOffset", 0.0)
        val scrollTop = archive.optDouble("scrollTop", 0.0)

        val safeCss = css.replace(
            oldValue = "</style",
            newValue = "<\\/style",
            ignoreCase = true,
        )

        return """
            <!doctype html>
            <html class="$htmlClass" style="$htmlStyle">
            <head>
              <meta charset="utf-8">
              <meta
                http-equiv="Content-Security-Policy"
                content="default-src 'none'; img-src data: blob:; style-src 'unsafe-inline'; font-src data:; connect-src 'none'; frame-src 'none'; object-src 'none'; form-action 'none'"
              >
              <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
              <meta name="aihub-archive-anchor" content="$anchorKey">
              <meta name="aihub-archive-offset" content="$anchorOffset">
              <meta name="aihub-snapshot-scroll" content="$scrollTop">
              <base href="$baseUrl">
              <title>$title</title>
              <style>
              $safeCss
              </style>
              <style>
                * {
                  animation: none !important;
                  transition: none !important;
                }
                html, body {
                  min-height: 100% !important;
                  height: auto !important;
                  overflow-y: auto !important;
                  overscroll-behavior-y: auto !important;
                  scroll-behavior: auto !important;
                  touch-action: pan-y pinch-zoom !important;
                  -webkit-overflow-scrolling: touch !important;
                }
                body {
                  margin: 0 !important;
                  -webkit-user-select: text !important;
                  user-select: text !important;
                }
                #aihub-frozen-thread {
                  width: 100% !important;
                  min-height: 100vh !important;
                  box-sizing: border-box !important;
                }
                #aihub-frozen-thread button,
                #aihub-frozen-thread input,
                #aihub-frozen-thread textarea,
                #aihub-frozen-thread [contenteditable="true"] {
                  pointer-events: none !important;
                }
                [data-aihub-archive-key] {
                  -webkit-user-select: text !important;
                  user-select: text !important;
                }
              </style>
            </head>
            <body class="$bodyClass" style="$bodyStyle">
              <main
                id="aihub-frozen-thread"
                class="$threadClass"
                style="$threadStyle"
              >$body</main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun pageIdentity(raw: String): String? = runCatching {
        val uri = Uri.parse(raw.trim())
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        if (scheme !in setOf("http", "https") || host.isBlank()) {
            return@runCatching null
        }
        val path = uri.path.orEmpty()
            .trimEnd('/')
            .ifBlank { "/" }
        "$scheme://$host$path"
    }.getOrNull()

    private fun touch(file: File) {
        val metadata = readMetadata(file) ?: return
        writeMetadata(
            file,
            metadata.put("updatedAt", System.currentTimeMillis()),
        )
    }

    private fun readArchive(file: File): JSONObject? = runCatching {
        readGzip(file)
            ?.takeIf { it.isNotBlank() }
            ?.let(::JSONObject)
    }.getOrNull()

    private fun readGzip(file: File): String? = runCatching {
        if (!file.exists()) return@runCatching null
        GZIPInputStream(FileInputStream(file))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }.getOrNull()

    private fun writeGzipAtomic(
        file: File,
        value: String,
    ) {
        val temp = File(file.parentFile, file.name + ".tmp")
        GZIPOutputStream(FileOutputStream(temp))
            .bufferedWriter(Charsets.UTF_8)
            .use { it.write(value) }

        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) {
                throw IllegalStateException(
                    "Cannot replace " + file.name
                )
            }
        }
    }

    private fun readMetadata(file: File): JSONObject? = runCatching {
        if (!file.exists()) return@runCatching null
        JSONObject(file.readText())
    }.getOrNull()

    private fun writeMetadata(file: File, value: JSONObject) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(value.toString())
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    private fun deleteDirectoryContents(directory: File) {
        directory.listFiles()?.forEach(::deleteRecursively)
        directory.mkdirs()
    }

    private fun deleteRecursively(file: File) {
        runCatching {
            if (file.isDirectory) {
                file.listFiles()?.forEach(::deleteRecursively)
            }
            file.delete()
        }
    }

    private fun sanitizeArchivedHtml(
        value: String,
    ): String {
        if (value.isBlank()) return ""

        return value
            .replace(
                Regex(
                    """(?is)<script\b[^>]*>.*?</script\s*>"""
                ),
                "",
            )
            .replace(
                Regex(
                    """(?is)<(?:iframe|object|embed|form)\b[^>]*>.*?</(?:iframe|object|form)\s*>"""
                ),
                "",
            )
            .replace(
                Regex(
                    """(?is)<(?:iframe|object|embed|form)\b[^>]*/?>"""
                ),
                "",
            )
            .replace(
                Regex(
                    """(?is)<meta\b[^>]*http-equiv\s*=\s*["']?refresh["']?[^>]*>"""
                ),
                "",
            )
            .replace(
                Regex(
                    """(?is)\s+on[a-z0-9_-]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)"""
                ),
                "",
            )
            .replace(
                Regex(
                    """(?is)\s+(?:href|src)\s*=\s*(["'])\s*(?:javascript:|data:text/html)[^"']*\1"""
                ),
                "",
            )
    }

    private fun escapeAttribute(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun escapeText(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun safe(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
