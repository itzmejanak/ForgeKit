package com.forgekit.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure tests for the terminal text selection model: ordering (normalize), per-row column
 * ranges, and text extraction (line-wise, trailing blanks trimmed) — the logic behind
 * long-press select + Copy.
 */
class TerminalSelectionTest {

    private fun cell(c: Char) = TerminalBuffer.Cell(c)

    private fun screenOf(vararg rows: String, columns: Int = 10): TerminalBuffer.Screen {
        val lines = rows.map { text -> (0 until columns).map { cell(if (it < text.length) text[it] else ' ') } }
        return TerminalBuffer.Screen(
            columns = columns,
            rows = lines.size,
            lines = lines,
            cursorColumn = 0,
            cursorRow = 0,
            cursorVisible = false,
            alternateScreen = false,
            title = "",
            scrollbackSize = 0,
        )
    }

    @Test
    fun `single-line selection trims trailing blanks`() {
        val screen = screenOf("hello world")
        // select columns 0..4 on row 0 → "hello"
        val sel = TerminalSelection(0, 0, 0, 4)
        assertEquals("hello", sel.extractText(screen))
    }

    @Test
    fun `selection to end of a padded line trims the padding`() {
        val screen = screenOf("hi", columns = 10) // "hi        "
        val sel = TerminalSelection(0, 0, 0, 9)
        assertEquals("hi", sel.extractText(screen))
    }

    @Test
    fun `multi-line selection joins rows with newline and spans full inner rows`() {
        val screen = screenOf("abcde", "fghij", "klmno", columns = 5)
        // from row0 col2 to row2 col1 → "cde", full "fghij", "kl"
        val sel = TerminalSelection(0, 2, 2, 1)
        assertEquals("cde\nfghij\nkl", sel.extractText(screen))
    }

    @Test
    fun `normalize orders reversed selections`() {
        val reversed = TerminalSelection(2, 1, 0, 2).normalized()
        assertEquals(TerminalSelection(0, 2, 2, 1), reversed)
        // extraction works regardless of drag direction
        val screen = screenOf("abcde", "fghij", "klmno", columns = 5)
        assertEquals(
            TerminalSelection(0, 2, 2, 1).extractText(screen),
            TerminalSelection(2, 1, 0, 2).extractText(screen),
        )
    }

    @Test
    fun `columnRange is null outside the selected rows`() {
        val sel = TerminalSelection(1, 0, 2, 3)
        assertNull(sel.columnRange(0, columns = 10))
        assertEquals(0..9, sel.columnRange(1, columns = 10)) // first row: from startCol to line end
        assertEquals(0..3, sel.columnRange(2, columns = 10)) // last row: 0..endCol
    }
}
