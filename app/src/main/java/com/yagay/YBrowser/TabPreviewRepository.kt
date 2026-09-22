package com.yagay.YBrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

        directory.listFiles().orEmpty().forEach { file ->
            val id = file.nameWithoutExtension.toLongOrNull()
            if (id == null || id !in allowed) {
                runCatching { file.delete() }
            }
        }

        return buildMap {
            tabs.filterNot { it.privateMode }.forEach { tab ->
                val file = File(directory, tab.id.toString() + ".webp")
                if (!file.isFile) return@forEach
                val bitmap = runCatching {
                    BitmapFactory.decodeFile(file.absolutePath)
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
            val targetWidth = minOf(MAX_WIDTH, bitmap.width).coerceAtLeast(1)
            val targetHeight = (
                bitmap.height.toLong() * targetWidth /
                    bitmap.width.coerceAtLeast(1)
                ).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(
                bitmap,
                targetWidth,
                targetHeight,
                true,
            )
        }.getOrNull() ?: return

        executor.execute {
            try {
                val directory = profileDir(appContext, profileId).apply { mkdirs() }
                val output = File(directory, tabId.toString() + ".webp")
                val temp = File(directory, tabId.toString() + ".tmp")
                temp.outputStream().use { stream ->
                    check(
                        copy.compress(
                            Bitmap.CompressFormat.WEBP_LOSSY,
                            WEBP_QUALITY,
                            stream,
                        ),
                    )
                }
                if (output.exists()) output.delete()
                if (!temp.renameTo(output)) {
                    temp.copyTo(output, overwrite = true)
                    temp.delete()
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
                File(
                    profileDir(appContext, profileId),
                    tabId.toString() + ".webp",
                ).delete()
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
        val safeProfile = profileId.replace(
            Regex("[^A-Za-z0-9_.-]"),
            "_",
        )
        return File(
            File(context.filesDir, "tab_previews"),
            safeProfile,
        )
    }

    private const val MAX_WIDTH = 480
    private const val WEBP_QUALITY = 72
}
