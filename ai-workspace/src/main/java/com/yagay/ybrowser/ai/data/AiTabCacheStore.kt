package com.yagay.ybrowser.ai.data

import android.content.Context
import android.net.Uri
import com.yagay.ybrowser.ai.model.ChatWindow
import org.json.JSONObject
import java.io.File

/**
 * Owns all cold-start artifacts that belong to one AI workspace tab.
 *
 * Bound project tabs are persistent. Unbound tabs are transient and are
 * removed when the workspace UI exits. A rebind to another conversation
 * invalidates the previous tab cache before the new binding is recorded.
 */
class AiTabCacheStore(context: Context) {
    private val root = File(
        context.applicationContext.filesDir,
        "aihub_tab_cache",
    ).apply { mkdirs() }

    data class Files(
        val directory: File,
        val snapshotHtml: File,
        val sessionState: File,
        val metadata: File,
    )

    fun files(windowId: String): Files {
        val directory = File(root, safe(windowId)).apply { mkdirs() }
        return Files(
            directory = directory,
            snapshotHtml = File(directory, "snapshot.html"),
            sessionState = File(directory, "session-state.json"),
            metadata = File(directory, "meta.json"),
        )
    }

    fun isPersistent(windowId: String): Boolean =
        readMetadata(files(windowId).metadata)
            ?.optBoolean("persistent", false)
            ?: false

    fun writeSnapshotHtml(
        windowId: String,
        html: String,
    ) {
        if (!isPersistent(windowId) || html.isBlank()) return
        runCatching {
            val target = files(windowId)
            target.snapshotHtml.writeText(html)
            touch(target.metadata)
        }
    }

    fun readSnapshotHtml(windowId: String): String? = runCatching {
        val target = files(windowId)
        if (!target.snapshotHtml.exists()) return@runCatching null
        target.snapshotHtml.readText()
    }.getOrNull()

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
            // The same tab was rebound to a different conversation/project.
            // Never let the old project's page snapshot leak into the new one.
            deleteDirectoryContents(target.directory)
        }

        writeMetadata(
            target.metadata,
            JSONObject()
                .put("windowId", window.id)
                .put("providerId", window.providerId)
                .put("boundIdentity", identity)
                .put("boundUrl", boundUrl)
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

    fun reconcile(windows: List<ChatWindow>) {
        val liveIds = windows.mapTo(mutableSetOf()) { it.id }

        windows.forEach { window ->
            if (window.boundUrl.isNullOrBlank()) {
                markUnbound(window.id)
            } else {
                markBound(window)
            }
        }

        root.listFiles()
            ?.filter { it.isDirectory && it.name !in liveIds.map(::safe) }
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

        // Refresh metadata for all surviving bound caches.
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

    private fun readMetadata(file: File): JSONObject? = runCatching {
        if (!file.exists()) return@runCatching null
        JSONObject(file.readText())
    }.getOrNull()

    private fun writeMetadata(file: File, value: JSONObject) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(value.toString())
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

    private fun safe(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
