package com.yagay.YBrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabCallbackOwnershipTest {
    @Test fun replacingRendererRejectsOldCallbacks() {
        val ownership = TabCallbackOwnership()
        val older = ownership.begin(8L)
        assertTrue(ownership.isCurrent(8L, older))
        val newer = ownership.begin(8L)
        assertFalse(ownership.isCurrent(8L, older))
        assertTrue(ownership.isCurrent(8L, newer))
    }

    @Test fun sessionEvictionKeepsLateFlushOnlyUntilReplacement() {
        val ownership = TabCallbackOwnership()
        val first = ownership.begin(1L)
        ownership.retire(1L, preserveSessionState = true)
        assertTrue(ownership.isCurrent(1L, first))
        ownership.begin(1L)
        assertFalse(ownership.isCurrent(1L, first))
    }

    @Test fun closingOrDestroyingRevokesCallbacks() {
        val ownership = TabCallbackOwnership()
        val a = ownership.begin(2L)
        val b = ownership.begin(3L)
        ownership.retire(2L, preserveSessionState = false)
        assertFalse(ownership.isCurrent(2L, a))
        assertTrue(ownership.isCurrent(3L, b))
        ownership.clear()
        assertFalse(ownership.isCurrent(3L, b))
    }
}
