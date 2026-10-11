package com.yagay.YBrowser

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebAppManifestDiscoveryTest {
    private val page = URI("https://example.org/app/start")

    @Test fun picksHttpsAppleTouchIconWithoutManifest() {
        val html = """
            <html><head>
            <link rel="shortcut icon" href="/favicon.png">
            <link rel="apple-touch-icon" sizes="180x180" href="/touch.png">
            </head></html>
        """
        assertEquals(
            "https://example.org/touch.png",
            WebAppManifestDiscovery.siteIcon(page, html),
        )
    }

    @Test fun ignoresHttpAndDataIcons() {
        val html = """
            <link rel="icon" href="http://example.org/icon.png">
            <link rel="icon" href="data:image/png;base64,abc">
        """
        assertNull(WebAppManifestDiscovery.siteIcon(page, html))
    }

    @Test fun detectsManifestLinkWithMultipleRelTokens() {
        assertEquals(
            "https://example.org/app/app.webmanifest",
            WebAppManifestDiscovery.manifestUrl(
                page,
                """<link rel='alternate manifest' href='app.webmanifest'>""",
            )?.toString(),
        )
    }
}
