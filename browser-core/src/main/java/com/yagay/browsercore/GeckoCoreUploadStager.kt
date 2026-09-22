package com.yagay.browsercore

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

internal class GeckoCoreUploadStager(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val sessionRoot = File(
        File(appContext.cacheDir, ROOT_DIR),
        UUID.randomUUID().toString(),
    ).apply { mkdirs() }

    fun stage(uris: List<Uri>): Array<Uri>? {
        if (uris.isEmpty() || uris.size > MAX_FILES) return null
        val promptDir = File(sessionRoot, UUID.randomUUID().toString())
        if (!promptDir.mkdirs()) return null

        return runCatching {
            var copiedBytes = 0L
            uris.mapIndexed { index, uri ->
                val itemDir = File(promptDir, index.toString()).apply {
                    check(mkdirs())
                }
                val file = File(itemDir, safeFileName(uri))
                val input = resolver.openInputStream(uri)
                    ?: error("Selected file cannot be opened")
                input.use { source ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            copiedBytes += count
                            check(copiedBytes <= MAX_TOTAL_BYTES) {
                                "Selected upload is too large"
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                Uri.fromFile(file)
            }.toTypedArray()
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

        return displayName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.replace(Regex("""[^A-Za-z0-9._ -]"""), "_")
            ?.trim('.', ' ')
            ?.take(120)
            ?.takeIf { it.isNotBlank() }
            ?: "upload"
    }

    private companion object {
        const val ROOT_DIR = "ybrowser-core-uploads"
        const val MAX_FILES = 100
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_TOTAL_BYTES = 1024L * 1024L * 1024L
    }
}
