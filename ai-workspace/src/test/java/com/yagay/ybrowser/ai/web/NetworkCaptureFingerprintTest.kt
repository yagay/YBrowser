package com.yagay.ybrowser.ai.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NetworkCaptureFingerprintTest {
    private fun capture(
        canonical: Boolean,
    ) =
        CapturedNetworkPayload(
            requestId = "wire-request",
            url =
                "https://chatgpt.com/backend-api/conversations/demo",
            method = "GET",
            statusCode = 200,
            contentType = "application/json",
            body = """{"messages":[1,2,3]}""",
            stream = false,
            complete = true,
            truncated = false,
            canonical = canonical,
            capturedAt = 1L,
        )

    @Test
    fun passiveDuplicate_ignoresRequestId() {
        val packet = capture(canonical = false)

        assertEquals(
            NetworkCaptureFingerprint.key(
                "chatgpt-window",
                "passive-a",
                packet,
            ),
            NetworkCaptureFingerprint.key(
                "chatgpt-window",
                "passive-b",
                packet,
            ),
        )
    }

    @Test
    fun canonicalRead_neverCollidesWithPassiveCapture() {
        val passive =
            capture(canonical = false)
        val canonical =
            capture(canonical = true)

        assertNotEquals(
            NetworkCaptureFingerprint.key(
                "chatgpt-window",
                "same-request",
                passive,
            ),
            NetworkCaptureFingerprint.key(
                "chatgpt-window",
                "same-request",
                canonical,
            ),
        )
    }

    @Test
    fun separateCanonicalReads_remainDistinct() {
        val packet = capture(canonical = true)

        assertNotEquals(
            NetworkCaptureFingerprint.key(
                "chatgpt-window",
                "canonical-a",
                packet,
            ),
            NetworkCaptureFingerprint.key(
                "chatgpt-window",
                "canonical-b",
                packet,
            ),
        )
    }
}
