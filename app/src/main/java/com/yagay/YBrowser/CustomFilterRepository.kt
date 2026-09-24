package com.yagay.YBrowser

import android.content.Context
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class FilterSubscription(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val updatedAt: Long,
    val ruleCount: Int,
    val blockedHosts: List<String>,
    val exceptionHosts: List<String>,
)

data class FilterSubscriptionUpdateResult(
    val success: Boolean,
    val message: String,
)

object CustomFilterRepository {
    private const val PREFS = "ybrowser_custom_filters"
    private const val KEY_HOSTS = "blocked_hosts"
    private const val KEY_SUBSCRIPTIONS = "subscriptions"
    private const val MAX_MANUAL_HOSTS = 5000
    private const val MAX_SUBSCRIPTIONS = 12
    private const val MAX_HOSTS_PER_SUBSCRIPTION = 120_000

    fun list(context: Context): List<String> {
        val manual = listManual(context)
        val subscriptions = subscriptions(context).filter { it.enabled }
        val exceptions = subscriptions
            .flatMap { it.exceptionHosts }
            .toHashSet()
        return (manual + subscriptions.flatMap { it.blockedHosts })
            .asSequence()
            .filterNot { host ->
                exceptions.any { exception ->
                    host == exception || host.endsWith("." + exception)
                }
            }
            .distinct()
            .sorted()
            .toList()
    }

    fun listManual(context: Context): List<String> {
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
        persistManual(context, (listManual(context) + host).distinct().sorted())
        return true
    }

    fun remove(context: Context, host: String) {
        persistManual(context, listManual(context).filterNot { it == host })
    }

    fun matches(hosts: Collection<String>, uri: Uri): Boolean {
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return hosts.any { blocked ->
            host == blocked || host.endsWith("." + blocked)
        }
    }

    fun subscriptions(context: Context): List<FilterSubscription> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SUBSCRIPTIONS, null)
        val array = runCatching { JSONArray(raw ?: "[]") }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val url = item.optString("url")
                if (!url.startsWith("http://") && !url.startsWith("https://")) continue
                val blocked = jsonHosts(item.optJSONArray("blockedHosts"))
                val exceptions = jsonHosts(item.optJSONArray("exceptionHosts"))
                add(
                    FilterSubscription(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = item.optString("name").ifBlank {
                            runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault(url)
                        },
                        url = url,
                        enabled = item.optBoolean("enabled", true),
                        updatedAt = item.optLong("updatedAt", 0L),
                        ruleCount = item.optInt("ruleCount", blocked.size + exceptions.size),
                        blockedHosts = blocked,
                        exceptionHosts = exceptions,
                    )
                )
            }
        }
    }

    fun addSubscription(
        context: Context,
        name: String,
        url: String,
    ): FilterSubscription? {
        val normalizedUrl = url.trim()
        if (
            !normalizedUrl.startsWith("https://") &&
            !normalizedUrl.startsWith("http://")
        ) return null
        val current = subscriptions(context).toMutableList()
        current.firstOrNull { it.url.equals(normalizedUrl, ignoreCase = true) }
            ?.let { return it }
        if (current.size >= MAX_SUBSCRIPTIONS) return null
        val item = FilterSubscription(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank {
                runCatching {
                    Uri.parse(normalizedUrl).host.orEmpty()
                }.getOrDefault("").ifBlank { "过滤订阅" }
            },
            url = normalizedUrl,
            enabled = true,
            updatedAt = 0L,
            ruleCount = 0,
            blockedHosts = emptyList(),
            exceptionHosts = emptyList(),
        )
        persistSubscriptions(context, current + item)
        return item
    }

    fun setSubscriptionEnabled(
        context: Context,
        id: String,
        enabled: Boolean,
    ) {
        persistSubscriptions(
            context,
            subscriptions(context).map {
                if (it.id == id) it.copy(enabled = enabled) else it
            },
        )
    }

    fun removeSubscription(
        context: Context,
        id: String,
    ) {
        persistSubscriptions(
            context,
            subscriptions(context).filterNot { it.id == id },
        )
    }

    fun updateSubscription(
        context: Context,
        id: String,
    ): FilterSubscriptionUpdateResult {
        val current = subscriptions(context)
        val subscription = current.firstOrNull { it.id == id }
            ?: return FilterSubscriptionUpdateResult(false, "订阅不存在")
        val response = fetchText(subscription.url)
        if (response.first !in 200..299 || response.second == null) {
            return FilterSubscriptionUpdateResult(
                false,
                "更新失败：HTTP " + response.first,
            )
        }

        val parsed = parseAdblockHosts(response.second!!)
        if (parsed.first.isEmpty() && parsed.second.isEmpty()) {
            return FilterSubscriptionUpdateResult(
                false,
                "没有解析到可用于原生网络阻断的域名规则",
            )
        }
        val updated = subscription.copy(
            updatedAt = System.currentTimeMillis(),
            ruleCount = parsed.first.size + parsed.second.size,
            blockedHosts = parsed.first.take(MAX_HOSTS_PER_SUBSCRIPTION),
            exceptionHosts = parsed.second.take(MAX_HOSTS_PER_SUBSCRIPTION),
        )
        persistSubscriptions(
            context,
            current.map { if (it.id == id) updated else it },
        )
        return FilterSubscriptionUpdateResult(
            true,
            "已更新 " + updated.blockedHosts.size +
                " 条阻断域名 / " + updated.exceptionHosts.size + " 条例外",
        )
    }

    fun updateAll(context: Context): FilterSubscriptionUpdateResult {
        val enabled = subscriptions(context).filter { it.enabled }
        if (enabled.isEmpty()) {
            return FilterSubscriptionUpdateResult(false, "没有启用的过滤订阅")
        }
        var successCount = 0
        var failedCount = 0
        enabled.forEach { item ->
            if (updateSubscription(context, item.id).success) successCount++ else failedCount++
        }
        return FilterSubscriptionUpdateResult(
            success = successCount > 0,
            message = "已更新 " + successCount + " 个订阅" +
                if (failedCount > 0) "，失败 " + failedCount + " 个" else "",
        )
    }

    fun addEasyListPresets(context: Context): Int {
        var added = 0
        if (
            addSubscription(
                context,
                "EasyList",
                "https://easylist.to/easylist/easylist.txt",
            ) != null
        ) added++
        if (
            addSubscription(
                context,
                "EasyPrivacy",
                "https://easylist.to/easylist/easyprivacy.txt",
            ) != null
        ) added++
        return added
    }

    private fun parseAdblockHosts(
        raw: String,
    ): Pair<List<String>, List<String>> {
        val blocked = LinkedHashSet<String>()
        val exceptions = LinkedHashSet<String>()

        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (
                line.isBlank() ||
                line.startsWith("!") ||
                line.startsWith("[") ||
                "##" in line ||
                "#@#" in line ||
                "#?#" in line ||
                "#$#" in line
            ) return@forEach

            val exception = line.startsWith("@@")
            val source = if (exception) line.removePrefix("@@") else line
            val host = when {
                source.startsWith("||") -> {
                    source.removePrefix("||")
                        .substringBefore("^")
                        .substringBefore("$")
                        .substringBefore("/")
                        .substringBefore("*")
                }
                source.startsWith("0.0.0.0 ") ||
                    source.startsWith("127.0.0.1 ") -> {
                    source.substringAfter(' ').trim().substringBefore(' ')
                }
                else -> null
            }?.let(::normalizeHost) ?: return@forEach

            if (exception) exceptions += host else blocked += host
        }

        return blocked.toList() to exceptions.toList()
    }

    private fun fetchText(url: String): Pair<Int, String?> {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                "YBrowser/" + BuildConfig.VERSION_NAME,
            )
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            } else {
                null
            }
            code to body
        } catch (_: Exception) {
            -1 to null
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeHost(raw: String): String? {
        val value = raw.trim()
            .removePrefix("||")
            .substringBefore("^")
            .substringBefore("$")
        if (value.isBlank()) return null
        val parsed = runCatching {
            if ("://" in value) Uri.parse(value).host
            else Uri.parse("https://" + value).host
        }.getOrNull()
            ?.lowercase()
            ?.removePrefix("www.")
            ?.trimEnd('.')
            ?.takeIf {
                it.isNotBlank() &&
                    "." in it &&
                    !it.contains("*") &&
                    !it.contains("/")
            }
            ?: return null
        return parsed
    }

    private fun jsonHosts(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                normalizeHost(array.optString(index))?.let(::add)
            }
        }.distinct()
    }

    private fun persistManual(
        context: Context,
        hosts: List<String>,
    ) {
        val array = JSONArray()
        hosts.take(MAX_MANUAL_HOSTS).forEach(array::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOSTS, array.toString())
            .apply()
    }

    private fun persistSubscriptions(
        context: Context,
        subscriptions: List<FilterSubscription>,
    ) {
        val array = JSONArray()
        subscriptions.take(MAX_SUBSCRIPTIONS).forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("url", item.url)
                    .put("enabled", item.enabled)
                    .put("updatedAt", item.updatedAt)
                    .put("ruleCount", item.ruleCount)
                    .put(
                        "blockedHosts",
                        JSONArray().apply {
                            item.blockedHosts
                                .take(MAX_HOSTS_PER_SUBSCRIPTION)
                                .forEach(::put)
                        },
                    )
                    .put(
                        "exceptionHosts",
                        JSONArray().apply {
                            item.exceptionHosts
                                .take(MAX_HOSTS_PER_SUBSCRIPTION)
                                .forEach(::put)
                        },
                    )
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SUBSCRIPTIONS, array.toString())
            .apply()
    }
}
