package com.forgekit.app.screens.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import com.forgekit.app.ForgeViewModel.ProvisioningState
import com.forgekit.job.api.JobRecord
import com.forgekit.ui.design.ForgeCard
import com.forgekit.ui.design.ForgeDivider
import com.forgekit.ui.design.ForgeNoticeBox
import com.forgekit.ui.design.ForgePalette
import com.forgekit.ui.design.ForgeStatusLine
import com.forgekit.ui.design.ForgeTextAction
import com.forgekit.ui.design.ForgeTypography
import com.forgekit.ui.design.MetadataLine
import com.forgekit.ui.design.MonoText
import com.forgekit.ui.design.SectionHeader
import com.forgekit.ui.design.StatusTone

/**
 * One job row, laid out like the plugin card: action name, plugin · created time, and a meta
 * line led by the colored state word. Tapping the card opens the job log; the arrow expands the
 * audit (input, timestamps, output, error). Cancel appears only for a job that can be stopped.
 */
@Composable
internal fun JobSummaryCard(
    job: JobRecord,
    liveProvision: ProvisioningState?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenLog: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "job-expand")
    ForgeCard(modifier, onClick = onOpenLog) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    jobActionLabel(job.action),
                    color = ForgePalette.textPrimary,
                    style = ForgeTypography.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MonoText("${compactTime(job.createdAtMillis)} · ${job.pluginId}")
                ForgeStatusLine(
                    status = job.state.name,
                    tone = job.state.tone(),
                    detail = job.summaryDetail(),
                    pulse = !job.state.terminal,
                )
            }
            IconButton(onClick = onToggleExpanded) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Hide job details" else "Show job details",
                    tint = ForgePalette.textMuted,
                    modifier = Modifier.rotate(arrowRotation),
                )
            }
        }
        liveProvision?.let { ProvisioningInline(it) }
        // A live dependency install cannot be interrupted safely, so it offers no Cancel.
        if (!job.state.terminal && liveProvision == null) {
            ForgeTextAction(
                text = "Cancel job",
                icon = Icons.Outlined.Cancel,
                tone = StatusTone.ERROR,
                onClick = onCancel,
                modifier = Modifier.align(Alignment.End),
            )
        }
        if (expanded) JobAudit(job)
    }
}

/** Expanded job audit: identifiers, timestamps, input snapshot, full output and error. */
@Composable
private fun JobAudit(job: JobRecord) {
    ForgeDivider()
    SectionHeader("job")
    MetadataLine("id", job.id.raw)
    MetadataLine("plugin", job.pluginId)
    MetadataLine("action", job.action)
    MetadataLine("created", dateTime(job.createdAtMillis))
    job.startedAtMillis?.let { MetadataLine("started", dateTime(it)) }
    job.completedAtMillis?.let { MetadataLine("finished", dateTime(it)) }
    job.processId?.let { MetadataLine("process", it.take(24)) }
    if (job.input.isNotEmpty()) {
        SectionHeader("input")
        job.input.entries.take(16).forEach { (k, v) -> MetadataLine(k, v.take(80)) }
    }
    if (job.output.isNotEmpty()) {
        SectionHeader("output")
        job.output.entries.take(16).forEach { (k, v) -> MetadataLine(k, v.take(160)) }
    }
    job.error?.let { ForgeNoticeBox(text = it, tone = StatusTone.ERROR) }
}
