package com.forgekit.ui.terminal

import androidx.compose.ui.graphics.Color
import com.forgekit.ui.design.ForgePalette

/**
 * Encoding of a cell color inside [TerminalBuffer.Style]: values `0..255` are xterm palette
 * indices; [rgb] values carry a 24-bit color (SGR `38;2;r;g;b`). One Int keeps cells compact
 * and keeps palette indices exactly as the parser read them.
 */
public object TerminalColor {
    private const val RGB_FLAG = 0x1000000

    public fun rgb(red: Int, green: Int, blue: Int): Int =
        RGB_FLAG or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)

    public fun isRgb(encoded: Int): Boolean = encoded and RGB_FLAG != 0

    /** The 0xRRGGBB value of an [rgb]-encoded color. */
    public fun rgbValue(encoded: Int): Int = encoded and 0xFFFFFF
}

/**
 * The xterm 256-color palette in ForgeKit hues. The base 16 carry the ForgeKit heritage
 * (terminal-green, amber, error-red, charcoal ground); the 6×6×6 cube and grayscale ramp
 * follow xterm's exact levels so 256-color programs render the shades they expect.
 */
public object TerminalTheme {

    /** Foreground when a cell sets none. */
    public val defaultForeground: Color = ForgePalette.textPrimary

    /** Background when a cell sets none (the terminal draws on the page canvas). */
    public val defaultBackground: Color = ForgePalette.background

    private val cubeLevels = intArrayOf(0, 95, 135, 175, 215, 255)

    public val palette: List<Color> = buildList {
        val base = listOf(
            0xFF0A0D0B, 0xFFFF5449, 0xFF43D9A3, 0xFFFFC24B,
            0xFF6AA9FF, 0xFFC4A5FF, 0xFF4CC9D6, 0xFFE9EFE7,
            0xFF5F6C60, 0xFFFF7A70, 0xFF6FF0C0, 0xFFFFD98A,
            0xFF9CC6FF, 0xFFDCC8FF, 0xFF7EE6EE, 0xFFFFFFFF,
        )
        addAll(base.map { Color(it) })
        // 16-231: 6×6×6 color cube on xterm's levels
        for (r in cubeLevels) for (g in cubeLevels) for (b in cubeLevels) {
            add(Color(0xFF000000.toInt() or (r shl 16) or (g shl 8) or b))
        }
        // 232-255: grayscale ramp (8, 18, … 238)
        for (level in 8..238 step 10) {
            add(Color(0xFF000000.toInt() or (level shl 16) or (level shl 8) or level))
        }
    }

    /** Color of an encoded cell color ([TerminalColor]). */
    public fun color(encoded: Int): Color = when {
        TerminalColor.isRgb(encoded) -> Color(0xFF000000.toInt() or TerminalColor.rgbValue(encoded))
        encoded in palette.indices -> palette[encoded]
        else -> defaultForeground
    }
}

/** Final drawing colors of one cell: [background] null means "leave the canvas". */
public data class TerminalCellColors(val foreground: Color, val background: Color?)

/** Style → drawing colors, applying xterm conventions. Pure, so it is unit-tested. */
public object TerminalColors {

    /** Alpha applied to faint (SGR 2) text. */
    public const val DIM_ALPHA: Float = 0.6f

    public fun resolve(style: TerminalBuffer.Style): TerminalCellColors {
        // Bold brightens the 8 basic colors, as xterm and Termux do.
        val foregroundIndex = style.foreground?.let { if (style.bold && it in 0..7) it + 8 else it }
        var foreground = foregroundIndex?.let(TerminalTheme::color) ?: TerminalTheme.defaultForeground
        var background = style.background?.let(TerminalTheme::color)
        if (style.reverse) {
            val swapped = background ?: TerminalTheme.defaultBackground
            background = foreground
            foreground = swapped
        }
        if (style.dim) foreground = foreground.copy(alpha = foreground.alpha * DIM_ALPHA)
        return TerminalCellColors(foreground, background)
    }

    /** Whether a blank cell with [style] paints anything (a background or reverse block). */
    public fun paintsBackground(style: TerminalBuffer.Style): Boolean = style.background != null || style.reverse
}
