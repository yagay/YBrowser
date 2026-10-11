package com.yagay.YBrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Registry for confirmed home-screen installations. Pin callbacks record
 * entries only after the launcher accepts them; an attempted installation is
 * never presented as successfully installed.
 */
internal data class InstalledWebApp(
    val id: String,
    val name: String,
    val startUrl: String,
    val scope: String,
    val manifestUrl: String? = null,
    val installedAt: Long = System.currentTimeMillis(),
) {
    val isPwa: Boolean get() = manifestUrl != null
}

internal object InstalledWebAppRegistry {
    private const val PREFS = "ybrowser_installed_webapps"
    private const val KEY_ENTRIES = "installed"
    private const val MAX_APPS = 100

    fun idFor(url: String): String {
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        return "pwa-$sha"
    }

    @Synchronized
    fun list(context: Context): List<InstalledWebApp> {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, "[]")
        val json = runCatching { JSONArray(encoded) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until minOf(json.length(), MAX_APPS)) {
                val item = json.optJSONObject(index) ?: continue
                val start = item.optString("startUrl")
                val scope = item.optString("scope")
                if (!WebAppScope.contains(scope, start)) continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                add(
                    InstalledWebApp(
                        id = id,
                        name = item.optString("name").ifBlank { start },
                        startUrl = start,
                        scope = scope,
                        manifestUrl = item.optString("manifestUrl")
                            .takeIf(String::isNotBlank),
                        installedAt = item.optLong("installedAt", 0),
                    )
                )
            }
        }.sortedByDescending { it.installedAt }
    }

    @Synchronized
    fun find(context: Context, id: String): InstalledWebApp? =
        list(context).firstOrNull { it.id == id }

    @Synchronized
    fun save(context: Context, entry: InstalledWebApp) {
        if (!WebAppScope.contains(entry.scope, entry.startUrl)) return
        val items = list(context).filterNot { it.id == entry.id }
        persist(context, (listOf(entry) + items).take(MAX_APPS))
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        persist(context, list(context).filterNot { it.id == id })
    }

    private fun persist(context: Context, items: List<InstalledWebApp>) {
        val array = JSONArray()
        items.take(MAX_APPS).forEach { app ->
            array.put(
                JSONObject()
                    .put("id", app.id)
                    .put("name", app.name)
                    .put("startUrl", app.startUrl)
                    .put("scope", app.scope)
                    .put("manifestUrl", app.manifestUrl.orEmpty())
                    .put("installedAt", app.installedAt)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENTRIES, array.toString()).apply()
    }
}
