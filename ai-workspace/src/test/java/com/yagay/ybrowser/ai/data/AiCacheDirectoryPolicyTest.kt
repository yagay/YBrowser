package com.yagay.ybrowser.ai.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCacheDirectoryPolicyTest {
    @Test fun neverMapsWindowIdToCurrentOrParentDirectory() {
        for (value in listOf("", ".", "..", "/", "./", "../", "\\\\")) {
            val safe = AiCacheDirectoryPolicy.safe(value)
            assertFalse(safe.isBlank())
            assertFalse(safe == ".")
            assertFalse(safe == "..")
            assertFalse(safe.contains('/'))
            assertFalse(safe.contains('\\'))
        }
    }

    @Test fun unsafeAndLongIdsDoNotCollideWithSafeIds() {
        assertFalse(AiCacheDirectoryPolicy.safe("chat/a") == AiCacheDirectoryPolicy.safe("chat_a"))
        assertFalse(AiCacheDirectoryPolicy.safe("chat a") == AiCacheDirectoryPolicy.safe("chat/a"))
        assertFalse(AiCacheDirectoryPolicy.safe("a".repeat(130)) == AiCacheDirectoryPolicy.safe("a".repeat(129) + "b"))
        assertTrue(AiCacheDirectoryPolicy.safe("chat/a").startsWith("~"))
    }

    @Test fun preservesExistingNormalIds() {
        assertTrue(AiCacheDirectoryPolicy.safe("ai-chat_12.3") == "ai-chat_12.3")
    }
}
