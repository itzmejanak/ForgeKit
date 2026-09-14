package com.forgekit.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Lifecycle of one step in a multi-step operation (e.g. one dependency being provisioned). */
public enum class ForgeStepState { PENDING, ACTIVE, DONE, SATISFIED, FAILED }

/** Presentation-only step row; domain → visual mapping stays in the caller's module. */
public data class ForgeStepVisual(
    public val key: String,
    public val label: String,
    public val state: ForgeStepState,
    public val status: String,
    public val detail: String? = null,
)

/** Ordered step list: state indicator, mono label, status on the right, optional detail. */
@Composable
public fun ForgeStepList(
    steps: List<ForgeStepVisual>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ForgeSpacing.compact)) {
        steps.forEach { step -> ForgeStepRow(step) }
    }
}

@Composable
private fun ForgeStepRow(step: ForgeStepVisual) {
    val statusColor = stepColor(step.state)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ForgeSpacing.compact + ForgeSpacing.hairline),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = ForgeSpacing.hairline).size(14.dp), contentAlignment = Alignment.Center) {
            when (step.state) {
                ForgeStepState.ACTIVE -> CircularProgressIndicator(
                    color = ForgePalette.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(12.dp),
                )
                ForgeStepState.DONE -> Icon(Icons.Outlined.CheckCircle, null, tint = statusColor, modifier = Modifier.size(14.dp))
                ForgeStepState.FAILED -> Icon(Icons.Outlined.ErrorOutline, null, tint = statusColor, modifier = Modifier.size(14.dp))
                ForgeStepState.SATISFIED, ForgeStepState.PENDING -> StatusDot(statusColor)
            }
        }
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ForgeSpacing.compact)) {
                Text(
                    step.label,
                    style = ForgeTypography.monoValue,
                    color = if (step.state == ForgeStepState.PENDING) ForgePalette.textSecondary else ForgePalette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(step.status, style = ForgeTypography.badge, color = statusColor, maxLines = 1)
            }
            step.detail?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = ForgeTypography.monoValue,
                    color = if (step.state == ForgeStepState.FAILED) ForgePalette.error else ForgePalette.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun stepColor(state: ForgeStepState): Color = when (state) {
    ForgeStepState.PENDING -> ForgePalette.textMuted
    ForgeStepState.ACTIVE -> ForgePalette.primary
    ForgeStepState.DONE, ForgeStepState.SATISFIED -> ForgePalette.success
    ForgeStepState.FAILED -> ForgePalette.error
}
