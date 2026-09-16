package com.forgekit.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The one tooltip-backed icon action used by console chrome and page headers.
 * A single implementation keeps touch targets, tooltip colors and progress feedback
 * consistent instead of letting each screen invent a smaller icon button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun ForgeTooltipIconButton(
    icon: ImageVector,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(
                shape = ForgeShapes.control,
                containerColor = ForgePalette.surfaceElevated,
                contentColor = ForgePalette.textPrimary,
            ) {
                Text(tooltip, style = ForgeTypography.caption)
            }
        },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled && !busy,
            modifier = modifier,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = ForgePalette.primary,
                    strokeWidth = ForgeSpacing.hairline,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = tooltip,
                    tint = if (enabled) ForgePalette.textSecondary else ForgePalette.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Short-lived zoom feedback shared by the terminal and log consoles. It deliberately
 * appears only after the value changes, so opening a screen never creates decorative noise.
 */
@Composable
public fun ForgeZoomIndicator(
    zoomPercent: Int,
    modifier: Modifier = Modifier,
) {
    var observedPercent by remember { mutableIntStateOf(zoomPercent) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(zoomPercent) {
        if (zoomPercent != observedPercent) {
            observedPercent = zoomPercent
            visible = true
            delay(ZOOM_FEEDBACK_MILLIS)
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + scaleIn(initialScale = 0.94f),
        exit = fadeOut() + scaleOut(targetScale = 0.94f),
    ) {
        Surface(
            shape = ForgeShapes.control,
            color = ForgePalette.surfaceElevated,
            contentColor = ForgePalette.textPrimary,
            shadowElevation = 4.dp,
        ) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                Text("$zoomPercent%", style = ForgeTypography.badge)
            }
        }
    }
}

private const val ZOOM_FEEDBACK_MILLIS = 900L
