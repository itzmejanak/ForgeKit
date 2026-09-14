package com.forgekit.ui.terminal

/**
 * The VT100/ANSI screen model (M9): a cell grid with cursor, modes and
 * scrollback, updated by [VtParser]. Pure Kotlin — no Android dependency —
 * so the emulator behavior is testable on the JVM exactly as it runs on
 * the device.
 *
 * Scope is a REAL terminal subset (what bash/vim/htop actually emit):
 *  - C0 controls: BS, HT, LF/VT/FF (linefeed), CR, BEL (counted), SO/SI (charset switch, ignored)
 *  - CSI: CUU/CUD/CUF/CUB, CUP/HPA/VPA, ED (0/1/2), EL (0/1/2), IL/DL, SU/SD,
 *    SGR (0,1,2,4,5,7,8,22,24,25,27,28,30-37,38;5,38;2,39,40-47,48;5,48;2,49,90-97,100-107),
 *    DECSTBC (margin), save/restore cursor (DECSC/DECRC, CSI s/u), window ops (ignored)
 *  - Modes: ?25 cursor visibility, ?1049/?47/?1047 alternate screen, ?7 autowrap
 *  - OSC: 0/2 title capture (terminated by BEL or ESC \)
 *  - UTF-8 decoding with replacement for invalid bytes
 */
public class TerminalBuffer(
    columns: Int,
    rows: Int,
    /** Lines kept above the viewport once scrolled away. */
    public val scrollbackLimit: Int = 1000,
) {
    /**
     * Current grid width. Mutable because [resize] rebuilds the grid: while these were
     * immutable constructor properties, a resize left every line array at the new width
     * but `columns` still reporting the original one, so anything iterating to `columns`
     * (eraseLine, the renderer) indexed past the end of the array.
     */
    public var columns: Int = columns
        private set

    /** Current grid height. Mutable for the same reason as [columns]. */
    public var rows: Int = rows
        private set

    public data class Style(
        /** [TerminalColor]-encoded: 0-255 palette index or a 24-bit value; null = default. */
        val foreground: Int? = null,
        val background: Int? = null,
        val bold: Boolean = false,
        /** Faint (SGR 2): drawn at reduced alpha. */
        val dim: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikethrough: Boolean = false,
        val reverse: Boolean = false,
    ) {
        public companion object {
            public val DEFAULT: Style = Style()
        }
    }

    public class Cell(
        public var char: Char = ' ',
        public var style: Style = Style.DEFAULT,
    )

    public class Line(
        val cells: Array<Cell>,
        /**
         * True when this row continues the row above: it was produced by autowrap
         * rather than an explicit newline. Lets [resize] re-join wrapped rows into a
         * single logical line so a column change can re-wrap content without losing it.
         */
        public var wrapped: Boolean = false,
    ) {
        public constructor(columns: Int) : this(Array(columns) { Cell() })

        public fun copy(): Line =
            Line(Array(cells.size) { i ->
                Cell().also { it.char = cells[i].char; it.style = cells[i].style }
            }, wrapped)
    }

    // ---- screen state -----------------------------------------------------

    /** Scrollback lines (oldest first), capped at [scrollbackLimit]. */
    public val scrollback: ArrayDeque<Line> = ArrayDeque()

    /** The viewport. rows × columns. */
    public val screen: MutableList<Line> = (0 until rows).map { Line(columns) }.toMutableList()

    public var cursorColumn: Int = 0
        private set
    public var cursorRow: Int = 0
        private set

    /** DECAWM: wrap long lines automatically (default on, like a real tty). */
    public var autoWrap: Boolean = true

    /** Pending wrap flag (the "last column" quirk of VT100). */
    private var pendingWrap: Boolean = false

    public var cursorVisible: Boolean = true
        private set

    public var alternateScreen: Boolean = false
        private set
    private var savedPrimary: Triple<List<Line>, Int, Int>? = null

    public var title: String = ""
        private set

    public var bellCount: Int = 0
        private set

    /** Total bytes fed — the transport-side counter. */
    public var bytesFed: Long = 0
        private set

    // ---- feed API (called by VtParser) -------------------------------------

    /** Puts one grapheme char at the cursor honoring wrap + scrolling. */
    public fun putChar(c: Char, style: Style) {
        if (pendingWrap && autoWrap) {
            pendingWrap = false
            cursorColumn = 0
            lineFeed()
            screen[cursorRow].wrapped = true // this row continues the one above
        }
        if (cursorColumn >= columns) { // autowrap off: overwrite last cell
            cursorColumn = columns - 1
        }
        val cell = screen[cursorRow].cells[cursorColumn]
        cell.char = c
        cell.style = style
        if (cursorColumn == columns - 1) {
            pendingWrap = true
        } else {
            cursorColumn++
        }
    }

    public fun carriageReturn() {
        cursorColumn = 0
        pendingWrap = false
    }

    public fun lineFeed() {
        if (cursorRow == rows - 1) {
            scrollUp(1)
        } else {
            cursorRow++
            // an explicit newline starts a fresh logical line (un-wraps any stale flag)
            screen[cursorRow].wrapped = false
        }
    }

    public fun backspace() {
        if (cursorColumn > 0) {
            cursorColumn--
            pendingWrap = false
        }
    }

    public fun tab() {
        if (cursorColumn >= columns - 1) return
        cursorColumn = ((cursorColumn / 8) + 1) * 8
        if (cursorColumn >= columns) cursorColumn = columns - 1
    }

    public fun bell() {
        bellCount++
    }

    public fun moveCursor(row: Int, column: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorColumn = column.coerceIn(0, columns - 1)
        pendingWrap = false
    }

    public fun moveCursorRelative(rowDelta: Int, columnDelta: Int) {
        moveCursor(cursorRow + rowDelta, cursorColumn + columnDelta)
    }

    /** ED — erase display. 0=cursor→end, 1=start→cursor, 2=all. */
    public fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLine(0)
                for (r in cursorRow + 1 until rows) clearLine(r)
            }
            1 -> {
                eraseLine(1)
                for (r in 0 until cursorRow) clearLine(r)
            }
            2 -> for (r in 0 until rows) clearLine(r)
        }
    }

    /** EL — erase line. 0=cursor→end, 1=start→cursor, 2=all. */
    public fun eraseLine(mode: Int) {
        val line = screen[cursorRow]
        val from: Int; val to: Int
        when (mode) {
            0 -> { from = cursorColumn; to = columns }
            1 -> { from = 0; to = cursorColumn + 1 }
            else -> { from = 0; to = columns }
        }
        for (c in from until to) {
            line.cells[c].char = ' '
            line.cells[c].style = Style.DEFAULT
        }
        pendingWrap = false
    }

    /** Scroll content up by [n]; top lines go to scrollback (primary screen only). */
    public fun scrollUp(n: Int) {
        repeat(n) {
            if (!alternateScreen) {
                scrollback.addLast(screen.removeAt(0))
                if (scrollback.size > scrollbackLimit) scrollback.removeFirst()
            } else {
                screen.removeAt(0)
            }
            screen.add(Line(columns))
        }
    }

    public fun scrollDown(n: Int) {
        repeat(n) {
            screen.removeAt(screen.size - 1)
            screen.add(0, Line(columns))
        }
    }

    /** IL — insert blank lines at the cursor row. */
    public fun insertLines(n: Int) {
        if (cursorRow == rows) return
        repeat(n) {
            screen.removeAt(screen.size - 1)
            screen.add(cursorRow, Line(columns))
        }
    }

    /** DL — delete lines at the cursor row. */
    public fun deleteLines(n: Int) {
        repeat(n) {
            screen.removeAt(cursorRow)
            screen.add(Line(columns))
        }
    }

    // ---- modes ---------------------------------------------------------------

    public fun setMode(privateMode: Boolean, mode: Int, enabled: Boolean) {
        if (!privateMode) return // DEC modes come as ?N; public modes are ignored (v1)
        when (mode) {
            7 -> autoWrap = enabled
            25 -> cursorVisible = enabled
            47, 1047, 1049 -> setAlternateScreen(enabled, mode)
        }
    }

    private fun setAlternateScreen(enabled: Boolean, mode: Int = 1049) {
        if (enabled == alternateScreen) return
        if (enabled) {
            savedPrimary = Triple(screen.map { it.copy() }, cursorRow, cursorColumn)
            alternateScreen = true
            for (r in 0 until rows) screen[r] = Line(columns)
            // xterm 1049 semantics: save cursor, switch, clear — cursor homes
            if (mode == 1049) moveCursor(0, 0)
        } else {
            savedPrimary?.let { (saved, row, column) ->
                for (r in 0 until minOf(rows, saved.size)) screen[r] = saved[r].copy()
                cursorRow = row; cursorColumn = column
            }
            savedPrimary = null
            alternateScreen = false
        }
    }

    // ---- title / save-restore ---------------------------------------------------

    public fun setTitle(value: String) {
        title = value
    }

    public fun saveCursor() {
        savedCursor = Triple(cursorRow, cursorColumn, pendingWrap)
    }

    public fun restoreCursor() {
        savedCursor?.let { (r, c, wrap) ->
            cursorRow = r.coerceIn(0, rows - 1); cursorColumn = c.coerceIn(0, columns - 1); pendingWrap = wrap
        }
    }

    private var savedCursor: Triple<Int, Int, Boolean>? = null

    // ---- snapshots ---------------------------------------------------------------

    /**
     * Immutable render snapshot of the visible grid.
     *
     * [viewportOffset] scrolls the window up into [scrollback]: 0 = the live grid
     * (identical to the pre-viewport behavior), N = N lines back into history.
     * The cursor is hidden whenever the viewport is not on the live grid, and the
     * alternate screen never exposes scrollback (vim/htop own that space).
     */
    public fun snapshot(viewportOffset: Int = 0): Screen {
        val offset = if (alternateScreen) 0 else viewportOffset.coerceIn(0, scrollback.size)
        val windowStart = (scrollback.size + screen.size - rows - offset).coerceAtLeast(0)
        val lines = (windowStart until windowStart + rows).map { i ->
            val source = if (i < scrollback.size) scrollback[i] else screen[i - scrollback.size]
            // Normalize every line to exactly `columns` cells. Scrollback keeps the width
            // it was written at, so after a resize an older line is shorter (or longer)
            // than the current viewport — and consumers index by Screen.columns.
            List(columns) { col ->
                val cell = source.cells.getOrNull(col)
                if (cell == null) Cell() else Cell(cell.char, cell.style)
            }
        }
        val cursorOnLiveGrid = offset == 0 &&
            (scrollback.size + cursorRow) in windowStart until windowStart + rows
        return Screen(
            columns = columns,
            rows = rows,
            lines = lines,
            cursorColumn = cursorColumn,
            cursorRow = cursorRow,
            cursorVisible = cursorVisible && cursorOnLiveGrid,
            alternateScreen = alternateScreen,
            title = title,
            scrollbackSize = scrollback.size,
        )
    }

    public data class Screen(
        public val columns: Int,
        public val rows: Int,
        /** [rows] lines, each exactly [columns] cells — safe to index by column. */
        public val lines: List<List<Cell>>,
        public val cursorColumn: Int,
        public val cursorRow: Int,
        public val cursorVisible: Boolean,
        public val alternateScreen: Boolean,
        public val title: String,
        public val scrollbackSize: Int,
    )

    public fun countBytes(n: Int) {
        bytesFed += n
    }

    /**
     * Resize the viewport. A height-only change keeps rows top-anchored (existing content,
     * new rows blank, cursor clamped). A width change REFLOWS: wrapped rows are re-joined
     * into their logical lines and re-broken at the new width, so zooming in/out re-wraps
     * long lines instead of truncating them. Overflow (more reflowed rows than fit) is kept
     * in scrollback rather than dropped. Scrollback is otherwise untouched.
     */
    public fun resize(newColumns: Int, newRows: Int) {
        if (newColumns == columns && newRows == rows) return
        if (newColumns == columns) {
            // Height-only: the grid width is unchanged, so cells copy straight across.
            val newScreen = (0 until newRows).map { r -> screen.getOrNull(r)?.copy() ?: Line(newColumns) }
            screen.clear()
            screen.addAll(newScreen)
            rows = newRows
            cursorRow = cursorRow.coerceIn(0, newRows - 1)
            pendingWrap = false
            return
        }
        reflowResize(newColumns, newRows)
    }

    /**
     * Reflows the ENTIRE transcript (scrollback + live screen) to a new width. Rows flagged
     * [Line.wrapped] continue the row above, so the whole history is first stitched back into
     * logical lines (characters + per-cell styles) and then hard-wrapped at [newColumns] —
     * exactly what xterm/Termux do. This is why a portrait→landscape rotation or a pinch
     * zoom-out fills the new width instead of leaving old narrow wraps with a wasted right
     * margin: an on-screen continuation whose head had scrolled into history re-joins its
     * head, and scrolled-back pages re-wrap too. The cursor follows its character; the last
     * [newRows] reflowed rows stay on the live grid and the rest is history (oldest dropped
     * past [scrollbackLimit], like a real tty).
     */
    private fun reflowResize(newColumns: Int, newRows: Int) {
        // 1. Concatenate history + live grid, then stitch wrapped rows into logical lines.
        //    The cursor lives in the live screen, so track which logical line + offset its
        //    character lands on as we build them.
        val all = ArrayList<Line>(scrollback.size + screen.size)
        all.addAll(scrollback)
        val screenBase = all.size
        all.addAll(screen)

        val logicals = ArrayList<ArrayList<Cell>>()
        var cursorLogical = -1
        var cursorOffset = 0
        for (i in all.indices) {
            val line = all[i]
            if (logicals.isEmpty() || !line.wrapped) logicals.add(ArrayList())
            val logical = logicals.last()
            val base = logical.size
            for (cell in line.cells) logical.add(Cell().also { it.char = cell.char; it.style = cell.style })
            val screenRow = i - screenBase
            if (screenRow == cursorRow && screenRow >= 0) {
                cursorLogical = logicals.lastIndex
                cursorOffset = base + cursorColumn
            }
        }

        // 2. Trim trailing blank cells per logical line (only the final wrapped row can carry
        //    padding — interior joined rows were full to the edge by definition).
        for (logical in logicals) {
            var end = logical.size
            while (end > 0 && logical[end - 1].char == ' ' && logical[end - 1].style == Style.DEFAULT) end--
            while (logical.size > end) logical.removeAt(logical.size - 1)
        }

        // 2b. Drop trailing EMPTY logical lines — they are the unused blank rows below the
        //     cursor, not real content, and re-emitting them would push real content off the
        //     top into scrollback on every reflow. Interior blanks (a real "\n\n") and the
        //     cursor's own line are kept.
        val lastNonEmpty = logicals.indexOfLast { it.isNotEmpty() }
        val keepThrough = maxOf(lastNonEmpty, cursorLogical)
        while (logicals.size > keepThrough + 1) logicals.removeAt(logicals.size - 1)

        // 3. Re-break each logical line at the new width; an empty logical stays one blank row.
        val reflowed = ArrayList<Line>()
        var cursorRowInReflow = -1
        var cursorColInReflow = 0
        for ((li, logical) in logicals.withIndex()) {
            val firstRow = reflowed.size
            if (logical.isEmpty()) {
                reflowed.add(Line(newColumns))
            } else {
                var i = 0
                var chunk = 0
                while (i < logical.size) {
                    val take = minOf(newColumns, logical.size - i)
                    val out = Line(newColumns).also { it.wrapped = chunk > 0 }
                    for (ci in 0 until take) {
                        out.cells[ci].char = logical[i + ci].char
                        out.cells[ci].style = logical[i + ci].style
                    }
                    reflowed.add(out)
                    i += take
                    chunk++
                }
            }
            if (li == cursorLogical) {
                val off = cursorOffset.coerceIn(0, logical.size)
                cursorRowInReflow = firstRow + off / newColumns.coerceAtLeast(1)
                cursorColInReflow = off % newColumns.coerceAtLeast(1)
            }
        }
        if (reflowed.isEmpty()) reflowed.add(Line(newColumns))

        // 4. Last [newRows] rows are the live grid; the rest becomes history (rebuilt, so its
        //    wrapped flags stay correct for the next reflow).
        val excess = (reflowed.size - newRows).coerceAtLeast(0)
        scrollback.clear()
        for (k in 0 until excess) {
            scrollback.addLast(reflowed[k])
            if (scrollback.size > scrollbackLimit) scrollback.removeFirst()
        }
        val window = (0 until newRows).map { r -> reflowed.getOrNull(excess + r) ?: Line(newColumns) }
        screen.clear()
        screen.addAll(window)
        columns = newColumns
        rows = newRows

        // 5. Place the cursor on its character within the live grid; clamp if it flowed into
        //    history (only possible when content below it overflowed the smaller grid).
        if (cursorRowInReflow >= 0) {
            cursorRow = (cursorRowInReflow - excess).coerceIn(0, newRows - 1)
            cursorColumn = cursorColInReflow.coerceIn(0, newColumns - 1)
        } else {
            cursorRow = cursorRow.coerceIn(0, newRows - 1)
            cursorColumn = cursorColumn.coerceIn(0, newColumns - 1)
        }
        pendingWrap = false
    }

    /** RIS (ESC c): full reset — fresh grid, cursor home, modes to defaults. */
    public fun reset() {
        scrollback.clear()
        for (r in 0 until rows) screen[r] = Line(columns)
        cursorColumn = 0
        cursorRow = 0
        autoWrap = true
        cursorVisible = true
        savedPrimary = null
        alternateScreen = false
        pendingWrap = false
        savedCursor = null
        bellCount = 0
        title = ""
    }

    private fun clearLine(row: Int) {
        val line = screen[row]
        for (c in 0 until columns) {
            line.cells[c].char = ' '
            line.cells[c].style = Style.DEFAULT
        }
    }
}
