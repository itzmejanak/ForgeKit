package com.forgekit.app.screens.importflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.forgekit.app.screens.component.ProvisioningLog
import com.forgekit.app.screens.component.ProvisioningStatusCard
import com.forgekit.plugin.api.PluginDescriptor
import com.forgekit.plugin.api.PluginStatus
import com.forgekit.ui.design.ForgeButtonRow
import com.forgekit.ui.design.ForgePrimaryButton
import com.forgekit.ui.design.ForgeSecondaryButton
import com.forgekit.ui.design.ForgeSpacing

/**
 * Step 3: one surface from approval to result — status card on top, the provider
 * log filling the rest of the page. It never jumps away when the run ends, so the
 * evidence stays next to the outcome.
 */
@Composable
internal fun ImportInstallContent(
    descriptor: PluginDescriptor,
    phase: ImportPhase,
    modifier: Modifier = Modifier,
) {
    val summary = phase.statusSummary() ?: return
    val live = phase.liveProvisioning()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ForgeSpacing.rowGap)) {
        ProvisioningStatusCard(
            summary = summary,
            provisioning = live,
            title = descriptor.name,
            subtitle = "${descriptor.id.raw} · ${descriptor.version.raw}",
        )
        ProvisioningLog(live, Modifier.weight(1f))
    }
}

/** Pinned install actions: what the user can do next in each outcome. */
@Composable
internal fun ImportInstallActions(
    phase: ImportPhase,
    hasJob: Boolean,
    onOpenPlugin: (PluginDescriptor) -> Unit,
    onOpenJob: () -> Unit,
    onRetry: () -> Unit,
    onChooseAnother: () -> Unit,
) {
    when (phase) {
        is ImportPhase.Provisioning -> ForgeSecondaryButton(text = "View job log", enabled = hasJob, onClick = onOpenJob)
        is ImportPhase.Installed -> {
            ForgePrimaryButton(
                text = if (phase.descriptor.status == PluginStatus.READY) "Open plugin" else "Open plugin to retry",
                onClick = { onOpenPlugin(phase.descriptor) },
            )
            ForgeButtonRow {
                ForgeSecondaryButton(text = "Job log", enabled = hasJob, onClick = onOpenJob, modifier = Modifier.weight(1f))
                ForgeSecondaryButton(text = "New import", onClick = onChooseAnother, modifier = Modifier.weight(1f))
            }
        }
        is ImportPhase.Failed -> {
            ForgePrimaryButton(text = "Retry install", onClick = onRetry)
            ForgeButtonRow {
                ForgeSecondaryButton(text = "Job log", enabled = hasJob, onClick = onOpenJob, modifier = Modifier.weight(1f))
                ForgeSecondaryButton(text = "Choose file", onClick = onChooseAnother, modifier = Modifier.weight(1f))
            }
        }
        ImportPhase.Idle, is ImportPhase.Inspecting, ImportPhase.Review -> Unit
    }
}
