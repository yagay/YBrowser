package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserFileChooserPolicyTest {
    @Test fun originalEnginePromptCanReceiveResultAfterSpaNavigation() {
        // Gecko/FileChooser callbacks already belong to their source document.
        assertTrue(BrowserFileChooserPolicy.isCurrent(tabExists = true))
    }

    @Test fun closedTabMustNotReceiveLateAndroidPickerResult() {
        assertFalse(BrowserFileChooserPolicy.isCurrent(tabExists = false))
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
