package com.yagay.YBrowser.integration.yagayhub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YagaYHubBroadcastPolicyTest {
    @Test fun acceptsOnlyAndroidVerifiedPackageAndUid() {
        val packageName = YagaYHubContract.PACKAGE
        assertTrue(YagaYHubBroadcastPolicy.isTrusted(12345, packageName, arrayOf(packageName)))
        assertFalse(YagaYHubBroadcastPolicy.isTrusted(12345, packageName, arrayOf("other.app")))
        assertFalse(YagaYHubBroadcastPolicy.isTrusted(12345, "other.app", arrayOf(packageName)))
        assertFalse(YagaYHubBroadcastPolicy.isTrusted(-1, packageName, arrayOf(packageName)))
        assertFalse(YagaYHubBroadcastPolicy.isTrusted(12345, null, arrayOf(packageName)))
        assertFalse(YagaYHubBroadcastPolicy.isTrusted(12345, packageName, null))
    }
}
