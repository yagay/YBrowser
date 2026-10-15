package com.yagay.YBrowser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.yagay.browsercore.GeckoCoreFilePromptKind

internal data class BrowserUploadValidation(
    val uris: List<Uri>,
    val rejectedCount: Int,
    val failure: String? = null,
)

/**
 * Call from Dispatchers.IO, never from an ActivityResult callback directly.
 * The originating file input's accept type is advisory UI filtering AND
 * validated here. Do not record URI strings, filenames, or remote paths in logs.
 */
internal object BrowserFileChooserValidator {
    fun validate(
        context: Context,
        uris: List<Uri>?,
        mimeTypes: List<String>,
        allowMultiple: Boolean,
        kind: GeckoCoreFilePromptKind,
        captureUri: Uri? = null,
    ): BrowserUploadValidation {
        val input = BrowserFileChooserPolicy.distinctSelection(uris, allowMultiple)
            ?: return BrowserUploadValidation(emptyList(), 0, "empty")
        val resolver = context.applicationContext.contentResolver
        val owner = context.packageName.lowercase()
        var rejected = 0
        var reason: String? = null
        val output = ArrayList<Uri>(input.size)
        for (uri in input) {
            val structured = BrowserFileChooserPolicy.safeUriString(uri.toString())
            val authority = uri.authority?.lowercase().orEmpty()
            val selfProvided = authority == owner || authority.startsWith("$owner.")
            if (!structured || authority.isEmpty() ||
                (selfProvided && uri != captureUri)) {
                rejected++
                reason = "unsafe-uri"
                continue
            }
            if (kind == GeckoCoreFilePromptKind.FOLDER) {
                output.add(uri)
                continue
            }
            val mime = runCatching { resolver.getType(uri) }.getOrNull()
            val filename = runCatching {
                resolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
                )?.use { cursor ->
                    val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (col >= 0 && cursor.moveToFirst()) cursor.getString(col)
                    else null
                }
            }.getOrNull()
            if (!BrowserFileChooserPolicy.acceptsMimeType(mime, mimeTypes, filename)) {
                rejected++
                reason = "mime-mismatch"
                continue
            }
            val readable = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    // Opening the stream (including virtual/cloud documents) is
                    // sufficient; no need to copy or buffer its entire contents.
                    input.read() >= -1
                } == true
            }.getOrDefault(false)
            if (!readable) {
                rejected++
                reason = "unreadable"
                continue
            }
            output.add(uri)
        }
        return BrowserUploadValidation(output, rejected, reason)
    }
}
