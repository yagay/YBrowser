package com.yagay.YBrowser

import java.net.URI
import java.util.Locale

internal data class WebAppIconCandidate(
    val src: String,
    val sizes: String = "",
    val purpose: String = "",
    val type: String = "",
)

/** Choose an HTTPS raster icon, preferring maskable 192-512px squares. */
internal object WebAppIconPicker {
    private val sizeToken = Regex("""^(\d{1,4})x(\d{1,4})$""")

    fun choose(manifestUrl: String, candidates: List<WebAppIconCandidate>): String? {
        val base = runCatching { URI(manifestUrl) }.getOrNull() ?: return null
        return candidates
            .take(100)
            .mapNotNull { candidate ->
                val raw = candidate.src.trim()
                if (raw.isBlank() || raw.length > 2048) return@mapNotNull null
                val uri = runCatching { base.resolve(raw).normalize() }.getOrNull()
                    ?: return@mapNotNull null
                if (!uri.scheme.equals("https", ignoreCase = true) ||
                    uri.host.isNullOrBlank() || uri.userInfo != null ||
                    uri.fragment != null
                ) return@mapNotNull null
                val contentType = candidate.type.lowercase(Locale.ROOT)
                val path = uri.path.orEmpty().lowercase(Locale.ROOT)
                // SVG and data: icons need independent sanitisation and rasterisation;
                // don't pass them to Android's bitmap decoder.
                if (contentType.contains("svg") || path.endsWith(".svg") ||
                    (contentType.isNotBlank() &&
                        contentType !in setOf("image/png", "image/webp",
                            "image/jpeg", "image/jpg", "image/x-icon", "image/vnd.microsoft.icon"))
                ) return@mapNotNull null
                val sizes = candidate.sizes.lowercase(Locale.ROOT).split(Regex("""\s+"""))
                val squareSizes = sizes.mapNotNull { token ->
                    val m = sizeToken.matchEntire(token) ?: return@mapNotNull null
                    val w = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    val h = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                    w.takeIf { w == h && w in 48..4096 }
                }
                val size = squareSizes.minByOrNull { kotlin.math.abs(it - 256) }
                val any = "any" in sizes
                val purposes = candidate.purpose.lowercase(Locale.ROOT)
                    .split(Regex("""\s+"""))
                // Empty purpose defaults to "any" under the web app manifest spec.
                if (purposes.contains("monochrome") &&
                    !purposes.contains("any") && !purposes.contains("maskable")
                ) return@mapNotNull null
                val score = (when {
                    size != null -> 1000 - kotlin.math.abs(size - 256)
                    any -> 500
                    else -> 100
                }) + if ("maskable" in purposes) 40 else 0
                score to uri.toString()
            }
            .maxByOrNull { it.first }
            ?.second
    }
}
