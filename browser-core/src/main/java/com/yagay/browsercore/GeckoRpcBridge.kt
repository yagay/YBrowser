package com.yagay.browsercore

import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.security.MessageDigest
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
        onExtensionReady: ((Boolean) -> Unit)? = null,
        onEvent: ((String, String) -> Unit)? = null,
        onDiagnostic: ((String, String) -> Unit)? = null,
    ): GeckoRpcBridge {
        val bridge = GeckoRpcBridge(
            session = session,
            mainHandler = mainHandler,
            onReady = onReady,
            onExtensionReady = onExtensionReady,
            onEvent = onEvent,
            onDiagnostic = onDiagnostic,
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
    private val onExtensionReady: ((Boolean) -> Unit)?,
    private val onEvent: ((String, String) -> Unit)?,
    private val onDiagnostic: ((String, String) -> Unit)?,
) {
    private data class Pending(
        val code: String,
        val callback: (String?, String?) -> Unit,
        val timeout: Runnable,
        var sent: Boolean = false,
        val chunks: MutableList<ByteArray> = mutableListOf(),
        var chunkCount: Int? = null,
        var totalBytes: Long? = null,
        var sha256: String? = null,
    ) {
        fun resetChunks() {
            chunks.clear()
            chunkCount = null
            totalBytes = null
            sha256 = null
        }
    }

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
                        onDiagnostic?.invoke("port-rejected", "sender/session mismatch")
                        runCatching { newPort.disconnect() }
                        return
                    }
                    onDiagnostic?.invoke("port-connected", "")

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
                                            onDiagnostic?.invoke(
                                                "event-received",
                                                "event=$event bytes=${payload.length}"
                                            )
                                            onEvent?.invoke(event, payload)
                                        }
                                        return
                                    }

                                    "rpc-result-chunk" -> {
                                        handleChunk(message)
                                        return
                                    }

                                    "rpc-result-end" -> {
                                        finishChunked(message)
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
                                if (error != null) {
                                    onDiagnostic?.invoke(
                                        "rpc-result-error",
                                        error.take(300)
                                    )
                                }
                                val value = if (message.has("value")) {
                                    message.optString("value")
                                } else {
                                    null
                                }
                                request.callback(value, error)
                            }

                            override fun onDisconnect(sourcePort: WebExtension.Port) {
                                if (port === sourcePort) {
                                    onDiagnostic?.invoke(
                                        "port-disconnected",
                                        "pending=${pending.size}"
                                    )
                                    port = null
                                    pending.values.forEach {
                                        it.sent = false
                                        it.resetChunks()
                                    }
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
        onDiagnostic?.invoke("extension-attached", "")
        onExtensionReady?.invoke(true)
    }

    fun markUnavailable() {
        onDiagnostic?.invoke("extension-unavailable", "")
        unavailable = true
        onExtensionReady?.invoke(false)
        val requests = pending.values.toList()
        pending.clear()
        requests.forEach {
            mainHandler.removeCallbacks(it.timeout)
            it.callback(null, "rpc-extension-unavailable")
        }
    }

    fun evaluate(
        code: String,
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
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
            val removed = pending.remove(requestId)
            if (removed != null) {
                onDiagnostic?.invoke(
                    "rpc-timeout",
                    "requestId=$requestId sent=${removed.sent} pending=${pending.size}"
                )
                removed.callback.invoke(null, "rpc-timeout")
            }
        }
        pending[requestId] = Pending(
            code = code,
            callback = callback,
            timeout = timeout,
        )
        mainHandler.postDelayed(
            timeout,
            timeoutMs.coerceIn(
                MIN_REQUEST_TIMEOUT_MS,
                MAX_REQUEST_TIMEOUT_MS,
            ),
        )
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

    private fun handleChunk(message: JSONObject) {
        val requestId = message.optInt("requestId", -1)
        val request = pending[requestId] ?: return

        val index = message.optInt("chunkIndex", -1)
        val count = message.optInt("chunkCount", -1)
        val totalBytes = message.optLong("totalBytes", -1L)
        val sha256 = message.optString("sha256")
        val data = message.optString("data")

        val manifestValid =
            index >= 0 &&
                count > 0 &&
                index < count &&
                totalBytes >= 0L &&
                totalBytes <= MAX_CHUNKED_RESULT_BYTES &&
                SHA256_RE.matches(sha256) &&
                data.isNotBlank()

        if (!manifestValid) {
            failChunked(requestId, "rpc-chunk-manifest-invalid")
            return
        }

        if (
            request.chunkCount != null &&
            (
                request.chunkCount != count ||
                    request.totalBytes != totalBytes ||
                    request.sha256 != sha256
                )
        ) {
            failChunked(requestId, "rpc-chunk-manifest-mismatch")
            return
        }

        if (index != request.chunks.size) {
            failChunked(requestId, "rpc-chunk-order-invalid")
            return
        }

        val decoded =
            runCatching {
                Base64.decode(data, Base64.DEFAULT)
            }.getOrNull()
                ?: run {
                    failChunked(requestId, "rpc-chunk-base64-invalid")
                    return
                }

        request.chunkCount = count
        request.totalBytes = totalBytes
        request.sha256 = sha256
        request.chunks += decoded

        onDiagnostic?.invoke(
            "rpc-result-chunk",
            "requestId=$requestId index=$index count=$count bytes=${decoded.size}",
        )
    }

    private fun finishChunked(message: JSONObject) {
        val requestId = message.optInt("requestId", -1)
        val request = pending[requestId] ?: return

        val count = message.optInt("chunkCount", -1)
        val totalBytes = message.optLong("totalBytes", -1L)
        val sha256 = message.optString("sha256")

        if (
            request.chunkCount != count ||
            request.totalBytes != totalBytes ||
            request.sha256 != sha256 ||
            request.chunks.size != count ||
            totalBytes < 0L ||
            totalBytes > Int.MAX_VALUE
        ) {
            failChunked(requestId, "rpc-chunk-final-manifest-mismatch")
            return
        }

        val body = ByteArray(totalBytes.toInt())
        var offset = 0
        for (chunk in request.chunks) {
            if (offset + chunk.size > body.size) {
                failChunked(requestId, "rpc-chunk-total-bytes-mismatch")
                return
            }
            chunk.copyInto(
                destination = body,
                destinationOffset = offset,
            )
            offset += chunk.size
        }

        if (offset != body.size) {
            failChunked(requestId, "rpc-chunk-total-bytes-mismatch")
            return
        }

        val actual =
            MessageDigest
                .getInstance("SHA-256")
                .digest(body)
                .joinToString("") {
                    "%02x".format(it)
                }

        if (!actual.equals(sha256, ignoreCase = true)) {
            failChunked(requestId, "rpc-chunk-digest-mismatch")
            return
        }

        pending.remove(requestId)
        mainHandler.removeCallbacks(request.timeout)
        val value = body.toString(Charsets.UTF_8)

        onDiagnostic?.invoke(
            "rpc-result-chunk-complete",
            "requestId=$requestId chunks=$count bytes=$totalBytes",
        )
        request.callback(value, null)
    }

    private fun failChunked(
        requestId: Int,
        reason: String,
    ) {
        val request = pending.remove(requestId) ?: return
        mainHandler.removeCallbacks(request.timeout)
        onDiagnostic?.invoke("rpc-result-error", reason)
        request.callback(null, reason)
    }

    private fun flush() {
        val activePort = port ?: run {
            if (pending.isNotEmpty()) {
                onDiagnostic?.invoke(
                    "flush-no-port",
                    "pending=${pending.size}"
                )
            }
            return
        }
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
        const val MIN_REQUEST_TIMEOUT_MS = 1_000L
        const val MAX_REQUEST_TIMEOUT_MS = 120_000L
        const val MAX_CHUNKED_RESULT_BYTES =
            64L * 1024L * 1024L
        val SHA256_RE = Regex("^[0-9a-f]{64}$")
    }
}
