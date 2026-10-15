package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserFileChooserPolicyTest {
    @Test fun currentTabCanReceiveResultDespiteFragmentNavigation() {
        assertTrue(BrowserFileChooserPolicy.isCurrent(
            11, "https://example.org/upload#start", 11, true,
            "https://example.org/upload#done",
        ))
    }

    @Test fun staleDocumentAndClosedOrSwitchedTabMustNotReceiveResult() {
        assertFalse(BrowserFileChooserPolicy.isCurrent(
            11, "https://example.org/upload", 12, true, "https://example.org/upload",
        ))
        assertFalse(BrowserFileChooserPolicy.isCurrent(
            11, "https://example.org/upload", 11, false, null,
        ))
        assertFalse(BrowserFileChooserPolicy.isCurrent(
            11, "https://example.org/upload", 11, true, "https://example.org/other",
        ))
    }

    @Test fun singleAndMultipleFileSelectionAreBoundedAndDeduplicated() {
        assertEquals(listOf(1), BrowserFileChooserPolicy.distinctSelection(
            listOf(1, 1, 2), false,
        ))
        assertEquals(listOf(1, 2), BrowserFileChooserPolicy.distinctSelection(
            listOf(1, 1, 2), true,
        ))
        assertEquals(100, BrowserFileChooserPolicy.distinctSelection(
            (1..120).toList(), true,
        )?.size)
        assertEquals(null, BrowserFileChooserPolicy.distinctSelection<Int>(
            emptyList(), true,
        ))
    }

    @Test fun callbackCompletesExactlyOnceEvenIfLaterResultsArrive() {
        val delivered = mutableListOf<List<Int>?>()
        val callback = BrowserFileChooserDelivery<Int> { delivered += it }
        assertTrue(callback.complete(null))
        assertFalse(callback.complete(listOf(99)))
        assertEquals(listOf<List<Int>?>(null), delivered)
    }
}
