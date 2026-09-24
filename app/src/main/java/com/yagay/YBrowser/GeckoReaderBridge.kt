package com.yagay.YBrowser

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

internal object GeckoReaderExtensionHost {
    private const val EXTENSION_ID = "ybrowser-reader@yagay.com"
    private const val EXTENSION_LOCATION = "resource://android/assets/ybrowser_reader/"
    private const val NATIVE_APP = "com.yagay.YBrowser.reader"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var extension: WebExtension? = null
    private var initializing = false
    private val waiters = mutableListOf<(WebExtension?) -> Unit>()

    fun bind(
        runtime: GeckoRuntime,
        session: GeckoSession,
        onMediaState: (BrowserMediaState?) -> Unit,
        onContentBlocked: (BrowserPrivacyEvent) -> Unit,
    ): GeckoReaderSessionBridge {
        val bridge = GeckoReaderSessionBridge(
            session = session,
            mainHandler = mainHandler,
            onMediaState = onMediaState,
            onContentBlocked = onContentBlocked,
        )
        ensure(runtime) { installed ->
            if (installed != null) {
                bridge.attach(installed)
            } else {
                bridge.markUnavailable()
            }
        }
        return bridge
    }

    private fun ensure(
        runtime: GeckoRuntime,
        callback: (WebExtension?) -> Unit,
    ) {
        extension?.let {
            callback(it)
            return
        }

        waiters += callback
        if (initializing) return
        initializing = true

        runtime.webExtensionController
            .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
            .withHandler(mainHandler)
            .accept(
                { installed ->
                    if (installed == null || installed.id != EXTENSION_ID) {
                        finish(null)
                        return@accept
                    }
                    runtime.webExtensionController
                        .setAllowedInPrivateBrowsing(installed, true)
                        .withHandler(mainHandler)
                        .accept(
                            { finish(installed) },
                            { finish(installed) },
                        )
                },
                { finish(null) },
            )
    }

    private fun finish(installed: WebExtension?) {
        initializing = false
        extension = installed
        val callbacks = waiters.toList()
        waiters.clear()
        callbacks.forEach { it(installed) }
    }

    internal const val APP = NATIVE_APP
    internal const val ID = EXTENSION_ID
}

internal class GeckoReaderSessionBridge(
    private val session: GeckoSession,
    private val mainHandler: Handler,
    private val onMediaState: (BrowserMediaState?) -> Unit,
    private val onContentBlocked: (BrowserPrivacyEvent) -> Unit,
) {
    private data class Pending(
        val callback: (String?) -> Unit,
        val timeout: Runnable,
        var sent: Boolean = false,
    )

    private var extension: WebExtension? = null
    private var port: WebExtension.Port? = null
    private var unavailable = false
    private var closed = false
    private var nextRequestId = 0
    private var userScripts: List<BrowserUserScript> = emptyList()
    private var customBlockedHosts: Set<String> = emptySet()
    private var doNotTrackEnabled = true
    private var globalPrivacyControlEnabled = true
    private var webRtcProtectionMode =
        WebRtcProtectionMode.STANDARD
    private val pending = linkedMapOf<Int, Pending>()

    fun attach(installed: WebExtension) {
        if (closed) return
        extension = installed
        session.webExtensionController.setMessageDelegate(
            installed,
            object : WebExtension.MessageDelegate {
                override fun onConnect(newPort: WebExtension.Port) {
                    if (closed ||
                        newPort.sender.webExtension.id != GeckoReaderExtensionHost.ID ||
                        newPort.sender.session !== session
                    ) {
                        runCatching { newPort.disconnect() }
                        return
                    }

                    port?.takeIf { it !== newPort }?.let {
                        runCatching { it.disconnect() }
                    }
                    port = newPort
                    newPort.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(
                            message: Any,
                            sourcePort: WebExtension.Port,
                        ) {
                            if (sourcePort !== port || message !is JSONObject) return
                            when (message.optString("type")) {
                                "reader-result" -> {
                                    val requestId = message.optInt("requestId", -1)
                                    val request = pending.remove(requestId) ?: return
                                    mainHandler.removeCallbacks(request.timeout)
                                    val payload = message.optJSONObject("payload")?.toString()
                                    request.callback(payload)
                                }

                                "media-state" -> {
                                    onMediaState(
                                        BrowserMediaState(
                                            title = message.optString("title")
                                                .ifBlank { "网页媒体" },
                                            url = message.optString("url"),
                                            playing = message.optBoolean("playing", false),
                                            durationMs = message.optLong("durationMs", -1L),
                                            positionMs = message.optLong("positionMs", 0L),
                                        ),
                                    )
                                }

                                "custom-blocked" -> {
                                    val url = message.optString("url")
                                    if (url.isNotBlank()) {
                                        onContentBlocked(
                                            BrowserPrivacyEvent(
                                                url = url,
                                                category = "自定义过滤",
                                            ),
                                        )
                                    }
                                }
                            }
                        }

                        override fun onDisconnect(sourcePort: WebExtension.Port) {
                            if (port === sourcePort) {
                                port = null
                                pending.values.forEach { it.sent = false }
                                onMediaState(null)
                            }
                        }
                    })
                    flush()
                    sendUserScripts()
                    sendCustomBlockedHosts()
                    sendPrivacyPolicy()
                }
            },
            GeckoReaderExtensionHost.APP,
        )
    }

    fun markUnavailable() {
        unavailable = true
        val callbacks = pending.values.map { it.callback }
        pending.values.forEach { mainHandler.removeCallbacks(it.timeout) }
        pending.clear()
        callbacks.forEach { it(null) }
    }

    fun sendMediaCommand(command: BrowserMediaCommand) {
        val activePort = port ?: return
        runCatching {
            activePort.postMessage(
                JSONObject()
                    .put("type", "media-command")
                    .put("command", command.name.lowercase()),
            )
        }
    }

    fun setPageMuted(muted: Boolean) {
        val activePort = port ?: return
        runCatching {
            activePort.postMessage(
                JSONObject()
                    .put("type", "set-muted")
                    .put("muted", muted),
            )
        }
    }

    fun setUserScripts(scripts: List<BrowserUserScript>) {
        userScripts = scripts.filter { it.enabled }
        sendUserScripts()
    }

    private fun sendUserScripts() {
        val activePort = port ?: return
        val payload = JSONArray()
        userScripts.forEach { script ->
            payload.put(
                JSONObject()
                    .put("id", script.id)
                    .put("name", script.name)
                    .put("match", script.match)
                    .put("code", script.code),
            )
        }
        runCatching {
            activePort.postMessage(
                JSONObject()
                    .put("type", "user-scripts")
                    .put("scripts", payload),
            )
        }
    }

    fun setCustomBlockedHosts(hosts: Set<String>) {
        customBlockedHosts = hosts
            .map { it.lowercase().trim().trimEnd('.') }
            .filter { it.isNotBlank() }
            .toSet()
        sendCustomBlockedHosts()
    }

    private fun sendCustomBlockedHosts() {
        val activePort = port ?: return
        val payload = JSONArray()
        customBlockedHosts.sorted().forEach(payload::put)
        runCatching {
            activePort.postMessage(
                JSONObject()
                    .put("type", "custom-block-hosts")
                    .put("hosts", payload),
            )
        }
    }

    fun setPrivacyPolicy(
        doNotTrackEnabled: Boolean,
        globalPrivacyControlEnabled: Boolean,
        webRtcProtectionMode: WebRtcProtectionMode,
    ) {
        this.doNotTrackEnabled =
            doNotTrackEnabled
        this.globalPrivacyControlEnabled =
            globalPrivacyControlEnabled
        this.webRtcProtectionMode =
            webRtcProtectionMode
        sendPrivacyPolicy()
    }

    private fun sendPrivacyPolicy() {
        val activePort = port ?: return
        runCatching {
            activePort.postMessage(
                JSONObject()
                    .put(
                        "type",
                        "privacy-policy",
                    )
                    .put(
                        "doNotTrackEnabled",
                        doNotTrackEnabled,
                    )
                    .put(
                        "globalPrivacyControlEnabled",
                        globalPrivacyControlEnabled,
                    )
                    .put(
                        "webRtcProtectionMode",
                        webRtcProtectionMode.name,
                    ),
            )
        }
    }

    fun extract(onResult: (String?) -> Unit) {
        if (closed || unavailable) {
            onResult(null)
            return
        }

        nextRequestId = if (nextRequestId == Int.MAX_VALUE) 1 else nextRequestId + 1
        val requestId = nextRequestId
        val timeout = Runnable {
            pending.remove(requestId)?.callback?.invoke(null)
        }
        pending[requestId] = Pending(
            callback = onResult,
            timeout = timeout,
        )
        mainHandler.postDelayed(timeout, REQUEST_TIMEOUT_MS)
        flush()
    }

    fun close() {
        if (closed) return
        closed = true
        port?.let { runCatching { it.disconnect() } }
        port = null
        pending.values.forEach { mainHandler.removeCallbacks(it.timeout) }
        val callbacks = pending.values.map { it.callback }
        pending.clear()
        callbacks.forEach { it(null) }
        onMediaState(null)

        extension?.let { installed ->
            runCatching {
                session.webExtensionController.setMessageDelegate(
                    installed,
                    null,
                    GeckoReaderExtensionHost.APP,
                )
            }
        }
        extension = null
    }

    private fun flush() {
        val activePort = port ?: return
        pending.forEach { (requestId, request) ->
            if (request.sent) return@forEach
            val sent = runCatching {
                activePort.postMessage(
                    JSONObject()
                        .put("type", "extract-reader")
                        .put("requestId", requestId),
                )
                true
            }.getOrDefault(false)
            request.sent = sent
        }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 12_000L
    }
}
