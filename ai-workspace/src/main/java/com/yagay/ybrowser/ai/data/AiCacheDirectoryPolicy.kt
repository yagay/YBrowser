package com.yagay.ybrowser.ai.data

import java.security.MessageDigest

/** Safe IDs stay stable; unsafe IDs use a collision-resistant reserved prefix. */
internal object AiCacheDirectoryPolicy {
    private val simple = Regex("[A-Za-z0-9._-]{1,128}")
    fun safe(windowId: String): String {
        if (simple.matches(windowId) && windowId != "." && windowId != "..") {
            return windowId
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(windowId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "~$digest"
    }
}
