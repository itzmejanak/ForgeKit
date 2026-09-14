package com.forgekit.app.screens.importflow

import com.forgekit.app.ForgeViewModel.ProvisioningState
import com.forgekit.app.screens.component.ProvisioningOutcome
import com.forgekit.app.screens.component.ProvisioningSummary
import com.forgekit.app.screens.component.summary
import com.forgekit.plugin.api.PluginDescriptor
import com.forgekit.plugin.api.PluginStatus
import com.forgekit.ui.design.StatusTone
import java.nio.file.Path

/**
 * What the user asked for on the current review, and what came back. This is the
 * only screen-local import state; everything else is derived from live ViewModel state.
 */
internal sealed interface ImportApproval {
    data object NotRequested : ImportApproval

    /**
     * Approval accepted by the ViewModel. [previousJobId] is the provisioning job that was
     * already recorded for this plugin id before approval, so its stale snapshot is ignored.
     */
    data class Started(val previousJobId: String?) : ImportApproval

    data class Installed(val descriptor: PluginDescriptor) : ImportApproval

    data class Failed(val previousJobId: String?) : ImportApproval
}

/** The import page's visible phase (the review itself stays on the overlay, not in the phase). */
internal sealed interface ImportPhase {
    data object Idle : ImportPhase

    data class Inspecting(val path: Path) : ImportPhase

    data object Review : ImportPhase

    /** Approved; install + provisioning in flight (provisioning is null until the job starts). */
    data class Provisioning(val provisioning: ProvisioningState?) : ImportPhase

    /** Registered: [descriptor] status tells READY from UNRESOLVED. */
    data class Installed(
        val descriptor: PluginDescriptor,
        val provisioning: ProvisioningState?,
    ) : ImportPhase

    /** The install threw before registration; the pending import is still retryable. */
    data class Failed(val provisioning: ProvisioningState?) : ImportPhase
}

internal fun deriveImportPhase(
    pendingPath: Path?,
    hasReview: Boolean,
    approval: ImportApproval,
    provisioning: ProvisioningState?,
): ImportPhase {
    if (pendingPath != null) return ImportPhase.Inspecting(pendingPath)
    if (!hasReview) return ImportPhase.Idle
    return when (approval) {
        is ImportApproval.Installed -> ImportPhase.Installed(approval.descriptor, provisioning)
        is ImportApproval.Started -> ImportPhase.Provisioning(provisioning.freshSince(approval.previousJobId))
        is ImportApproval.Failed -> ImportPhase.Failed(provisioning.freshSince(approval.previousJobId))
        ImportApproval.NotRequested ->
            // A run for this package that is already live (e.g. the page was recomposed
            // from scratch mid-install) keeps showing progress instead of a stale Review.
            if (provisioning?.active == true) ImportPhase.Provisioning(provisioning) else ImportPhase.Review
    }
}

/** The run this phase shows; any snapshot from an earlier run of the same plugin id is already dropped. */
internal fun ImportPhase.liveProvisioning(): ProvisioningState? = when (this) {
    is ImportPhase.Provisioning -> provisioning
    is ImportPhase.Installed -> provisioning
    is ImportPhase.Failed -> provisioning
    ImportPhase.Idle, is ImportPhase.Inspecting, ImportPhase.Review -> null
}

/** Drops a snapshot left over from an earlier run of the same plugin id. */
private fun ProvisioningState?.freshSince(previousJobId: String?): ProvisioningState? =
    this?.takeIf { it.jobId != null && it.jobId != previousJobId }

/** Header subtitle: where the user is in the three-step import. */
internal fun ImportPhase.stepLabel(): String = when (this) {
    ImportPhase.Idle -> "step 1 of 3 · choose a document"
    is ImportPhase.Inspecting -> "step 1 of 3 · inspecting"
    ImportPhase.Review -> "step 2 of 3 · review facts"
    is ImportPhase.Provisioning, is ImportPhase.Installed, is ImportPhase.Failed -> "step 3 of 3 · install"
}

/**
 * Status card content for the install step. The registered descriptor has the final
 * word on READY vs UNRESOLVED; a failure before the job started still gets a card.
 */
internal fun ImportPhase.statusSummary(): ProvisioningSummary? = when (this) {
    is ImportPhase.Provisioning -> (provisioning ?: ProvisioningState(active = true)).summary()
    is ImportPhase.Failed -> {
        val base = (provisioning ?: ProvisioningState(failedReason = "Install stopped before provisioning started")).summary()
        if (base.outcome == ProvisioningOutcome.FAILED) {
            base
        } else {
            base.copy(outcome = ProvisioningOutcome.FAILED, headline = "Install failed", chip = "FAILED", tone = StatusTone.ERROR)
        }
    }
    is ImportPhase.Installed -> {
        val base = (provisioning ?: ProvisioningState()).summary()
        if (descriptor.status == PluginStatus.READY) {
            base.copy(outcome = ProvisioningOutcome.SUCCEEDED, headline = "Installed and ready", chip = "READY", tone = StatusTone.READY, fraction = 1f)
        } else {
            base.copy(
                outcome = ProvisioningOutcome.PARTIAL,
                headline = "Installed · environment unresolved",
                chip = "UNRESOLVED",
                tone = StatusTone.UNRESOLVED,
                detail = if (base.outcome == ProvisioningOutcome.SUCCEEDED) "Open the plugin to retry provisioning" else base.detail,
            )
        }
    }
    ImportPhase.Idle, is ImportPhase.Inspecting, ImportPhase.Review -> null
}
