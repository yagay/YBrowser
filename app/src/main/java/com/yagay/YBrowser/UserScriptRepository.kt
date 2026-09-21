package com.yagay.YBrowser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class BrowserUserScript(
    val id: Long,
    val name: String,
    val match: String,
    val code: String,
    val enabled: Boolean = true,
)

object BrowserUserScriptRepository {
    private const val PREFS = "ybrowser_user_scripts"
    private const val KEY_ITEMS = "items"

    fun list(context: Context): List<BrowserUserScript> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null)
        val array = runCatching { JSONArray(raw ?: "[]") }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val id = obj.optLong("id", 0L)
                val name = obj.optString("name").trim()
                val match = obj.optString("match").trim()
                val code = obj.optString("code")
                if (id <= 0L || name.isBlank() || match.isBlank() || code.isBlank()) continue
                add(
                    BrowserUserScript(
                        id = id,
                        name = name,
                        match = match,
                        code = code,
                        enabled = obj.optBoolean("enabled", true),
                    ),
                )
            }
        }.sortedBy { it.name.lowercase() }
    }

    fun save(context: Context, script: BrowserUserScript) {
        val id = if (script.id > 0L) script.id else System.currentTimeMillis().coerceAtLeast(1L)
        val updated = list(context)
            .filterNot { it.id == id } +
            script.copy(id = id)
        persist(context, updated)
    }

    fun remove(context: Context, id: Long) {
        persist(context, list(context).filterNot { it.id == id })
    }

    fun setEnabled(context: Context, id: Long, enabled: Boolean) {
        persist(
            context,
            list(context).map { item ->
                if (item.id == id) item.copy(enabled = enabled) else item
            },
        )
    }

    fun enabled(context: Context): List<BrowserUserScript> =
        list(context).filter { it.enabled }

    fun matches(script: BrowserUserScript, url: String): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()
            ?: return false
        return matchHost(script.match, host)
    }

    private fun matchHost(patternRaw: String, host: String): Boolean {
        val pattern = patternRaw
            .trim()
            .lowercase()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .trimEnd('.')
        return when {
            pattern == "*" || pattern == "<all_urls>" -> true
            pattern.startsWith("*.") -> {
                val base = pattern.removePrefix("*.")
                host == base || host.endsWith("." + base)
            }
            else -> host == pattern || host.endsWith("." + pattern)
        }
    }

    private fun persist(context: Context, scripts: List<BrowserUserScript>) {
        val array = JSONArray()
        scripts.take(200).forEach { script ->
            array.put(
                JSONObject()
                    .put("id", script.id)
                    .put("name", script.name)
                    .put("match", script.match)
                    .put("code", script.code)
                    .put("enabled", script.enabled),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }
}
