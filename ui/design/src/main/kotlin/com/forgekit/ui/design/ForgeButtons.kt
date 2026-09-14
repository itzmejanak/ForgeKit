package com.forgekit.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Primary action button: the single ember affordance per screen region. */
@Composable
public fun ForgePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ForgeShapes.control,
        colors = ButtonDefaults.buttonColors(
            containerColor = ForgePalette.primary,
            contentColor = ForgePalette.onPrimary,
            disabledContainerColor = ForgePalette.primary.copy(alpha = 0.35f),
            disabledContentColor = ForgePalette.onPrimary.copy(alpha = 0.5f),
        ),
        modifier = modifier.fillMaxWidth().height(ForgeSpacing.buttonHeight),
    ) {
        ForgeButtonContent(text, icon)
    }
}

/** Secondary action: quiet filled surface with the accent, never competing with the primary. */
@Composable
public fun ForgeSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ForgeShapes.control,
        colors = ButtonDefaults.buttonColors(
            containerColor = ForgePalette.surface,
            contentColor = ForgePalette.primary,
            disabledContainerColor = ForgePalette.surface.copy(alpha = 0.5f),
            disabledContentColor = ForgePalette.textMuted,
        ),
        modifier = modifier.fillMaxWidth().height(ForgeSpacing.buttonHeight),
    ) {
        ForgeButtonContent(text, icon)
    }
}

/** Destructive action: outlined in the error tone (removal, rejection). */
@Composable
public fun ForgeDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ForgeShapes.control,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = ForgePalette.error,
            disabledContentColor = ForgePalette.textMuted,
        ),
        border = BorderStroke(1.dp, ForgePalette.error.copy(alpha = 0.55f)),
        modifier = modifier.fillMaxWidth().height(ForgeSpacing.buttonHeight),
    ) {
        ForgeButtonContent(text, null)
    }
}

/**
 * Compact inline text action for secondary, in-card commands (e.g. Cancel on a job row).
 * [tone] colors it (error for stop/cancel); defaults to the ember accent.
 */
@Composable
public fun ForgeTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: StatusTone? = null,
    icon: ImageVector? = null,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        shape = ForgeShapes.control,
        contentPadding = PaddingValues(horizontal = ForgeSpacing.rowGap, vertical = ForgeSpacing.micro),
        colors = ButtonDefaults.textButtonColors(contentColor = tone?.let(::toneColor) ?: ForgePalette.primary),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(ForgeSpacing.micro + ForgeSpacing.hairline))
        }
        Text(text = text, style = ForgeTypography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun ForgeButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(ForgeSpacing.compact))
    }
    Text(text = text, style = ForgeTypography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
