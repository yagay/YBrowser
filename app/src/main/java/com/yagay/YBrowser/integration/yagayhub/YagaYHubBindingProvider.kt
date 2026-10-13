package com.yagay.YBrowser.integration.yagayhub

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.yagay.YBrowser.BrowserSessionRegistry
import com.yagay.YBrowser.retainedSessionTabId

/**
 * Cross-application binding API. Every call uses the OS-verified Binder UID
 * and calling package. This works on Android 12-13 as well as newer systems;
 * an exported receiver cannot reliably learn the sender UID before Android 14.
 */
class YagaYHubBindingProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val app = context ?: return response(false)
        val uid = Binder.getCallingUid()
        val claimed = callingPackage
        val trusted = YagaYHubBroadcastPolicy.isTrusted(
            uid, claimed, app.packageManager.getPackagesForUid(uid),
        )
        if (!trusted) return response(false)
        val data = extras ?: return response(false)
        return runCatching {
            when (method) {
                "remove" -> {
                    val url = normalizedUrl(
                        data.getString(YagaYHubContract.EXTRA_BIND_URL)
                    )
                    YagaYHubBindingStore(app).remove(url)
                    BrowserSessionRegistry.close(
                        YagaYHubContract.RETAINED_SESSION_POOL_KEY,
                        retainedSessionTabId(url),
                    )
                    YagaYHubKeepAliveService.syncWithSessionPool(app)
                    response(true)
                }
                "sync" -> {
                    val repo = data.getString(YagaYHubContract.EXTRA_BIND_REPO).orEmpty()
                    val url = normalizedUrl(
                        data.getString(YagaYHubContract.EXTRA_BIND_URL)
                    )
                    require(repo.isNotBlank() && repo.length <= 512)
                    val project = data.getString(YagaYHubContract.EXTRA_BIND_PROJECT).orEmpty()
                    val title = data.getString(YagaYHubContract.EXTRA_BIND_TITLE).orEmpty()
                    YagaYHubBindingStore(app).save(
                        YagaYHubBindingRecord(
                            repoKey = repo,
                            project = project.ifBlank { repo.substringAfterLast('/') },
                            url = url,
                            title = title.ifBlank { "AI" },
                        ),
                    )
                    response(true)
                }
                else -> response(false)
            }
        }.getOrDefault(response(false))
    }

    private fun normalizedUrl(raw: String?): String {
        val url = raw.orEmpty().trim().trimEnd('/')
        require(url.length in 1..4096) { "Binding URL size invalid" }
        val parsed = Uri.parse(url)
        require(parsed.scheme?.lowercase() in setOf("http", "https") &&
            !parsed.host.isNullOrBlank()
        ) { "Binding must use a valid HTTP(S) URL" }
        return url
    }

    private fun response(success: Boolean): Bundle =
        Bundle().apply { putBoolean("success", success) }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String? = null
}
