package com.forgekit.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.forgekit.ui.design.ForgePalette
import com.forgekit.ui.terminal.TerminalBuffer.Screen
import kotlinx.coroutines.flow.StateFlow

/**
 * Density-aware monospace cell metrics for a [fontSize] — the single source of
 * truth for the glyph grid. Both the renderer (drawing) and the screen
 * (computing rows/cols for [TerminalEmulator.resize]) use the exact same
 * numbers, so a zoom change and a layout change always agree on the grid.
 */
@Composable
public fun rememberTerminalMetrics(fontSize: Int): Pair<Float, Float> {
    val textMeasurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = fontSize.sp, fontFamily = FontFamily.Monospace)
    // Measure a RUN of glyphs and divide: a single "M" rounds to whole pixels and
    // underestimates the true advance, which then over-counts columns and lets long
    // lines spill past the right edge. Averaging a 20-char run gives a precise advance.
    val sample = "MMMMMMMMMMMMMMMMMMMM"
    val cellWidth = textMeasurer.measure(sample, style).size.width.toFloat() / sample.length
    val cellHeight = with(LocalDensity.current) { (fontSize * 1.35).sp.toPx() }
    return remember(fontSize, cellWidth, cellHeight) { cellWidth to cellHeight }
}

/**
 * The terminal surface (M9): renders [Screen] snapshots from the engine onto a Canvas cell
 * grid — monospace glyph runs, SGR colors and attributes (via [TerminalColors]), cursor and
 * selection. Drawing is snapshot-driven: the pty stream updates the buffer, a recomposition
 * renders; nothing here touches processes (Rule 1).
 *
 * Renders one [Screen] snapshot at [fontSize]sp with the given cell padding.
 * Optimized for correctness over throughput: one draw pass per snapshot,
 * visible-region cells only.
 */
@Composable
public fun TerminalView(
    screen: Screen,
    modifier: Modifier = Modifier,
    fontSize: Int = 13,
    showCursor: Boolean = true,
    selection: TerminalSelection? = null,
    /**
     * Cell metrics override. When the host already measured the grid (to compute the
     * rows/cols it resized the pty to), it MUST pass the same numbers here so drawing
     * and resize agree to the pixel — otherwise a divergence lets long lines spill past
     * the right edge. Null falls back to measuring locally (standalone use).
     */
    cellWidthOverride: Float? = null,
    cellHeightOverride: Float? = null,
) {
    val measured = rememberTerminalMetrics(fontSize)
    val cellWidthCell = cellWidthOverride ?: measured.first
    val glyphHeightCell = cellHeightOverride ?: measured.second
    val textMeasurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = fontSize.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val rowH = glyphHeightCell
                val colW = cellWidthCell
                for (row in 0 until screen.rows) {
                    val line = screen.lines[row]
                    var col = 0
                    while (col < screen.columns) {
                        val cell = line[col]
                        if (cell.char == ' ' && !TerminalColors.paintsBackground(cell.style)) {
                            col++
                            continue
                        }
                        val topLeft = Offset(col * colW, row * rowH)
                        val cellStyle = cell.style
                        val colors = TerminalColors.resolve(cellStyle)

                        // background (SGR bg, or the swapped colors of reverse video)
                        colors.background?.let { bg ->
                            drawRect(
                                color = bg,
                                topLeft = topLeft,
                                size = Size(colW, rowH),
                            )
                        }

                        // glyph run: measure a whole same-style span per line
                        var runEnd = col + 1
                        while (runEnd < screen.columns) {
                            val next = line[runEnd]
                            val sameStyle = next.style == cellStyle
                            if (!sameStyle) break
                            runEnd++
                        }
                        if (colors.background != null && runEnd > col + 1) {
                            drawRect(
                                color = colors.background,
                                topLeft = Offset((col + 1) * colW, row * rowH),
                                size = Size((runEnd - col - 1) * colW, rowH),
                            )
                        }
                        val text = line.subList(col, runEnd).joinToString("") { it.char.toString() }
                        val decorations = buildList {
                            if (cellStyle.underline) add(TextDecoration.Underline)
                            if (cellStyle.strikethrough) add(TextDecoration.LineThrough)
                        }
                        val measured = textMeasurer.measure(
                            text,
                            style.copy(
                                color = colors.foreground,
                                fontWeight = if (cellStyle.bold) FontWeight.Bold else FontWeight.Normal,
                                fontStyle = if (cellStyle.italic) FontStyle.Italic else FontStyle.Normal,
                                textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
                            ),
                        )
                        drawText(measured, topLeft = topLeft)

                        col = runEnd
                    }
                }

                // selection highlight (translucent overlay over the selected cells)
                selection?.let { sel ->
                    for (row in 0 until screen.rows) {
                        val range = sel.columnRange(row, screen.columns) ?: continue
                        val x = range.first * colW
                        val width = (range.last - range.first + 1).coerceAtLeast(1) * colW
                        drawRect(
                            color = ForgePalette.primary.copy(alpha = 0.30f),
                            topLeft = Offset(x, row * rowH),
                            size = Size(width, rowH),
                        )
                    }
                }

                // cursor block (never a fake: it reflects engine state)
                if (showCursor && screen.cursorVisible) {
                    val cursorTopLeft = Offset(screen.cursorColumn * colW, screen.cursorRow * rowH)
                    drawRect(
                        color = ForgePalette.primary.copy(alpha = 0.55f),
                        topLeft = cursorTopLeft,
                        size = Size(colW, rowH),
                    )
                }
            },
    )
}

/** Convenience: collects a [StateFlow] of screens and renders it. */
@Composable
public fun TerminalView(
    screens: StateFlow<Screen>,
    modifier: Modifier = Modifier,
    fontSize: Int = 13,
    showCursor: Boolean = true,
) {
    val screen: Screen by screens.collectAsState()
    TerminalView(screen, modifier, fontSize, showCursor)
}
