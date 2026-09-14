package com.forgekit.app.screens.component

import com.forgekit.app.ForgeViewModel.DepStatus
import com.forgekit.app.ForgeViewModel.DependencyState
import com.forgekit.app.ForgeViewModel.ProvisionEventKind
import com.forgekit.app.ForgeViewModel.ProvisioningLogEntry
import com.forgekit.app.ForgeViewModel.ProvisioningState
import com.forgekit.plugin.resolver.DependencyPlan
import com.forgekit.plugin.validator.ValidationReport
import com.forgekit.ui.design.ForgeLogLineVisual
import com.forgekit.ui.design.ForgeLogTone
import com.forgekit.ui.design.ForgeStepState
import com.forgekit.ui.design.ForgeStepVisual
import com.forgekit.ui.design.StatusTone
import java.util.TimeZone

/** How a provisioning run stands, derived only from the live [ProvisioningState]. */
internal enum class ProvisioningOutcome { RUNNING, SUCCEEDED, PARTIAL, FAILED }

/** Everything the status card of a provisioning run shows. */
internal data class ProvisioningSummary(
    val outcome: ProvisioningOutcome,
    val headline: String,
    val chip: String,
    val tone: StatusTone,
    /** `null` while the dependency plan is not known yet (indeterminate bar). */
    val fraction: Float?,
    val detail: String,
    /** `completed / total`, or null when nothing is declared. */
    val counter: String?,
)

internal fun ProvisioningState.outcome(): ProvisioningOutcome = when {
    active -> ProvisioningOutcome.RUNNING
    failedReason != null -> ProvisioningOutcome.FAILED
    missingAfter.isNotEmpty() || dependencies.any { it.status == DepStatus.FAILED } -> ProvisioningOutcome.PARTIAL
    else -> ProvisioningOutcome.SUCCEEDED
}

internal fun ProvisioningState.summary(): ProvisioningSummary {
    val total = dependencies.size
    val completed = dependencies.count { it.status == DepStatus.DONE || it.status == DepStatus.PRESENT }
    val counter = if (total > 0) "$completed / $total" else null
    return when (val outcome = outcome()) {
        ProvisioningOutcome.RUNNING -> {
            val current = dependencies.firstOrNull { it.status == DepStatus.INSTALLING }
            ProvisioningSummary(
                outcome = outcome,
                headline = if (total == 0) "Preparing dependency plan" else "Installing dependencies",
                chip = "PROVISIONING",
                tone = StatusTone.INFO,
                fraction = if (total == 0) null else fraction,
                detail = current?.let { "${it.label} · ${it.phase?.name?.lowercase() ?: "installing"}" }
                    ?: if (total == 0) "Resolving what the package declares" else "Checking the next dependency",
                counter = counter,
            )
        }
        ProvisioningOutcome.SUCCEEDED -> ProvisioningSummary(
            outcome = outcome,
            headline = "Environment ready",
            chip = "READY",
            tone = StatusTone.READY,
            fraction = 1f,
            detail = if (total == 0) "No dependencies declared" else "All $total dependencies verified",
            counter = counter,
        )
        ProvisioningOutcome.PARTIAL -> {
            val missing = missingAfter.ifEmpty {
                dependencies.filter { it.status == DepStatus.FAILED }.map { "${it.manager}/${it.name}" }
            }
            ProvisioningSummary(
                outcome = outcome,
                headline = "Some dependencies are missing",
                chip = "UNRESOLVED",
                tone = StatusTone.UNRESOLVED,
                fraction = fraction,
                detail = "Missing: ${missing.joinToString()}",
                counter = counter,
            )
        }
        ProvisioningOutcome.FAILED -> ProvisioningSummary(
            outcome = outcome,
            headline = "Provisioning failed",
            chip = "FAILED",
            tone = StatusTone.ERROR,
            fraction = fraction,
            detail = failedReason.orEmpty(),
            counter = counter,
        )
    }
}

internal fun DependencyState.toStepVisual(): ForgeStepVisual = when (status) {
    DepStatus.PENDING -> ForgeStepVisual(label, label, ForgeStepState.PENDING, "pending")
    DepStatus.MISSING -> ForgeStepVisual(label, label, ForgeStepState.PENDING, "not installed")
    DepStatus.PRESENT -> ForgeStepVisual(label, label, ForgeStepState.SATISFIED, "present")
    DepStatus.INSTALLING -> ForgeStepVisual(
        key = label,
        label = label,
        state = ForgeStepState.ACTIVE,
        status = phase?.name?.lowercase() ?: "installing",
        detail = message,
    )
    DepStatus.DONE -> ForgeStepVisual(label, label, ForgeStepState.DONE, "installed")
    DepStatus.FAILED -> ForgeStepVisual(label, label, ForgeStepState.FAILED, "failed", detail = detail)
}

internal fun ProvisioningLogEntry.toLogLine(zone: TimeZone = TimeZone.getDefault()): ForgeLogLineVisual {
    val subject = listOfNotNull(manager, dependency).joinToString("/")
    val text = if (kind == ProvisionEventKind.OUTPUT || subject.isBlank()) message else "$subject · $message"
    return ForgeLogLineVisual(
        id = sequence,
        text = stripAnsi(text),
        tone = when (kind) {
            ProvisionEventKind.PLAN -> ForgeLogTone.INFO
            ProvisionEventKind.PHASE -> ForgeLogTone.ACCENT
            ProvisionEventKind.OUTPUT -> ForgeLogTone.NORMAL
            ProvisionEventKind.RESULT -> ForgeLogTone.SUCCESS
            ProvisionEventKind.FAILURE -> ForgeLogTone.ERROR
        },
        time = clockTime(timestampMillis, zone),
        tag = when (kind) {
            ProvisionEventKind.PLAN -> "PLAN"
            ProvisionEventKind.PHASE -> "PHASE"
            ProvisionEventKind.OUTPUT -> "OUT"
            ProvisionEventKind.RESULT -> "OK"
            ProvisionEventKind.FAILURE -> "FAIL"
        },
    )
}

/** Live runtime plan (review / plugin detail) → dependency row state. */
internal fun DependencyPlan.toDependencyState(): DependencyState = DependencyState(
    manager = manager,
    name = name,
    version = versionRequirement,
    status = if (alreadyPresent) DepStatus.PRESENT else DepStatus.MISSING,
)

/** Import-time static fact (before the live plan is available) → dependency row state. */
internal fun ValidationReport.DependencyFact.toDependencyState(): DependencyState = DependencyState(
    manager = manager,
    name = name,
    version = version,
    status = if (missing) DepStatus.MISSING else DepStatus.PRESENT,
)
