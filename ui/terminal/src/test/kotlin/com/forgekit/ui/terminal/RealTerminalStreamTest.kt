package com.forgekit.ui.terminal

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * REAL terminal-stream integration: an actual `bash` subprocess whose
 * environment carries a real TERM, emitting genuine ANSI/VT100 bytes
 * (forced-color ls, echo -e control sequences). The engine must survive
 * real-world output: SGR bursts, CRLF line discipline, partial sequences
 * across chunk boundaries.
 */
class RealTerminalStreamTest {

    private fun bashOutput(script: String, term: String = "xterm-256color"): ByteArray {
        val dir = File(System.getProperty("java.io.tmpdir"), "vt-${System.nanoTime()}")
        dir.mkdirs()
        val process = ProcessBuilder("/bin/bash", "-c", script)
            .directory(dir)
            .redirectErrorStream(true)
            .apply { environment()["TERM"] = term }
            .start()
        val bytes = process.inputStream.readBytes()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) {
            "bash exited ${process.exitValue()}: ${bytes.toString(Charsets.UTF_8).take(200)}"
        }
        dir.deleteRecursively()
        return bytes
    }

    private fun render(buffer: TerminalBuffer): List<String> =
        buffer.snapshot().lines.map { line -> line.joinToString("") { it.char.toString() } }

    @Test
    fun `colored ls output parses into styled cells and survives chunking`() {
        val raw = bashOutput("ls --color=always /usr/bin/ | head -40; echo VT-DONE")
        assertTrue(raw.contains(0x1B), "forced-color listing must carry SGR: ${raw.size} bytes")

        val buffer = TerminalBuffer(columns = 120, rows = 40, scrollbackLimit = 400)
        val parser = VtParser(buffer)
        // awkward chunk size: escape sequences get split mid-way
        var i = 0
        while (i < raw.size) {
            val end = minOf(i + 13, raw.size)
            parser.feed(raw, i, end)
            i = end
        }
        val text = render(buffer).joinToString("\n")
        assertTrue("VT-DONE" in text, "output tail must be on screen")
        val styled = buffer.snapshot().lines.flatten().count { it.style.foreground != null || it.style.bold }
        assertTrue(styled > 50, "ls --color must produce styled cells: $styled styled cells of ${raw.size} bytes")
    }

    @Test
    fun `a real cursor-addressed program lands where it draws`() {
        // echo -e emits genuine control sequences through bash itself
        val raw = bashOutput("printf 'line one\\nline two\\n'; printf '\\033[2;6H'; printf 'X'; echo; echo CU-DONE")
        val buffer = TerminalBuffer(columns = 40, rows = 10)
        val parser = VtParser(buffer)
        parser.feed(raw)
        val text = render(buffer)
        assertEquals("X", text[1].drop(5).take(1), "CUP 2;6 places X at row 1 col 5")
        assertTrue(text.any { "line one" in it })
        assertTrue(text.any { "CU-DONE" in it })
    }

    @Test
    fun `long real output scrolls into history exactly like a terminal`() {
        val raw = bashOutput("for i in \$(seq 1 200); do echo \"line \$i\"; done")
        val buffer = TerminalBuffer(columns = 80, rows = 24, scrollbackLimit = 500)
        VtParser(buffer).feed(raw)
        val text = render(buffer)
        assertEquals("line 200", text[22].trim(), "the last line sits on the bottom row")
        assertTrue(text.none { it.trim() == "line 1" }, "line 1 scrolled off")
        val history = buffer.scrollback.joinToString("\n") { line ->
            line.cells.joinToString("") { it.char.toString() }
        }
        assertTrue("line 1" in history, "history starts at line 1: ${buffer.scrollback.size} lines")
        assertTrue("line 2" in history)
        assertTrue(buffer.scrollback.size in 170..200, "history holds the scrolled-off lines: ${buffer.scrollback.size}")
    }

    @Test
    fun `TERM=dumb output parses as plain text`() {
        val raw = bashOutput("echo plain-dumb; echo second", term = "dumb")
        val buffer = TerminalBuffer(columns = 40, rows = 5)
        VtParser(buffer).feed(raw)
        val text = render(buffer)
        assertTrue(text.any { "plain-dumb" in it })
        assertTrue(text.any { "second" in it })
        assertEquals(0, buffer.snapshot().lines.flatten().count { it.style.foreground != null })
    }
}
