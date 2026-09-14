package com.forgekit.app.screens.importflow

import com.forgekit.app.ForgeViewModel.DepStatus
import com.forgekit.app.ForgeViewModel.DependencyState
import com.forgekit.app.ForgeViewModel.ProvisioningState
import com.forgekit.app.screens.component.ProvisioningOutcome
import com.forgekit.ui.design.StatusTone
import com.forgekit.core.model.Version
import com.forgekit.plugin.api.PluginDescriptor
import com.forgekit.plugin.api.PluginId
import com.forgekit.plugin.api.PluginStatus
import com.forgekit.plugin.api.PluginTrustLevel
import com.forgekit.plugin.api.PluginVersion
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Import page phase derivation: every approval outcome lands on a phase with a way
 * forward — no endless spinner when approval is refused, no dead end after a failure.
 */
class ImportPhaseTest {

    private val running = ProvisioningState(jobId = "job-2", label = "provision x", active = true)

    @Test
    fun `pending path wins over everything`() {
        val phase = deriveImportPhase(Paths.get("/tmp/a.forge"), hasReview = true, ImportApproval.Installed(descriptor()), running)
        assertIs<ImportPhase.Inspecting>(phase)
    }

    @Test
    fun `no review and no path is the idle picker`() {
        assertEquals(ImportPhase.Idle, deriveImportPhase(null, hasReview = false, ImportApproval.NotRequested, null))
    }

    @Test
    fun `fresh review without approval is review`() {
        assertEquals(ImportPhase.Review, deriveImportPhase(null, hasReview = true, ImportApproval.NotRequested, null))
    }

    @Test
    fun `finished snapshot of an earlier run does not hide the review`() {
        val stale = ProvisioningState(jobId = "job-1", active = false, failedReason = "boom")
        assertEquals(ImportPhase.Review, deriveImportPhase(null, hasReview = true, ImportApproval.NotRequested, stale))
    }

    @Test
    fun `live run for the package shows provisioning even before local approval state`() {
        val phase = deriveImportPhase(null, hasReview = true, ImportApproval.NotRequested, running)
        assertEquals(ImportPhase.Provisioning(running), phase)
    }

    @Test
    fun `started approval ignores the previous job snapshot until the new job begins`() {
        val stale = ProvisioningState(jobId = "job-1", active = false, failedReason = "old")
        val phase = deriveImportPhase(null, hasReview = true, ImportApproval.Started(previousJobId = "job-1"), stale)
        assertIs<ImportPhase.Provisioning>(phase)
        assertNull(phase.provisioning)
    }

    @Test
    fun `job link never points at the previous run`() {
        val stale = ProvisioningState(jobId = "job-1", active = false)
        val waiting = deriveImportPhase(null, hasReview = true, ImportApproval.Started(previousJobId = "job-1"), stale)
        assertNull(waiting.liveProvisioning()?.jobId)
        val started = deriveImportPhase(null, hasReview = true, ImportApproval.Started(previousJobId = "job-1"), running)
        assertEquals("job-2", started.liveProvisioning()?.jobId)
    }

    @Test
    fun `started approval shows the new job`() {
        val phase = deriveImportPhase(null, hasReview = true, ImportApproval.Started(previousJobId = "job-1"), running)
        assertEquals(ImportPhase.Provisioning(running), phase)
    }

    @Test
    fun `installed approval carries the registered descriptor`() {
        val descriptor = descriptor(PluginStatus.UNRESOLVED)
        val phase = deriveImportPhase(null, hasReview = true, ImportApproval.Installed(descriptor), running)
        assertIs<ImportPhase.Installed>(phase)
        assertEquals(PluginStatus.UNRESOLVED, phase.descriptor.status)
    }

    @Test
    fun `failed approval is a failed phase with the new job evidence`() {
        val failed = ProvisioningState(jobId = "job-2", active = false, failedReason = "install error")
        val phase = deriveImportPhase(null, hasReview = true, ImportApproval.Failed(previousJobId = null), failed)
        assertEquals(ImportPhase.Failed(failed), phase)
    }

    @Test
    fun `step labels follow the three steps`() {
        assertEquals("step 1 of 3 · choose a document", ImportPhase.Idle.stepLabel())
        assertEquals("step 2 of 3 · review facts", ImportPhase.Review.stepLabel())
        assertEquals("step 3 of 3 · install", ImportPhase.Failed(null).stepLabel())
    }

    @Test
    fun `review phases have no status card`() {
        assertNull(ImportPhase.Review.statusSummary())
    }

    @Test
    fun `provisioning before the job starts is indeterminate`() {
        val summary = ImportPhase.Provisioning(null).statusSummary()!!
        assertEquals(ProvisioningOutcome.RUNNING, summary.outcome)
        assertNull(summary.fraction)
    }

    @Test
    fun `failure before provisioning started still explains itself`() {
        val summary = ImportPhase.Failed(null).statusSummary()!!
        assertEquals(ProvisioningOutcome.FAILED, summary.outcome)
        assertEquals(StatusTone.ERROR, summary.tone)
        assertEquals("Install stopped before provisioning started", summary.detail)
    }

    @Test
    fun `descriptor status decides installed outcome`() {
        val clean = ProvisioningState(jobId = "j", dependencies = listOf(DependencyState("PIP", "rich", null, DepStatus.DONE)))
        val ready = ImportPhase.Installed(descriptor(PluginStatus.READY), clean).statusSummary()!!
        assertEquals(StatusTone.READY, ready.tone)

        val unresolved = ImportPhase.Installed(descriptor(PluginStatus.UNRESOLVED), clean).statusSummary()!!
        assertEquals(ProvisioningOutcome.PARTIAL, unresolved.outcome)
        assertEquals("UNRESOLVED", unresolved.chip)
        assertEquals("Open the plugin to retry provisioning", unresolved.detail)
    }

    private fun descriptor(status: PluginStatus = PluginStatus.READY) = PluginDescriptor(
        id = PluginId("com.example.hello"),
        version = PluginVersion(Version("1.0.0")),
        name = "Hello",
        description = null,
        status = status,
        trust = PluginTrustLevel.UNSIGNED,
        publisherKeyFingerprint = null,
        packageSha256 = null,
        installedPath = null,
        runtimeType = "python",
        runtimeVersionRequirement = null,
        capabilities = emptyList(),
        requestedPermissions = emptyList(),
        installedAtMillis = 0,
        updatedAtMillis = 0,
    )
}
