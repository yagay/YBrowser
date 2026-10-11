package com.yagay.YBrowser

/** A strict byte-range validator prevents silent corruption of partial files. */
internal object BrowserDownloadIntegrity {
    sealed interface Decision {
        data class Accept(val append: Boolean, val totalBytes: Long) : Decision
        data class Reject(val reason: String) : Decision
    }

    private val rangePattern = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")

    fun resumeValidator(etag: String?, lastModified: String?): String? =
        etag?.trim()?.takeIf {
            it.isNotBlank() && !it.startsWith("W/", ignoreCase = true)
        } ?: lastModified?.trim()?.takeIf(String::isNotBlank)

    fun inspect(
        statusCode: Int,
        alreadyDownloaded: Long,
        contentRange: String?,
        responseLength: Long,
        previousEtag: String?,
        responseEtag: String?,
        previousLastModified: String?,
        responseLastModified: String?,
    ): Decision {
        if (alreadyDownloaded < 0 || responseLength < -1) {
            return Decision.Reject("Invalid transfer length")
        }
        if (statusCode == 200) {
            // Servers which ignore Range must restart from byte zero.
            return Decision.Accept(append = false, totalBytes = responseLength)
        }
        if (statusCode != 206) return Decision.Reject("Unexpected HTTP status")
        val match = rangePattern.matchEntire(contentRange?.trim().orEmpty())
            ?: return Decision.Reject("Missing/invalid Content-Range")
        val start = match.groupValues[1].toLongOrNull()
            ?: return Decision.Reject("Invalid range start")
        val end = match.groupValues[2].toLongOrNull()
            ?: return Decision.Reject("Invalid range end")
        val total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull() ?: -1L
        if (start != alreadyDownloaded || end < start || end == Long.MAX_VALUE) {
            return Decision.Reject("Wrong resume offset")
        }
        if (total > 0L && end >= total) {
            return Decision.Reject("Range exceeds total length")
        }
        if (responseLength >= 0L && responseLength != end - start + 1L) {
            return Decision.Reject("Content-Length differs from Content-Range")
        }
        if (alreadyDownloaded > 0L) {
            val strongEtag = previousEtag?.takeIf {
                it.isNotBlank() && !it.startsWith("W/", ignoreCase = true)
            }
            if (strongEtag != null && responseEtag != null &&
                strongEtag != responseEtag
            ) return Decision.Reject("Resource ETag changed")
            if (strongEtag == null && previousLastModified != null &&
                responseLastModified != null &&
                previousLastModified != responseLastModified
            ) return Decision.Reject("Resource Last-Modified changed")
        }
        return Decision.Accept(
            append = alreadyDownloaded > 0L,
            totalBytes = total,
        )
    }
}
