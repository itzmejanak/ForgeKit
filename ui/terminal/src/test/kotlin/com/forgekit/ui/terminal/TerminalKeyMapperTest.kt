package com.forgekit.ui.terminal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/** The full key→byte table, exercised on the JVM exactly as the device uses it. */
class TerminalKeyMapperTest {

    private fun bytes(key: Key, cp: Int = -1, ctrl: Boolean = false, alt: Boolean = false): ByteArray? =
        TerminalKeyMapper.map(
            key = key,
            codePoint = cp,
            ctrlPressed = ctrl,
            altPressed = alt,
            shiftPressed = false,
            eventType = KeyEventType.KeyDown,
        )

    @Test
    fun `printable keys become their UTF-8 bytes`() {
        assertContentEquals("h".toByteArray(), bytes(Key.Unknown, cp = 'h'.code))
        assertContentEquals("é".toByteArray(Charsets.UTF_8), bytes(Key.Unknown, cp = 'é'.code))
    }

    @Test
    fun `alt prefixes printables with escape`() {
        assertContentEquals("\u001Bh".toByteArray(), bytes(Key.Unknown, cp = 'h'.code, alt = true))
        assertContentEquals("\u001Bé".toByteArray(), bytes(Key.Unknown, cp = 'é'.code, alt = true))
    }

    @Test
    fun `ctrl letters collapse to classic control bytes`() {
        assertContentEquals(byteArrayOf(0x03), bytes(Key.C, ctrl = true))
        assertContentEquals(byteArrayOf(0x04), bytes(Key.D, ctrl = true))
        assertContentEquals(byteArrayOf(0x09), bytes(Key.I, ctrl = true)) // Tab alias
        assertContentEquals(byteArrayOf(0x0C), bytes(Key.L, ctrl = true)) // clear screen
        assertContentEquals(byteArrayOf(0x0D), bytes(Key.M, ctrl = true)) // CR alias
        assertContentEquals(byteArrayOf(0x1A), bytes(Key.Z, ctrl = true)) // suspend
    }

    @Test
    fun `alt ctrl letters become ESC plus control byte`() {
        assertContentEquals(byteArrayOf(0x1B, 0x05), bytes(Key.E, ctrl = true, alt = true))
    }

    @Test
    fun `ctrl space is NUL and ctrl bracket is ESC`() {
        assertContentEquals(byteArrayOf(0x00), bytes(Key.Unknown, cp = ' '.code, ctrl = true))
        assertContentEquals(byteArrayOf(0x1B), bytes(Key.LeftBracket, ctrl = true))
    }

    @Test
    fun `named keys map to the bytes a real tty expects`() {
        assertContentEquals(byteArrayOf(0x09), bytes(Key.Tab))
        assertContentEquals("\r".toByteArray(), bytes(Key.Enter))
        assertContentEquals(byteArrayOf(0x7F), bytes(Key.Backspace))
        assertContentEquals(byteArrayOf(0x1B), bytes(Key.Escape))
        assertContentEquals("\u001B[A".toByteArray(), bytes(Key.DirectionUp))
        assertContentEquals("\u001B[D".toByteArray(), bytes(Key.DirectionLeft))
        assertContentEquals("\u001B[3~".toByteArray(), bytes(Key.Delete))
        assertContentEquals("\u001B[H".toByteArray(), bytes(Key.MoveHome))
        assertContentEquals("\u001B[F".toByteArray(), bytes(Key.MoveEnd))
        assertContentEquals("\u001B[5~".toByteArray(), bytes(Key.PageUp))
        assertContentEquals("\u001BOP".toByteArray(), bytes(Key.F1))
        assertContentEquals("\u001B[24~".toByteArray(), bytes(Key.F12))
    }

    @Test
    fun `key up and unknown codes are ignored`() {
        assertNull(
            TerminalKeyMapper.map(
                Key.A, -1, ctrlPressed = false, altPressed = false,
                eventType = KeyEventType.KeyUp,
            ),
        )
        assertNull(bytes(Key.Unknown))
    }

    @Test
    fun `ctrl fixes are consistent with unix kill line`() {
        assertContentEquals(byteArrayOf(0x15), bytes(Key.U, ctrl = true))
        assertContentEquals(byteArrayOf(0x17), bytes(Key.W, ctrl = true))
        assertContentEquals(byteArrayOf(0x15), bytes(Key.U, ctrl = true))
    }
}