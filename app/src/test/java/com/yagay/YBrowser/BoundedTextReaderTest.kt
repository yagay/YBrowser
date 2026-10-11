package com.yagay.YBrowser

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedTextReaderTest {
    @Test fun readsUtf8AndEmptyText() {
        assertEquals("你好", BoundedTextReader.readUtf8(
            ByteArrayInputStream("你好".toByteArray(Charsets.UTF_8)), 6,
        ))
        assertEquals("", BoundedTextReader.readUtf8(ByteArrayInputStream(byteArrayOf()), 3))
    }

    @Test fun rejectsOversizedResponseWithoutReadingWholeStream() {
        val input = object : ByteArrayInputStream(ByteArray(100_000)) {}
        assertNull(BoundedTextReader.readUtf8(input, 1024))
        assertEquals(100_000 - 1025, input.available())
    }

    @Test fun rejectsNonPositiveLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            BoundedTextReader.readUtf8(ByteArrayInputStream(byteArrayOf(1)), 0)
        }
    }
}
