package com.yagay.ybrowser.core

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object BrowserAddress {
    const val HOME = "https://duckduckgo.com/"
    fun resolve(raw: String): String {
        val input = raw.trim()
        if (input.isEmpty()) return HOME
        if (input.equals("about:blank", true)) return "about:blank"
        if (input.startsWith("https://", true) || input.startsWith("http://", true)) return input
        if (input.none(Char::isWhitespace) &&
            (input.contains('.') || input.equals("localhost", true)) &&
            !input.contains("://") &&
            !input.startsWith("javascript:", true)) return "https://$input"
        return "https://duckduckgo.com/?q=" + URLEncoder.encode(input, StandardCharsets.UTF_8.name())
    }
}
