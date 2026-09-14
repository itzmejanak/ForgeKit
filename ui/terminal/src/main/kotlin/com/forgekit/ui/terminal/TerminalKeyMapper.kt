package com.forgekit.ui.terminal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType

/**
 * Pure key-event → terminal-bytes mapper for the ForgeKit shell.
 *
 * The real terminal semantics (ARCHITECTURE §17, Mode B) are a tiny map:
 *   - printable (and Alt+printable → ESC-prefixed) UTF-8 bytes
 *   - Ctrl+letter → the classic control byte (Ctrl+C = 0x03, Ctrl+D = 0x04, …)
 *   - control/special keys → C0 bytes or ANSI sequences (Home/End/PgUp/del/F-keys)
 *
 * The function is pure (a [KeyEventType.KeyDown] with a [Key] + codepoint in,
 * bytes out, `null` = nothing to send) so the full table is unit-testable on
 * the JVM exactly as it runs on-device — no Android state, no IME, no pty.
 */
public object TerminalKeyMapper {

    /** Maps one key event to the bytes a real terminal would receive. */
    public fun map(
        key: Key,
        codePoint: Int,
        ctrlPressed: Boolean,
        altPressed: Boolean,
        shiftPressed: Boolean = false,
        eventType: KeyEventType = KeyEventType.KeyDown,
    ): ByteArray? {
        if (eventType != KeyEventType.KeyDown) return null

        // Ctrl+letter → control code (this also covers Ctrl+I = Tab, Ctrl+M = CR,
        // Ctrl+J = LF — the traditional ASCII aliases terminals rely on).
        val ctrl = ctrlByte(key)
        if (ctrl != null) {
            // Alt+Ctrl+letter → ESC + control byte (meta/emacs idiom).
            return if (altPressed) byteArrayOf(0x1B, ctrl) else byteArrayOf(ctrl)
        }

        if (ctrlPressed) {
            when (key) {
                Key.LeftBracket -> return byteArrayOf(0x1B) // Ctrl+[ == ESC
                Key.Backslash -> return byteArrayOf(0x1C)
                Key.RightBracket -> return byteArrayOf(0x1D)
                Key.Unknown -> if (codePoint in 0..0x1F) return byteArrayOf(codePoint.toByte())
                else -> Unit
            }
            if (codePoint == ' '.code) return byteArrayOf(0x00) // Ctrl+Space == NUL
        }

        // Named keys → C0 bytes or ANSI sequences.
        when (key) {
            Key.Tab -> return byteArrayOf(0x09) // completion
            Key.Enter -> return "\r".toByteArray() // CRLF discipline: carriage return only
            Key.Backspace -> return byteArrayOf(0x7F) // DEL, the Termux/bash backspace byte
            Key.Escape -> return byteArrayOf(0x1B)
            Key.Delete -> return "\u001B[3~".toByteArray()
            Key.Insert -> return "\u001B[2~".toByteArray()
            Key.MoveHome -> return "\u001B[H".toByteArray()
            Key.MoveEnd -> return "\u001B[F".toByteArray()
            Key.PageUp -> return "\u001B[5~".toByteArray()
            Key.PageDown -> return "\u001B[6~".toByteArray()
            Key.DirectionUp -> return "\u001B[A".toByteArray()
            Key.DirectionDown -> return "\u001B[B".toByteArray()
            Key.DirectionRight -> return "\u001B[C".toByteArray()
            Key.DirectionLeft -> return "\u001B[D".toByteArray()
            in FUNCTION_KEYS -> return FUNCTION_KEYS.getValue(key).toByteArray()
            else -> Unit
        }

        // Everything else: a printable UTF-16 code point (hardware key or IME-composed).
        if (codePoint > 0 && !Character.isISOControl(codePoint)) {
            val text = String(Character.toChars(codePoint))
            return if (altPressed) ("\u001B$text").toByteArray() else text.toByteArray(Charsets.UTF_8)
        }
        return null
    }

    /** ctrl+letter → ASCII control byte. `null` when the key is not A–Z. */
    private fun ctrlByte(key: Key): Byte? = when (key) {
        Key.A -> 0x01; Key.B -> 0x02; Key.C -> 0x03; Key.D -> 0x04; Key.E -> 0x05
        Key.F -> 0x06; Key.G -> 0x07; Key.H -> 0x08; Key.I -> 0x09; Key.J -> 0x0A
        Key.K -> 0x0B; Key.L -> 0x0C; Key.M -> 0x0D; Key.N -> 0x0E; Key.O -> 0x0F
        Key.P -> 0x10; Key.Q -> 0x11; Key.R -> 0x12; Key.S -> 0x13; Key.T -> 0x14
        Key.U -> 0x15; Key.V -> 0x16; Key.W -> 0x17; Key.X -> 0x18; Key.Y -> 0x19
        Key.Z -> 0x1A
        else -> null
    }

    private val FUNCTION_KEYS: Map<Key, String> = mapOf(
        Key.F1 to "\u001BOP", Key.F2 to "\u001BOQ", Key.F3 to "\u001BOR", Key.F4 to "\u001BOS",
        Key.F5 to "\u001B[15~", Key.F6 to "\u001B[17~", Key.F7 to "\u001B[18~",
        Key.F8 to "\u001B[19~", Key.F9 to "\u001B[20~", Key.F10 to "\u001B[21~",
        Key.F11 to "\u001B[23~", Key.F12 to "\u001B[24~",
    )
}