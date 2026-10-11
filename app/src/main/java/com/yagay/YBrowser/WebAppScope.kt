package com.yagay.YBrowser

import java.net.URI

/**
 * A standalone web-app window may navigate inside its declared HTTPS origin
 * and path scope only. Scheme, host and effective port must all match.
 * Dot-segments are normalized before checking the directory boundary.
 */
internal object WebAppScope {
    fun canonicalHttps(url: String): URI? = runCatching {
        URI(url).normalize().takeIf {
            it.scheme.equals("https", ignoreCase = true) &&
                !it.host.isNullOrBlank() &&
                it.userInfo == null &&
                it.port in -1..65535
        }
    }.getOrNull()

    fun sameOrigin(a: URI, b: URI): Boolean =
        a.scheme.equals(b.scheme, ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            effectivePort(a) == effectivePort(b)

    fun contains(scopeUrl: String, targetUrl: String): Boolean {
        val scope = canonicalHttps(scopeUrl) ?: return false
        val target = canonicalHttps(targetUrl) ?: return false
        if (!sameOrigin(scope, target)) return false
        val scopePath = scope.rawPath.orEmpty().ifBlank { "/" }
        val targetPath = target.rawPath.orEmpty().ifBlank { "/" }
        // A manifest scope that ends in "/" protects a directory. A literal
        // path scope such as "/app" must not include "/app-evasion".
        return targetPath == scopePath ||
            targetPath.startsWith(
                if (scopePath.endsWith("/")) scopePath else "$scopePath/",
            )
    }

    fun defaultScope(startUrl: String): String? {
        val start = canonicalHttps(startUrl) ?: return null
        val path = start.rawPath.orEmpty().ifBlank { "/" }
        val folder = path.substringBeforeLast('/', "") + "/"
        return URI(
            start.scheme, null, start.host, start.port, folder, null, null,
        ).toString()
    }

    fun validatedScope(startUrl: String, rawScope: String?): String? {
        val start = canonicalHttps(startUrl) ?: return null
        val default = defaultScope(startUrl) ?: return null
        val requested = rawScope?.takeIf(String::isNotBlank)
            ?.let { runCatching { start.resolve(it).toString() }.getOrNull() }
            ?: return default
        return if (contains(requested, startUrl)) requested else default
    }

    private fun effectivePort(uri: URI): Int =
        if (uri.port >= 0) uri.port else 443
}
