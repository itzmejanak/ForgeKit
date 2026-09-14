package com.forgekit.app.terminal

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.key.Key
import com.forgekit.ui.terminal.TerminalKeyMapper

/** Cell font size (sp) defaults + zoom band shared by the terminal surface. */
internal const val DEFAULT_FONT_SIZE = 13
internal const val MIN_FONT_SIZE = 7
internal const val MAX_FONT_SIZE = 32

/** Coalescing window for zoom/SIGWINCH resizes (pinch churn absorbed to one pty resize). */
internal const val ZOOM_RESIZE_DEBOUNCE_MS = 120L

/**
 * Applies armed sticky modifiers (from the extra-keys bar) to a single outgoing byte
 * sequence, then the surface clears the arming. Pure so it is unit-testable.
 *
 *  - CTRL + a printable ASCII byte → the classic control byte (`b & 0x1F`): 'c'→0x03,
 *    '['→ESC, etc. Only applied to a lone printable byte.
 *  - ALT → ESC prefix (meta), applied to whatever the bytes are.
 */
internal fun applyModifiers(raw: ByteArray, ctrl: Boolean, alt: Boolean): ByteArray {
    var bytes = raw
    if (ctrl && raw.size == 1) {
        val c = raw[0].toInt() and 0xFF
        if (c in 0x20..0x7E) bytes = byteArrayOf((c and 0x1F).toByte())
    }
    if (alt) bytes = byteArrayOf(0x1B) + bytes
    return bytes
}

/**
 * Hardware-key dispatch. Local scrollback paging owns PgUp/PgDn in the plain shell
 * (no full-screen app); everything else is mapped to pty bytes by [TerminalKeyMapper].
 *
 * @return the bytes to send, or null when the event was consumed locally (scroll) or is
 *   not a key-down we translate.
 */
internal fun mapHardwareKey(
    event: KeyEvent,
    alternateScreen: Boolean,
    screenLines: Int,
    onScroll: (Int) -> Unit,
): ByteArray? {
    if (event.type != KeyEventType.KeyDown) return null
    when (event.key) {
        Key.PageUp -> if (!alternateScreen) { onScroll(screenLines); return null }
        Key.PageDown -> if (!alternateScreen) { onScroll(-screenLines); return null }
        else -> Unit
    }
    return TerminalKeyMapper.map(
        key = event.key,
        codePoint = event.utf16CodePoint,
        ctrlPressed = event.isCtrlPressed,
        altPressed = event.isAltPressed,
        shiftPressed = event.isShiftPressed,
        eventType = event.type,
    )
}
