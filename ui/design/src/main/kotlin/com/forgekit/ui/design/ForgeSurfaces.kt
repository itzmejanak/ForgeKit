package com.forgekit.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Surfaces and text primitives of the component kit. Every screen composes from
 * these — screens never re-style raw Material components, so the design language
 * stays consistent and a token change is a one-file change.
 */

/** Charcoal-green gradient canvas with an ember glow at the top end. */
@Composable
public fun ForgeBackground(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(ForgePalette.background)) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        ForgePalette.background,
                        ForgePalette.backgroundDeep,
                        ForgePalette.background,
                    ),
                ),
            ),
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(340.dp)
                .background(
                    Brush.radialGradient(
                        listOf(ForgePalette.primary.copy(alpha = 0.13f), Color.Transparent),
                    ),
                ),
        )
        content()
    }
}

/**
 * Elevated dark card with the signature large radius (no border). Pass [onClick] for
 * tappable cards so the ripple is clipped to the card shape.
 */
@Composable
public fun ForgeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ForgeShapes.card)
            .background(ForgePalette.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(ForgeSpacing.cardInner),
        verticalArrangement = Arrangement.spacedBy(ForgeSpacing.cardGap),
        content = content,
    )
}

/** Uppercase letter-spaced mono section header (e.g. `IDENTITY`, `ACTIONS`). */
@Composable
public fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = ForgeTypography.sectionHeader,
        color = ForgePalette.textMuted,
        modifier = modifier,
    )
}

/** One metadata line: quiet mono label on the left, mono value on the right. */
@Composable
public fun MetadataLine(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = ForgeSpacing.hairline),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            style = ForgeTypography.labelSmall,
            color = ForgePalette.textMuted,
            maxLines = 1,
            modifier = Modifier.weight(0.38f),
        )
        Text(
            text = value,
            style = ForgeTypography.monoValue,
            color = ForgePalette.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.62f).padding(start = ForgeSpacing.rowGap),
        )
    }
}

/** Thin divider inside cards. */
@Composable
public fun ForgeDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.fillMaxWidth().height(1.dp),
        color = ForgePalette.outline,
    )
}

/** Quiet mono text block (ids, hashes, paths). */
@Composable
public fun MonoText(text: String, modifier: Modifier = Modifier, maxLines: Int = 1, color: Color = ForgePalette.textSecondary) {
    Text(
        text = text,
        style = ForgeTypography.monoValue,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
