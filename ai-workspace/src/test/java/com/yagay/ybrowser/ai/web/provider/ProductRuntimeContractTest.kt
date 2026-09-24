package com.yagay.ybrowser.ai.web.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductRuntimeContractTest {
    @Test
    fun chatGptContractKeepsCwaFinalityAndRetryInvariants() {
        val contract =
            ChatGptProductContract.runtime

        assertEquals(1, contract.schema)
        assertEquals(
            "ordinary-chatgpt",
            contract.productSemantics,
        )
        assertEquals(
            "gecko-browser-owned",
            contract.transport,
        )
        assertEquals(
            ProductTransportSupportTier.PRODUCTION,
            contract.transportSupportTier,
        )
        assertFalse(contract.automaticWriteRetry)
        assertNull(contract.fallbackTransport)
        assertFalse(contract.legacyDirectWriteFallback)
        assertTrue(
            contract.ambiguousWriteRequiresReconciliation
        )
        assertFalse(
            contract.incrementalObservationIsCanonicalFinality
        )
    }

    @Test
    fun upstreamPinIsExplicit() {
        assertEquals("v0.3.0", CwaUpstream.RELEASE)
        assertEquals(
            "83a99e79817db2bba656944e0eedcfe1c661929c",
            CwaUpstream.MAIN_COMMIT,
        )
    }
}
