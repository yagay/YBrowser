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

    @Test fun promptsOnlyReachAnAttachedCurrentRenderer() {
        val ownership = TabCallbackOwnership()
        val first = ownership.begin(9L)
        assertTrue(ownership.mayDispatchUi(9L, first, first, false, true))
        assertFalse(ownership.mayDispatchUi(9L, first, first, false, false))
        assertFalse(ownership.mayDispatchUi(9L, first, null, false, true))

        // Eviction preserves a last Gecko state flush but not UI prompts.
        ownership.retire(9L, preserveSessionState = true)
        assertTrue(ownership.isCurrent(9L, first))
        assertFalse(ownership.mayDispatchUi(9L, first, null, false, true))

        val second = ownership.begin(9L)
        assertFalse(ownership.mayDispatchUi(9L, first, second, false, true))
        assertTrue(ownership.mayDispatchUi(9L, second, null, true, true))
        assertFalse(ownership.mayDispatchUi(9L, first, null, true, true))
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
