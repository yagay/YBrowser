package com.yagay.ybrowser.engine

import android.content.Context
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup

/** Isolates WebView-specific APIs from tab presentation and app-level controllers. */
class WebViewSession(context: Context, private val listener: Listener) {
    interface Listener {
        fun address(url: String)
        fun title(value: String)
        fun progress(value: Int)
        fun chooseFile(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
        fun popup(message: Message): Boolean
        fun closePopup()
        fun download(url: String, agent: String, disposition: String, mime: String)
        fun requestPermission(request: PermissionRequest)
        fun externalUri(uri: Uri): Boolean
    }

    val view = WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                return if (scheme == "http" || scheme == "https" || scheme == "about") false
                else listener.externalUri(request.url)
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) = listener.address(url)
            override fun onPageFinished(view: WebView, url: String) = listener.address(url)
        }
        webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                if (!title.isNullOrBlank()) listener.title(title)
            }
            override fun onProgressChanged(view: WebView, newProgress: Int) = listener.progress(newProgress)
            override fun onShowFileChooser(
                webView: WebView?, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean = listener.chooseFile(callback, params)
            override fun onCreateWindow(view: WebView?, isDialog: Boolean,
                isUserGesture: Boolean, resultMsg: Message
            ): Boolean = isUserGesture && listener.popup(resultMsg)
            override fun onCloseWindow(window: WebView) = listener.closePopup()
            override fun onPermissionRequest(request: PermissionRequest) = listener.requestPermission(request)
        }
        setDownloadListener(DownloadListener { url, agent, disposition, mime, _ ->
            listener.download(url, agent ?: "", disposition ?: "", mime ?: "")
        })
    }

    fun load(url: String) = view.loadUrl(url)
    fun back() = view.goBack()
    fun forward() = view.goForward()
    fun reload() = view.reload()
    fun dispose() {
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }
}
