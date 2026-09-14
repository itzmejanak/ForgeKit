package com.forgekit.job.api

import com.forgekit.core.model.ExitStatus

/**
 * One persisted/streamed job record (§30). Pure data: the manager owns
 * transitions, the store owns durability, the UI owns presentation.
 */
public data class JobRecord(
    public val id: JobId,
    public val pluginId: String,
    /** The action invoked (manifest ACTIONS id). */
    public val action: String,
    public val state: JobState,
    /** The exact invoke input payload (control ids → values). */
    public val input: Map<String, String>,
    /** Protocol result output when COMPLETED. */
    public val output: Map<String, String> = emptyMap(),
    public val error: String? = null,
    /** Runtime process id (ExecutionHandle.processId) while live. */
    public val processId: String? = null,
    public val createdAtMillis: Long,
    public val startedAtMillis: Long? = null,
    public val completedAtMillis: Long? = null,
) {
    public val terminal: Boolean get() = state.terminal
}

/** Job identifier: opaque, stable, lexically sortable by creation time. */
@JvmInline
public value class JobId(public val raw: String) {
    public override fun toString(): String = raw

    public companion object {
        /** Generates a time-sortable id: epoch-millis + random suffix. */
        public fun generate(): JobId =
            JobId("${System.currentTimeMillis()}-${randomSuffix()}")

        private fun randomSuffix(): String =
            java.security.SecureRandom().nextInt(0x10000).toString(16).padStart(4, '0')
    }
}

/**
 * Typed internal events (§40 Event Architecture — UI observes application
 * state, never processes directly). Events are appended to the store and
 * broadcast live by the manager.
 */
public sealed interface JobEvent {
    public val jobId: JobId
    public val timestampMillis: Long

    public data class StateChanged(
        override val jobId: JobId,
        val from: JobState,
        val to: JobState,
        override val timestampMillis: Long,
    ) : JobEvent

    public data class LogLine(
        override val jobId: JobId,
        val line: String,
        val channel: String,
        override val timestampMillis: Long,
    ) : JobEvent

    /** Protocol progress event mapped 1:1 from forgekit/1. */
    public data class Progress(
        override val jobId: JobId,
        val value: Double,
        val message: String?,
        override val timestampMillis: Long,
    ) : JobEvent

    /** Safe prompt metadata. No submitted value is ever persisted in this event. */
    public data class PromptRequested(
        override val jobId: JobId,
        val prompt: JobPrompt,
        override val timestampMillis: Long,
    ) : JobEvent

    /** Prompt completion marker. Deliberately records status but never the value. */
    public data class PromptResolved(
        override val jobId: JobId,
        val promptId: String,
        val status: JobPromptResponseStatus,
        override val timestampMillis: Long,
    ) : JobEvent

    public data class Completed(
        override val jobId: JobId,
        val output: Map<String, String>,
        override val timestampMillis: Long,
    ) : JobEvent

    public data class Failed(
        override val jobId: JobId,
        val reason: String,
        val exitStatus: ExitStatus?,
        override val timestampMillis: Long,
    ) : JobEvent

    public data class Cancelled(
        override val jobId: JobId,
        override val timestampMillis: Long,
    ) : JobEvent
}

public enum class JobPromptKind { CONFIRM, TEXT, PASSWORD, SELECT }

/** One pending, protocol-originated interaction for a running job. */
public data class JobPrompt(
    public val id: String,
    public val kind: JobPromptKind,
    public val title: String,
    public val message: String? = null,
    public val required: Boolean = true,
    public val choices: List<String> = emptyList(),
    public val default: String? = null,
    public val placeholder: String? = null,
)

public enum class JobPromptResponseStatus { SUBMITTED, CANCELLED }

/** Ephemeral response passed to the process; [value] must never be logged or persisted. */
public data class JobPromptResponse(
    public val status: JobPromptResponseStatus,
    public val value: String? = null,
)

/** History queries (§30 logs / §61 backup). */
public data class JobQuery(
    public val pluginId: String? = null,
    public val states: Set<JobState> = emptySet(),
    public val sinceMillis: Long? = null,
    public val limit: Int = 100,
)
