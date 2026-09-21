package com.yagay.YBrowser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BrowserBackupResult(
    val success: Boolean,
    val message: String,
)

object BrowserBackupManager {
    private const val FORMAT_VERSION = 1
    private val preferenceNames = listOf(
        "ybrowser_store",
        "ybrowser_downloads",
        "ybrowser_user_scripts",
        "ybrowser_snoozed_tabs",
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
        readerDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .take(500)
            .forEach { file ->
                val content = runCatching {
                    file.readText(Charsets.UTF_8)
                }.getOrNull() ?: return@forEach
                readerFiles.put(
                    JSONObject()
                        .put("name", file.name)
                        .put("content", content),
                )
            }
        root.put("readerFiles", readerFiles)
        return root.toString(2)
    }

    fun importJson(context: Context, raw: String): BrowserBackupResult {
        return runCatching {
            val root = JSONObject(raw)
            val version = root.optInt("formatVersion", -1)
            require(version == FORMAT_VERSION) {
                "不支持的备份格式版本：$version"
            }

            val preferences = root.optJSONObject("preferences")
                ?: error("备份缺少 preferences")
            preferenceNames.forEach { name ->
                val source = preferences.optJSONObject(name) ?: JSONObject()
                restorePreferences(
                    context.getSharedPreferences(name, Context.MODE_PRIVATE),
                    source,
                )
            }

            val readerDir = File(context.filesDir, "reader_saves").apply {
                mkdirs()
            }
            readerDir.listFiles().orEmpty().forEach { file ->
                if (file.isFile && file.extension == "json") {
                    runCatching { file.delete() }
                }
            }
            val readerFiles = root.optJSONArray("readerFiles") ?: JSONArray()
            for (index in 0 until minOf(readerFiles.length(), 500)) {
                val item = readerFiles.optJSONObject(index) ?: continue
                val name = item.optString("name")
                if (!name.matches(Regex("[a-fA-F0-9]{64}\\.json"))) continue
                val content = item.optString("content")
                if (content.isBlank()) continue
                File(readerDir, name).writeText(content, Charsets.UTF_8)
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
