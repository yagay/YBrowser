package com.yagay.YBrowser

import android.content.Context
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL

data class BrowserWebDavConfig(
    val endpoint: String = "",
    val username: String = "",
    val hasPassword: Boolean = false,
)

data class BrowserSyncResult(
    val success: Boolean,
    val message: String,
)

object BrowserWebDavSync {
    private const val PREFS = "ybrowser_webdav_sync"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_USERNAME = "username"
    private const val SECRET_PASSWORD = "webdav_password"

    fun load(context: Context): BrowserWebDavConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return BrowserWebDavConfig(
            endpoint = prefs.getString(KEY_ENDPOINT, "").orEmpty(),
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            hasPassword = !BrowserSecretStore.get(context, SECRET_PASSWORD).isNullOrBlank(),
        )
    }

    fun save(
        context: Context,
        endpoint: String,
        username: String,
        password: String?,
    ): BrowserSyncResult {
        val normalized = endpoint.trim()
        if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) {
            return BrowserSyncResult(false, "WebDAV 地址必须以 http:// 或 https:// 开头")
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENDPOINT, normalized)
            .putString(KEY_USERNAME, username.trim())
            .apply()
        if (password != null) {
            if (password.isBlank()) {
                BrowserSecretStore.remove(context, SECRET_PASSWORD)
            } else {
                BrowserSecretStore.put(context, SECRET_PASSWORD, password)
            }
        }
        return BrowserSyncResult(true, "同步设置已保存")
    }

    fun upload(context: Context): BrowserSyncResult {
        val config = load(context)
        if (config.endpoint.isBlank()) return BrowserSyncResult(false, "请先设置 WebDAV 地址")
        val payload = BrowserBackupManager.exportJson(context).toByteArray(Charsets.UTF_8)
        return request(
            context = context,
            method = "PUT",
            endpoint = config.endpoint,
            username = config.username,
            requestBody = payload,
        ).let { response ->
            if (response.first in 200..299) {
                BrowserSyncResult(true, "已上传浏览器备份")
            } else {
                BrowserSyncResult(false, "上传失败：HTTP " + response.first)
            }
        }
    }

    fun downloadAndRestore(context: Context): BrowserSyncResult {
        val config = load(context)
        if (config.endpoint.isBlank()) return BrowserSyncResult(false, "请先设置 WebDAV 地址")
        val response = request(
            context = context,
            method = "GET",
            endpoint = config.endpoint,
            username = config.username,
            requestBody = null,
        )
        if (response.first !in 200..299) {
            return BrowserSyncResult(false, "下载失败：HTTP " + response.first)
        }
        val raw = response.second ?: return BrowserSyncResult(false, "远端备份为空")
        val restored = BrowserBackupManager.importJson(context, raw)
        return BrowserSyncResult(restored.success, restored.message)
    }

    fun test(context: Context): BrowserSyncResult {
        val config = load(context)
        if (config.endpoint.isBlank()) return BrowserSyncResult(false, "请先设置 WebDAV 地址")
        val response = request(
            context = context,
            method = "HEAD",
            endpoint = config.endpoint,
            username = config.username,
            requestBody = null,
        )
        return if (response.first in 200..399 || response.first == 404 || response.first == 405) {
            BrowserSyncResult(true, "WebDAV 服务器可访问")
        } else {
            BrowserSyncResult(false, "连接失败：HTTP " + response.first)
        }
    }

    private fun request(
        context: Context,
        method: String,
        endpoint: String,
        username: String,
        requestBody: ByteArray?,
    ): Pair<Int, String?> {
        val password = BrowserSecretStore.get(context, SECRET_PASSWORD).orEmpty()
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json,*/*")
            if (username.isNotBlank() || password.isNotBlank()) {
                val auth = Base64.encodeToString(
                    (username + ":" + password).toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP,
                )
                connection.setRequestProperty("Authorization", "Basic " + auth)
            }
            if (requestBody != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(requestBody.size)
                connection.outputStream.use { it.write(requestBody) }
            }
            val code = connection.responseCode
            val body = runCatching {
                val stream =
                    if (code >= 400) connection.errorStream
                    else connection.inputStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull()
            code to body
        } catch (error: Exception) {
            -1 to error.message
        } finally {
            connection.disconnect()
        }
    }
}
