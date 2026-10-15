package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserFileChooserPolicyTest {
    private val identity = BrowserFileChooserIdentity(tabId = 42L, documentGeneration = 104L)
    private val current = BrowserFileChooserState(
        selectedTabId = 42L, documentGeneration = 104L,
        tabExists = true, isActivityStarted = true,
    )

    @Test fun sameDocumentAndSpaRouteStayValid() {
        assertTrue(BrowserFileChooserPolicy.isCurrent(identity, current))
    }

    @Test fun reloadSameUrlInvalidatesOldChooserGeneration() {
        assertFalse(BrowserFileChooserPolicy.isCurrent(
            identity, current.copy(documentGeneration = 105L),
        ))
    }

    @Test fun switchedClosedOrStoppedActivityRejectsStaleResults() {
        assertFalse(BrowserFileChooserPolicy.isCurrent(identity, current.copy(selectedTabId = 12L)))
        assertFalse(BrowserFileChooserPolicy.isCurrent(identity, current.copy(tabExists = false)))
        assertFalse(BrowserFileChooserPolicy.isCurrent(identity, current.copy(isActivityStarted = false)))
        assertFalse(BrowserFileChooserPolicy.isCurrent(identity, current.copy(documentGeneration = null)))
    }

    @Test fun uriInputRequiresStructuredContentWithoutEmbeddedUserInfoOrControl() {
        assertTrue(BrowserFileChooserPolicy.safeUriString("content://media/external/images/1"))
        assertFalse(BrowserFileChooserPolicy.safeUriString("file:///data/local/file"))
        assertFalse(BrowserFileChooserPolicy.safeUriString("content:///missing-authority"))
        assertFalse(BrowserFileChooserPolicy.safeUriString("content://user@provider/file"))
        assertFalse(BrowserFileChooserPolicy.safeUriString("content://media/file\nextra"))
    }

    @Test fun mimeAcceptsWildcardExactCategoryAndHtmlExtensions() {
        assertTrue(BrowserFileChooserPolicy.acceptsMimeType("image/jpeg", listOf("image/*")))
        assertTrue(BrowserFileChooserPolicy.acceptsMimeType("application/pdf", listOf(".pdf"), "a.PDF"))
        assertTrue(BrowserFileChooserPolicy.acceptsMimeType(null, listOf("*/*")))
        assertTrue(BrowserFileChooserPolicy.acceptsMimeType("application/pdf", emptyList()))
        assertFalse(BrowserFileChooserPolicy.acceptsMimeType("application/pdf", listOf("image/*")))
        assertFalse(BrowserFileChooserPolicy.acceptsMimeType(null, listOf("image/png")))
    }

    @Test fun singleAndMultipleSelectionAreBoundedAndUnique() {
        assertEquals(listOf(1), BrowserFileChooserPolicy.distinctSelection(listOf(1, 1, 2), false))
        assertEquals(listOf(1, 2), BrowserFileChooserPolicy.distinctSelection(listOf(1, 1, 2), true))
        assertEquals(100, BrowserFileChooserPolicy.distinctSelection((1..120).toList(), true)?.size)
        assertEquals(null, BrowserFileChooserPolicy.distinctSelection<Int>(emptyList(), true))
    }

    @Test fun callbacksCompleteOnceEvenAfterCancellation() {
        val calls = mutableListOf<List<Int>?>()
        val delivery = BrowserFileChooserDelivery<Int> { calls += it }
        assertTrue(delivery.complete(null))
        assertFalse(delivery.complete(listOf(3)))
        assertEquals(listOf<List<Int>?>(null), calls)
    }
}
