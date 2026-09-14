package com.forgekit.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Scrollback windowing: snapshot(offset) must page into history exactly like a tty. */
class TerminalBufferViewportTest {

    private fun feed(buffer: TerminalBuffer, text: String) = VtParser(buffer).feed(text)

    /** Renders a snapshot as a |separated list of trimmed lines (Cell is identity-equal). */
    private fun textOf(screen: TerminalBuffer.Screen): String =
        screen.lines.joinToString("|") { line -> line.joinToString("") { it.char.toString() }.trimEnd() }

    @Test
    fun `offset zero is identical to the live grid`() {
        val buffer = TerminalBuffer(columns = 10, rows = 3)
        feed(buffer, "abc\ndef\nghi")
        val live = buffer.snapshot()
        val windowed = buffer.snapshot(viewportOffset = 0)
        assertEquals(textOf(live), textOf(windowed))
        assertTrue(windowed.cursorVisible)
    }

    @Test
    fun `viewport pages up into history and hides the cursor`() {
        val buffer = TerminalBuffer(columns = 10, rows = 3, scrollbackLimit = 50)
        // 6 lines of output scroll history into the deque at rows=3
        feed(buffer, "line1\nline2\nline3\nline4\nline5\nline6\n")
        assertTrue(buffer.scrollback.isNotEmpty(), "excess lines must enter scrollback")

        // live grid shows the latest output
        val live = buffer.snapshot(viewportOffset = 0)
        val liveText = textOf(live)
        assertTrue(liveText.contains("line6"), "live grid shows latest output: $liveText")
        assertTrue(live.cursorVisible, "live grid keeps the cursor")

        // one full page back shows history, not the live bottom
        val back = buffer.snapshot(viewportOffset = buffer.scrollback.size)
        val backText = textOf(back)
        assertTrue(backText.contains("line1"), "oldest page must contain the first line: $backText")
        assertFalse(backText.contains("line6"), "history page must not show the newest line")
        assertFalse(back.cursorVisible, "cursor belongs to the live grid")
        assertTrue(backText != liveText, "scrolled viewport must differ from the live grid")
    }

    @Test
    fun `offset clamps to history size`() {
        val buffer = TerminalBuffer(columns = 5, rows = 2)
        feed(buffer, "a\nb\nc")
        assertTrue(buffer.scrollback.isNotEmpty(), "at least one line must scroll into history")
        // asking for 999 lines must clamp to the oldest available page
        val snap = buffer.snapshot(viewportOffset = 999)
        assertTrue(textOf(snap).contains("a"), "clamped page shows the oldest line: ${textOf(snap)}")
        assertFalse(textOf(snap).contains("c"), "clamped page hides the newest line")
    }

    @Test
    fun `alternate screen never exposes scrollback`() {
        val buffer = TerminalBuffer(columns = 5, rows = 2, scrollbackLimit = 20)
        feed(buffer, "1\n2\n3\n4")
        buffer.scrollback.clear()
        // enter alternate screen (vim-style full-screen app)
        feed(buffer, "\u001B[?1049h")
        feed(buffer, "top")
        val snap = buffer.snapshot(viewportOffset = 5) // even a scroll request is ignored
        assertTrue(textOf(snap).startsWith("top"), "alt screen keeps its own grid: ${textOf(snap)}")
    }
}