package com.forgekit.app.screens.component

import com.forgekit.job.api.JobState
import com.forgekit.plugin.api.PluginStatus
import com.forgekit.runtime.api.RuntimeState
import com.forgekit.ui.design.StatusTone
import kotlin.test.Test
import kotlin.test.assertEquals

class StatusTonesTest {

    @Test
    fun `plugin statuses`() {
        assertEquals(StatusTone.READY, PluginStatus.READY.tone())
        assertEquals(StatusTone.UNRESOLVED, PluginStatus.UNRESOLVED.tone())
        assertEquals(StatusTone.ERROR, PluginStatus.QUARANTINED.tone())
        assertEquals(StatusTone.ERROR, PluginStatus.ERROR.tone())
        assertEquals(StatusTone.NEUTRAL, PluginStatus.DISABLED.tone())
        assertEquals(StatusTone.NEUTRAL, PluginStatus.REMOVED.tone())
    }

    @Test
    fun `job states`() {
        assertEquals(StatusTone.READY, JobState.COMPLETED.tone())
        assertEquals(StatusTone.ERROR, JobState.FAILED.tone())
        assertEquals(StatusTone.NEUTRAL, JobState.CANCELLED.tone())
        assertEquals(StatusTone.WARNING, JobState.CANCELLING.tone())
        listOf(JobState.QUEUED, JobState.PREPARING, JobState.INSTALLING_DEPENDENCIES, JobState.STARTING, JobState.RUNNING)
            .forEach { assertEquals(StatusTone.INFO, it.tone(), it.name) }
    }

    @Test
    fun `runtime states`() {
        assertEquals(StatusTone.READY, RuntimeState.READY.tone())
        assertEquals(StatusTone.ERROR, RuntimeState.FAILED.tone())
        assertEquals(StatusTone.WARNING, RuntimeState.DEGRADED.tone())
        assertEquals(StatusTone.INFO, RuntimeState.INSTALLING.tone())
    }

    @Test
    fun `provision jobs read as provision`() {
        assertEquals("provision", jobActionLabel("forge://provision"))
        assertEquals("greet", jobActionLabel("greet"))
    }
}
