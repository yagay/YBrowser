package com.yagay.YBrowser

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ScheduledThreadPoolExecutor
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
        val key = file.absolutePath
        synchronized(pending) {
            // Read-your-writes for the 800ms debounce window and an in-flight
            // writer. Avoid restoring an older disk snapshot after tab eviction.
            if (key in pendingDeletes || clearingPrefixes.any { key.startsWith(it) }) {
                return null
            }
            pendingData[key]?.let { return it }
        }
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
            // A removed browser Profile must not be resurrected by a late
            // navigation-state callback from its closing Gecko session.
            if (clearingPrefixes.any { key.startsWith(it) }) return
            pendingData[key] = data
            pending.remove(key)?.cancel(false)
            lateinit var task: ScheduledFuture<*>
            task = scheduler.schedule({
                try {
                    write(file, data)
                } finally {
                    synchronized(pending) {
                        if (pending[key] === task) {
                            pending.remove(key)
                            pendingData.remove(key)
                        }
                    }
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
            pendingData.remove(key)
            pendingDeletes.add(key)
            scheduler.execute {
                try {
                    AtomicFile(file).delete()
                } finally {
                    synchronized(pending) { pendingDeletes.remove(key) }
                }
            }
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
            pendingData.keys.removeAll { it.startsWith(prefix) }
            clearingPrefixes.add(prefix)
            scheduler.execute {
                try {
                    profileFolder.deleteRecursively()
                } finally {
                    synchronized(pending) { clearingPrefixes.remove(prefix) }
                }
            }
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
        val scheduler = ScheduledThreadPoolExecutor(1) { job ->
            Thread(job, "YBrowser-SessionState").apply { isDaemon = true }
        }.apply {
            // Frequent Gecko state changes cancel old debounced writes.
            // Remove those cancelled timers instead of retaining each for 800ms.
            removeOnCancelPolicy = true
        }
        val pending = mutableMapOf<String, ScheduledFuture<*>>()
        val pendingData = mutableMapOf<String, String>()
        val pendingDeletes = mutableSetOf<String>()
        val clearingPrefixes = mutableSetOf<String>()
    }
}
