package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabPreviewSizePolicyTest {
    @Test fun preservesSmallImagesWithoutEnlarging() {
        assertEquals(TabPreviewSize(400, 500), TabPreviewSizePolicy.fit(400, 500))
    }

    @Test fun limitsBothLandscapeAndTallPageImages() {
        assertEquals(TabPreviewSize(480, 320), TabPreviewSizePolicy.fit(1200, 800))
        assertEquals(TabPreviewSize(72, 720), TabPreviewSizePolicy.fit(300, 3000))
    }

    @Test fun supportsViewportSizedCaptureWithoutDistortingAspectRatio() {
        assertEquals(
            TabPreviewSize(420, 280),
            TabPreviewSizePolicy.fit(1200, 800, maxWidth = 420, maxHeight = 720),
        )
        assertEquals(
            TabPreviewSize(72, 720),
            TabPreviewSizePolicy.fit(300, 3000, maxWidth = 420, maxHeight = 720),
        )
        assertNull(TabPreviewSizePolicy.fit(100, 100, maxWidth = 0))
        assertNull(TabPreviewSizePolicy.fit(100, 100, maxHeight = 0))
    }

    @Test fun handlesOverflowAndInvalidDimensions() {
        assertEquals(TabPreviewSize(480, 480), TabPreviewSizePolicy.fit(Int.MAX_VALUE, Int.MAX_VALUE))
        assertNull(TabPreviewSizePolicy.fit(0, 100))
        assertNull(TabPreviewSizePolicy.fit(-1, 100))
        val result = TabPreviewSizePolicy.fit(1, Int.MAX_VALUE)!!
        assertTrue(result.width in 1..480 && result.height in 1..720)
    }
}
