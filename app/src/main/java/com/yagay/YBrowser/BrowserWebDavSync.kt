package com.yagay.YBrowser

import android.content.Context
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URI
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

/**
 * Browser backups can contain private browsing metadata: never transmit
 * credentials or exported history via an insecure WebDAV connection.
 */
internal object BrowserSyncTransportPolicy {
    fun isSecureEndpoint(value: String): Boolean = runCatching {
        val endpoint = URI(value.trim())
        endpoint.scheme.equals("https", ignoreCase = true) &&
            !endpoint.host.isNullOrBlank() &&
            endpoint.userInfo == null &&
            endpoint.fragment == null &&
            endpoint.port in -1..65535
    }.getOrDefault(false)
}

object BrowserWebDavSync {
    private const val PREFS = "ybrowser_webdav_sync"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_USERNAME = "username"
    private const val SECRET_PASSWORD = "webdav_password"
    // Remote endpoints are not trusted to send bounded responses.
    private const val MAX_BACKUP_RESPONSE_BYTES = 64 * 1024 * 1024
    private const val MAX_DIAGNOSTIC_RESPONSE_BYTES = 64 * 1024

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
        if (!BrowserSyncTransportPolicy.isSecureEndpoint(normalized)) {
            return BrowserSyncResult(false, "WebDAV 必须使用 HTTPS 且不能在网址里包含账号密码；HTTP 会暴露浏览记录和 Basic Auth 凭据")
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

    fun clearPassword(context: Context) {
        BrowserSecretStore.remove(context, SECRET_PASSWORD)
    }

    fun upload(context: Context): BrowserSyncResult {
        val config = load(context)
        if (config.endpoint.isBlank()) return BrowserSyncResult(false, "请先设置 WebDAV 地址")
        if (!BrowserSyncTransportPolicy.isSecureEndpoint(config.endpoint)) {
            return BrowserSyncResult(false, "已阻止不安全的 HTTP WebDAV，请将服务器地址升级为 HTTPS")
        }
        val payload = runCatching {
            BrowserBackupManager.exportJson(context).toByteArray(Charsets.UTF_8)
        }.getOrElse {
            return BrowserSyncResult(false, "备份导出失败：" + (it.message ?: "数据异常"))
        }
        if (payload.size > MAX_BACKUP_RESPONSE_BYTES) {
            return BrowserSyncResult(false, "备份超过同步大小限制")
        }
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
        if (!BrowserSyncTransportPolicy.isSecureEndpoint(config.endpoint)) {
            return BrowserSyncResult(false, "已阻止不安全的 HTTP WebDAV，请将服务器地址升级为 HTTPS")
        }
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
        if (!BrowserSyncTransportPolicy.isSecureEndpoint(config.endpoint)) {
            return BrowserSyncResult(false, "已阻止不安全的 HTTP WebDAV，请将服务器地址升级为 HTTPS")
        }
        val response = request(
            context = context,
            method = "HEAD",
            endpoint = config.endpoint,
            username = config.username,
            requestBody = null,
        )
        return if (response.first in 200..299 || response.first == 404 || response.first == 405) {
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
        // Enforce this at the transport boundary even for legacy settings.
        if (!BrowserSyncTransportPolicy.isSecureEndpoint(endpoint)) {
            return -1 to "Refusing insecure WebDAV endpoint"
        }
        val password = BrowserSecretStore.get(context, SECRET_PASSWORD).orEmpty()
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            // A redirect may change host or downgrade HTTPS. Never forward
            // credentials or private browsing history across such a redirect.
            connection.instanceFollowRedirects = false
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
                val limit = if (method == "GET") MAX_BACKUP_RESPONSE_BYTES
                    else MAX_DIAGNOSTIC_RESPONSE_BYTES
                stream?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= limit) {
                        val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                    }
                    if (output.size() > limit) error("WebDAV 响应超过安全大小限制")
                    output.toString(Charsets.UTF_8.name())
                }
            }.getOrNull()
            code to body
        } catch (error: Exception) {
            -1 to error.message
        } finally {
            connection.disconnect()
        }
    }
}
