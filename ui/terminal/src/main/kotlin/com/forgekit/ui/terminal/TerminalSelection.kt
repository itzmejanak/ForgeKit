package com.forgekit.ui.terminal

import com.forgekit.ui.terminal.TerminalBuffer.Screen

/**
 * A line-wise text selection over the visible screen grid (inclusive start/end cells),
 * like a text editor: the anchor cell to the focus cell, spanning full intermediate rows.
 * Coordinates are visible-row / column indices into [Screen.lines].
 */
public data class TerminalSelection(
    val startRow: Int,
    val startCol: Int,
    val endRow: Int,
    val endCol: Int,
) {
    /** Ordered so start precedes end in reading order. */
    public fun normalized(): TerminalSelection {
        val startBefore = startRow < endRow || (startRow == endRow && startCol <= endCol)
        return if (startBefore) this else TerminalSelection(endRow, endCol, startRow, startCol)
    }

    /** The selected column range on [row], or null when the row is outside the selection. */
    public fun columnRange(row: Int, columns: Int): IntRange? {
        val s = normalized()
        if (row < s.startRow || row > s.endRow) return null
        val lo = (if (row == s.startRow) s.startCol else 0).coerceIn(0, columns - 1)
        val hi = (if (row == s.endRow) s.endCol else columns - 1).coerceIn(0, columns - 1)
        return lo..hi
    }

    /** Extracts the selected text from [screen], trimming trailing blanks on each line. */
    public fun extractText(screen: Screen): String {
        val s = normalized()
        val sb = StringBuilder()
        val lastRow = s.endRow.coerceAtMost(screen.rows - 1)
        for (row in s.startRow.coerceAtLeast(0)..lastRow) {
            val range = columnRange(row, screen.columns) ?: continue
            val line = screen.lines[row]
            val text = range.joinToString("") { line[it].char.toString() }.trimEnd()
            sb.append(text)
            if (row != lastRow) sb.append('\n')
        }
        return sb.toString()
    }
}
