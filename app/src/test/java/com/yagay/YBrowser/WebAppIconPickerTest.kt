package com.yagay.YBrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebAppIconPickerTest {
    private val manifest = "https://app.example.org/app/manifest.webmanifest"

    @Test fun selectsMaskable256IconOverSmallFavicon() {
        assertEquals(
            "https://app.example.org/app/img/maskable.png",
            WebAppIconPicker.choose(
                manifest,
                listOf(
                    WebAppIconCandidate("favicon.png", "48x48", "any", "image/png"),
                    WebAppIconCandidate("img/maskable.png", "256x256", "maskable", "image/png"),
                    WebAppIconCandidate("img/small.png", "192x192", "any", "image/png"),
                ),
            ),
        )
    }

    @Test fun onlyPermitsHttpsRasterUrls() {
        assertNull(WebAppIconPicker.choose(
            manifest,
            listOf(
                WebAppIconCandidate("http://app.example.org/logo.png"),
                WebAppIconCandidate("javascript:alert(1)"),
                WebAppIconCandidate("data:image/png;base64,abc"),
                WebAppIconCandidate("https://app.example.org/logo.svg", type = "image/svg+xml"),
                WebAppIconCandidate("https://user:password@app.example.org/logo.png"),
            ),
        ))
    }

    @Test fun resolvesLegitimateHttpsCdnIcons() {
        assertEquals(
            "https://cdn.example.org/icons/app.webp",
            WebAppIconPicker.choose(
                manifest,
                listOf(WebAppIconCandidate(
                    "https://cdn.example.org/icons/app.webp",
                    sizes = "192x192 512x512",
                    type = "image/webp",
                )),
            ),
        )
    }

    @Test fun ignoresUnusableMonochromeOnlyIcons() {
        assertNull(WebAppIconPicker.choose(
            manifest,
            listOf(WebAppIconCandidate(
                "single.png", "any", "monochrome", "image/png",
            )),
        ))
    }
}
