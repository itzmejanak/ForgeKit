package com.forgekit.app.screens.importflow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.forgekit.app.ForgeViewModel
import com.forgekit.app.navigation.Overlay
import com.forgekit.job.api.JobId
import com.forgekit.plugin.api.PluginDescriptor
import com.forgekit.plugin.resolver.DependencyPlan
import com.forgekit.ui.design.ForgeLoadingState
import com.forgekit.ui.design.ForgePage
import com.forgekit.ui.design.ForgePageHeader
import java.nio.file.Path

/**
 * The dedicated import flow page: choose → inspect → review → install, all on one
 * page. Only the approval request is screen-local ([ImportApproval]); the visible
 * phase is derived from it plus live ViewModel state ([deriveImportPhase]), and every
 * phase ends with a way forward in the pinned action bar.
 */
@Composable
public fun ImportScreen(
    overlay: Overlay.Import,
    livePlans: List<DependencyPlan>?,
    provisioning: ForgeViewModel.ProvisioningState?,
    onSelectDocument: () -> Unit,
    onApprove: (onInstalled: (PluginDescriptor?) -> Unit) -> Boolean,
    onReject: () -> Unit,
    onOpenPlugin: (PluginDescriptor) -> Unit,
    onOpenJob: (JobId) -> Unit,
    onInspect: (Path) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val review = overlay.review
    var approval by remember(review) { mutableStateOf<ImportApproval>(ImportApproval.NotRequested) }

    LaunchedEffect(overlay.pendingPath) {
        overlay.pendingPath?.let(onInspect)
    }

    val phase = deriveImportPhase(overlay.pendingPath, review != null, approval, provisioning)
    val approve = {
        val previousJobId = provisioning?.jobId
        val started = onApprove { descriptor ->
            approval = if (descriptor != null) {
                ImportApproval.Installed(descriptor)
            } else {
                ImportApproval.Failed(previousJobId)
            }
        }
        if (started) approval = ImportApproval.Started(previousJobId)
    }
    val liveJobId = phase.liveProvisioning()?.jobId
    val openJob: () -> Unit = { liveJobId?.let { onOpenJob(JobId(it)) } }

    ForgePage(
        modifier = modifier,
        header = { ForgePageHeader(title = "Import package", subtitle = phase.stepLabel(), onBack = onBack) },
        footer = when {
            review == null -> null
            phase == ImportPhase.Review -> {
                {
                    ImportReviewActions(
                        valid = review.report.valid,
                        onApprove = approve,
                        onChooseAnother = onSelectDocument,
                        onReject = onReject,
                    )
                }
            }
            else -> {
                {
                    ImportInstallActions(
                        phase = phase,
                        hasJob = liveJobId != null,
                        onOpenPlugin = onOpenPlugin,
                        onOpenJob = openJob,
                        onRetry = approve,
                        onChooseAnother = onSelectDocument,
                    )
                }
            }
        },
    ) {
        when (phase) {
            ImportPhase.Idle -> ImportIdleContent(onSelectDocument, Modifier.weight(1f))
            is ImportPhase.Inspecting -> ForgeLoadingState(
                title = "Inspecting package",
                message = "Checking the archive, manifest and signature. No package code runs.",
            )
            ImportPhase.Review -> review?.let { ImportReviewContent(it, livePlans, Modifier.weight(1f)) }
            is ImportPhase.Provisioning, is ImportPhase.Installed, is ImportPhase.Failed -> review?.let {
                ImportInstallContent(
                    descriptor = it.descriptor,
                    phase = phase,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
