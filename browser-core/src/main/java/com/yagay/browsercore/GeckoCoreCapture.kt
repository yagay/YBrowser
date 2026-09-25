package com.yagay.browsercore

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

data class GeckoCoreCaptureTarget(
    val uri: Uri,
    val file: File,
    val intent: Intent,
)

fun createGeckoCoreCaptureTarget(
    context: Context,
    request: GeckoCoreFilePromptRequest,
): GeckoCoreCaptureTarget? {
    val types =
        normalizeGeckoCoreFileMimeTypes(
            request.mimeTypes
        )
    val wantsVideo =
        types.any {
            it.startsWith("video/")
        }
    val wantsImage =
        types.any {
            it.startsWith("image/")
        } ||
            (
                !wantsVideo &&
                    types.any {
                        it == "*/*"
                    }
                )

    val action =
        when {
            wantsImage ->
                MediaStore.ACTION_IMAGE_CAPTURE
            wantsVideo ->
                MediaStore.ACTION_VIDEO_CAPTURE
            else ->
                return null
        }

    val extension =
        if (
            action ==
                MediaStore.ACTION_VIDEO_CAPTURE
        ) {
            ".mp4"
        } else {
            ".jpg"
        }

    val directory =
        File(
            context.cacheDir,
            "web-captures",
        ).apply {
            mkdirs()
        }
    val file =
        File.createTempFile(
            "capture-",
            extension,
            directory,
        )
    val uri =
        FileProvider.getUriForFile(
            context,
            context.packageName +
                ".fileprovider",
            file,
        )

    val intent =
        Intent(action).apply {
            putExtra(
                MediaStore.EXTRA_OUTPUT,
                uri,
            )
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            clipData =
                ClipData.newRawUri(
                    "YBrowser capture",
                    uri,
                )
        }

    return GeckoCoreCaptureTarget(
        uri = uri,
        file = file,
        intent = intent,
    )
}

fun normalizeGeckoCoreFileMimeTypes(
    rawTypes: List<String>,
): List<String> {
    val mimeTypeMap =
        android.webkit.MimeTypeMap
            .getSingleton()
    val normalized =
        rawTypes
            .asSequence()
            .flatMap { raw ->
                raw.split(',')
                    .asSequence()
            }
            .map {
                it.trim()
            }
            .filter {
                it.isNotBlank()
            }
            .mapNotNull { value ->
                val clean =
                    value.substringBefore(';')
                        .trim()
                when {
                    clean == "*" ||
                        clean == "*/*" ->
                        "*/*"

                    clean.startsWith(".") -> {
                        val extension =
                            clean
                                .removePrefix(".")
                                .substringAfterLast('.')
                                .lowercase()
                        mimeTypeMap
                            .getMimeTypeFromExtension(
                                extension
                            )
                    }

                    '/' in clean ->
                        clean.lowercase()

                    else -> {
                        val extension =
                            clean
                                .substringAfterLast(
                                    '.',
                                    missingDelimiterValue =
                                        "",
                                )
                                .lowercase()
                        extension
                            .takeIf {
                                it.isNotBlank()
                            }
                            ?.let(
                                mimeTypeMap::
                                    getMimeTypeFromExtension
                            )
                    }
                }
            }
            .distinct()
            .toList()

    return normalized.ifEmpty {
        listOf("*/*")
    }
}
