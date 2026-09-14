package com.forgekit.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forgekit.ui.design.ForgePalette
import com.forgekit.ui.design.PlexMono

/**
 * Termux-style extra-keys bar: the keys a shell needs that a phone soft-keyboard lacks
 * (ESC, TAB, CTRL, ALT, arrows, HOME/END, PGUP/PGDN, `/`, `-`). Two fixed rows sized to
 * sit directly above the soft keyboard.
 *
 * CTRL and ALT are *sticky*: tapping arms them (highlighted), and the surface applies the
 * modifier to the next byte it sends (from this bar, the IME, or a hardware key), then
 * clears it. Everything else emits terminal bytes immediately via [onSend].
 */
public sealed interface ExtraKey {
    public val label: String

    /** Emits [bytes] to the pty (subject to armed CTRL/ALT). */
    public data class Send(override val label: String, val bytes: ByteArray) : ExtraKey {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Send && label == other.label && bytes.contentEquals(other.bytes))
        override fun hashCode(): Int = 31 * label.hashCode() + bytes.contentHashCode()
    }

    /** Toggles a sticky modifier. */
    public data class Toggle(override val label: String, val modifier: ExtraModifier) : ExtraKey
}

/** The sticky modifiers the bar can arm. */
public enum class ExtraModifier { CTRL, ALT }

private fun esc(seq: String): ByteArray = ("" + seq).toByteArray()

/** Termux's default extra-keys layout (matches the reference: 7 columns × 2 rows). */
public val DEFAULT_EXTRA_KEYS: List<List<ExtraKey>> = listOf(
    listOf(
        ExtraKey.Send("ESC", byteArrayOf(0x1B)),
        ExtraKey.Send("/", "/".toByteArray()),
        ExtraKey.Send("—", "-".toByteArray()),
        ExtraKey.Send("HOME", esc("[H")),
        ExtraKey.Send("↑", esc("[A")),
        ExtraKey.Send("END", esc("[F")),
        ExtraKey.Send("PGUP", esc("[5~")),
    ),
    listOf(
        ExtraKey.Send("TAB", byteArrayOf(0x09)),
        ExtraKey.Toggle("CTRL", ExtraModifier.CTRL),
        ExtraKey.Toggle("ALT", ExtraModifier.ALT),
        ExtraKey.Send("←", esc("[D")),
        ExtraKey.Send("↓", esc("[B")),
        ExtraKey.Send("→", esc("[C")),
        ExtraKey.Send("PGDN", esc("[6~")),
    ),
)

@Composable
public fun TerminalExtraKeys(
    ctrlArmed: Boolean,
    altArmed: Boolean,
    onSend: (ByteArray) -> Unit,
    onToggle: (ExtraModifier) -> Unit,
    modifier: Modifier = Modifier,
    rows: List<List<ExtraKey>> = DEFAULT_EXTRA_KEYS,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(ForgePalette.surfaceElevated)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in rows) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (key in row) {
                    val armed = when (key) {
                        is ExtraKey.Toggle -> (key.modifier == ExtraModifier.CTRL && ctrlArmed) ||
                            (key.modifier == ExtraModifier.ALT && altArmed)
                        else -> false
                    }
                    ExtraKeyButton(
                        label = key.label,
                        armed = armed,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            when (key) {
                                is ExtraKey.Send -> onSend(key.bytes)
                                is ExtraKey.Toggle -> onToggle(key.modifier)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtraKeyButton(
    label: String,
    armed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (armed) ForgePalette.primary else ForgePalette.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (armed) ForgePalette.background else ForgePalette.textPrimary,
            fontSize = 13.sp,
            fontFamily = PlexMono,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
