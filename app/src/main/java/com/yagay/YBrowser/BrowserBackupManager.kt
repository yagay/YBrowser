package com.yagay.YBrowser

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class BrowserBackupResult(
    val success: Boolean,
    val message: String,
)

object BrowserBackupManager {
    private const val FORMAT_VERSION = 1
    private const val MAX_BACKUP_BYTES = 64 * 1024 * 1024
    private const val MAX_READER_BYTES = 2 * 1024 * 1024
    private const val MAX_READER_ITEMS = 500
    private val readerFilePattern = Regex("[a-f0-9]{64}\\.json")
    private val preferenceNames = listOf(
        "ybrowser_store",
        "ybrowser_profiles",
        "ybrowser_downloads",
        "ybrowser_user_scripts",
        "ybrowser_custom_filters",
        "ybrowser_snoozed_tabs",
        "ybrowser_navigation_trails",
    )

    fun exportJson(context: Context): String {
        val root = JSONObject()
            .put("formatVersion", FORMAT_VERSION)
            .put("exportedAt", System.currentTimeMillis())

        val preferences = JSONObject()
        preferenceNames.forEach { name ->
            preferences.put(
                name,
                encodePreferences(
                    context.getSharedPreferences(name, Context.MODE_PRIVATE),
                ),
            )
        }
        root.put("preferences", preferences)

        val readerDir = File(context.filesDir, "reader_saves")
        val readerFiles = JSONArray()
        var readerPayloadBytes = 0L
        // An export must never create a file larger than the importer's
        // 64 MiB limit. Reject early instead of building a huge in-memory JSON.
        readerDir.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".json.bak")) }
            .map { if (it.name.endsWith(".bak")) File(it.path.removeSuffix(".bak")) else it }
            .distinctBy { it.name }
            .filter { readerFilePattern.matches(it.name) }
            .take(MAX_READER_ITEMS)
            .forEach { file ->
                val content = runCatching {
                    val backing = File(file.path + ".bak").takeIf { it.isFile } ?: file
                    if (!backing.isFile || backing.length() !in 1..MAX_READER_BYTES.toLong()) {
                        return@runCatching null
                    }
                    AtomicFile(file).openRead().use { stream ->
                        val bytes = stream.readBounded(MAX_READER_BYTES) ?: return@use null
                        String(bytes, Charsets.UTF_8)
                    }
                }.getOrNull() ?: return@forEach
                readerPayloadBytes += content.toByteArray(Charsets.UTF_8).size
                require(readerPayloadBytes < MAX_BACKUP_BYTES.toLong() - 2L * 1024 * 1024) {
                    "离线文章过多，备份会超过可恢复的容量限制"
                }
                readerFiles.put(JSONObject().put("name", file.name).put("content", content))
            }
        root.put("readerFiles", readerFiles)
        val backup = root.toString(2)
        require(backup.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) {
            "备份超出 64 MiB 导入限制，请减少离线文章后再导出"
        }
        return backup
    }

    fun importJson(context: Context, raw: String): BrowserBackupResult {
        return runCatching {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) {
                "备份文件超过最大允许大小"
            }
            val root = JSONObject(raw)
            val version = root.optInt("formatVersion", -1)
            require(version == FORMAT_VERSION) {
                "不支持的备份格式版本：$version"
            }
            val preferences = root.optJSONObject("preferences")
                ?: error("备份缺少 preferences")

            // Parse and validate all reader entries before changing live files
            // or preferences. Names must match the document's actual URL digest.
            val readerFiles = root.optJSONArray("readerFiles") ?: JSONArray()
            require(readerFiles.length() <= MAX_READER_ITEMS) { "离线文章数量超过限制" }
            val restoredReaders = linkedMapOf<String, ByteArray>()
            for (index in 0 until readerFiles.length()) {
                val item = readerFiles.optJSONObject(index) ?: error("离线文章条目格式错误")
                val name = item.optString("name")
                require(readerFilePattern.matches(name)) { "离线文章文件名无效" }
                val content = item.optString("content")
                val bytes = content.toByteArray(Charsets.UTF_8)
                require(bytes.size in 1..MAX_READER_BYTES) { "离线文章超过大小限制" }
                val document = JSONObject(content).optJSONObject("document")
                    ?: error("离线文章缺少内容")
                val url = document.optString("sourceUrl")
                require(OfflineSnapshotPolicy.canSave(url, false)) { "离线文章网址无效" }
                require(name == readerFileName(url)) { "离线文章文件身份不匹配" }
                require((document.optJSONArray("blocks")?.length() ?: 0) > 0) {
                    "离线文章缺少正文"
                }
                require(restoredReaders.putIfAbsent(name, bytes) == null) { "离线文章重复" }
            }

            // Validate every preference section before mutating disk: a corrupt
            // entry must never wipe settings through edit().clear().
            val restoredPreferences = preferenceNames.mapNotNull { name ->
                if (!preferences.has(name)) null else {
                    val section = preferences.optJSONObject(name)
                        ?: error("备份中的配置段格式错误：$name")
                    validatePreferences(section)
                    name to section
                }
            }

            val readerDir = File(context.filesDir, "reader_saves").apply { mkdirs() }
            // Write with AtomicFile, rather than deleting all existing copies
            // before knowing the new backup is readable and valid.
            restoredReaders.forEach { (name, bytes) ->
                val atomic = AtomicFile(File(readerDir, name))
                val output = atomic.startWrite()
                try {
                    output.write(bytes)
                    atomic.finishWrite(output)
                } catch (error: Exception) {
                    atomic.failWrite(output)
                    throw error
                }
            }
            if (root.has("readerFiles")) {
                readerDir.listFiles().orEmpty().forEach { file ->
                    val name = file.name.removeSuffix(".bak").removeSuffix(".new")
                    if (file.isFile && readerFilePattern.matches(name) &&
                        name !in restoredReaders
                    ) AtomicFile(File(readerDir, name)).delete()
                }
            }
            restoredPreferences.forEach { (name, source) ->
                restorePreferences(
                    context.getSharedPreferences(name, Context.MODE_PRIVATE), source,
                )
            }

            BrowserBackupResult(
                success = true,
                message = "备份已恢复",
            )
        }.getOrElse { error ->
            BrowserBackupResult(
                success = false,
                message = "恢复失败：" + (error.message ?: error.javaClass.simpleName),
            )
        }
    }

    private fun readerFileName(url: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) } + ".json"

    private fun java.io.InputStream.readBounded(limit: Int): ByteArray? {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
        }
        return output.toByteArray().takeIf { it.size in 1..limit }
    }

    private fun encodePreferences(prefs: SharedPreferences): JSONObject {
        val output = JSONObject()
        prefs.all.forEach { (key, value) ->
            val encoded = when (value) {
                is String -> JSONObject().put("type", "string").put("value", value)
                is Boolean -> JSONObject().put("type", "boolean").put("value", value)
                is Int -> JSONObject().put("type", "int").put("value", value)
                is Long -> JSONObject().put("type", "long").put("value", value)
                is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
                is Set<*> -> JSONObject()
                    .put("type", "stringSet")
                    .put(
                        "value",
                        JSONArray().apply {
                            value.filterIsInstance<String>().forEach(::put)
                        },
                    )
                else -> null
            }
            if (encoded != null) output.put(key, encoded)
        }
        return output
    }

    private fun validatePreferences(source: JSONObject) {
        require(source.length() <= 20_000) { "备份配置条目过多" }
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            require(key.isNotBlank() && key.length <= 512) { "备份配置键名无效" }
            val entry = source.optJSONObject(key) ?: error("备份配置值格式错误")
            val value = entry.opt("value")
            when (entry.optString("type")) {
                "string" -> require(value is String) { "备份字符串格式错误" }
                "boolean" -> require(value is Boolean) { "备份布尔值格式错误" }
                "int", "long", "float" ->
                    require(value is Number) { "备份数值格式错误" }
                "stringSet" -> {
                    val array = value as? JSONArray
                        ?: error("备份字符串集合格式错误")
                    require(array.length() <= 20_000) { "备份集合条目过多" }
                    for (index in 0 until array.length()) {
                        require(array.opt(index) is String) { "备份集合元素格式错误" }
                    }
                }
                else -> error("备份中包含不支持的配置类型")
            }
        }
    }

    private fun restorePreferences(
        prefs: SharedPreferences,
        source: JSONObject,
    ) {
        val editor = prefs.edit().clear()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val encoded = source.optJSONObject(key) ?: continue
            when (encoded.optString("type")) {
                "string" -> editor.putString(key, encoded.optString("value"))
                "boolean" -> editor.putBoolean(key, encoded.optBoolean("value"))
                "int" -> editor.putInt(key, encoded.optInt("value"))
                "long" -> editor.putLong(key, encoded.optLong("value"))
                "float" -> editor.putFloat(
                    key,
                    encoded.optDouble("value", 0.0).toFloat(),
                )
                "stringSet" -> {
                    val array = encoded.optJSONArray("value") ?: JSONArray()
                    val values = buildSet {
                        for (index in 0 until array.length()) {
                            array.optString(index)
                                .takeIf { it.isNotBlank() }
                                ?.let(::add)
                        }
                    }
                    editor.putStringSet(key, values)
                }
            }
        }
        editor.apply()
    }
}
