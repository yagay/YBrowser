package com.yagay.YBrowser

import android.content.Context
import java.io.File

/**
 * Captures are retained until the owning tab closes, not deleted the moment
 * the chooser returns (WebView may read the URI asynchronously afterwards).
 * Registry survives Activity/Compose recreation for retained browser sessions.
 */
internal object BrowserCaptureFileRegistry {
    private val retained = mutableMapOf<String, MutableMap<Long, MutableSet<File>>>()

    @Synchronized
    fun retain(owner: String, tabId: Long, file: File) {
        retained.getOrPut(owner) { mutableMapOf() }
            .getOrPut(tabId) { mutableSetOf() }.add(file)
    }

    @Synchronized
    fun discard(owner: String, tabId: Long, file: File) {
        retained[owner]?.get(tabId)?.remove(file)
        runCatching { file.delete() }
    }

    @Synchronized
    fun release(owner: String, tabId: Long) {
        retained[owner]?.remove(tabId)?.forEach { file ->
            runCatching { file.delete() }
        }
        if (retained[owner]?.isEmpty() == true) retained.remove(owner)
    }

    @Synchronized
    fun releaseAll(owner: String) {
        retained.remove(owner)?.values?.flatten()?.forEach { file ->
            runCatching { file.delete() }
        }
    }

    /** Called off the main thread. Preserve files still owned by live tabs. */
    @Synchronized
    fun clearExpired(context: Context) {
        val active = retained.values.flatMap { it.values.flatten() }.toSet()
        val cutoff = System.currentTimeMillis() - 48L * 60L * 60L * 1000L
        File(context.cacheDir, "web-captures").listFiles()?.forEach { file ->
            if (file.isFile && file !in active && file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
    }
}
