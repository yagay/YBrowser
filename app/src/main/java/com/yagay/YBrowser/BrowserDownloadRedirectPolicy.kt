package com.yagay.YBrowser

import java.net.URI

/**
 * Resolve download redirects without allowing an HTTPS downgrade or passing
 * the original site's cookies to a different origin.
 */
internal object BrowserDownloadRedirectPolicy {
    const val MAX_REDIRECTS = 5

    fun resolve(currentUrl: String, location: String?): String? = runCatching {
        if (location.isNullOrBlank()) return null
        val current = URI(currentUrl)
        val target = current.resolve(location.trim()).normalize()
        val scheme = target.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (target.host.isNullOrBlank() || target.userInfo != null ||
            target.port !in -1..65535
        ) return null
        if (current.scheme.equals("https", true) && scheme != "https") return null
        target.toASCIIString().substringBefore('#')
    }.getOrNull()

    fun canForwardCookie(originalUrl: String, destinationUrl: String): Boolean =
        runCatching {
            val original = URI(originalUrl)
            val destination = URI(destinationUrl)
            original.scheme.equals(destination.scheme, true) &&
                !original.host.isNullOrBlank() &&
                original.host.equals(destination.host, true) &&
                effectivePort(original) == effectivePort(destination)
        }.getOrDefault(false)

    private fun effectivePort(uri: URI): Int =
        when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", true) -> 443
            uri.scheme.equals("http", true) -> 80
            else -> -1
        }
}
