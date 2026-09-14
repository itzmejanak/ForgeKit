package com.forgekit.ui.design

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Semantic color of one console line (channel / event kind → tone happens in the caller). */
public enum class ForgeLogTone { MUTED, NORMAL, ACCENT, INFO, SUCCESS, WARNING, ERROR }

/**
 * Presentation-only console line. [id] must be unique and stable within one console;
 * [time] and [tag] are optional fixed-width prefixes (e.g. `12:01:05`, `PHASE`).
 */
public data class ForgeLogLineVisual(
    public val id: Long,
    public val text: String,
    public val tone: ForgeLogTone = ForgeLogTone.NORMAL,
    public val time: String? = null,
    public val tag: String? = null,
)

/**
 * The one log/console surface (provisioning, job output, plugin `log` blocks).
 *
 * Lines render lazily and transparently on the page canvas — no card, no border — so
 * the log reads as the page's own output. The console follows the tail while new lines
 * arrive; dragging it pauses following, and scrolling back to the end or tapping
 * the jump-to-latest button resumes it. Size it from the caller (`Modifier.weight(1f)` on a fixed page,
 * [ForgeSpacing.consoleEmbeddedHeight] inside a scrolling list).
 */
@Composable
public fun ForgeLogConsole(
    lines: List<ForgeLogLineVisual>,
    modifier: Modifier = Modifier,
    title: String? = null,
    emptyText: String = "No output yet",
) {
    var follow by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val atEnd by remember { derivedStateOf { !listState.canScrollForward } }

    LaunchedEffect(follow, lines.size) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            val scrollable = listState.canScrollForward || listState.canScrollBackward
            if (interaction is DragInteraction.Start && scrollable) follow = false
        }
    }
    // Reaching the tail by hand resumes following (only on the transition, not while paused at rest).
    LaunchedEffect(atEnd) {
        if (atEnd && !follow && listState.isScrollInProgress) follow = true
    }

    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = ForgeSpacing.compact),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(title, Modifier.weight(1f))
                if (lines.isNotEmpty()) {
                    Text("${lines.size} lines", style = ForgeTypography.badge, color = ForgePalette.textMuted)
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f, fill = true)) {
            if (lines.isEmpty()) {
                Text(emptyText, style = ForgeTypography.console, color = ForgePalette.textMuted)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = ForgeSpacing.micro),
                    verticalArrangement = Arrangement.spacedBy(ForgeSpacing.hairline),
                ) {
                    itemsIndexed(lines, key = { _, line -> line.id }) { _, line -> ForgeLogLine(line) }
                }
            }
            if (!follow && listState.canScrollForward) {
                Surface(
                    onClick = { follow = true },
                    shape = CircleShape,
                    color = ForgePalette.surfaceElevated,
                    contentColor = ForgePalette.primary,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(ForgeSpacing.compact)
                        .size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.ArrowDownward, contentDescription = "Jump to latest", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ForgeLogLine(line: ForgeLogLineVisual) {
    val color = logToneColor(line.tone)
    val text = buildAnnotatedString {
        line.time?.let { withStyle(SpanStyle(color = ForgePalette.textMuted)) { append(it); append("  ") } }
        line.tag?.let { withStyle(SpanStyle(color = tagColor(line.tone))) { append(it.padEnd(TAG_WIDTH)); append(' ') } }
        withStyle(SpanStyle(color = color)) { append(line.text) }
    }
    Text(text = text, style = ForgeTypography.console, modifier = Modifier.fillMaxWidth())
}

private const val TAG_WIDTH = 6

private fun logToneColor(tone: ForgeLogTone): Color = when (tone) {
    ForgeLogTone.MUTED -> ForgePalette.textMuted
    ForgeLogTone.NORMAL -> ForgePalette.textPrimary
    ForgeLogTone.ACCENT -> ForgePalette.primary
    ForgeLogTone.INFO -> ForgePalette.info
    ForgeLogTone.SUCCESS -> ForgePalette.success
    ForgeLogTone.WARNING -> ForgePalette.warning
    ForgeLogTone.ERROR -> ForgePalette.error
}

/** Tags carry the tone even when the message itself stays neutral. */
private fun tagColor(tone: ForgeLogTone): Color = when (tone) {
    ForgeLogTone.NORMAL, ForgeLogTone.MUTED -> ForgePalette.textSecondary
    else -> logToneColor(tone)
}
