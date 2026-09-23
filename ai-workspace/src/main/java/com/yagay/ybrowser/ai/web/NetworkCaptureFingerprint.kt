package com.yagay.ybrowser.ai.web

/**
 * Stable dedupe identity for captured provider responses.
 *
 * Passive captures may be collapsed when their response is identical.
 * Canonical reads are explicit authority requests and must stay distinct from
 * passive traffic and from one another, even when the wire body is identical.
 */
internal object NetworkCaptureFingerprint {
    fun key(
        runtimeKey: String,
        requestId: String,
        capture: CapturedNetworkPayload,
    ): String =
        buildString {
            append(runtimeKey)
            append('|')
            append(capture.url)
            append('|')
            append(capture.statusCode)
            append('|')
            append(capture.complete)
            append('|')
            append(capture.truncated)
            append('|')
            append(capture.body.length)
            append('|')
            append(capture.body.hashCode())

            if (capture.canonical) {
                append("|canonical|")
                append(requestId)
            } else {
                append("|passive")
            }
        }
}
