package com.forgekit.app.screens

import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.forgekit.app.ForgeViewModel
import com.forgekit.app.screens.component.JobSummaryCard
import com.forgekit.job.api.JobRecord
import com.forgekit.ui.design.ForgeDangerButton
import com.forgekit.ui.design.ForgeEmptyState
import com.forgekit.ui.design.ForgeLazyPage
import com.forgekit.ui.design.ForgePageHeader
import com.forgekit.ui.design.ForgeSwipeToDelete
import com.forgekit.ui.design.rememberConfirmGate

/**
 * Jobs list + per-job detail (reference screen 2, grown into §30-32: every
 * execution carries its input, timestamps, output or error — the job IS the
 * audit trail). Empty until a validated action is queued.
 */
@Composable
public fun JobsScreen(
    jobs: List<JobRecord>,
    onCancel: (JobRecord) -> Unit,
    onClear: () -> Unit,
    provisioning: Map<String, ForgeViewModel.ProvisioningState> = emptyMap(),
    onOpenJob: (JobRecord) -> Unit = {},
    onDelete: (JobRecord) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf<String?>(null) }
    val clearGate = rememberConfirmGate()

    ForgeLazyPage(
        modifier = modifier,
        header = {
            ForgePageHeader(
                title = "Jobs",
                subtitle = "${jobs.size} persisted · tap for logs${if (jobs.any { it.state.terminal }) " · swipe right to delete" else ""}",
            )
        },
    ) {
        if (jobs.isEmpty()) {
            item {
                ForgeEmptyState(
                    icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                    title = "No jobs yet",
                    message = "Jobs appear here only after a validated action is explicitly queued.",
                )
            }
        } else {
            items(jobs, key = { it.id.raw }) { job ->
                ForgeSwipeToDelete(
                    onDelete = { onDelete(job) },
                    enabled = job.state.terminal,
                    modifier = Modifier.animateItem(),
                ) {
                    JobSummaryCard(
                        job = job,
                        liveProvision = provisioning[job.pluginId]?.takeIf { it.active && it.jobId == job.id.raw },
                        expanded = expanded == job.id.raw,
                        onToggleExpanded = { expanded = if (expanded == job.id.raw) null else job.id.raw },
                        onOpenLog = { onOpenJob(job) },
                        onCancel = { onCancel(job) },
                    )
                }
            }
            item { ForgeDangerButton(text = "Clear job history", onClick = clearGate::request) }
        }
    }

    clearGate.Dialog(
        title = "Clear job history?",
        message = "Every job record and its logs are deleted. Running jobs are removed from the list too.",
        confirmLabel = "Clear",
        onConfirm = onClear,
        destructive = true,
    )
}
