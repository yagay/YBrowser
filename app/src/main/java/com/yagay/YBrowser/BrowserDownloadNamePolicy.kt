package com.yagay.YBrowser

import java.util.Locale

/**
 * Keep completed downloads and active jobs intact when the user downloads the
 * same filename again. Both native MediaStore and Android DownloadManager
 * receive an unused display name, instead of preemptively deleting old files.
 */
internal object BrowserDownloadNamePolicy {
    fun choose(requested: String, existing: Collection<String>): String {
        val used = existing.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        if (requested.lowercase(Locale.ROOT) !in used) return requested
        val dot = requested.lastIndexOf('.').takeIf { it > 0 } ?: requested.length
        val stem = requested.substring(0, dot)
        val extension = requested.substring(dot)
        for (index in 2..10_000) {
            val candidate = "$stem ($index)$extension"
            if (candidate.lowercase(Locale.ROOT) !in used) return candidate
        }
        throw IllegalStateException("Too many downloads with the same filename")
    }
}
