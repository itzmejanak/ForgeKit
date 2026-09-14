package com.forgekit.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MarkdownDocumentBundleTest {

    @Test
    fun `merges sources in declared order with stable line endings`() {
        val sources = mapOf(
            "00-start.md" to "# Guide\r\n\r\nStart\r\n",
            "01-next.md" to "## Next\n\nFinish\n\n",
        )

        val merged = MarkdownDocumentBundle.merge(
            "# ordered sources\n01-next.md\n\n00-start.md\n",
        ) { sources.getValue(it) }

        assertEquals("## Next\n\nFinish\n\n# Guide\n\nStart\n", merged)
    }

    @Test
    fun `rejects duplicate unsafe and empty orders`() {
        assertFailsWith<IllegalArgumentException> {
            MarkdownDocumentBundle.sourceOrder("a.md\na.md")
        }
        assertFailsWith<IllegalArgumentException> {
            MarkdownDocumentBundle.sourceOrder("../a.md")
        }
        assertFailsWith<IllegalArgumentException> {
            MarkdownDocumentBundle.sourceOrder("# only a comment")
        }
    }

    @Test
    fun `rejects a missing or empty source through the loader`() {
        assertFailsWith<IllegalArgumentException> {
            MarkdownDocumentBundle.merge("empty.md") { "   " }
        }
    }
}
