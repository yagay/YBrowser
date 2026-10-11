package com.yagay.YBrowser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * First-party favicon fallback for GeckoView; System WebView supplies onReceivedIcon directly.
 * No Google favicon proxy, no cookies and no lookups for private or non-HTTPS tabs.
 */
internal object BrowserFaviconRepository {
    private val lock = Any()
    private val cache = LruCache<String, Bitmap>(48)
    private val missing = TimedNegativeCache(maxEntries = 128, ttlMillis = 15 * 60 * 1000L)
    private const val MAX_ICON_BYTES = 128 * 1024

    suspend fun load(pageUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(pageUrl) }.getOrNull()
            ?: return@withContext null
        if (!uri.scheme.equals("https", ignoreCase = true)) return@withContext null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        val safeHost = if (':' in host && !host.startsWith("[")) "[$host]" else host
        val origin = "https://" + safeHost +
            (if (uri.port > 0) ":" + uri.port else "")
        synchronized(lock) {
            cache.get(origin)?.let { return@withContext it }
            if (missing.isSuppressed(origin, System.currentTimeMillis())) {
                return@withContext null
            }
        }
        val bitmap = listOf("/favicon.ico", "/favicon.png", "/apple-touch-icon.png")
            .firstNotNullOfOrNull { path ->
                runCatching {
                    val connection = URL(origin + path).openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 2000
                        connection.readTimeout = 2000
                        connection.instanceFollowRedirects = false
                        if (connection.responseCode != 200 ||
                            connection.contentLengthLong > MAX_ICON_BYTES
                        ) return@runCatching null
                        val bytes = connection.inputStream.use { stream ->
                            val buffer = ByteArray(4096)
                            val output = java.io.ByteArrayOutputStream()
                            while (true) {
                                val count = stream.read(buffer)
                                if (count <= 0) break
                                if (output.size() + count > MAX_ICON_BYTES) return@use null
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: return@runCatching null
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                            bounds.outWidth > 512 || bounds.outHeight > 512
                        ) return@runCatching null
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull()
            }
        synchronized(lock) {
            if (bitmap != null) {
                cache.put(origin, bitmap)
                missing.clear(origin)
            } else {
                missing.recordFailure(origin, System.currentTimeMillis())
            }
        }
        bitmap
    }
}
