package com.yagay.YBrowser

import android.text.Html

object BookmarkHtmlManager {
    fun export(
        bookmarks: List<BookmarkEntry>,
    ): String = buildString {
        appendLine("<!DOCTYPE NETSCAPE-Bookmark-file-1>")
        appendLine("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">")
        appendLine("<TITLE>YBrowser Bookmarks</TITLE>")
        appendLine("<H1>YBrowser Bookmarks</H1>")
        appendLine("<DL><p>")
        bookmarks
            .distinctBy { it.url }
            .forEach { bookmark ->
                append("  <DT><A HREF=\"")
                append(escapeAttribute(bookmark.url))
                append("\" ADD_DATE=\"")
                append((bookmark.createdAt / 1000L).coerceAtLeast(0L))
                append("\">")
                append(escapeText(bookmark.title.ifBlank { bookmark.url }))
                appendLine("</A>")
            }
        appendLine("</DL><p>")
    }

    fun import(
        raw: String,
        now: Long = System.currentTimeMillis(),
    ): List<BookmarkEntry> {
        if (raw.isBlank()) return emptyList()
        val regex = Regex(
            """<A\s+[^>]*HREF\s*=\s*["']([^"']+)["'][^>]*>(.*?)</A>""",
            setOf(
                RegexOption.IGNORE_CASE,
                RegexOption.DOT_MATCHES_ALL,
            ),
        )
        return regex
            .findAll(raw.take(MAX_IMPORT_CHARS))
            .mapNotNull { match ->
                val url =
                    decodeHtml(match.groupValues[1])
                        .trim()
                if (
                    !url.startsWith("http://", true) &&
                    !url.startsWith("https://", true)
                ) {
                    return@mapNotNull null
                }
                val title =
                    decodeHtml(match.groupValues[2])
                        .trim()
                        .ifBlank { url }
                BookmarkEntry(
                    url = url,
                    title = title.take(512),
                    createdAt = now,
                )
            }
            .distinctBy { it.url }
            .take(MAX_BOOKMARKS)
            .toList()
    }

    private fun decodeHtml(value: String): String =
        Html.fromHtml(
            value,
            Html.FROM_HTML_MODE_LEGACY,
        ).toString()

    private fun escapeText(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun escapeAttribute(value: String): String =
        escapeText(value)
            .replace("\"", "&quot;")

    private const val MAX_IMPORT_CHARS =
        8 * 1024 * 1024
    private const val MAX_BOOKMARKS = 20_000
}
