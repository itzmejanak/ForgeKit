package com.forgekit.ui.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Snackbar visuals wired to the Forge [tone]. Standard [SnackbarHost] handles
 * queueing, duration, swipe-away and tap-outside dismissal — this only carries
 * the severity through to the host's presenter.
 */
public data class ForgeToastVisuals(
    override val message: String,
    public val tone: StatusTone = StatusTone.NEUTRAL,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
) : SnackbarVisuals

/** Tone → leading icon (Outlined family, same language the app uses elsewhere). */
internal fun toneIcon(tone: StatusTone): ImageVector? = when (tone) {
    StatusTone.READY -> Icons.Outlined.CheckCircle
    StatusTone.WARNING, StatusTone.UNRESOLVED -> Icons.Outlined.Warning
    StatusTone.ERROR -> Icons.Outlined.ErrorOutline
    StatusTone.INFO -> Icons.Outlined.Info
    StatusTone.NEUTRAL -> null
}

/**
 * Edge-to-edge, square notification bar. SnackbarHost still owns queueing and
 * dismissal; this presenter intentionally has no floating-card geometry.
 */
@Composable
public fun ForgeToast(data: SnackbarData) {
    val visuals = data.visuals
    val tone = (visuals as? ForgeToastVisuals)?.tone ?: StatusTone.NEUTRAL
    val (fg, _) = toneColors(tone)
    val icon = toneIcon(tone)

    Surface(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RectangleShape,
        color = ForgePalette.surfaceElevated,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = ForgeSpacing.cardInner, vertical = ForgeSpacing.compact),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(ForgeSpacing.compact))
            }
            Text(
                text = visuals.message,
                style = ForgeTypography.body,
                color = ForgePalette.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val actionLabel = visuals.actionLabel
            if (actionLabel != null) {
                Spacer(Modifier.width(ForgeSpacing.compact))
                Text(
                    text = actionLabel,
                    style = ForgeTypography.labelLarge,
                    color = fg,
                    modifier = Modifier.clickable { data.performAction() }.padding(horizontal = ForgeSpacing.compact),
                )
            }
        }
    }
}
