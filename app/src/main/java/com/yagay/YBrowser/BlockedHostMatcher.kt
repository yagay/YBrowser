package com.yagay.YBrowser

/** Domain/subdomain matching without scanning every blocked entry per request. */
internal object BlockedHostMatcher {
    fun matches(rawHost: String, hosts: Collection<String>): Boolean {
        if (hosts.isEmpty()) return false
        val membership: Set<*> = if (hosts is Set<*>) hosts else hosts.toHashSet()
        var suffix = rawHost.lowercase().trimEnd('.')
        while (suffix.isNotEmpty()) {
            if (suffix in membership) return true
            val dot = suffix.indexOf('.')
            if (dot < 0) break
            suffix = suffix.substring(dot + 1)
        }
        return false
    }
}
