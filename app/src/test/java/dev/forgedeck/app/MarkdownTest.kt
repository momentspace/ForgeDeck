package dev.forgedeck.app

import org.junit.Assert.*
import org.junit.Test

class MarkdownTest {
    @Test
    fun htmlIsLiteralAndUnsafeLinksHaveNoAction() {
        val parts =
            markdownParts(
                "# Title\n\n<strong>Literal HTML</strong>\n\n[Bad](javascript:alert(1)) [Good](https://github.com/sample/repo)"
            )
        assertEquals(1, parts.first().heading)
        val text = parts.last().text
        assertTrue(text.text.contains("Bad"))
        assertEquals(1, text.getStringAnnotations("URL", 0, text.length).size)
        assertEquals(
            "https://github.com/sample/repo",
            text.getStringAnnotations("URL", 0, text.length).single().item,
        )
        assertTrue(parts.any { it.text.text.contains("<strong>") })
    }

    @Test
    fun fencedCodeDoesNotParseLinksOrHtml() {
        val part = markdownParts("```html\n<script>alert(1)</script>\n```").single()
        assertTrue(part.code)
        assertEquals("<script>alert(1)</script>", part.text.text)
        assertTrue(part.text.getStringAnnotations("URL", 0, part.text.length).isEmpty())
    }
}
