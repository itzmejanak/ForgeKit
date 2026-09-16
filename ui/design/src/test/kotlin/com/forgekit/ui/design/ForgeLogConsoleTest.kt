package com.forgekit.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals

class ForgeLogConsoleTest {
    @Test
    fun `zoom step stays inside the supported band`() {
        assertEquals(MIN_LOG_FONT_SIZE_SP, stepLogFontSize(MIN_LOG_FONT_SIZE_SP, -1))
        assertEquals(MAX_LOG_FONT_SIZE_SP, stepLogFontSize(MAX_LOG_FONT_SIZE_SP, 1))
        assertEquals(12.5f, stepLogFontSize(DEFAULT_LOG_FONT_SIZE_SP, 1))
        assertEquals(10.5f, stepLogFontSize(DEFAULT_LOG_FONT_SIZE_SP, -1))
    }

    @Test
    fun `default zoom reports one hundred percent`() {
        assertEquals(100, logZoomPercent(DEFAULT_LOG_FONT_SIZE_SP))
        assertEquals(209, logZoomPercent(MAX_LOG_FONT_SIZE_SP))
    }

    @Test
    fun `latest action enables only while paused away from the tail`() {
        assertEquals(false, canJumpToLatest(hasLines = false, following = false, canScrollForward = true))
        assertEquals(false, canJumpToLatest(hasLines = true, following = true, canScrollForward = true))
        assertEquals(false, canJumpToLatest(hasLines = true, following = false, canScrollForward = false))
        assertEquals(true, canJumpToLatest(hasLines = true, following = false, canScrollForward = true))
    }

    @Test
    fun `copy all preserves the prefixes users see`() {
        val lines = listOf(
            ForgeLogLineVisual(id = 1, time = "12:01:02", tag = "OUT", text = "hello"),
            ForgeLogLineVisual(id = 2, tag = "ERR", text = "broken", tone = ForgeLogTone.ERROR),
            ForgeLogLineVisual(id = 3, text = "plain"),
        )

        assertEquals(
            "12:01:02  OUT    hello\nERR    broken\nplain",
            lines.toPlainLogText(),
        )
    }
}
