package com.forgekit.app.screens.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.forgekit.app.ForgeViewModel.ProvisioningState
import com.forgekit.ui.design.ForgeCard
import com.forgekit.ui.design.ForgeDivider
import com.forgekit.ui.design.ForgeLogConsole
import com.forgekit.ui.design.ForgePalette
import com.forgekit.ui.design.ForgeProgressBar
import com.forgekit.ui.design.ForgeStepList
import com.forgekit.ui.design.ForgeTypography
import com.forgekit.ui.design.MonoText
import com.forgekit.ui.design.StatusChip
import com.forgekit.ui.design.toneColor

/**
 * Status card of one provisioning run: headline + state chip, progress with the
 * current step, and the per-dependency step list. [title]/[subtitle] identify the
 * package when the page header does not already (import flow).
 */
@Composable
internal fun ProvisioningStatusCard(
    summary: ProvisioningSummary,
    provisioning: ProvisioningState?,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
) {
    val steps = remember(provisioning?.dependencies) {
        provisioning?.dependencies.orEmpty().map { it.toStepVisual() }
    }
    val terminal = summary.outcome != ProvisioningOutcome.RUNNING
    ForgeCard(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                if (title != null) {
                    Text(title, style = ForgeTypography.labelLarge, color = ForgePalette.textPrimary)
                    subtitle?.let { MonoText(it) }
                }
                Text(
                    summary.headline,
                    style = if (title == null) ForgeTypography.labelLarge else ForgeTypography.caption,
                    color = if (terminal) toneColor(summary.tone) else ForgePalette.textSecondary,
                )
            }
            StatusChip(summary.chip, summary.tone)
        }
        ForgeProgressBar(
            fraction = summary.fraction,
            label = summary.detail,
            trailing = summary.counter,
            tone = if (terminal) summary.tone else null,
        )
        if (steps.isNotEmpty()) {
            ForgeDivider()
            ForgeStepList(
                steps = steps,
                modifier = Modifier.heightIn(max = 168.dp).verticalScroll(rememberScrollState()),
            )
        }
    }
}

/** Provider log console for one run. Size it from the caller (weight or fixed height). */
@Composable
internal fun ProvisioningLog(
    provisioning: ProvisioningState?,
    modifier: Modifier = Modifier,
) {
    val events = provisioning?.events.orEmpty()
    val lines = remember(events) { events.map { it.toLogLine() } }
    ForgeLogConsole(
        lines = lines,
        title = "provider log",
        emptyText = "Waiting for the dependency plan…",
        modifier = modifier,
    )
}

/** One-line progress for list cards (Home, Plugins, Jobs) while a run is active. */
@Composable
internal fun ProvisioningInline(
    provisioning: ProvisioningState,
    modifier: Modifier = Modifier,
) {
    val summary = provisioning.summary()
    ForgeProgressBar(
        fraction = summary.fraction,
        label = summary.detail,
        trailing = summary.counter,
        modifier = modifier,
    )
}
