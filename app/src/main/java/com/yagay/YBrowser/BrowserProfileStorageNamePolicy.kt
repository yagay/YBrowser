package com.yagay.YBrowser

import java.security.MessageDigest

/**
 * Retain the storage names used by existing safe profile IDs (including the
 * generated p_<uuid> IDs). A lossy character replacement or truncation could
 * otherwise cause two imported profiles to share a Gecko/WebView profile.
 */
internal object BrowserProfileStorageNamePolicy {
    private val simple = Regex("[A-Za-z0-9_.-]{1,64}")
    private const val HASH_PREFIX = "__hashed_"

    fun name(id: String): String {
        if (simple.matches(id) && id != "." && id != ".." &&
            !id.startsWith(HASH_PREFIX)
        ) return id
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return HASH_PREFIX + sha.take(48)
    }
}
