package com.yagay.YBrowser

import java.net.URI

/** A saved reader snapshot is intentionally only for normal HTTP(S) pages. */
internal object OfflineSnapshotPolicy {
    fun canSave(sourceUrl: String, privateMode: Boolean): Boolean {
        if (privateMode) return false
        val address = runCatching { URI(sourceUrl.trim()) }.getOrNull() ?: return false
        return (address.scheme.equals("http", true) ||
            address.scheme.equals("https", true)) &&
            !address.host.isNullOrBlank() &&
            address.userInfo == null &&
            address.port in -1..65535
    }
}
