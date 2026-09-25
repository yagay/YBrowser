package com.yagay.browsercore

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

class GeckoCoreUploadStager(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val sessionRoot = File(
        File(appContext.cacheDir, ROOT_DIR),
        UUID.randomUUID().toString(),
    ).apply { mkdirs() }

    fun stage(uris: List<Uri>): Array<Uri>? {
        if (
            uris.isEmpty() ||
            uris.size > MAX_FILES
        ) {
            return null
        }

        val promptDir =
            File(
                sessionRoot,
                UUID.randomUUID().toString(),
            )
        if (!promptDir.mkdirs()) {
            return null
        }

        return runCatching {
            var copiedBytes = 0L
            val staged =
                uris.mapIndexed { index, uri ->
                    val itemDir =
                        File(
                            promptDir,
                            index.toString(),
                        )
                    check(itemDir.mkdirs())

                    val file =
                        File(
                            itemDir,
                            safeFileName(uri),
                        )
                    val input =
                        resolver.openInputStream(uri)
                            ?: error(
                                "Selected file cannot be opened: $uri"
                            )

                    input.use { source ->
                        file.outputStream().use {
                            output ->
                            val buffer =
                                ByteArray(
                                    BUFFER_SIZE
                                )
                            while (true) {
                                val count =
                                    source.read(
                                        buffer
                                    )
                                if (count < 0) {
                                    break
                                }
                                copiedBytes += count
                                check(
                                    copiedBytes <=
                                        MAX_TOTAL_BYTES
                                ) {
                                    "Selected upload is too large"
                                }
                                check(
                                    sessionRoot
                                        .usableSpace >
                                        MIN_FREE_BYTES
                                ) {
                                    "Not enough free storage to stage upload"
                                }
                                output.write(
                                    buffer,
                                    0,
                                    count,
                                )
                            }
                        }
                    }

                    Uri.fromFile(file)
                }
            staged.toTypedArray()
        }.getOrElse {
            promptDir.deleteRecursively()
            null
        }
    }

    fun releaseAll() {
        runCatching { sessionRoot.deleteRecursively() }
    }

    private fun safeFileName(uri: Uri): String {
        val displayName = runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()

        val cleaned =
            displayName
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.take(MAX_NAME_LENGTH)
                ?.map { ch ->
                    when {
                        ch.isLetterOrDigit() -> ch
                        ch == '.' ||
                            ch == '-' ||
                            ch == '_' ||
                            ch == ' ' -> ch
                        else -> '_'
                    }
                }
                ?.joinToString("")
                ?.trim('.', ' ')
                ?.takeIf {
                    it.isNotBlank()
                }

        if (cleaned != null) {
            return cleaned
        }

        val mime =
            resolver.getType(uri).orEmpty()
        val extension =
            when (mime.lowercase()) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "application/pdf" -> "pdf"
                "text/plain" -> "txt"
                else ->
                    mime.substringAfter('/', "")
                        .substringBefore('+')
                        .takeIf { ext ->
                            ext.length in 1..12 &&
                                ext.all {
                                    it.isLetterOrDigit()
                                }
                        }
            }

        return if (
            extension.isNullOrBlank()
        ) {
            "upload"
        } else {
            "upload.$extension"
        }
    }

    private companion object {
        const val ROOT_DIR =
            "ybrowser-core-uploads"
        const val MAX_FILES = 100
        const val MAX_NAME_LENGTH = 120
        const val BUFFER_SIZE =
            64 * 1024
        const val MAX_TOTAL_BYTES =
            1024L * 1024L * 1024L
        const val MIN_FREE_BYTES =
            32L * 1024L * 1024L
    }
}
