package com.yagay.YBrowser

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/**
 * Best-effort discovery of a conventional web app manifest.
 *
 * The document/manifest must be HTTPS and share an origin, redirects are not
 * followed, and response bodies are bounded. Dynamic JavaScript-injected
 * manifests can still fall back to a normal home-screen page shortcut.
 */
internal object WebAppManifestDiscovery {
    private const val MAX_HTML_BYTES = 384 * 1024
    private const val MAX_MANIFEST_BYTES = 128 * 1024
    private val links = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val attributes = Regex(
        """([a-zA-Z][a-zA-Z0-9_:-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""",
    )

    fun discover(pageUrl: String, pageTitle: String): InstalledWebApp? {
        val page = WebAppScope.canonicalHttps(pageUrl) ?: return null
        val fallback = InstalledWebApp(
            id = InstalledWebAppRegistry.idFor(page.toString()),
            name = pageTitle.trim().ifBlank { page.host }.take(60),
            startUrl = page.toString(),
            scope = WebAppScope.defaultScope(page.toString()) ?: return null,
        )
        val html = download(page, MAX_HTML_BYTES) ?: return fallback
        val withSiteIcon = fallback.copy(iconUrl = siteIcon(page, html))
        val manifestUrl = manifestUrl(page, html) ?: return withSiteIcon
        val json = download(manifestUrl, MAX_MANIFEST_BYTES) ?: return withSiteIcon
        val manifest = runCatching { JSONObject(json) }.getOrNull() ?: return withSiteIcon
        val name = manifest.optString("short_name")
            .ifBlank { manifest.optString("name") }
            .trim()
            .take(60)
            .ifBlank { fallback.name }
        val resolvedStart = resolveUrl(manifestUrl, manifest.optString("start_url"))
            ?.takeIf { WebAppScope.sameOrigin(page, it) }
            ?: page
        val rawScope = manifest.optString("scope").takeIf(String::isNotBlank)
            ?.let { resolveUrl(manifestUrl, it)?.toString() }
        val scope = WebAppScope.validatedScope(resolvedStart.toString(), rawScope)
            ?: fallback.scope
        if (!WebAppScope.contains(scope, resolvedStart.toString())) return withSiteIcon

        val display = manifest.optString("display").lowercase()
        if (display !in setOf("standalone", "fullscreen", "minimal-ui")) return withSiteIcon
        return InstalledWebApp(
            id = InstalledWebAppRegistry.idFor(manifestUrl.toString()),
            name = name,
            startUrl = resolvedStart.toString(),
            scope = scope,
            manifestUrl = manifestUrl.toString(),
            iconUrl = selectIcon(manifestUrl, manifest.optJSONArray("icons"))
                ?: withSiteIcon.iconUrl,
            displayMode = display,
        )
    }

    internal fun selectIcon(manifest: URI, icons: JSONArray?): String? {
        if (icons == null) return null
        val candidates = buildList {
            for (index in 0 until minOf(icons.length(), 100)) {
                val item = icons.optJSONObject(index) ?: continue
                add(
                    WebAppIconCandidate(
                        src = item.optString("src"),
                        sizes = item.optString("sizes"),
                        purpose = item.optString("purpose"),
                        type = item.optString("type"),
                    ),
                )
            }
        }
        return WebAppIconPicker.choose(manifest.toString(), candidates)
    }

    /**
     * HTML favicon/apple-touch-icon fallback for sites without a usable PWA
     * manifest; never introduce an external scheme or insecure HTTP icon.
     */
    internal fun siteIcon(page: URI, html: String): String? {
        val candidates = buildList {
            for (match in links.findAll(html.take(MAX_HTML_BYTES))) {
                val attrs = linkAttributes(match.value)
                val rel = attrs["rel"].orEmpty().lowercase()
                val icon = when {
                    "apple-touch-icon" in rel -> 180
                    Regex("""(?:^|\s)icon(?:\s|$)""").containsMatchIn(rel) -> 64
                    else -> continue
                }
                val target = resolveUrl(page, attrs["href"].orEmpty()) ?: continue
                add(WebAppIconCandidate(
                    src = target.toString(), sizes = "${icon}x${icon}",
                    type = attrs["type"].orEmpty(),
                ))
            }
        }
        return WebAppIconPicker.choose(page.toString(), candidates)
    }

    private fun linkAttributes(tag: String): Map<String, String> =
        attributes.findAll(tag).associate { value ->
            val name = value.groupValues[1].lowercase()
            val data = value.groupValues[2].ifBlank {
                value.groupValues[3].ifBlank { value.groupValues[4] }
            }.replace("&amp;", "&")
            name to data
        }

    internal fun manifestUrl(page: URI, html: String): URI? {
        for (match in links.findAll(html.take(MAX_HTML_BYTES))) {
            val attrs = linkAttributes(match.value)
            val rel = attrs["rel"].orEmpty()
            if (!rel.split(Regex("""\s+""")).any {
                it.equals("manifest", ignoreCase = true)
            }) continue
            val candidate = resolveUrl(page, attrs["href"].orEmpty()) ?: continue
            if (WebAppScope.sameOrigin(page, candidate)) return candidate
        }
        return null
    }

    private fun resolveUrl(base: URI, relative: String): URI? {
        if (relative.isBlank() || relative.startsWith("//")) return null
        return runCatching { base.resolve(relative).normalize() }
            .getOrNull()
            ?.takeIf { WebAppScope.canonicalHttps(it.toString()) != null }
    }

    private fun download(url: URI, maxBytes: Int): String? {
        val connection = runCatching {
            url.toURL().openConnection() as HttpsURLConnection
        }.getOrNull() ?: return null
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 6000
            connection.setRequestProperty("Accept", "application/manifest+json,text/html,application/json")
            connection.setRequestProperty("User-Agent", "YBrowser/" + BuildConfig.VERSION_NAME)
            if (connection.responseCode != 200) return null
            if (connection.contentLengthLong > maxBytes) return null
            connection.inputStream.use { stream ->
                val bytes = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    if (bytes.size() + n > maxBytes) return null
                    bytes.write(chunk, 0, n)
                }
                bytes.toString(Charsets.UTF_8.name())
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
