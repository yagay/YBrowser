package com.yagay.ybrowser.ai.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichChatRenderingTest {
    @Test
    fun markdownImageBecomesImageBlock() {
        val blocks =
            parseChatTextBlocks(
                "![preview](https://example.com/a.png)"
            )

        assertEquals(1, blocks.size)
        assertEquals(
            ChatBlockType.IMAGE,
            blocks.single().type,
        )
        assertEquals(
            "https://example.com/a.png",
            blocks.single().marker,
        )
        assertEquals(
            "preview",
            blocks.single().text,
        )
    }

    @Test
    fun bareImageUrlBecomesImageBlock() {
        val blocks =
            parseChatTextBlocks(
                "https://example.com/photo.webp"
            )

        assertEquals(
            ChatBlockType.IMAGE,
            blocks.single().type,
        )
    }

    @Test
    fun standaloneUrlBecomesLinkCardBlock() {
        val blocks =
            parseChatTextBlocks(
                "https://example.com/path?q=1"
            )

        assertEquals(1, blocks.size)
        assertEquals(
            ChatBlockType.LINK,
            blocks.single().type,
        )
        assertEquals(
            "https://example.com/path?q=1",
            blocks.single().marker,
        )
    }

    @Test
    fun markdownLinkCarriesClickableAnnotation() {
        val value =
            buildInlineMarkdown(
                text =
                    "Open [site](https://example.com)",
                codeBackground =
                    Color.Transparent,
                linkColor = Color.Blue,
            )

        val links =
            value.getStringAnnotations(
                tag = "url",
                start = 0,
                end = value.length,
            )

        assertTrue(links.isNotEmpty())
        assertEquals(
            "https://example.com",
            links.single().item,
        )
    }

    @Test
    fun bareUrlCarriesClickableAnnotation() {
        val value =
            buildInlineMarkdown(
                text =
                    "Visit https://example.com/path",
                codeBackground =
                    Color.Transparent,
                linkColor = Color.Blue,
            )

        val links =
            value.getStringAnnotations(
                tag = "url",
                start = 0,
                end = value.length,
            )

        assertEquals(
            "https://example.com/path",
            links.single().item,
        )
    }
}
