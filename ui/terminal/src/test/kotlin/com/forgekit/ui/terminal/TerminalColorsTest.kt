package com.forgekit.ui.terminal

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** xterm conventions the renderer applies when turning a cell style into drawing colors. */
class TerminalColorsTest {

    private fun style(
        foreground: Int? = null,
        background: Int? = null,
        bold: Boolean = false,
        dim: Boolean = false,
        reverse: Boolean = false,
    ) = TerminalBuffer.Style(foreground = foreground, background = background, bold = bold, dim = dim, reverse = reverse)

    @Test
    fun `default style draws default foreground on the canvas`() {
        val colors = TerminalColors.resolve(TerminalBuffer.Style.DEFAULT)
        assertEquals(TerminalTheme.defaultForeground, colors.foreground)
        assertNull(colors.background)
    }

    @Test
    fun `bold brightens only the eight basic colors`() {
        assertEquals(TerminalTheme.palette[12], TerminalColors.resolve(style(foreground = 4, bold = true)).foreground)
        assertEquals(TerminalTheme.palette[196], TerminalColors.resolve(style(foreground = 196, bold = true)).foreground)
        assertEquals(TerminalTheme.palette[4], TerminalColors.resolve(style(foreground = 4)).foreground)
    }

    @Test
    fun `reverse swaps foreground and background including defaults`() {
        val plain = TerminalColors.resolve(style(reverse = true))
        assertEquals(TerminalTheme.defaultBackground, plain.foreground)
        assertEquals(TerminalTheme.defaultForeground, plain.background)
        val colored = TerminalColors.resolve(style(foreground = 1, background = 2, reverse = true))
        assertEquals(TerminalTheme.palette[2], colored.foreground)
        assertEquals(TerminalTheme.palette[1], colored.background)
    }

    @Test
    fun `dim lowers foreground alpha`() {
        assertEquals(TerminalColors.DIM_ALPHA, TerminalColors.resolve(style(dim = true)).foreground.alpha, 0.01f)
    }

    @Test
    fun `24-bit colors resolve exactly`() {
        assertEquals(Color(0xFFFF6B2C), TerminalColors.resolve(style(foreground = TerminalColor.rgb(255, 107, 44))).foreground)
        assertTrue(TerminalColor.isRgb(TerminalColor.rgb(0, 0, 0)), "black RGB is still RGB, not palette 0")
        assertFalse(TerminalColor.isRgb(255))
    }

    @Test
    fun `palette follows xterm cube and grayscale levels`() {
        assertEquals(256, TerminalTheme.palette.size)
        assertEquals(Color(0xFF5F87AF), TerminalTheme.palette[16 + 36 * 1 + 6 * 2 + 3], "cube (1,2,3) = 95,135,175")
        assertEquals(Color(0xFFFFFFFF), TerminalTheme.palette[231])
        assertEquals(Color(0xFF080808), TerminalTheme.palette[232])
        assertEquals(Color(0xFFEEEEEE), TerminalTheme.palette[255])
    }

    @Test
    fun `blank cells paint only with a background or reverse`() {
        assertFalse(TerminalColors.paintsBackground(TerminalBuffer.Style.DEFAULT))
        assertTrue(TerminalColors.paintsBackground(style(background = 1)))
        assertTrue(TerminalColors.paintsBackground(style(reverse = true)))
    }
}
