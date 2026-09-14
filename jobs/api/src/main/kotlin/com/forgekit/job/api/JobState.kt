package com.forgekit.job.api

/**
 * Job domain (ARCHITECTURE §30): every plugin execution is a Job with a
 * strict lifecycle. The state machine is enforced HERE — every layer
 * (manager, persistence, UI) shares the same transition table, and an
 * illegal transition is a programming error, not a data condition.
 */
public enum class JobState {
    QUEUED,
    PREPARING,
    INSTALLING_DEPENDENCIES,
    STARTING,
    RUNNING,
    PAUSED,
    CANCELLING,
    COMPLETED,
    FAILED,
    CANCELLED,
    ;

    public val terminal: Boolean get() = this in TERMINAL

    private companion object {
        val TERMINAL = setOf(COMPLETED, FAILED, CANCELLED)
    }
}

/** The legal-transition table (§30 + §31 recovery semantics). */
public object JobStateMachine {

    private val transitions: Map<JobState, Set<JobState>> = mapOf(
        JobState.QUEUED to setOf(JobState.PREPARING, JobState.CANCELLING, JobState.FAILED),
        JobState.PREPARING to setOf(
            JobState.INSTALLING_DEPENDENCIES, JobState.STARTING, JobState.FAILED, JobState.CANCELLING,
        ),
        JobState.INSTALLING_DEPENDENCIES to setOf(
            // STARTING continues a run after deps are ready; COMPLETED/FAILED end a
            // provisioning-only job (no process, so the RUNNING/process states never occur).
            JobState.STARTING, JobState.COMPLETED, JobState.FAILED, JobState.CANCELLING, JobState.QUEUED,
        ),
        JobState.STARTING to setOf(JobState.RUNNING, JobState.FAILED, JobState.CANCELLING),
        JobState.RUNNING to setOf(
            JobState.COMPLETED, JobState.FAILED, JobState.CANCELLING, JobState.PAUSED,
        ),
        JobState.PAUSED to setOf(JobState.RUNNING, JobState.CANCELLING, JobState.FAILED),
        JobState.CANCELLING to setOf(JobState.CANCELLED, JobState.FAILED, JobState.COMPLETED),
        // terminal states have no outgoing edges
        JobState.COMPLETED to emptySet(),
        JobState.FAILED to emptySet(),
        JobState.CANCELLED to emptySet(),
    )

    /** True when [from] → [to] is legal. */
    public fun canTransition(from: JobState, to: JobState): Boolean =
        transitions[from]?.contains(to) == true

    /** Asserts the transition and returns [to]; throws on illegal moves. */
    public fun transition(from: JobState, to: JobState): JobState {
        if (!canTransition(from, to)) {
            throw IllegalStateException("illegal job transition: $from → $to")
        }
        return to
    }

    /** All states reachable from [from] in one step. */
    public fun successors(from: JobState): Set<JobState> = transitions[from] ?: emptySet()
}
