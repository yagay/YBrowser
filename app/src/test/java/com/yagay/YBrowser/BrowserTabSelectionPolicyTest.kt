package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserTabSelectionPolicyTest {
    @Test fun retainsSelectedNormalTab() {
        assertEquals(8L, BrowserTabSelectionPolicy.persistedSelection(listOf(2L, 8L), 8L))
    }

    @Test fun privateTabSelectionFallsBackToRestorableTab() {
        assertEquals(2L, BrowserTabSelectionPolicy.persistedSelection(listOf(2L, 8L), 77L))
    }

    @Test fun privateOnlySessionFallsBackToDefaultTabId() {
        assertEquals(1L, BrowserTabSelectionPolicy.persistedSelection(emptyList(), 19L))
    }
}
