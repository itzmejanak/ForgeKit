package com.forgekit.ui.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Semantic status → chip tone mapping (statuses are the plugin lifecycle's, not ad-hoc). */
public enum class StatusTone {
    NEUTRAL,
    READY,
    UNRESOLVED,
    WARNING,
    ERROR,
    INFO,
}

/** Single source of truth for [StatusTone] → (foreground, container) colors. */
internal fun toneColors(tone: StatusTone): Pair<Color, Color> = when (tone) {
    StatusTone.READY -> ForgePalette.success to ForgePalette.successContainer
    StatusTone.UNRESOLVED -> ForgePalette.warning to ForgePalette.warningContainer
    StatusTone.WARNING -> ForgePalette.warning to ForgePalette.warningContainer
    StatusTone.ERROR -> ForgePalette.error to ForgePalette.errorContainer
    StatusTone.INFO -> ForgePalette.info to ForgePalette.infoContainer
    StatusTone.NEUTRAL -> ForgePalette.textSecondary to ForgePalette.surfaceElevated
}

/** Foreground color of a [StatusTone] (icons, accent text, status dots). */
public fun toneColor(tone: StatusTone): Color = toneColors(tone).first

/**
 * Status chip / pill. [tone] drives the color; the label is rendered in
 * mono — statuses are machine states, not prose. Chips are never buttons.
 */
@Composable
public fun StatusChip(label: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val (fg, bg) = toneColors(tone)
    Surface(
        modifier = modifier,
        shape = ForgeShapes.chip,
        color = bg,
    ) {
        Text(
            text = label.uppercase(),
            style = ForgeTypography.badge,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = ForgeSpacing.micro),
        )
    }
}

/**
 * A mono metadata line led by a state word in its tone color, e.g. `READY · 2 actions · UNSIGNED`.
 * The state stays readable text (not color alone); [pulse] breathes the word while in flight.
 */
@Composable
public fun ForgeStatusLine(
    status: String,
    tone: StatusTone,
    detail: String?,
    modifier: Modifier = Modifier,
    pulse: Boolean = false,
) {
    val alpha = if (pulse) {
        val transition = rememberInfiniteTransition(label = "status-pulse")
        val value by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "status-word",
        )
        value
    } else {
        1f
    }
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = toneColor(tone).copy(alpha = alpha), fontWeight = FontWeight.Medium)) {
            append(status.uppercase())
        }
        if (!detail.isNullOrBlank()) {
            withStyle(SpanStyle(color = ForgePalette.textSecondary)) {
                append(" · ")
                append(detail)
            }
        }
    }
    Text(
        text = text,
        style = ForgeTypography.monoValue,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Small round status dot (health lists, job rows, step lists). */
@Composable
public fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(8.dp),
        shape = ForgeShapes.chip,
        color = color,
    ) {}
}

/** Mono choice chip: selected glows ember; unselected sits quiet on Surface2. */
@Composable
public fun ForgeChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(ForgeSpacing.compact)
    val bg = if (selected) ForgePalette.primary.copy(alpha = 0.16f) else ForgePalette.surfaceElevated
    val borderColor = if (selected) ForgePalette.primary else ForgePalette.outline
    Row(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = ForgeTypography.badge,
            color = if (selected) ForgePalette.primary else ForgePalette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
