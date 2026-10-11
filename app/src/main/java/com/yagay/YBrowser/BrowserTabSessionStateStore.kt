package com.yagay.YBrowser

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Atomically stores native GeckoSession navigation state per normal tab.
 * Never stores private tabs, cookies or page HTML here. Gecko owns its own
 * profile cookies/storage. This is intentionally outside Android Auto Backup.
 */
internal class BrowserTabSessionStateStore(context: Context) {
    private val dir = File(context.applicationContext.noBackupFilesDir, "gecko_tab_states")
 
    fun read(profileId: String, tabId: Long): String? {
        val file = path(profileId, tabId)
        // AtomicFile may leave only the backup when a write is interrupted.
        val backup = File(file.path + ".bak")
        val readable = if (backup.isFile) backup else file
        if (!readable.isFile || readable.length() !in 1..MAX_BYTES) return null
        return runCatching {
            AtomicFile(file).openRead().use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= MAX_BYTES) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                if (bytes.size !in 1..MAX_BYTES) null
                else String(bytes, StandardCharsets.UTF_8).takeIf(String::isNotBlank)
            }
        }.getOrNull()
    }

    fun saveAsync(profileId: String, tabId: Long, data: String) {
        if (data.isBlank() || data.length > MAX_BYTES) return
        val file = path(profileId, tabId)
        val key = file.absolutePath
        synchronized(pending) {
            pending.remove(key)?.cancel(false)
            lateinit var task: ScheduledFuture<*>
            task = scheduler.schedule({
                write(file, data)
                synchronized(pending) {
                    if (pending[key] === task) pending.remove(key)
                }
            }, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            pending[key] = task
        }
    }

    fun delete(profileId: String, tabId: Long) {
        val file = path(profileId, tabId)
        val key = file.absolutePath
        synchronized(pending) {
            pending.remove(key)?.cancel(false)
            scheduler.execute { AtomicFile(file).delete() }
        }
    }

    /** Removing a browser Profile removes its navigation state as well. */
    fun clearProfile(profileId: String) {
        val profileFolder = directory(profileId)
        val prefix = profileFolder.absolutePath + File.separator
        synchronized(pending) {
            pending.keys.filter { it.startsWith(prefix) }.forEach { key ->
                pending.remove(key)?.cancel(false)
            }
            scheduler.execute { profileFolder.deleteRecursively() }
        }
    }

    private fun write(file: File, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size !in 1..MAX_BYTES) return
        runCatching {
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try {
                out.write(bytes)
                atomic.finishWrite(out)
            } catch (error: Exception) {
                atomic.failWrite(out)
                throw error
            }
        }
    }

    private fun path(profileId: String, tabId: Long): File =
        File(directory(profileId), "$tabId.state")

    private fun directory(profileId: String): File {
        // Per-profile hashed directory allows complete, safe profile cleanup.
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(profileId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, hash)
    }

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val SAVE_DEBOUNCE_MS = 800L
        // All managers share a single writer to avoid competing disk writes
        // and thread leakage when Activities or Profiles change.
        val scheduler = Executors.newSingleThreadScheduledExecutor { job ->
            Thread(job, "YBrowser-SessionState").apply { isDaemon = true }
        }
        val pending = mutableMapOf<String, ScheduledFuture<*>>()
    }
}
