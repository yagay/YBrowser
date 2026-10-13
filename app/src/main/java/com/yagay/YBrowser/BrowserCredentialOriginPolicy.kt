package com.yagay.YBrowser

import java.net.URI
import java.util.Locale

/**
 * Login credentials are sensitive: never return a different origin just
 * because two hosts share a suffix (including www or sibling subdomains).
 */
internal object BrowserCredentialOriginPolicy {
    fun matches(storedOrigin: String, requestedDomain: String): Boolean {
        val raw = requestedDomain.trim()
        if (raw.isBlank()) return false
        return runCatching {
            val stored = URI(storedOrigin)
            val requested = URI(if ("://" in raw) raw else "//$raw")
            val storedHost = stored.host?.lowercase(Locale.ROOT)?.trimEnd('.')
            val requestedHost = requested.host?.lowercase(Locale.ROOT)?.trimEnd('.')
            if (storedHost.isNullOrEmpty() || requestedHost.isNullOrEmpty() ||
                storedHost != requestedHost) return@runCatching false
            val storedScheme = stored.scheme?.lowercase(Locale.ROOT)
            if (storedScheme != "https" && storedScheme != "http") return@runCatching false
            val requestedScheme = requested.scheme?.lowercase(Locale.ROOT)
            if (requestedScheme != null && requestedScheme != storedScheme) return@runCatching false
            // A Gecko host-only query lacks scheme and port information. Do
            // not share credentials saved on an unrelated non-default port.
            val storedPort = if (stored.port >= 0) stored.port
                else if (storedScheme == "https") 443 else 80
            val requestPort = if (requested.port >= 0) requested.port
                else if (requestedScheme == null) storedPort
                else if (requestedScheme == "https") 443 else 80
            storedPort == requestPort
        }.getOrDefault(false)
    }
}
