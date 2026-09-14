package com.forgekit.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WIDEN regression (device: landscape rotation + pinch zoom-out). Zooming OUT or rotating to
 * landscape grows the grid width: pre-existing WRAPPED rows must re-join into full-width logical
 * lines again — otherwise text stays narrow on the left with the new width wasted on the right,
 * exactly what was reported on device.
 *
 * Every test here represents a state reached after plain streaming (50-col portrait), then an
 * application of a wider grid (110-col landscape). Streaming uses CRLF (as a real shell does):
 * a bare LF never resets the column, so multi-line fixtures must send "\r\n".
 */
class TerminalReflowWidenTest {

    private fun feed(buffer: TerminalBuffer, text: String) = VtParser(buffer).feed(text)

    private fun linesOf(screen: TerminalBuffer.Screen): List<String> =
        screen.lines.map { line -> line.joinToString("") { it.char.toString() }.trimEnd() }

    private fun screenText(buffer: TerminalBuffer): List<String> = linesOf(buffer.snapshot())

    private fun contentLength(buffer: TerminalBuffer): Int =
        (buffer.scrollback.sumOf { line -> line.cells.count { it.char == 'L' } } +
            buffer.screen.sumOf { line -> line.cells.count { it.char == 'L' } })

    @Test
    fun `widening re-joins a wrapped line into one full-width logical row`() {
        val buffer = TerminalBuffer(columns = 50, rows = 6, scrollbackLimit = 50)
        feed(buffer, "L".repeat(80) + "\n") // 50/30 wrapped pair, then blank rows
        buffer.resize(110, 6)

        assertEquals(
            listOf("L".repeat(80), "", "", "", "", ""),
            screenText(buffer),
            "the 50/30 wrap pair must merge back into a single 80-char line at 110 cols",
        )
        assertEquals(80, contentLength(buffer), "no cell may be lost on widen")
    }

    @Test
    fun `widening after a long stream keeps every recent line wide and loses nothing`() {
        val buffer = TerminalBuffer(columns = 50, rows = 6, scrollbackLimit = 100)
        val line = "L".repeat(70) // wraps 50/20 at portrait width
        repeat(8) { feed(buffer, line + "\r\n") } // CRLF, like a real shell (LF alone never resets the column)
        val before = contentLength(buffer)
        assertTrue(before == 8 * 70, "all streamed L's present before widen: $before")

        buffer.resize(110, 6)

        val text = screenText(buffer)
        for (row in text) {
            assertTrue(
                row.isEmpty() || row.length == 70,
                "wrapped fragments must re-join: found a row of length ${row.length}",
            )
        }
        assertEquals(before, contentLength(buffer), "widen must never drop streamed content")
    }

    @Test
    fun `cursor keeps its exact write column across a widen`() {
        val buffer = TerminalBuffer(columns = 50, rows = 6, scrollbackLimit = 50)
        feed(buffer, "A".repeat(45)) // cursor at col 45 on row 0
        buffer.resize(110, 6)
        assertEquals(45, buffer.cursorColumn, "cursor must stay on its own cell after widening")
        feed(buffer, "B")
        assertEquals(
            listOf("A".repeat(45) + "B", "", "", "", "", ""),
            screenText(buffer),
            "the next printed char must land immediately after the 45 A's, not shifted right",
        )
    }

    @Test
    fun `shrink-then-widen round trip stays full width and content-complete`() {
        val buffer = TerminalBuffer(columns = 50, rows = 6, scrollbackLimit = 50)
        feed(buffer, "L".repeat(120) + "\n") // 50/50/20 wrapped triple
        buffer.resize(110, 6) // widen
        buffer.resize(50, 6) // zoom in
        buffer.resize(110, 6) // widen again (the exact two-hop the device does)
        val text = screenText(buffer)
        assertTrue(
            text[0] == "L".repeat(110) && text[1] == "L".repeat(10),
            "content must re-wrap to the wide grid after a shrink hop: ${text.take(2)}",
        )
    }

    @Test
    fun `widening re-joins an on-screen continuation with its head from scrollback`() {
        // Streaming scrolled a wrapped line's head into scrollback, leaving the continuation
        // alone at the top of the live grid (wrapped=true, no head above it). Reflow spans the
        // whole transcript, so the widen re-joins the continuation with its head and re-wraps
        // the logical line at the new width — it must not lose content nor merge unrelated rows.
        val buffer = TerminalBuffer(columns = 50, rows = 2, scrollbackLimit = 50)
        feed(buffer, "L".repeat(90) + "\r\n") // [L50, L40]; the L50 head scrolls into history
        assertEquals(1, buffer.scrollback.size, "precondition: the head row scrolled away")
        buffer.resize(110, 2)
        val text = screenText(buffer)
        assertTrue(
            text[0] == "L".repeat(90),
            "head (50) + continuation (40) re-join into one 90-char row at 110 cols: ${text[0].length}",
        )
        assertEquals(90, contentLength(buffer), "head (50) + continuation (40) must both survive")
    }
}