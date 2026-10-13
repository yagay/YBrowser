package com.yagay.YBrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.Executors

object TabPreviewRepository {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "YBrowser-TabPreview").apply {
            isDaemon = true
        }
    }

    fun load(
        context: Context,
        profileId: String,
        tabs: List<BrowserTab>,
    ): Map<Long, Bitmap> {
        val directory = profileDir(context, profileId)
        if (!directory.exists()) return emptyMap()

        val allowed = tabs
            .asSequence()
            .filterNot { it.privateMode }
            .map { it.id }
            .toSet()

        // A preview writer uses the same directory on a separate executor.
        // Never delete its in-progress .new file, or a newly-created preview
        // for a tab that appeared after the caller captured the tab list.
        val now = System.currentTimeMillis()
        directory.listFiles().orEmpty().forEach { file ->
            if (TabPreviewCleanupPolicy.shouldDelete(
                    file.name, file.lastModified(), now, allowed,
                )
            ) {
                runCatching { file.delete() }
            }
        }

        return buildMap {
            tabs.filterNot { it.privateMode }.forEach { tab ->
                val file = File(directory, tab.id.toString() + ".webp")
                val backing = File(file.path + ".bak").takeIf { it.isFile } ?: file
                if (!backing.isFile || backing.length() !in 1L..MAX_PREVIEW_BYTES.toLong()) {
                    return@forEach
                }
                val bitmap = runCatching {
                    AtomicFile(file).openRead().use { input ->
                        val bytes = readBounded(input) ?: return@use null
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        if (options.outWidth !in 1..MAX_DECODE_DIMENSION ||
                            options.outHeight !in 1..MAX_DECODE_DIMENSION
                        ) return@use null
                        var sample = 1
                        while (options.outWidth / sample > MAX_DECODE_WIDTH ||
                            options.outHeight / sample > MAX_DECODE_HEIGHT
                        ) sample *= 2
                        BitmapFactory.decodeByteArray(
                            bytes, 0, bytes.size,
                            BitmapFactory.Options().apply { inSampleSize = sample },
                        )
                    }
                }.getOrNull()
                if (bitmap != null && !bitmap.isRecycled) {
                    put(tab.id, bitmap)
                }
            }
        }
    }

    fun saveAsync(
        context: Context,
        profileId: String,
        tabId: Long,
        bitmap: Bitmap,
    ) {
        if (bitmap.isRecycled) return
        val appContext = context.applicationContext
        val copy = runCatching {
            val size = TabPreviewSizePolicy.fit(bitmap.width, bitmap.height)
                ?: return@runCatching null
            val scaled = Bitmap.createScaledBitmap(
                bitmap, size.width, size.height, true,
            )
            // createScaledBitmap may return the original when no resizing is
            // required. The worker MUST own its bitmap before recycling it.
            if (scaled === bitmap) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else scaled
        }.getOrNull() ?: return

        executor.execute {
            try {
                val directory = profileDir(appContext, profileId).apply { mkdirs() }
                val output = File(directory, tabId.toString() + ".webp")
                val atomic = AtomicFile(output)
                val stream = atomic.startWrite()
                try {
                    check(copy.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, stream))
                    atomic.finishWrite(stream)
                } catch (error: Exception) {
                    atomic.failWrite(stream)
                    throw error
                }
            } catch (_: Throwable) {
                // Preview persistence is best-effort and must never break browsing.
            } finally {
                if (!copy.isRecycled) copy.recycle()
            }
        }
    }

    fun removeAsync(
        context: Context,
        profileId: String,
        tabId: Long,
    ) {
        val appContext = context.applicationContext
        executor.execute {
            runCatching {
                AtomicFile(File(
                    profileDir(appContext, profileId),
                    tabId.toString() + ".webp",
                )).delete()
            }
        }
    }

    fun removeProfileAsync(
        context: Context,
        profileId: String,
    ) {
        val appContext = context.applicationContext
        executor.execute {
            runCatching {
                profileDir(appContext, profileId).deleteRecursively()
            }
        }
    }

    private fun profileDir(
        context: Context,
        profileId: String,
    ): File {
        // Preview storage must use the same collision-safe mapping as
        // Gecko/WebView profile storage, including for restored legacy IDs.
        val safeProfile = BrowserProfileStorageNamePolicy.name(profileId)
        return File(
            File(context.filesDir, "tab_previews"),
            safeProfile,
        )
    }

    private fun readBounded(stream: java.io.InputStream): ByteArray? {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= MAX_PREVIEW_BYTES) {
            val count = stream.read(
                buffer, 0, minOf(buffer.size, MAX_PREVIEW_BYTES + 1 - output.size()),
            )
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
        }
        return output.toByteArray().takeIf { it.size in 1..MAX_PREVIEW_BYTES }
    }

    private const val MAX_PREVIEW_BYTES = 2 * 1024 * 1024
    private const val MAX_DECODE_DIMENSION = 2048
    private const val MAX_DECODE_WIDTH = 480
    private const val MAX_DECODE_HEIGHT = 720
    private const val WEBP_QUALITY = 72
}
