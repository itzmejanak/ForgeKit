package com.forgekit.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Width-change reflow: zooming in/out must RE-WRAP long lines at the new width and
 * never erase content (regression for the destructive top-left resize).
 */
class TerminalReflowTest {

    private fun feed(buffer: TerminalBuffer, text: String) = VtParser(buffer).feed(text)

    /** Screen rows as trimmed strings (pure content, no padding). */
    private fun linesOf(screen: TerminalBuffer.Screen): List<String> =
        screen.lines.map { line -> line.joinToString("") { it.char.toString() }.trimEnd() }

    private fun screenText(buffer: TerminalBuffer): List<String> = linesOf(buffer.snapshot())

    @Test
    fun `zoom round-trip preserves wrapped content`() {
        val buffer = TerminalBuffer(columns = 80, rows = 5, scrollbackLimit = 50)
        feed(buffer, "x".repeat(180) + "\n") // wraps into 80/80/20
        buffer.resize(60, 5)                 // zoom in
        buffer.resize(80, 5)                 // zoom out
        assertEquals(
            listOf("x".repeat(80), "x".repeat(80), "x".repeat(20), "", ""),
            screenText(buffer),
            "all 180 chars must survive the zoom round-trip and re-wrap at 80 cols",
        )
    }

    @Test
    fun `shrinking rewraps long lines at the new width`() {
        val buffer = TerminalBuffer(columns = 80, rows = 5, scrollbackLimit = 50)
        feed(buffer, "x".repeat(180) + "\n")
        buffer.resize(60, 5)
        assertEquals(
            listOf("x".repeat(60), "x".repeat(60), "x".repeat(60), "", ""),
            screenText(buffer),
            "180 chars must hard-wrap into three even rows at 60 cols (no left-aligned stub)",
        )
    }

    @Test
    fun `per-cell styles survive a reflow across the wrap boundary`() {
        val buffer = TerminalBuffer(columns = 80, rows = 5, scrollbackLimit = 50)
        // 70 red chars then 130 default chars -> crosses wrap at 80
        feed(buffer, "\u001B[31m" + "r".repeat(70) + "\u001B[0m" + "n".repeat(130) + "\n")
        buffer.resize(50, 5) // 70+130 = 200 -> four 50-cell rows
        val screen = buffer.snapshot()
        val rows = screen.lines.map { it.map { cell -> cell.char to cell.style.foreground } }

        fun fg(rowIdx: Int, colIdx: Int): Int? = rows[rowIdx][colIdx].second
        assertEquals('r', rows[0][0].first)
        assertEquals(1, fg(0, 0), "row0 starts in the red run")
        assertEquals(1, fg(0, 49), "row0 ends still inside the red run")
        assertEquals(1, fg(1, 19), "red run spills 20 cells into row1")
        assertEquals('n', rows[1][20].first)
        assertEquals(null, fg(1, 20), "row1 col20 is default-normal")
        assertEquals(null, fg(3, 49), "last row all default")
    }

    @Test
    fun `cursor stays on its character after a width round-trip`() {
        val buffer = TerminalBuffer(columns = 80, rows = 5, scrollbackLimit = 50)
        feed(buffer, "abcdefghij") // cursor at col 10, row 0
        buffer.resize(60, 5)
        buffer.resize(80, 5)
        assertEquals(0, buffer.cursorRow)
        assertEquals(10, buffer.cursorColumn)
    }

    @Test
    fun `overflow during shrink pushes oldest rows into scrollback instead of dropping`() {
        val buffer = TerminalBuffer(columns = 80, rows = 3, scrollbackLimit = 50)
        feed(buffer, "x".repeat(240)) // 3 full rows at 80, no trailing newline to keep it all in-window
        assertEquals(0, buffer.scrollback.size)
        buffer.resize(60, 3) // 240 chars -> 4 rows at 60; only 3 fit
        assertEquals(1, buffer.scrollback.size, "oldest reflowed row is preserved in scrollback")
        assertEquals(
            listOf("x".repeat(60), "x".repeat(60), "x".repeat(60)),
            screenText(buffer),
            "most recent content stays on the live grid",
        )
        val history = linesOf(buffer.snapshot(viewportOffset = buffer.scrollback.size))
        assertTrue(history[0] == "x".repeat(60), "scrolled-back page shows the overflow row")
    }

    @Test
    fun `scrollback history survives a width round-trip`() {
        val buffer = TerminalBuffer(columns = 80, rows = 3, scrollbackLimit = 50)
        feed(buffer, "hist1\nhist2\nhist3\nhist4") // hist1 is in scrollback
        val sbBefore = buffer.scrollback.size
        assertTrue(sbBefore > 0)
        buffer.resize(60, 3)
        buffer.resize(80, 3)
        assertEquals(sbBefore, buffer.scrollback.size, "history is never dropped by resizes")
        val history = linesOf(buffer.snapshot(viewportOffset = sbBefore))
        assertTrue(history.contains("hist1"), "oldest history line is intact after round-trip")
    }
}