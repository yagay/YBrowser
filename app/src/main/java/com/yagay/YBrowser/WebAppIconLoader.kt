package com.yagay.YBrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Bounded HTTPS icon acquisition. Never decode an arbitrary full-size image. */
internal object WebAppIconLoader {
    private const val MAX_BYTES = 768 * 1024
    private const val MAX_DIMENSION = 4096
    private const val OUTPUT_DIMENSION = 256

    fun loadCached(context: Context, iconUrl: String?): Bitmap? {
        if (iconUrl.isNullOrBlank()) return null
        val file = cacheFile(context, iconUrl)
        // AtomicFile may retain a valid .bak after an interrupted write.
        val backing = File(file.path + ".bak").takeIf { it.isFile } ?: file
        if (!backing.isFile || backing.length() !in 1..MAX_BYTES.toLong()) return null
        return runCatching {
            AtomicFile(file).openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= MAX_BYTES) {
                    val size = input.read(
                        buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()),
                    )
                    if (size < 0) break
                    if (size == 0) continue
                    output.write(buffer, 0, size)
                }
                if (output.size() > MAX_BYTES) null else decode(output.toByteArray())
            }
        }.getOrNull()
    }

    /** Call on a background thread, not the Compose/UI thread. */
    fun fetchAndCache(context: Context, iconUrl: String?): Bitmap? {
        if (iconUrl.isNullOrBlank()) return null
        loadCached(context, iconUrl)?.let { return it }
        val address = runCatching { URI(iconUrl) }.getOrNull() ?: return null
        if (!address.scheme.equals("https", ignoreCase = true) ||
            address.host.isNullOrBlank() ||
            address.userInfo != null || address.fragment != null
        ) return null
        val conn = runCatching {
            address.toURL().openConnection() as HttpsURLConnection
        }.getOrNull() ?: return null
        val bitmap = try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 4000
            conn.readTimeout = 5000
            conn.setRequestProperty("Accept", "image/png,image/webp,image/jpeg,image/*")
            if (conn.responseCode != 200 ||
                conn.contentLengthLong > MAX_BYTES ||
                conn.contentLengthLong < -1L
            ) return null
            val raw = conn.inputStream.use { input ->
                val buffer = ByteArray(8192)
                val out = ByteArrayOutputStream()
                while (true) {
                    val len = input.read(buffer)
                    if (len < 0) break
                    if (out.size() + len > MAX_BYTES) return null
                    out.write(buffer, 0, len)
                }
                out.toByteArray()
            }
            decode(raw)
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
        if (bitmap != null) {
            val file = cacheFile(context, iconUrl)
            runCatching {
                file.parentFile?.mkdirs()
                val atomic = AtomicFile(file)
                val stream = atomic.startWrite()
                try {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        error("Could not encode icon")
                    }
                    atomic.finishWrite(stream)
                } catch (error: Exception) {
                    atomic.failWrite(stream)
                    throw error
                }
            }
        }
        return bitmap
    }

    private fun decode(data: ByteArray): Bitmap? {
        if (data.isEmpty() || data.size > MAX_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) return null
        var sample = 1
        while (width / (sample * 2) >= OUTPUT_DIMENSION &&
            height / (sample * 2) >= OUTPUT_DIMENSION
        ) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            data, 0, data.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        if (decoded.width <= OUTPUT_DIMENSION &&
            decoded.height <= OUTPUT_DIMENSION
        ) return decoded
        val scale = minOf(
            OUTPUT_DIMENSION.toFloat() / decoded.width,
            OUTPUT_DIMENSION.toFloat() / decoded.height,
        )
        val resized = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (decoded !== resized) decoded.recycle()
        return resized
    }

    private fun cacheFile(context: Context, url: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(File(context.applicationContext.cacheDir, "webapp_icons"), "$hash.png")
    }
}
