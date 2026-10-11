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
    private val scheduler = Executors.newSingleThreadScheduledExecutor { job ->
        Thread(job, "YBrowser-SessionState").apply { isDaemon = true }
    }
    private val pending = mutableMapOf<String, ScheduledFuture<*>>()

    fun read(profileId: String, tabId: Long): String? {
        val file = path(profileId, tabId)
        if (!file.isFile || file.length() !in 1..MAX_BYTES) return null
        return runCatching {
            AtomicFile(file).openRead().use { stream ->
                val bytes = stream.readBytes()
                if (bytes.size !in 1..MAX_BYTES) null
                else String(bytes, StandardCharsets.UTF_8).takeIf(String::isNotBlank)
            }
        }.getOrNull()
    }

    fun saveAsync(profileId: String, tabId: Long, data: String) {
        if (data.isBlank() || data.length > MAX_BYTES) return
        val file = path(profileId, tabId)
        val key = file.name
        synchronized(pending) {
            pending.remove(key)?.cancel(false)
            pending[key] = scheduler.schedule({
                write(file, data)
                synchronized(pending) { pending.remove(key) }
            }, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun delete(profileId: String, tabId: Long) {
        val file = path(profileId, tabId)
        val key = file.name
        synchronized(pending) {
            pending.remove(key)?.cancel(false)
            scheduler.execute { AtomicFile(file).delete() }
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

    private fun path(profileId: String, tabId: Long): File {
        // Hash names to keep profile paths safe and stable across restarts.
        val label = profileId + ":" + tabId
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(label.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, hash + ".state")
    }

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val SAVE_DEBOUNCE_MS = 800L
    }
}
