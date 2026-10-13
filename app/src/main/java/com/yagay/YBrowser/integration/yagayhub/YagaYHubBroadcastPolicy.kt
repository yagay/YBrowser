package com.yagay.YBrowser.integration.yagayhub

/** Small testable gate for untrusted cross-app binding mutations. */
internal object YagaYHubBroadcastPolicy {
    fun isTrusted(uid: Int, claimedPackage: String?, packagesForUid: Array<String>?): Boolean =
        uid > 0 &&
            claimedPackage == YagaYHubContract.PACKAGE &&
            packagesForUid?.contains(YagaYHubContract.PACKAGE) == true
}
