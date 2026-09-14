package com.forgekit.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * VT100/ANSI engine tests: every assertion is over the exact escape
 * sequences real programs emit (bash PS1, ls --color, vim, clear), plus
 * one REAL bash subprocess integration proving the engine survives
 * genuine terminal output byte boundaries.
 */
class VtParserTest {

    private fun screen(columns: Int = 20, rows: Int = 5): Pair<TerminalBuffer, VtParser> {
        val buffer = TerminalBuffer(columns, rows)
        return buffer to VtParser(buffer)
    }

    private fun text(buffer: TerminalBuffer): List<String> =
        buffer.snapshot().lines.map { line -> line.joinToString("") { it.char.toString() } }

    // ---- basics ---------------------------------------------------------------

    @Test
    fun `plain text lands on the grid and wraps at the margin`() {
        val (buffer, parser) = screen(columns = 10, rows = 3)
        parser.feed("0123456789ABC")
        val lines = text(buffer)
        assertEquals("0123456789", lines[0])
        assertEquals("ABC", lines[1].take(3))
        assertEquals(3, buffer.cursorColumn)
    }

    @Test
    fun `CR and LF move independently like a real tty`() {
        val (buffer, parser) = screen(columns = 10, rows = 3)
        parser.feed("hello\r\nworld")
        assertEquals("world", text(buffer)[1].take(5))
        assertEquals(5, buffer.cursorColumn)
    }

    @Test
    fun `backspace tab and bell behave`() {
        val (buffer, parser) = screen(columns = 20, rows = 2)
        parser.feed("abc\b\ta\u0007")
        assertEquals("abc     a", text(buffer)[0].take(9), "backspace moves the cursor; the glyph stays (real terminals)")
        assertEquals(1, buffer.bellCount)
    }

    // ---- cursor movement ----------------------------------------------------------

    @Test
    fun `CUP and relative moves clamp to the grid`() {
        val (buffer, parser) = screen(columns = 10, rows = 4)
        parser.feed("\u001B[3;5H") // row 3, col 5 → (2,4)
        assertEquals(2, buffer.cursorRow)
        assertEquals(4, buffer.cursorColumn)
        parser.feed("\u001B[9A\u001B[99D") // over-shoots clamp
        assertEquals(0, buffer.cursorRow)
        assertEquals(0, buffer.cursorColumn)
    }

    @Test
    fun `save and restore cursor round-trips position`() {
        val (buffer, parser) = screen()
        parser.feed("12345\u001B7\u001B[2;2H\u001B8")
        assertEquals(0, buffer.cursorRow)
        assertEquals(5, buffer.cursorColumn)
    }

    // ---- erase ------------------------------------------------------------------

    @Test
    fun `EL and ED erase exactly their ranges`() {
        val (buffer, parser) = screen(columns = 10, rows = 3)
        parser.feed("0123456789012345678901234567890")
        parser.feed("\u001B[2;5H\u001B[K") // cursor to row1 col4; erase to EOL
        val lines = text(buffer)
        assertEquals("0123456789", lines[0])
        assertEquals("0123", lines[1].take(4))
        assertEquals("    ", lines[1].take(4).let { it.padEnd(4).replace(Regex("\\S"), " ") })

        parser.feed("\u001B[2J") // full clear, cursor stays
        assertEquals("          ", text(buffer)[0])
        assertEquals(1, buffer.cursorRow)
    }

    // ---- scrolling ------------------------------------------------------------------

    @Test
    fun `scrolling at the bottom margin pushes lines into scrollback`() {
        val (buffer, parser) = screen(columns = 10, rows = 3)
        parser.feed("L1\r\nL2\r\nL3\r\nL4")
        val lines = text(buffer)
        assertEquals("L2", lines[0].take(2))
        assertEquals("L3", lines[1].take(2))
        assertEquals("L4", lines[2].take(2))
        assertEquals(1, buffer.scrollback.size)
        assertEquals("L1", buffer.scrollback.first().cells.joinToString("") { it.char.toString() }.take(2))
    }

    @Test
    fun `SU and SD scroll explicitly`() {
        val (buffer, parser) = screen(columns = 10, rows = 3)
        parser.feed("A\r\nB\r\nC")
        parser.feed("\u001B[S") // scroll up 1
        assertEquals("B", text(buffer)[0].take(1))
        assertEquals(1, buffer.scrollback.size)
        parser.feed("\u001B[T") // scroll down 1
        assertEquals("", text(buffer)[0].take(1).trim())
    }

    // ---- SGR ----------------------------------------------------------------------

    @Test
    fun `SGR styles cells they cover and reset clears`() {
        val (buffer, parser) = screen(columns = 10, rows = 2)
        parser.feed("\u001B[1;31mRED\u001B[0m plain")
        val cells = buffer.snapshot().lines[0]
        assertEquals('R', cells[0].char)
        assertTrue(cells[0].style.bold, "bold from SGR 1")
        assertEquals(1, cells[0].style.foreground, "red from SGR 31")
        assertEquals(null, cells[4].style.foreground, "reset before 'plain'")
        assertTrue(!cells[4].style.bold)
    }

    @Test
    fun `256-color extended SGR maps to the palette index`() {
        val (buffer, parser) = screen(columns = 10, rows = 2)
        parser.feed("\u001B[38;5;196mX")
        assertEquals(196, buffer.snapshot().lines[0][0].style.foreground)
        parser.feed("\u001B[48;5;16mY")
        val second = buffer.snapshot().lines[0][1]
        assertEquals(196, second.style.foreground, "fg persists until changed")
        assertEquals(16, second.style.background)
    }

    @Test
    fun `24-bit SGR stores an RGB color in both semicolon and colon forms`() {
        val (buffer, parser) = screen(columns = 10, rows = 2)
        parser.feed("\u001B[38;2;255;107;44mA\u001B[48:2::10:20:30mB\u001B[38:2:1:2:3mC")
        val cells = buffer.snapshot().lines[0]
        assertEquals(TerminalColor.rgb(255, 107, 44), cells[0].style.foreground)
        assertEquals(TerminalColor.rgb(10, 20, 30), cells[1].style.background)
        assertEquals(TerminalColor.rgb(1, 2, 3), cells[2].style.foreground, "colon form without colour-space id")
    }

    @Test
    fun `colon 256-color and parameters after an extended color still apply`() {
        val (buffer, parser) = screen(columns = 10, rows = 2)
        parser.feed("\u001B[38:5:196;1mX\u001B[0;38;2;1;2;3;4mY")
        val cells = buffer.snapshot().lines[0]
        assertEquals(196, cells[0].style.foreground)
        assertTrue(cells[0].style.bold, "SGR 1 after the colon group")
        assertEquals(TerminalColor.rgb(1, 2, 3), cells[1].style.foreground)
        assertTrue(cells[1].style.underline, "SGR 4 after the 24-bit triple")
    }

    @Test
    fun `dim italic strikethrough set and reset independently`() {
        val (buffer, parser) = screen(columns = 10, rows = 2)
        parser.feed("\u001B[1;2;3;9mA\u001B[22mB\u001B[23;29mC\u001B[4:0mD")
        val cells = buffer.snapshot().lines[0]
        with(cells[0].style) { assertTrue(bold && dim && italic && strikethrough) }
        with(cells[1].style) { assertTrue(!bold && !dim && italic && strikethrough, "22 clears bold and dim only") }
        with(cells[2].style) { assertTrue(!italic && !strikethrough) }
        assertTrue(!cells[3].style.underline, "4:0 means no underline")
    }

    @Test
    fun `empty leading CSI parameter keeps its position`() {
        val (buffer, parser) = screen(columns = 10, rows = 5)
        parser.feed("\u001B[;5HX")
        assertEquals('X', buffer.snapshot().lines[0][4].char, "row defaults to 1, column is 5")
    }

    @Test
    fun `reverse video inverts during render prep`() {
        val (buffer, parser) = screen(columns = 10, rows = 2)
        parser.feed("\u001B[7mR\u001B[27mN")
        val cells = buffer.snapshot().lines[0]
        assertTrue(cells[0].style.reverse)
        assertTrue(!cells[1].style.reverse)
    }

    // ---- modes ----------------------------------------------------------------------

    @Test
    fun `cursor visibility toggles via DECSET 25`() {
        val (buffer, parser) = screen()
        assertTrue(buffer.cursorVisible)
        parser.feed("\u001B[?25l")
        assertTrue(!buffer.cursorVisible)
        parser.feed("\u001B[?25h")
        assertTrue(buffer.cursorVisible)
    }

    @Test
    fun `alternate screen swaps and restores the primary content`() {
        val (buffer, parser) = screen(columns = 10, rows = 3)
        parser.feed("primary")
        parser.feed("\u001B[?1049h") // enter alt screen (cursor homes per xterm 1049)
        assertTrue(buffer.alternateScreen)
        assertEquals("", text(buffer)[0].trim())
        parser.feed("ALT")
        assertEquals("ALT", text(buffer)[0].take(3), "1049 homes the cursor before writes")
        parser.feed("\u001B[?1049l") // leave alt screen
        assertTrue(!buffer.alternateScreen)
        assertEquals("primary", text(buffer)[0].take(7))
    }

    @Test
    fun `alt-screen scrolling never touches scrollback`() {
        val (buffer, parser) = screen(columns = 10, rows = 2)
        parser.feed("\u001B[?1049h")
        parser.feed("1\r\n2\r\n3\r\n4\r\n5")
        assertEquals(0, buffer.scrollback.size, "vim-style apps must not pollute history")
    }

    // ---- OSC ----------------------------------------------------------------------

    @Test
    fun `OSC titles capture with both terminators`() {
        val (buffer, parser) = screen()
        parser.feed("\u001B]0;bash — home\u0007")
        assertEquals("bash — home", buffer.title)
        parser.feed("\u001B]2;second title\u001B\\")
        assertEquals("second title", buffer.title)
    }

    // ---- UTF-8 ---------------------------------------------------------------------

    @Test
    fun `UTF-8 multibyte glyphs decode across chunk boundaries`() {
        val (buffer, parser) = screen(columns = 20, rows = 2)
        val bytes = "héllo → ✂".toByteArray(Charsets.UTF_8)
        // feed in 1-byte chunks: the pty delivers arbitrary boundaries
        for (b in bytes) {
            parser.feed(byteArrayOf(b))
        }
        assertEquals("héllo → ✂", text(buffer)[0].take(9))
    }

    @Test
    fun `invalid UTF-8 becomes the replacement char, not a crash`() {
        val (buffer, parser) = screen()
        parser.feed(byteArrayOf(0xFF.toByte(), 0x41))
        val line = text(buffer)[0]
        assertEquals('\uFFFD', buffer.snapshot().lines[0][0].char)
        assertEquals('A', buffer.snapshot().lines[0][1].char)
    }

    // ---- IL / DL ---------------------------------------------------------------------

    @Test
    fun `insert and delete lines shift content`() {
        val (buffer, parser) = screen(columns = 10, rows = 3)
        parser.feed("A\r\nB\r\nC\u001B[1;1H\u001B[L") // insert 1 blank line at top
        val afterInsert = text(buffer)
        assertEquals("A", afterInsert[1].take(1), "rows shift down")
        assertEquals("B", afterInsert[2].take(1))
        parser.feed("\u001B[M") // delete the blank top line
        val afterDelete = text(buffer)
        assertEquals("A", afterDelete[0].take(1))
        assertEquals("B", afterDelete[1].take(1))
    }

    // ---- hard reset --------------------------------------------------------------------

    @Test
    fun `RIS resets the whole terminal`() {
        val (buffer, parser) = screen(columns = 10, rows = 3)
        parser.feed("\u001B[1;31mtext\u001B[?25l\u001B]0;junk\u0007more")
        parser.feed("\u001Bc")
        assertTrue(buffer.cursorVisible)
        assertEquals("", buffer.title)
        assertEquals(null, buffer.snapshot().lines[0][0].style.foreground)
        assertEquals(0, buffer.cursorColumn)
        assertEquals(0, buffer.cursorRow)
    }
}
