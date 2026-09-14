package com.forgekit.ui.terminal

import kotlin.test.Test

class ScratchProbe {
    @Test
    fun dump() {
        val buffer = TerminalBuffer(columns = 50, rows = 6, scrollbackLimit = 100)
        val line = "L".repeat(70)
        repeat(8) { VtParser(buffer).feed(line + "\n") }
        buffer.resize(110, 6)
        for (r in 0 until 2) {
            val cells = buffer.screen[r].cells
            val codes = (65 until 95).joinToString(",") { i ->
                val c = cells[i]
                "${c.char.code}:${if (c.style == TerminalBuffer.Style.DEFAULT) "D" else "S"}"
            }
            println("row $r cells[65..94] = $codes")
        }
    }
}
