package com.forgekit.app.screens.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.forgekit.app.ForgeViewModel.ProvisioningState
import com.forgekit.plugin.api.PluginDescriptor
import com.forgekit.ui.design.ForgeCard
import com.forgekit.ui.design.ForgePalette
import com.forgekit.ui.design.ForgeSpacing
import com.forgekit.ui.design.ForgeStatusLine
import com.forgekit.ui.design.ForgeTypography
import com.forgekit.ui.design.MetadataLine
import com.forgekit.ui.design.MonoText
import com.forgekit.ui.design.StatusChip
import com.forgekit.ui.design.StatusTone

/**
 * The one plugin card (Home registry and Plugins list): name, id · version, and a meta line
 * led by the lifecycle state word in its color (pulsing while a run is active, with inline
 * provisioning progress below). [showDetails] adds tags and runtime.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PluginSummaryCard(
    plugin: PluginDescriptor,
    provisioning: ProvisioningState?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDetails: Boolean = false,
) {
    val active = provisioning?.takeIf { it.active }
    ForgeCard(modifier, onClick = onClick) {
        Column {
            Text(
                plugin.name,
                color = ForgePalette.textPrimary,
                style = ForgeTypography.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MonoText("${plugin.id.raw} · ${plugin.version.raw}")
            ForgeStatusLine(
                status = if (active != null) "provisioning" else plugin.status.name,
                tone = if (active != null) StatusTone.INFO else plugin.status.tone(),
                detail = "${plugin.capabilities.size} actions · ${plugin.trust.name}",
                pulse = active != null,
            )
        }
        active?.let { ProvisioningInline(it) }
        if (showDetails) {
            if (plugin.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(ForgeSpacing.micro + ForgeSpacing.hairline),
                    verticalArrangement = Arrangement.spacedBy(ForgeSpacing.micro),
                ) {
                    plugin.tags.take(4).forEach { tag -> StatusChip(label = tag, tone = StatusTone.NEUTRAL) }
                }
            }
            MetadataLine("runtime", "${plugin.runtimeType}${plugin.runtimeVersionRequirement?.let { " $it" } ?: ""}")
        }
    }
}
