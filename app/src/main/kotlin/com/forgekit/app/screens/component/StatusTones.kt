package com.forgekit.app.screens.component

import com.forgekit.app.ForgeViewModel
import com.forgekit.job.api.JobState
import com.forgekit.plugin.api.PluginStatus
import com.forgekit.runtime.api.RuntimeState
import com.forgekit.ui.design.StatusTone

/**
 * Domain state → [StatusTone]: the only place lifecycle enums pick a color.
 * Exhaustive `when`s make a new enum value a compile error instead of a silent default.
 */

internal fun PluginStatus.tone(): StatusTone = when (this) {
    PluginStatus.READY -> StatusTone.READY
    PluginStatus.UNRESOLVED -> StatusTone.UNRESOLVED
    PluginStatus.QUARANTINED, PluginStatus.ERROR -> StatusTone.ERROR
    PluginStatus.DISABLED, PluginStatus.REMOVED -> StatusTone.NEUTRAL
}

internal fun JobState.tone(): StatusTone = when (this) {
    JobState.COMPLETED -> StatusTone.READY
    JobState.FAILED -> StatusTone.ERROR
    JobState.CANCELLED -> StatusTone.NEUTRAL
    JobState.PAUSED, JobState.CANCELLING -> StatusTone.WARNING
    JobState.QUEUED,
    JobState.PREPARING,
    JobState.INSTALLING_DEPENDENCIES,
    JobState.STARTING,
    JobState.RUNNING,
    -> StatusTone.INFO
}

internal fun RuntimeState.tone(): StatusTone = when (this) {
    RuntimeState.READY -> StatusTone.READY
    RuntimeState.FAILED -> StatusTone.ERROR
    RuntimeState.DEGRADED -> StatusTone.WARNING
    RuntimeState.NOT_INSTALLED,
    RuntimeState.INSTALLING,
    RuntimeState.INITIALIZING,
    RuntimeState.REPAIRING,
    -> StatusTone.INFO
}

/** Human label of a job action: provisioning-only jobs read as `provision`. */
internal fun jobActionLabel(action: String): String =
    if (action.trim() == ForgeViewModel.PROVISION_ACTION) "provision" else action
