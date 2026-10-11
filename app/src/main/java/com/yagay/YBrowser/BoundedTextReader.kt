package com.yagay.YBrowser

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Stop reading at the byte limit; truncating *after* readText still allocates unbounded memory. */
internal object BoundedTextReader {
    fun readUtf8(input: InputStream, maxBytes: Int): String? {
        require(maxBytes > 0)
        val out = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        while (out.size() <= maxBytes) {
            val amount = input.read(
                buffer, 0, minOf(buffer.size, maxBytes + 1 - out.size()),
            )
            if (amount < 0) break
            if (amount == 0) continue
            out.write(buffer, 0, amount)
        }
        return if (out.size() > maxBytes) null
        else out.toString(Charsets.UTF_8.name())
    }
}
