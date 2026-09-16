package com.forgekit.ui.design

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

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
 * the jump-to-latest button resumes it. Text is natively selectable/copyable and a
 * two-finger pinch changes the mono font size without consuming one-finger list scrolls.
 * Size it from the caller (`Modifier.weight(1f)` on a fixed page,
 * [ForgeSpacing.consoleEmbeddedHeight] inside a scrolling list).
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
public fun ForgeLogConsole(
    lines: List<ForgeLogLineVisual>,
    modifier: Modifier = Modifier,
    title: String? = null,
    emptyText: String = "No output yet",
) {
    var follow by rememberSaveable { mutableStateOf(true) }
    var fontSizeSp by rememberSaveable { mutableFloatStateOf(DEFAULT_LOG_FONT_SIZE_SP) }
    var zoomAccum by remember { mutableFloatStateOf(1f) }
    val listState = rememberLazyListState()
    val atEnd by remember { derivedStateOf { !listState.canScrollForward } }
    val hasLines = lines.isNotEmpty()
    val latestActionEnabled by remember(hasLines) {
        derivedStateOf {
            canJumpToLatest(
                hasLines = hasLines,
                following = follow,
                canScrollForward = listState.canScrollForward,
            )
        }
    }
    val clipboard = LocalClipboardManager.current
    val zoomPercent = logZoomPercent(fontSizeSp)
    val lineStyle = ForgeTypography.console.copy(
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp * LOG_LINE_HEIGHT_RATIO).sp,
    )
    val zoomState = rememberTransformableState { zoomChange, _, _ ->
        zoomAccum *= zoomChange
        when {
            zoomAccum > ZOOM_IN_THRESHOLD -> {
                fontSizeSp = stepLogFontSize(fontSizeSp, 1)
                zoomAccum = 1f
            }
            zoomAccum < ZOOM_OUT_THRESHOLD -> {
                fontSizeSp = stepLogFontSize(fontSizeSp, -1)
                zoomAccum = 1f
            }
        }
    }

    LaunchedEffect(follow, lines.size) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            val scrollable = listState.canScrollForward || listState.canScrollBackward
            when {
                interaction is DragInteraction.Start && scrollable -> follow = false
                (interaction is DragInteraction.Stop || interaction is DragInteraction.Cancel) && atEnd -> follow = true
            }
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
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = true)
                .transformable(
                    state = zoomState,
                    canPan = { false },
                    lockRotationOnZoomPan = true,
                ),
        ) {
            if (lines.isEmpty()) {
                Text(emptyText, style = lineStyle, color = ForgePalette.textMuted)
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = ForgeSpacing.micro),
                        verticalArrangement = Arrangement.spacedBy(ForgeSpacing.hairline),
                    ) {
                        itemsIndexed(lines, key = { _, line -> line.id }) { _, line ->
                            ForgeLogLine(line, lineStyle)
                        }
                    }
                }
            }
            ForgeZoomIndicator(
                zoomPercent = zoomPercent,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = ForgeSpacing.micro),
            )
            if (hasLines) {
                val actionLabel = if (latestActionEnabled) "Go to latest output" else "Copy all output"
                val actionIcon = if (latestActionEnabled) Icons.Filled.ArrowDownward else Icons.Outlined.ContentCopy
                Surface(
                    onClick = {
                        if (latestActionEnabled) {
                            follow = true
                        } else {
                            clipboard.setText(AnnotatedString(lines.toPlainLogText()))
                        }
                    },
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
                        Icon(
                            imageVector = actionIcon,
                            contentDescription = actionLabel,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ForgeLogLine(line: ForgeLogLineVisual, style: TextStyle) {
    val color = logToneColor(line.tone)
    val text = buildAnnotatedString {
        line.time?.let { withStyle(SpanStyle(color = ForgePalette.textMuted)) { append(it); append("  ") } }
        line.tag?.let { withStyle(SpanStyle(color = tagColor(line.tone))) { append(it.padEnd(TAG_WIDTH)); append(' ') } }
        withStyle(SpanStyle(color = color)) { append(line.text) }
    }
    Text(text = text, style = style, modifier = Modifier.fillMaxWidth())
}

private const val TAG_WIDTH = 6
internal const val DEFAULT_LOG_FONT_SIZE_SP = 11.5f
internal const val MIN_LOG_FONT_SIZE_SP = 8f
internal const val MAX_LOG_FONT_SIZE_SP = 24f
private const val LOG_FONT_STEP_SP = 1f
private const val LOG_LINE_HEIGHT_RATIO = 1.4f
private const val ZOOM_IN_THRESHOLD = 1.10f
private const val ZOOM_OUT_THRESHOLD = 0.90f

internal fun stepLogFontSize(current: Float, direction: Int): Float =
    (current + direction.coerceIn(-1, 1) * LOG_FONT_STEP_SP)
        .coerceIn(MIN_LOG_FONT_SIZE_SP, MAX_LOG_FONT_SIZE_SP)

internal fun logZoomPercent(fontSizeSp: Float): Int =
    (fontSizeSp / DEFAULT_LOG_FONT_SIZE_SP * 100f).roundToInt()

/** The action is meaningful only while the user has deliberately paused away from the tail. */
internal fun canJumpToLatest(
    hasLines: Boolean,
    following: Boolean,
    canScrollForward: Boolean,
): Boolean = hasLines && !following && canScrollForward

internal fun List<ForgeLogLineVisual>.toPlainLogText(): String = joinToString("\n") { line ->
    buildString {
        line.time?.let { append(it); append("  ") }
        line.tag?.let { append(it.padEnd(TAG_WIDTH)); append(' ') }
        append(line.text)
    }
}

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
