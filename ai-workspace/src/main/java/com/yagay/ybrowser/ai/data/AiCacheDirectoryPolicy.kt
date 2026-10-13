package com.yagay.ybrowser.ai.data

/** Prevent untrusted/restored window ids from selecting . or .. directories. */
internal object AiCacheDirectoryPolicy {
    private val forbidden = Regex("[^A-Za-z0-9._-]")
    fun safe(windowId: String): String {
        val filtered = windowId.replace(forbidden, "_").take(128)
        return if (filtered.isBlank() || filtered == "." || filtered == "..") {
            "invalid-" + windowId.hashCode().toUInt().toString(16)
        } else {
            filtered
        }
    }
}
