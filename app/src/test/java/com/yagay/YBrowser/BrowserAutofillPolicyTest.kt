package com.yagay.YBrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAutofillPolicyTest {
    @Test fun canUseAndroidAutofillInNormalTabs() {
        assertTrue(BrowserAutofillPolicy.enabled(true, false))
        assertFalse(BrowserAutofillPolicy.enabled(false, false))
    }

    @Test fun privateTabsNeverPassFormsToAndroidAutofill() {
        assertFalse(BrowserAutofillPolicy.enabled(true, true))
        assertFalse(BrowserAutofillPolicy.enabled(false, true))
    }
}
