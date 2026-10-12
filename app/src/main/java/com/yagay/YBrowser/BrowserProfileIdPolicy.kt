package com.yagay.YBrowser

import java.util.UUID

/** Generates safe stable IDs for Gecko/WebView storage isolation. */
internal object BrowserProfileIdPolicy {
    private val allowed = Regex("p_[a-f0-9]{32}")

    fun next(
        existing: Set<String>,
        candidate: () -> String = {
            "p_" + UUID.randomUUID().toString().replace("-", "")
        },
    ): String {
        repeat(16) {
            val id = candidate()
            if (allowed.matches(id) && id !in existing) return id
        }
        error("Unable to allocate a unique browser Profile ID")
    }
}
