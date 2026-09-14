package com.forgekit.ui.terminal

/**
 * Incremental VT100/ANSI parser: bytes → [TerminalBuffer] mutations.
 * A strict state machine (ground → escape → csi → osc), so partial
 * sequences arriving across chunk boundaries parse correctly — the pty
 * delivers arbitrary byte boundaries, and a real terminal never guesses.
 */
public class VtParser(
    private val buffer: TerminalBuffer,
) {

    private enum class State { GROUND, ESCAPE, CSI, OSC, OSC_ESC, CHARSET }

    private var state: State = State.GROUND

    // CSI parameter accumulation
    private var csiParams = mutableListOf<Int>()
    private var csiHasSubParams = false // "38;5;196" style color lists
    // Parallel to csiParams: true when that parameter was introduced by ':' (ITU T.416
    // sub-parameters, e.g. "38:2::255:107:44"), so SGR can group them with their parent.
    private var csiColon = mutableListOf<Boolean>()
    private var csiPrivate = false // leading '?'
    private var csiIntermediate: Char? = null

    // OSC accumulation (raw bytes: titles may be UTF-8, decoded at finish)
    private val oscBytes = mutableListOf<Byte>()

    // UTF-8 decoder state
    private var pendingCodePoint = 0
    private var pendingContinuation = 0

    /** Feeds one chunk of pty bytes. UTF-8 sequences may span chunks. */
    public fun feed(bytes: ByteArray, from: Int = 0, to: Int = bytes.size) {
        buffer.countBytes(to - from)
        for (i in from until to) {
            val byte = bytes[i].toInt() and 0xFF
            step(byte)
        }
    }

    /** Flushes an incomplete UTF-8 sequence as replacement (session close). */
    public fun flush() {
        flushUtf8AtBoundary()
    }

    public fun feed(text: String) {
        feed(text.toByteArray(Charsets.UTF_8))
    }

    private fun step(byte: Int) {
        // UTF-8 continuation only legal in GROUND
        if (state == State.GROUND) {
            if (pendingContinuation > 0) {
                if (byte and 0xC0 == 0x80) {
                    pendingCodePoint = (pendingCodePoint shl 6) or (byte and 0x3F)
                    pendingContinuation--
                    if (pendingContinuation == 0) {
                        buffer.putChar(decodeReplacement(pendingCodePoint), lastStyle)
                    }
                    return
                }
                // invalid continuation: emit replacement, reprocess byte
                buffer.putChar('\uFFFD', lastStyle)
                pendingContinuation = 0
            }
            when {
                byte < 0x80 -> handleGround(byte)
                byte and 0xE0 == 0xC0 -> { pendingCodePoint = byte and 0x1F; pendingContinuation = 1 }
                byte and 0xF0 == 0xE0 -> { pendingCodePoint = byte and 0x0F; pendingContinuation = 2 }
                byte and 0xF8 == 0xF0 -> { pendingCodePoint = byte and 0x07; pendingContinuation = 3 }
                else -> buffer.putChar('\uFFFD', lastStyle)
            }
            return
        }

        // inside escape sequences everything is ASCII
        when (state) {
            State.ESCAPE -> handleEscape(byte)
            State.CSI -> handleCsi(byte)
            State.OSC -> handleOsc(byte)
            State.OSC_ESC -> handleOscEsc(byte)
            State.CHARSET -> handleCharset(byte)
            State.GROUND -> Unit // unreachable
        }
    }

    private fun decodeReplacement(codePoint: Int): Char = when {
        codePoint in 0..0x10FFFF -> {
            val ch = Character.toChars(codePoint).firstOrNull() ?: '\uFFFD'
            if (Character.isISOControl(ch)) ' ' else ch
        }
        else -> '\uFFFD'
    }

    private fun flushUtf8AtBoundary() {
        if (pendingContinuation > 0) {
            buffer.putChar('\uFFFD', lastStyle)
            pendingContinuation = 0
        }
    }

    // ---- GROUND: C0 controls -------------------------------------------------

    private fun handleGround(byte: Int) {
        val style = lastStyle
        when (byte) {
            0x07 -> buffer.bell()
            0x08 -> buffer.backspace()
            0x09 -> buffer.tab()
            0x0A, 0x0B, 0x0C -> buffer.lineFeed()
            0x0D -> buffer.carriageReturn()
            0x0E, 0x0F -> Unit // SO/SI charset switching: ignored in v1
            0x1B -> { state = State.ESCAPE; resetCsi() }
            in 0x00..0x1F -> Unit // other C0: ignored
            else -> buffer.putChar(byte.toChar(), style)
        }
    }

    // ---- ESCAPE ------------------------------------------------------------

    private fun handleEscape(byte: Int) {
        when (byte) {
            '['.code -> state = State.CSI
            '('.code, ')'.code -> state = State.CHARSET
            ']'.code -> { state = State.OSC; oscBytes.clear() }
            '7'.code -> { buffer.saveCursor(); state = State.GROUND }
            '8'.code -> { buffer.restoreCursor(); state = State.GROUND }
            'M'.code -> { // RI: reverse linefeed
                if (buffer.cursorRow == 0) buffer.scrollDown(1) else buffer.moveCursorRelative(-1, 0)
                state = State.GROUND
            }
            'D'.code -> { buffer.lineFeed(); state = State.GROUND }
            'E'.code -> { buffer.carriageReturn(); buffer.lineFeed(); state = State.GROUND }
            'c'.code -> { hardReset(); state = State.GROUND }
            else -> state = State.GROUND // unknown two-char escapes: dropped
        }
    }

    // ---- CSI ---------------------------------------------------------------

    private fun handleCsi(byte: Int) {
        when {
            byte in '0'.code..'9'.code -> {
                if (csiParams.isEmpty()) {
                    csiParams += 0
                    csiColon += false
                }
                csiParams[csiParams.lastIndex] = csiParams.last() * 10 + (byte - '0'.code)
            }
            byte == ';'.code -> {
                // A leading ';' means the first parameter was empty (e.g. "ESC[;5H" = row default, column 5).
                if (csiParams.isEmpty()) {
                    csiParams += 0
                    csiColon += false
                }
                csiParams += 0
                csiColon += false
                csiHasSubParams = true
            }
            byte == ':'.code -> {
                if (csiParams.isEmpty()) {
                    csiParams += 0
                    csiColon += false
                }
                csiParams += 0
                csiColon += true
                csiHasSubParams = true
            }
            byte == '?'.code && csiParams.isEmpty() -> csiPrivate = true
            byte in 0x20..0x2F -> csiIntermediate = byte.toChar() // space, !, " …
            byte in 0x40..0x7E -> {
                executeCsi(byte.toChar())
                state = State.GROUND
                resetCsi()
            }
            else -> { // malformed: drop the sequence, resync
                state = State.GROUND
                resetCsi()
            }
        }
    }

    private fun resetCsi() {
        csiParams.clear()
        csiColon.clear()
        csiHasSubParams = false
        csiPrivate = false
        csiIntermediate = null
    }

    private fun params(default: Int = 0, index: Int = 0): Int {
        val value = csiParams.getOrNull(index)
        return if (value == null || value == 0) default else value
    }

    private fun executeCsi(final: Char) {
        when (final) {
            'A' -> buffer.moveCursorRelative(-params(1), 0)
            'B' -> buffer.moveCursorRelative(+params(1), 0)
            'C' -> buffer.moveCursorRelative(0, +params(1))
            'D' -> buffer.moveCursorRelative(0, -params(1))
            'E' -> { buffer.moveCursorRelative(+params(1), 0); buffer.carriageReturn() }
            'F' -> { buffer.moveCursorRelative(-params(1), 0); buffer.carriageReturn() }
            'H', 'f' -> buffer.moveCursor(params(1, 0) - 1, params(1, 1) - 1)
            'G', '`' -> buffer.moveCursor(buffer.cursorRow, params(1) - 1)
            'd' -> buffer.moveCursor(params(1) - 1, buffer.cursorColumn)
            'J' -> buffer.eraseDisplay(params(0))
            'K' -> buffer.eraseLine(params(0))
            'L' -> buffer.insertLines(params(1))
            'M' -> buffer.deleteLines(params(1))
            'S' -> buffer.scrollUp(params(1))
            'T' -> buffer.scrollDown(params(1))
            'm' -> if (csiIntermediate == null) applySgr() // SGR
            'h' -> buffer.setMode(csiPrivate, params(0), enabled = true)
            'l' -> buffer.setMode(csiPrivate, params(0), enabled = false)
            's' -> buffer.saveCursor()
            'u' -> buffer.restoreCursor()
            'r' -> Unit // DECSTBM margins: v1 treats the whole screen as scrollable
            else -> Unit // window ops, mouse reporting etc.: ignored (v1)
        }
    }

    // ---- SGR ------------------------------------------------------------------

    private var lastStyle: TerminalBuffer.Style = TerminalBuffer.Style.DEFAULT

    private fun applySgr() {
        if (csiParams.isEmpty()) {
            lastStyle = TerminalBuffer.Style.DEFAULT
            return
        }
        var i = 0
        while (i < csiParams.size) {
            // ':' sub-parameters belong to the parameter before them.
            var groupEnd = i + 1
            while (groupEnd < csiParams.size && csiColon[groupEnd]) groupEnd++
            val sub = csiParams.subList(i + 1, groupEnd)
            when (val p = csiParams[i]) {
                0 -> lastStyle = TerminalBuffer.Style.DEFAULT
                1 -> lastStyle = lastStyle.copy(bold = true)
                2 -> lastStyle = lastStyle.copy(dim = true)
                3 -> lastStyle = lastStyle.copy(italic = true)
                4 -> lastStyle = lastStyle.copy(underline = sub.firstOrNull() != 0) // 4:0 = no underline
                7 -> lastStyle = lastStyle.copy(reverse = true)
                9 -> lastStyle = lastStyle.copy(strikethrough = true)
                21 -> lastStyle = lastStyle.copy(underline = true) // double underline: drawn single
                22 -> lastStyle = lastStyle.copy(bold = false, dim = false)
                23 -> lastStyle = lastStyle.copy(italic = false)
                24 -> lastStyle = lastStyle.copy(underline = false)
                27 -> lastStyle = lastStyle.copy(reverse = false)
                29 -> lastStyle = lastStyle.copy(strikethrough = false)
                in 30..37 -> lastStyle = lastStyle.copy(foreground = p - 30)
                39 -> lastStyle = lastStyle.copy(foreground = null)
                in 40..47 -> lastStyle = lastStyle.copy(background = p - 40)
                49 -> lastStyle = lastStyle.copy(background = null)
                in 90..97 -> lastStyle = lastStyle.copy(foreground = p - 90 + 8) // bright fg
                in 100..107 -> lastStyle = lastStyle.copy(background = p - 100 + 8) // bright bg
                38, 48 -> {
                    val (color, consumed) = if (sub.isNotEmpty()) {
                        colonColor(sub) to 0
                    } else {
                        semicolonColor(i)
                    }
                    if (color != null) {
                        lastStyle = if (p == 38) lastStyle.copy(foreground = color) else lastStyle.copy(background = color)
                    }
                    groupEnd = maxOf(groupEnd, i + 1 + consumed)
                }
                else -> Unit
            }
            i = groupEnd
        }
    }

    /** `38;5;n` / `38;2;r;g;b`: the color and how many following parameters it used. */
    private fun semicolonColor(start: Int): Pair<Int?, Int> = when (csiParams.getOrNull(start + 1)) {
        5 -> csiParams.getOrNull(start + 2)?.takeIf { it in 0..255 } to 2
        2 -> {
            val r = csiParams.getOrNull(start + 2)
            val g = csiParams.getOrNull(start + 3)
            val b = csiParams.getOrNull(start + 4)
            (if (r != null && g != null && b != null) TerminalColor.rgb(r, g, b) else null) to 4
        }
        else -> null to 0
    }

    /** `38:5:n`, `38:2::r:g:b` (with colour-space id) or `38:2:r:g:b`. */
    private fun colonColor(sub: List<Int>): Int? = when (sub.firstOrNull()) {
        5 -> sub.getOrNull(1)?.takeIf { it in 0..255 }
        2 -> when {
            sub.size >= 5 -> TerminalColor.rgb(sub[2], sub[3], sub[4])
            sub.size == 4 -> TerminalColor.rgb(sub[1], sub[2], sub[3])
            else -> null
        }
        else -> null
    }

    // ---- OSC --------------------------------------------------------------------

    private fun handleOsc(byte: Int) {
        when (byte) {
            0x07 -> finishOsc()
            0x1B -> state = State.OSC_ESC
            in 0x20..0xFF -> oscBytes += byte.toByte()
            else -> Unit // control chars inside OSC: dropped
        }
    }

    private fun handleOscEsc(byte: Int) {
        if (byte == '\\'.code) {
            finishOsc()
        } else {
            // ESC that wasn't a terminator: close OSC and reprocess as escape
            state = State.ESCAPE
        }
    }

    private fun finishOsc() {
        val text = oscBytes.toByteArray().toString(Charsets.UTF_8)
        val separator = text.indexOf(';')
        val command = if (separator >= 0) text.substring(0, separator) else text
        val value = if (separator >= 0) text.substring(separator + 1) else ""
        if (command == "0" || command == "2") {
            buffer.setTitle(value)
        }
        oscBytes.clear()
        state = State.GROUND
    }

    // ---- CHARSET (ESC ( X) -----------------------------------------------------

    private fun handleCharset(byte: Int) {
        // charset designators (B, 0, …) are consumed and ignored in v1
        state = State.GROUND
    }

    private fun hardReset() {
        buffer.reset()
    }
}
