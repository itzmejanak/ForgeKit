package com.forgekit.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.forgekit.app.ForgeViewModel
import com.forgekit.app.screens.component.jobActionLabel
import com.forgekit.app.screens.component.summaryDetail
import com.forgekit.app.screens.component.toLogLine
import com.forgekit.app.screens.component.tone
import com.forgekit.job.api.JobEvent
import com.forgekit.job.api.JobId
import com.forgekit.ui.design.ForgeLogConsole
import com.forgekit.ui.design.ForgeLogLineVisual
import com.forgekit.ui.design.ForgePage
import com.forgekit.ui.design.ForgePageHeader
import com.forgekit.ui.design.ForgeProgressBar
import com.forgekit.ui.design.ForgeStatusLine

/**
 * Per-job observability (ARCHITECTURE §75): the live log console for one job. Loads
 * the persisted event history, then streams live [JobEvent]s — state transitions,
 * plugin progress, and stdout/stderr/plugin/provision log lines (§16/§41 separated
 * channels, tagged and color-coded). Read-only; cancel stays on the Jobs list.
 */
@Composable
public fun JobDetailScreen(
    viewModel: ForgeViewModel,
    jobId: JobId,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.state.collectAsState()
    val record = uiState.jobs.firstOrNull { it.id == jobId } ?: remember(jobId) { viewModel.jobRecord(jobId) }

    // History first, then live tail — mapped once per event, appended in order.
    val lines = remember(jobId) { mutableStateListOf<ForgeLogLineVisual>() }
    var lastProgress by remember(jobId) { mutableStateOf<JobEvent.Progress?>(null) }
    LaunchedEffect(jobId) {
        lines.clear()
        val append: (JobEvent) -> Unit = { event ->
            if (event is JobEvent.Progress) lastProgress = event
            lines.add(event.toLogLine(lines.size))
        }
        viewModel.jobHistory(jobId).forEach(append)
        viewModel.jobEvents(jobId).collect { append(it) }
    }

    ForgePage(
        modifier = modifier,
        header = {
            ForgePageHeader(
                title = "Job log",
                subtitle = record?.let { "${it.pluginId} · ${jobActionLabel(it.action)}" } ?: jobId.raw,
                onBack = onBack,
            )
        },
    ) {
        // State lives in the content (same colored state line as the job cards), never in the header.
        record?.let {
            ForgeStatusLine(
                status = it.state.name,
                tone = it.state.tone(),
                detail = it.summaryDetail(),
                pulse = !it.state.terminal,
            )
        }
        val progress = lastProgress
        if (progress != null && record?.state?.terminal != true) {
            ForgeProgressBar(fraction = progress.value.toFloat(), label = progress.message)
        }
        ForgeLogConsole(lines = lines, title = "output", modifier = Modifier.weight(1f))
    }
}
