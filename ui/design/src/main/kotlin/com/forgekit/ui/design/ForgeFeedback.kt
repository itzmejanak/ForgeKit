package com.forgekit.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Centered empty-state placeholder: icon, title, optional mono message,
 * optional accent detail line, and an optional action. Use the default
 * variant for whole-tab empties and [compact] for in-flow regions.
 */
@Composable
public fun ForgeEmptyState(
    icon: ImageVector,
    title: String,
    message: String? = null,
    detail: String? = null,
    action: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    tint: Color = ForgePalette.textMuted,
) {
    ForgeStateLayout(
        indicator = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(if (compact) 24.dp else 44.dp),
            )
        },
        title = title,
        message = message,
        detail = detail,
        action = action,
        modifier = modifier,
        compact = compact,
    )
}

/** Centered in-flight state (inspecting, loading): spinner, title, optional mono message. */
@Composable
public fun ForgeLoadingState(
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
) {
    ForgeStateLayout(
        indicator = { CircularProgressIndicator(color = ForgePalette.primary, modifier = Modifier.size(36.dp)) },
        title = title,
        message = message,
        detail = null,
        action = null,
        modifier = modifier,
        compact = false,
    )
}

@Composable
private fun ForgeStateLayout(
    indicator: @Composable () -> Unit,
    title: String,
    message: String?,
    detail: String?,
    action: (@Composable () -> Unit)?,
    modifier: Modifier,
    compact: Boolean,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (compact) ForgeSpacing.cardInner else ForgeSpacing.gutter * 2,
                vertical = if (compact) ForgeSpacing.compact else 48.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        indicator()
        Spacer(Modifier.height(if (compact) ForgeSpacing.compact else 14.dp))
        Text(
            text = title,
            style = ForgeTypography.labelLarge,
            color = ForgePalette.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Spacer(Modifier.height(if (compact) ForgeSpacing.micro else ForgeSpacing.compact))
            Text(
                text = message,
                style = ForgeTypography.monoValue,
                color = ForgePalette.textMuted,
                textAlign = TextAlign.Center,
            )
        }
        if (detail != null) {
            Spacer(Modifier.height(ForgeSpacing.compact))
            Text(
                text = detail,
                style = ForgeTypography.monoValue,
                color = ForgePalette.primary,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(ForgeSpacing.rowGap))
            action()
        }
    }
}

/**
 * The one progress bar. The track uses [ForgePalette.track] so it stays visible on
 * card surfaces; [fraction] `null` renders an indeterminate bar. [label] sits under
 * the bar on the left, [trailing] (e.g. `2 / 3`) on the right.
 */
@Composable
public fun ForgeProgressBar(
    fraction: Float?,
    modifier: Modifier = Modifier,
    label: String? = null,
    trailing: String? = null,
    tone: StatusTone? = null,
) {
    val color = tone?.let(::toneColor) ?: ForgePalette.primary
    Column(modifier.fillMaxWidth()) {
        if (fraction == null) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(ForgeShapes.chip),
                color = color,
                trackColor = ForgePalette.track,
                strokeCap = StrokeCap.Round,
            )
        } else {
            val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "forge-progress")
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(ForgeShapes.chip),
                color = color,
                trackColor = ForgePalette.track,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        if (label != null || trailing != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = ForgeSpacing.micro + ForgeSpacing.hairline),
                horizontalArrangement = Arrangement.spacedBy(ForgeSpacing.compact),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label.orEmpty(),
                    style = ForgeTypography.monoValue,
                    color = ForgePalette.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                trailing?.let {
                    Text(it, style = ForgeTypography.badge, color = ForgePalette.textMuted, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Tinted message box for errors, warnings and notes that must stand out from
 * surrounding metadata (rejection reasons, failure causes, runtime errors).
 */
@Composable
public fun ForgeNoticeBox(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val (fg, bg) = toneColors(tone)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ForgeShapes.control)
            .background(bg)
            .padding(ForgeSpacing.rowGap),
        horizontalArrangement = Arrangement.spacedBy(ForgeSpacing.compact + ForgeSpacing.hairline),
    ) {
        toneIcon(tone)?.let { icon ->
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ForgeSpacing.hairline)) {
            title?.let { Text(it, style = ForgeTypography.labelLarge, color = fg) }
            Text(text, style = ForgeTypography.monoValue, color = if (title == null) fg else ForgePalette.textPrimary)
        }
    }
}
