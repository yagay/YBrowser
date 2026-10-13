package com.yagay.YBrowser

import java.util.Locale

/**
 * Keep completed downloads and active jobs intact when the user downloads the
 * same filename again. Both native MediaStore and Android DownloadManager
 * receive an unused display name, instead of preemptively deleting old files.
 */
internal object BrowserDownloadNamePolicy {
    private val unsafe = Regex("""[\\/:*?"<>|\\p{Cntrl}]""")

    fun safeName(requested: String): String {
        val base = requested.substringAfterLast('/').substringAfterLast('\\')
            .replace(unsafe, "_")
            .trim().trimEnd('.')
        if (base.isBlank() || base == "." || base == "..") return "download"
        val dot = base.lastIndexOf('.')
            .takeIf { it > 0 && base.length - it <= 16 } ?: base.length
        val ext = base.substring(dot)
        val maxStemBytes = 180 - ext.toByteArray(Charsets.UTF_8).size
        val result = StringBuilder()
        var usedBytes = 0
        var index = 0
        while (index < dot) {
            val codePoint = Character.codePointAt(base, index)
            val encoded = String(Character.toChars(codePoint))
            val size = encoded.toByteArray(Charsets.UTF_8).size
            if (usedBytes + size > maxStemBytes) break
            result.append(encoded)
            usedBytes += size
            index += Character.charCount(codePoint)
        }
        return (result.toString().ifBlank { "download" } + ext)
            .takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "download"
    }

    fun choose(requested: String, existing: Collection<String>): String {
        val safe = safeName(requested)
        val used = existing.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        if (safe.lowercase(Locale.ROOT) !in used) return safe
        val dot = safe.lastIndexOf('.').takeIf { it > 0 } ?: safe.length
        val stem = safe.substring(0, dot)
        val extension = safe.substring(dot)
        for (index in 2..10_000) {
            val candidate = "$stem ($index)$extension"
            if (candidate.lowercase(Locale.ROOT) !in used) return candidate
        }
        throw IllegalStateException("Too many downloads with the same filename")
    }
}
