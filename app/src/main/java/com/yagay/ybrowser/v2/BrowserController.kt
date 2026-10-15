package com.yagay.ybrowser.v2

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yagay.ybrowser.core.BrowserAddress
import com.yagay.ybrowser.engine.WebViewSession
import org.json.JSONArray

class BrowserTab(val id: Long, val session: WebViewSession) {
    var title by mutableStateOf("New tab")
    var url by mutableStateOf("about:blank")
    var progress by mutableIntStateOf(100)
}

class BrowserController(private val activity: BrowserActivity) {
    private val prefs = activity.getSharedPreferences("browser_tabs_v2", Context.MODE_PRIVATE)
    val tabs = mutableStateListOf<BrowserTab>()
    var activeId by mutableLongStateOf(-1L)
        private set
    private var nextId = 1L
    private val maxTabs = 12

    val active: BrowserTab? get() = tabs.firstOrNull { it.id == activeId }

    fun restore(launchUrl: String?) {
        if (!launchUrl.isNullOrBlank()) {
            addTab(launchUrl)
            return
        }
        prefs.getString("urls", null)?.let { data ->
            runCatching {
                val urls = JSONArray(data)
                for (i in 0 until minOf(urls.length(), maxTabs)) {
                    val url = urls.optString(i)
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        addTab(url, activate = false)
                    }
                }
            }
        }
        if (tabs.isEmpty()) addTab(BrowserAddress.HOME)
        else select(tabs.getOrNull(prefs.getInt("activeIndex", 0))?.id ?: tabs.first().id)
    }

    fun addTab(url: String = BrowserAddress.HOME, activate: Boolean = true, load: Boolean = true): BrowserTab? {
        if (tabs.size >= maxTabs) {
            Toast.makeText(activity, "Maximum $maxTabs tabs", Toast.LENGTH_SHORT).show()
            return null
        }
        val id = nextId++
        val session = WebViewSession(activity, object : WebViewSession.Listener {
            override fun address(url: String) { tabs.firstOrNull { it.id == id }?.url = url }
            override fun title(value: String) { tabs.firstOrNull { it.id == id }?.title = value }
            override fun progress(value: Int) { tabs.firstOrNull { it.id == id }?.progress = value }
            override fun chooseFile(
                callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams
            ): Boolean = activity.chooseFile(id, callback, params)
            override fun popup(message: Message): Boolean {
                val child = addTab("about:blank", load = false) ?: return false
                val transport = message.obj as? WebView.WebViewTransport ?: return false
                transport.webView = child.session.view
                message.sendToTarget()
                return true
            }
            override fun closePopup() { closeTab(id) }
            override fun download(url: String, agent: String, disposition: String, mime: String) {
                downloadFile(url, agent, disposition, mime)
            }
            override fun requestPermission(request: PermissionRequest) {
                activity.confirmSitePermission(request)
            }
            override fun externalUri(uri: Uri): Boolean {
                if (uri.scheme !in setOf("mailto", "tel")) return true
                return try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, uri)
                        .addCategory(Intent.CATEGORY_BROWSABLE))
                    true
                } catch (_: ActivityNotFoundException) { true }
            }
        })
        val tab = BrowserTab(id, session)
        tabs.add(tab)
        if (activate || activeId == -1L) activeId = id
        if (load) session.load(BrowserAddress.resolve(url))
        return tab
    }

    fun navigate(address: String) { active?.session?.load(BrowserAddress.resolve(address)) }
    fun select(id: Long) { if (tabs.any { it.id == id }) activeId = id }
    fun closeTab(id: Long) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        activity.cancelPendingChooser(id)
        val tab = tabs.removeAt(index)
        if (activeId == id) activeId = tabs.getOrNull(index.coerceAtMost(tabs.lastIndex))?.id ?: -1L
        tab.session.dispose()
        if (tabs.isEmpty()) addTab()
    }
    fun back(): Boolean {
        val session = active?.session ?: return false
        return if (session.view.canGoBack()) { session.back(); true } else false
    }
    fun forward() { active?.session?.forward() }
    fun reload() { active?.session?.reload() }
    fun home() { active?.session?.load(BrowserAddress.HOME) }

    fun persist() {
        val stored = JSONArray()
        tabs.forEach {
            if (it.url.startsWith("http://") || it.url.startsWith("https://")) stored.put(it.url)
        }
        prefs.edit().putString("urls", stored.toString())
            .putInt("activeIndex", tabs.indexOfFirst { it.id == activeId }.coerceAtLeast(0)).apply()
    }
    fun dispose() { tabs.forEach { it.session.dispose() }; tabs.clear() }

    private fun downloadFile(url: String, agent: String, disposition: String, mime: String) {
        val uri = Uri.parse(url)
        if (uri.scheme !in setOf("http", "https")) {
            Toast.makeText(activity, "Unsupported download URL", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val name = URLUtil.guessFileName(url, disposition, mime)
            val request = DownloadManager.Request(uri)
                .setTitle(name)
                .setMimeType(mime)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                .addRequestHeader("User-Agent", agent)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }.onSuccess {
            Toast.makeText(activity, "Download started", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(activity, "Download failed: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
}
