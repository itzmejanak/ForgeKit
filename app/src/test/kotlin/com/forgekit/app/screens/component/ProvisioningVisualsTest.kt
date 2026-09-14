package com.forgekit.app.screens.component

import com.forgekit.app.ForgeViewModel.DepStatus
import com.forgekit.app.ForgeViewModel.DependencyState
import com.forgekit.app.ForgeViewModel.ProvisionEventKind
import com.forgekit.app.ForgeViewModel.ProvisioningLogEntry
import com.forgekit.app.ForgeViewModel.ProvisioningState
import com.forgekit.runtime.api.InstallPhase
import com.forgekit.ui.design.ForgeLogTone
import com.forgekit.ui.design.ForgeStepState
import com.forgekit.ui.design.StatusTone
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProvisioningVisualsTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun dep(name: String, status: DepStatus, phase: InstallPhase? = null, detail: String? = null) =
        DependencyState(manager = "PIP", name = name, version = null, status = status, phase = phase, detail = detail)

    @Test
    fun `running without a plan is indeterminate`() {
        val summary = ProvisioningState(active = true).summary()
        assertEquals(ProvisioningOutcome.RUNNING, summary.outcome)
        assertNull(summary.fraction)
        assertNull(summary.counter)
        assertEquals(StatusTone.INFO, summary.tone)
    }

    @Test
    fun `running with a plan reports the active dependency and counter`() {
        val state = ProvisioningState(
            active = true,
            dependencies = listOf(
                dep("rich", DepStatus.PRESENT),
                dep("requests", DepStatus.INSTALLING, InstallPhase.FETCHING),
                dep("r2pipe", DepStatus.PENDING),
            ),
        )
        val summary = state.summary()
        assertEquals("1 / 3", summary.counter)
        assertEquals("PIP/requests · fetching", summary.detail)
        assertEquals(state.fraction, summary.fraction)
    }

    @Test
    fun `finished with every dependency present is ready`() {
        val state = ProvisioningState(active = false, dependencies = listOf(dep("rich", DepStatus.DONE)))
        assertEquals(ProvisioningOutcome.SUCCEEDED, state.outcome())
        assertEquals(StatusTone.READY, state.summary().tone)
    }

    @Test
    fun `finished with a failed dependency is partial and names it`() {
        val state = ProvisioningState(active = false, dependencies = listOf(dep("rich", DepStatus.FAILED)))
        val summary = state.summary()
        assertEquals(ProvisioningOutcome.PARTIAL, summary.outcome)
        assertEquals("Missing: PIP/rich", summary.detail)
        assertEquals(StatusTone.UNRESOLVED, summary.tone)
    }

    @Test
    fun `failure reason wins over partial`() {
        val state = ProvisioningState(active = false, failedReason = "network down", missingAfter = listOf("PIP/rich"))
        val summary = state.summary()
        assertEquals(ProvisioningOutcome.FAILED, summary.outcome)
        assertEquals("network down", summary.detail)
    }

    @Test
    fun `dependency states map to step states`() {
        assertEquals(ForgeStepState.PENDING, dep("a", DepStatus.MISSING).toStepVisual().state)
        assertEquals(ForgeStepState.SATISFIED, dep("a", DepStatus.PRESENT).toStepVisual().state)
        assertEquals(ForgeStepState.DONE, dep("a", DepStatus.DONE).toStepVisual().state)
        val active = dep("a", DepStatus.INSTALLING, InstallPhase.UNPACKING).toStepVisual()
        assertEquals(ForgeStepState.ACTIVE, active.state)
        assertEquals("unpacking", active.status)
        val failed = dep("a", DepStatus.FAILED, detail = "no wheel").toStepVisual()
        assertEquals(ForgeStepState.FAILED, failed.state)
        assertEquals("no wheel", failed.detail)
    }

    @Test
    fun `log entries keep sequence ids and tone by kind`() {
        val plan = ProvisioningLogEntry(7, 3_723_000, ProvisionEventKind.PLAN, "PIP", "rich", "queued from pypi").toLogLine(utc)
        assertEquals(7L, plan.id)
        assertEquals("01:02:03", plan.time)
        assertEquals("PLAN", plan.tag)
        assertEquals("PIP/rich · queued from pypi", plan.text)
        assertEquals(ForgeLogTone.INFO, plan.tone)

        val output = ProvisioningLogEntry(8, 0, ProvisionEventKind.OUTPUT, "PIP", "rich", "Collecting rich").toLogLine(utc)
        assertEquals("Collecting rich", output.text)

        val failure = ProvisioningLogEntry(9, 0, ProvisionEventKind.FAILURE, null, null, "provisioning failed").toLogLine(utc)
        assertEquals("provisioning failed", failure.text)
        assertEquals(ForgeLogTone.ERROR, failure.tone)
    }
}
