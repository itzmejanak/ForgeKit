package com.forgekit.ui.terminal

import com.forgekit.runtime.bridge.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Glues one live [TerminalSession] (a real pty) to the VT engine and
 * publishes immutable [TerminalBuffer.Screen] snapshots for the UI:
 * pty bytes → [VtParser] → [TerminalBuffer] → StateFlow.
 *
 * Input is forwarded raw — the UI layer turns key events into terminal
 * bytes (including ANSI application-mode arrows) and calls [write].
 */
public class TerminalEmulator(
    private val session: TerminalSession,
    private val scope: CoroutineScope,
    columns: Int = 80,
    rows: Int = 24,
    scrollbackLimit: Int = 1000,
) {
    private val buffer = TerminalBuffer(columns, rows, scrollbackLimit)
    private val parser = VtParser(buffer)

    private val _screen = MutableStateFlow(buffer.snapshot())
    public val screen: StateFlow<TerminalBuffer.Screen> = _screen.asStateFlow()

    /** Current scrollback viewport offset (0 = live grid). */
    public val viewport: Int get() = viewportOffset

    private var viewportOffset: Int = 0
    private var pump: Job? = null

    /** Starts pumping pty output into the engine. Idempotent. */
    public fun start() {
        if (pump?.isActive == true) return
        pump = scope.launch {
            session.output.collect { chunk ->
                parser.feed(chunk)
                publish()
            }
        }
    }

    /** Scrolls the render window up into scrollback; clamps; keeps live grid at 0. */
    public fun scrollTo(offset: Int) {
        viewportOffset = offset.coerceIn(0, buffer.scrollback.size)
        publish()
    }

    /** Returns to the live grid. */
    public fun scrollToBottom() = scrollTo(0)

    /** Whether the viewport is showing history rather than the live grid. */
    public val isScrolledBack: Boolean get() = viewportOffset > 0

    /** Stops pumping (session may still be alive). */
    public fun stop() {
        pump?.cancel()
        pump = null
    }

    /** Forwards raw bytes (keystrokes, escape sequences) to the pty. */
    public fun write(bytes: ByteArray) {
        session.write(bytes)
    }

    /** Resizes the terminal and tells the pty (SIGWINCH). */
    public fun resize(columns: Int, rows: Int) {
        session.resize(columns, rows)
        buffer.resize(columns, rows)
        publish()
    }

    public fun killSession() {
        session.kill()
    }

    private fun publish() {
        _screen.value = buffer.snapshot(viewportOffset)
    }
}
