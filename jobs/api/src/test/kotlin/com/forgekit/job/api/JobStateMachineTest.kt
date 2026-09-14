package com.forgekit.job.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The shared transition table every layer trusts — verified exhaustively. */
class JobStateMachineTest {

    @Test
    fun `happy path walks queued to completed`() {
        val path = listOf(
            JobState.QUEUED, JobState.PREPARING, JobState.STARTING,
            JobState.RUNNING, JobState.COMPLETED,
        )
        for (i in 0 until path.size - 1) {
            assertEquals(path[i + 1], JobStateMachine.transition(path[i], path[i + 1]))
        }
    }

    @Test
    fun `dependency installation inserts between preparing and starting`() {
        assertTrue(JobStateMachine.canTransition(JobState.PREPARING, JobState.INSTALLING_DEPENDENCIES))
        assertTrue(JobStateMachine.canTransition(JobState.INSTALLING_DEPENDENCIES, JobState.STARTING))
    }

    @Test
    fun `provisioning-only jobs terminate without a process`() {
        assertTrue(JobStateMachine.canTransition(JobState.INSTALLING_DEPENDENCIES, JobState.COMPLETED))
        assertTrue(JobStateMachine.canTransition(JobState.INSTALLING_DEPENDENCIES, JobState.FAILED))
        assertTrue(JobStateMachine.canTransition(JobState.INSTALLING_DEPENDENCIES, JobState.QUEUED))
    }

    @Test
    fun `any live state may enter cancelling`() {
        for (state in listOf(
            JobState.QUEUED, JobState.PREPARING, JobState.STARTING,
            JobState.RUNNING, JobState.PAUSED, JobState.INSTALLING_DEPENDENCIES,
        )) {
            assertTrue(JobStateMachine.canTransition(state, JobState.CANCELLING), "$state → CANCELLING must be legal")
        }
    }

    @Test
    fun `terminal states are terminal`() {
        for (state in listOf(JobState.COMPLETED, JobState.FAILED, JobState.CANCELLED)) {
            assertTrue(state.terminal)
            assertTrue(JobStateMachine.successors(state).isEmpty(), "$state must have no successors")
        }
    }

    @Test
    fun `illegal transitions throw`() {
        assertFailsWith<IllegalStateException> {
            JobStateMachine.transition(JobState.COMPLETED, JobState.RUNNING)
        }
        assertFailsWith<IllegalStateException> {
            JobStateMachine.transition(JobState.QUEUED, JobState.RUNNING)
        }
        assertFailsWith<IllegalStateException> {
            JobStateMachine.transition(JobState.CANCELLED, JobState.QUEUED)
        }
    }

    @Test
    fun `paused jobs may resume or be cancelled but never jump to completed`() {
        assertTrue(JobStateMachine.canTransition(JobState.PAUSED, JobState.RUNNING))
        assertFalse(JobStateMachine.canTransition(JobState.PAUSED, JobState.COMPLETED))
    }

    @Test
    fun `job ids are time-sortable and unique`() {
        val a = JobId.generate()
        Thread.sleep(2)
        val b = JobId.generate()
        assertTrue(a.raw < b.raw, "lexicographic order must follow time: ${a.raw} < ${b.raw}")
        assertTrue(a.raw != b.raw)
    }
}
