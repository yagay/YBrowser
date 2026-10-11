package com.yagay.YBrowser

internal data class ParsedAdblockRules(
    val blocked: List<String>,
    val exceptions: List<String>,
    val cosmetics: List<String>,
)

/**
 * Safe ABP subset. Never collapse resource-type/party/path-specific rules
 * into unconditional host blocks: that would break first-party websites.
 */
internal object BrowserAdblockRuleParser {
    private val hostname = Regex("""^[a-z0-9-]+(?:\.[a-z0-9-]+)+$""")

    fun isValidCosmeticRule(line: String): Boolean {
        val marker = if (line.contains("#@#")) "#@#" else "##"
        val index = line.indexOf(marker)
        if (index < 0 || line.length > 240) return false
        val domains = line.substring(0, index)
        val selector = line.substring(index + marker.length)
        if (selector.isBlank() || selector.length > 180 ||
            selector.any { it == '{' || it == '}' || it == ';' || it == '\\' ||
                it == '\n' || it == '\r' || it.code < 32
            }
        ) return false
        if ("/*" in selector || "*/" in selector || "@import" in selector ||
            "url(" in selector.lowercase()
        ) return false
        return domains.isEmpty() || domains.split(',').all { token ->
            token.isNotBlank() && !token.startsWith("~") &&
                isHostname(token.trim())
        }
    }

    fun parse(
        text: String,
        maxHosts: Int,
        maxCosmeticRules: Int,
    ): ParsedAdblockRules {
        val blocked = LinkedHashSet<String>()
        val exceptions = LinkedHashSet<String>()
        val cosmetic = LinkedHashSet<String>()
        text.lineSequence().forEach { original ->
            val line = original.trim()
            if (line.isBlank() || line.startsWith("!") ||
                line.startsWith("[") || line.startsWith("# ")
            ) return@forEach
            if ("#?#" in line || "#$#" in line) return@forEach
            if ("##" in line || "#@#" in line) {
                if (cosmetic.size < maxCosmeticRules && isValidCosmeticRule(line)) {
                    cosmetic += line
                }
                return@forEach
            }
            val isException = line.startsWith("@@")
            val source = if (isException) line.substring(2) else line
            // Modifier rules such as $script, $third-party and $domain have
            // different semantics; interpreting them globally is unsafe.
            if ('$' in source || '*' in source || '?' in source) return@forEach
            val host = when {
                source.startsWith("||") -> {
                    val expression = source.substring(2)
                    val candidate = expression.substringBefore('^')
                    if (expression != candidate &&
                        expression != candidate + "^"
                    ) null
                    else if ('/' in candidate) null else candidate
                }
                source.startsWith("0.0.0.0 ") ||
                    source.startsWith("127.0.0.1 ") ->
                    source.substringAfter(' ').trim().substringBefore(' ')
                else -> null
            }?.lowercase()?.trimEnd('.')?.removePrefix("www.")
            if (host == null || !isHostname(host)) return@forEach
            if (isException) {
                if (exceptions.size < maxHosts) exceptions += host
            } else {
                if (blocked.size < maxHosts) blocked += host
            }
        }
        return ParsedAdblockRules(
            blocked.toList(), exceptions.toList(), cosmetic.toList(),
        )
    }

    private fun isHostname(value: String): Boolean =
        value.length <= 253 && hostname.matches(value.lowercase())
}
