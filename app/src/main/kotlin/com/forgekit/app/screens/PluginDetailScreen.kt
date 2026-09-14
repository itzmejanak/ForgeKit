package com.forgekit.app.screens

import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.forgekit.app.ForgeViewModel
import com.forgekit.app.screens.component.ProvisioningStatusCard
import com.forgekit.app.screens.component.summary
import com.forgekit.app.screens.component.toDependencyState
import com.forgekit.app.screens.component.toStepVisual
import com.forgekit.job.api.JobId
import com.forgekit.plugin.api.PluginCapability
import com.forgekit.plugin.api.PluginDescriptor
import com.forgekit.plugin.api.PluginStatus
import com.forgekit.plugin.resolver.DependencyPlan
import com.forgekit.ui.design.ForgeActionBar
import com.forgekit.ui.design.ForgeCard
import com.forgekit.ui.design.ForgeDangerButton
import com.forgekit.ui.design.ForgeEmptyState
import com.forgekit.ui.design.ForgeLazyPage
import com.forgekit.ui.design.ForgePageHeader
import com.forgekit.ui.design.ForgePalette
import com.forgekit.ui.design.ForgePrimaryButton
import com.forgekit.ui.design.ForgeSecondaryButton
import com.forgekit.ui.design.ForgeStepList
import com.forgekit.ui.design.ForgeTypography
import com.forgekit.ui.design.MetadataLine
import com.forgekit.ui.design.MonoText
import com.forgekit.ui.design.SectionHeader
import com.forgekit.ui.design.rememberConfirmGate

/**
 * Plugin detail (reference screens 6-7): identity, environment, dependencies with the
 * live provisioning run (status, steps, job log link), declared permissions, and
 * ACTIONS with the run entry point.
 */
@Composable
public fun PluginDetailScreen(
    descriptor: PluginDescriptor,
    onRunAction: (PluginCapability) -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
    onOpenUi: (() -> Unit)?,
    actionCounts: (PluginCapability) -> String,
    viewModel: ForgeViewModel,
    onProvision: (() -> Unit)? = null,
    onOpenJob: (JobId) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val provisioning = viewModel.state.collectAsState().value.provisioning[descriptor.id.raw]
    val activeProvisioning = provisioning?.active == true

    // Real presence plan from the live runtime whenever no run is streaming its own steps.
    var livePlans by remember(descriptor.id.raw, activeProvisioning) { mutableStateOf<List<DependencyPlan>?>(null) }
    LaunchedEffect(descriptor.id.raw, activeProvisioning) {
        livePlans = if (activeProvisioning) null else viewModel.dependencyPlans(descriptor.id)
    }
    val summary = provisioning?.summary()
    val planSteps = livePlans.orEmpty().map { it.toDependencyState().toStepVisual() }
    val actions = descriptor.capabilities.filter { it.kind == "action" }
    val removeGate = rememberConfirmGate()

    ForgeLazyPage(
        modifier = modifier,
        header = {
            ForgePageHeader(
                title = descriptor.name,
                subtitle = "${descriptor.id.raw} · ${descriptor.version.raw}",
                onBack = onBack,
                trailing = {
                    if (onOpenUi != null) {
                        IconButton(onClick = onOpenUi) {
                            Icon(Icons.Outlined.SpaceDashboard, contentDescription = "Open plugin interface", tint = ForgePalette.primary)
                        }
                    }
                },
            )
        },
    ) {
        descriptor.description?.let {
            item { Text(it, style = ForgeTypography.caption, color = ForgePalette.textSecondary) }
        }

        item {
            ForgeCard {
                SectionHeader("identity")
                MetadataLine("status", if (activeProvisioning) "provisioning" else descriptor.status.name.lowercase())
                descriptor.packageSha256?.let { MetadataLine("sha-256", it.take(24)) }
                descriptor.installedPath?.let { MetadataLine("installed", it.take(40)) }
                MetadataLine("trust", descriptor.trust.name)
            }
        }

        item {
            ForgeCard {
                SectionHeader("environment")
                MetadataLine("provider", "forge-native")
                MetadataLine("forge_package", "${descriptor.runtimeType}${descriptor.runtimeVersionRequirement?.let { " $it" } ?: ""}")
            }
        }

        // ---- dependencies: the live/last run of this session, else the real runtime plan.
        // The full provider log lives on the job log page ("View job log"), not inline.
        if (provisioning != null && summary != null) {
            item { ProvisioningStatusCard(summary = summary, provisioning = provisioning) }
        } else if (planSteps.isNotEmpty()) {
            item {
                ForgeCard {
                    SectionHeader("dependencies")
                    ForgeStepList(planSteps)
                }
            }
        }
        val canProvision = descriptor.status == PluginStatus.UNRESOLVED && !activeProvisioning
        if (canProvision || provisioning?.jobId != null) {
            item {
                ForgeActionBar {
                    if (canProvision) {
                        ForgePrimaryButton(text = "Provision now", enabled = onProvision != null, onClick = { onProvision?.invoke() })
                    }
                    provisioning?.jobId?.let { jobId ->
                        ForgeSecondaryButton(text = "View job log", onClick = { onOpenJob(JobId(jobId)) })
                    }
                }
            }
        }

        if (descriptor.requestedPermissions.isNotEmpty()) {
            item {
                ForgeCard {
                    SectionHeader("declared permissions")
                    descriptor.requestedPermissions.forEach { MonoText("• $it", maxLines = 2) }
                }
            }
        }

        item { SectionHeader("actions") }
        if (actions.isEmpty()) {
            item {
                ForgeEmptyState(
                    icon = Icons.Outlined.Bolt,
                    title = "No actions declared",
                    message = "This plugin exposes no runnable actions.",
                    compact = true,
                )
            }
        } else {
            items(actions, key = { it.name }) { action ->
                ForgeCard {
                    Text(action.title ?: action.name, style = ForgeTypography.labelLarge, color = ForgePalette.textPrimary)
                    action.description?.let { Text(it, style = ForgeTypography.caption, color = ForgePalette.textSecondary) }
                    MonoText(actionCounts(action))
                    ForgePrimaryButton(
                        text = "Prepare ${action.title ?: action.name}",
                        enabled = descriptor.status == PluginStatus.READY,
                        onClick = { onRunAction(action) },
                    )
                }
            }
        }

        item { ForgeDangerButton(text = "Remove plugin", onClick = removeGate::request) }
    }

    removeGate.Dialog(
        title = "Remove ${descriptor.name}?",
        message = "The installed package, its permissions and registry record are deleted. " +
            "Job history is kept. Import the package again to reinstall it.",
        confirmLabel = "Remove",
        onConfirm = onRemove,
        destructive = true,
    )
}
