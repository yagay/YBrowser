package com.yagay.YBrowser

import android.content.Context
import android.net.Uri
import org.json.JSONArray

object CustomFilterRepository {
    private const val PREFS = "ybrowser_custom_filters"
    private const val KEY_HOSTS = "blocked_hosts"

    fun list(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOSTS, null)
        val array = runCatching { JSONArray(raw ?: "[]") }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                normalizeHost(array.optString(index))?.let(::add)
            }
        }.distinct().sorted()
    }

    fun add(context: Context, raw: String): Boolean {
        val host = normalizeHost(raw) ?: return false
        persist(context, (list(context) + host).distinct().sorted())
        return true
    }

    fun remove(context: Context, host: String) {
        persist(context, list(context).filterNot { it == host })
    }

    fun matches(hosts: Collection<String>, uri: Uri): Boolean {
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return hosts.any { blocked ->
            host == blocked || host.endsWith("." + blocked)
        }
    }

    private fun normalizeHost(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val parsed = runCatching {
            if ("://" in value) Uri.parse(value).host
            else Uri.parse("https://" + value).host
        }.getOrNull()
            ?.lowercase()
            ?.removePrefix("www.")
            ?.trimEnd('.')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return parsed
    }

    private fun persist(context: Context, hosts: List<String>) {
        val array = JSONArray()
        hosts.take(2000).forEach(array::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOSTS, array.toString())
            .apply()
    }
}
