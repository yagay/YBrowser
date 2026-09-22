package com.yagay.browsercore

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

internal object GeckoRpcExtensionHost {
    private const val EXTENSION_ID = "ybrowser-core-rpc@yagay.com"
    private const val EXTENSION_LOCATION = "resource://android/assets/ybrowser_core_rpc/"
    private const val NATIVE_APP = "com.yagay.browsercore.rpc"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var extension: WebExtension? = null
    private var initializing = false
    private val waiters = mutableListOf<(WebExtension?) -> Unit>()

    fun bind(
        runtime: GeckoRuntime,
        session: GeckoSession,
        onReady: (() -> Unit)? = null,
        onEvent: ((String, String) -> Unit)? = null,
    ): GeckoRpcBridge {
        val bridge = GeckoRpcBridge(
            session = session,
            mainHandler = mainHandler,
            onReady = onReady,
            onEvent = onEvent,
        )
        ensure(runtime) { installed ->
            if (installed != null) bridge.attach(installed)
            else bridge.markUnavailable()
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

internal class GeckoRpcBridge(
    private val session: GeckoSession,
    private val mainHandler: Handler,
    private val onReady: (() -> Unit)?,
    private val onEvent: ((String, String) -> Unit)?,
) {
    private data class Pending(
        val code: String,
        val callback: (String?, String?) -> Unit,
        val timeout: Runnable,
        var sent: Boolean = false,
    )

    private var extension: WebExtension? = null
    private var port: WebExtension.Port? = null
    private var unavailable = false
    private var closed = false
    private var nextRequestId = 0
    private val pending = linkedMapOf<Int, Pending>()

    fun attach(installed: WebExtension) {
        if (closed) return
        extension = installed
        session.webExtensionController.setMessageDelegate(
            installed,
            object : WebExtension.MessageDelegate {
                override fun onConnect(newPort: WebExtension.Port) {
                    if (
                        closed ||
                        newPort.sender.webExtension.id != GeckoRpcExtensionHost.ID ||
                        newPort.sender.session !== session
                    ) {
                        runCatching { newPort.disconnect() }
                        return
                    }

                    port?.takeIf { it !== newPort }?.let {
                        runCatching { it.disconnect() }
                    }
                    port = newPort
                    newPort.setDelegate(
                        object : WebExtension.PortDelegate {
                            override fun onPortMessage(
                                message: Any,
                                sourcePort: WebExtension.Port,
                            ) {
                                if (sourcePort !== port || message !is JSONObject) return

                                when (message.optString("type")) {
                                    "rpc-event" -> {
                                        val event = message.optString("event")
                                        val payload = message.optString("payload")
                                        if (event.isNotBlank()) {
                                            onEvent?.invoke(event, payload)
                                        }
                                        return
                                    }
                                    "rpc-result" -> Unit
                                    else -> return
                                }

                                val requestId = message.optInt("requestId", -1)
                                val request = pending.remove(requestId) ?: return
                                mainHandler.removeCallbacks(request.timeout)

                                val error = message.optString("error")
                                    .takeIf { it.isNotBlank() }
                                val value = if (message.has("value")) {
                                    message.optString("value")
                                } else {
                                    null
                                }
                                request.callback(value, error)
                            }

                            override fun onDisconnect(sourcePort: WebExtension.Port) {
                                if (port === sourcePort) {
                                    port = null
                                    pending.values.forEach { it.sent = false }
                                }
                            }
                        },
                    )
                    flush()
                    onReady?.invoke()
                }
            },
            GeckoRpcExtensionHost.APP,
        )
    }

    fun markUnavailable() {
        unavailable = true
        val requests = pending.values.toList()
        pending.clear()
        requests.forEach {
            mainHandler.removeCallbacks(it.timeout)
            it.callback(null, "rpc-extension-unavailable")
        }
    }

    fun evaluate(
        code: String,
        callback: (String?, String?) -> Unit,
    ) {
        if (closed) {
            callback(null, "rpc-closed")
            return
        }
        if (unavailable) {
            callback(null, "rpc-extension-unavailable")
            return
        }

        nextRequestId = if (nextRequestId == Int.MAX_VALUE) 1 else nextRequestId + 1
        val requestId = nextRequestId
        val timeout = Runnable {
            pending.remove(requestId)?.callback?.invoke(null, "rpc-timeout")
        }
        pending[requestId] = Pending(
            code = code,
            callback = callback,
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
        val requests = pending.values.toList()
        pending.clear()
        requests.forEach { it.callback(null, "rpc-closed") }

        extension?.let { installed ->
            runCatching {
                session.webExtensionController.setMessageDelegate(
                    installed,
                    null,
                    GeckoRpcExtensionHost.APP,
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
                        .put("type", "rpc")
                        .put("requestId", requestId)
                        .put("code", request.code),
                )
                true
            }.getOrDefault(false)
            request.sent = sent
        }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}
