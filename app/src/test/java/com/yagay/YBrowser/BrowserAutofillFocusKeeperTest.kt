package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserAutofillFocusKeeperTest {
    @Test fun retainsWebFieldWhenOpeningMenuStealsFocus() {
        val keeper = BrowserAutofillFocusKeeper<Int>()
        keeper.snapshot(104)
        assertEquals(104, keeper.consume(null))
        assertNull(keeper.consume(null))
    }

    @Test fun prioritizesCurrentFieldAndClearsSnapshotAfterRequest() {
        val keeper = BrowserAutofillFocusKeeper<Int>()
        keeper.snapshot(104)
        assertEquals(201, keeper.consume(201))
        assertNull(keeper.consume(null))
    }

    @Test fun navigationRevokesPreviousPageField() {
        val keeper = BrowserAutofillFocusKeeper<Int>()
        keeper.snapshot(104)
        keeper.clear()
        assertNull(keeper.consume(null))
    }

    @Test fun noFocusNeverInventsAutofillTarget() {
        val keeper = BrowserAutofillFocusKeeper<Int>()
        keeper.snapshot(null)
        assertNull(keeper.consume(null))
    }
}
