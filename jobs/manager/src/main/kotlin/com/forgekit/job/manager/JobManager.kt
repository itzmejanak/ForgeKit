package com.forgekit.job.manager

import com.forgekit.core.model.ExitStatus
import com.forgekit.job.api.JobEvent
import com.forgekit.job.api.JobId
import com.forgekit.job.api.JobPrompt
import com.forgekit.job.api.JobPromptKind
import com.forgekit.job.api.JobPromptResponse
import com.forgekit.job.api.JobPromptResponseStatus
import com.forgekit.job.api.JobRecord
import com.forgekit.job.api.JobState
import com.forgekit.job.api.JobStateMachine
import com.forgekit.job.persistence.JobStore
import com.forgekit.plugin.protocol.ProtocolCodec
import com.forgekit.plugin.protocol.ProtocolMessage
import com.forgekit.runtime.api.ExecutionRequest
import com.forgekit.runtime.api.ForgeRuntime
import com.forgekit.runtime.api.ProcessOutput
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * The job lifecycle owner (ARCHITECTURE §30/§31).
 *
 * One manager per application scope. It drives the state machine, streams
 * typed events (§40), maps the forgekit/1 protocol to job outcomes, and
 * delegates process death to the runtime's §53 termination escalation.
 *
 * Honest by construction:
 *  - a COMPLETED job has seen a terminal protocol `result` AND exit 0;
 *  - a process that dies without a terminal message is FAILED, never OK;
 *  - stdout lines that are not valid protocol become diagnostic logs (§16),
 *    never parsed as truth.
 */
public class JobManager(
    /** The live embedded runtime. */
    private val runtime: ForgeRuntime,
    /** Optional durability (recommended: SqliteJobStore / platform store). */
    private val store: JobStore? = null,
    private val codec: ProtocolCodec = ProtocolCodec(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Ceiling for one job run; per-process §53 escalation is the runtime's. */
    private val defaultTimeoutMillis: Long = 10 * 60 * 1000,
) {

    private val records = ConcurrentHashMap<JobId, JobRecord>()
    private val specs = ConcurrentHashMap<JobId, JobSpec>()
    /** At most one outstanding request per job; different jobs may prompt concurrently. */
    private val pendingPrompts = ConcurrentHashMap<JobId, JobPrompt>()

    private val _events = MutableSharedFlow<JobEvent>(replay = 256, extraBufferCapacity = 256)
    public val events: SharedFlow<JobEvent> = _events.asSharedFlow()

    /** How a job is executed: the manifest entrypoint request assembled by the caller. */
    public data class JobSpec(
        public val pluginId: String,
        public val action: String,
        public val input: Map<String, String>,
        /** The execution to run; the invoke NDJSON line is written to stdin by the manager.
         *  Null only for provisioning-only jobs, which never start a process. */
        public val execution: ExecutionRequest? = null,
        public val timeoutMillis: Long? = null,
    )

    /** What the protocol stream decided before the process exited. */
    private sealed interface Outcome {
        data class Terminal(val message: ProtocolMessage, var exit: ExitStatus?) : Outcome
        data class NoTerminal(val exit: ExitStatus) : Outcome
    }

    // ---- enqueue --------------------------------------------------------------

    /** Registers a job (QUEUED) with its execution spec. Persisted immediately. */
    public fun enqueue(spec: JobSpec): JobRecord {
        val record = JobRecord(
            id = JobId.generate(),
            pluginId = spec.pluginId,
            action = spec.action,
            state = JobState.QUEUED,
            input = spec.input,
            createdAtMillis = clock(),
        )
        records[record.id] = record
        specs[record.id] = spec
        store?.insert(record)
        return record
    }

    // ---- run --------------------------------------------------------------------

    /**
     * Runs a queued job through its full lifecycle and returns the terminal
     * record (COMPLETED / FAILED / CANCELLED). Every transition is validated
     * and broadcast; the protocol invoke is written to the process stdin.
     */
    public suspend fun run(jobId: JobId): JobRecord = runFrom(jobId, provisioning = null)

    /**
     * Run path with real dependency provisioning (ARCHITECTURE §30
     * INSTALLING_DEPENDENCIES). The job advances QUEUED → PREPARING →
     * INSTALLING_DEPENDENCIES, [provision] streams the installs (its events
     * must be mapped to JobEvents by the caller through
     * [emitProvisionProgress]/[emitProvisionLog]), then continues the normal
     * STARTING → RUNNING lifecycle only when provisioning actually succeeded.
     * A provisioning failure fails the job without ever starting a process.
     */
    public suspend fun runWithProvisioning(
        jobId: JobId,
        provision: suspend () -> Boolean,
    ): JobRecord = runFrom(jobId, provisioning = provision)

    private suspend fun runFrom(
        jobId: JobId,
        provisioning: (suspend () -> Boolean)?,
    ): JobRecord {
        var record = records[jobId]
            ?: store?.find(jobId)?.also { records[jobId] = it }
            ?: throw IllegalStateException("unknown job '${jobId.raw}'")
        val spec = specs[jobId] ?: throw IllegalStateException("job '${jobId.raw}' has no execution spec")

        // The caller may have already driven this job into INSTALLING_DEPENDENCIES
        // (beginProvisioning before runWithProvisioning) — only advance along the
        // untouched pre-provision edges, never re-enter a state the job is already in.
        record = when (record.state) {
            JobState.QUEUED -> advance(record, JobState.PREPARING)
            JobState.PREPARING, JobState.INSTALLING_DEPENDENCIES -> record
            else -> throw IllegalStateException(
                "job '${jobId.raw}' cannot run from ${record.state}",
            )
        }

        if (provisioning != null) {
            record = if (record.state == JobState.PREPARING) {
                advance(record, JobState.INSTALLING_DEPENDENCIES)
            } else {
                record // already INSTALLING_DEPENDENCIES via beginProvisioning
            }
            val provisioned = try {
                provisioning()
            } catch (e: Exception) {
                emitProvisionLog(jobId, "provisioning aborted: ${e.message}", "stderr")
                false
            }
            if (!provisioned) {
                return fail(record, "dependency provisioning failed", null)
            }
        }

        val execution = spec.execution
            ?: throw IllegalStateException("job '${jobId.raw}' has no execution (provisioning-only job cannot run)")

        val invoke = ProtocolMessage.Invoke(
            protocol = ProtocolMessage.WIRE,
            requestId = jobId.raw,
            action = record.action,
            input = record.input.mapValues { (_, v) -> kotlinx.serialization.json.JsonPrimitive(v) },
        )
        val request = execution.copy(
            stdinBytes = (codec.encode(invoke) + "\n").toByteArray(),
            // A plugin may request a bounded prompt after receiving invoke. Keep stdin
            // open so the matching prompt_response can be delivered on the same stream.
            interactive = true,
            label = "job-${jobId.raw}",
        )

        record = advance(record, JobState.STARTING)
        val handle = try {
            runtime.execute(request)
        } catch (e: Exception) {
            return fail(record, e.message ?: "process failed to start", null)
        }
        record = mutate(record.copy(processId = handle.processId)) {
            it.copy(state = JobState.RUNNING, startedAtMillis = clock())
        }.also { emit(JobEvent.StateChanged(jobId, JobState.STARTING, JobState.RUNNING, clock())) }

        // per-job stream state: line framing + protocol outcome
        var outcome: Outcome? = null
        val lineBuffer = StringBuilder()

        try {
            withTimeout(spec.timeoutMillis ?: defaultTimeoutMillis) {
                runtime.output(handle.processId).collect { chunk ->
                    when (chunk) {
                        is ProcessOutput.Stdout -> {
                            lineBuffer.append(chunk.bytes.toString(Charsets.UTF_8))
                            if (lineBuffer.length > MAX_PROTOCOL_BUFFER_CHARS && '\n' !in lineBuffer) {
                                outcome = protocolFailure(jobId, "protocol line exceeded $MAX_PROTOCOL_BUFFER_CHARS characters")
                                lineBuffer.clear()
                                runtime.terminate(handle.processId)
                                return@collect
                            }
                            while (true) {
                                val newline = lineBuffer.indexOf('\n')
                                if (newline < 0) break
                                val line = lineBuffer.substring(0, newline).trim()
                                lineBuffer.delete(0, newline + 1)
                                if (line.isBlank()) continue
                                if (outcome is Outcome.Terminal) {
                                    outcome = protocolFailure(jobId, "message received after terminal protocol event")
                                    continue
                                }
                                consumeProtocolLine(jobId, line) { terminal ->
                                    if (outcome == null) outcome = terminal
                                }
                            }
                        }
                        is ProcessOutput.Stderr -> {
                            val line = chunk.bytes.toString(Charsets.UTF_8).trim()
                            if (line.isNotBlank()) {
                                emit(JobEvent.LogLine(jobId, line, "stderr", clock()))
                            }
                        }
                        is ProcessOutput.Exited -> {
                            val current = outcome
                            outcome = when (current) {
                                is Outcome.Terminal -> Outcome.Terminal(current.message, chunk.exitStatus)
                                else -> Outcome.NoTerminal(chunk.exitStatus)
                            }
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            runtime.terminate(handle.processId)
            return fail(record, "job timed out after ${spec.timeoutMillis ?: defaultTimeoutMillis}ms", null)
        }

        return finish(record, outcome)
    }

    // ---- provisioning ---------------------------------------------------------

    /**
     * Enters [JobState.INSTALLING_DEPENDENCIES] for a provisioning-only job
     * (or before [runWithProvisioning] is called). Broadcasts the legal
     * QUEUED → PREPARING → INSTALLING_DEPENDENCIES transitions.
     */
    public fun beginProvisioning(jobId: JobId): JobRecord {
        var record = records[jobId] ?: store?.find(jobId)
            ?: throw IllegalStateException("unknown job '${jobId.raw}'")
        record = advance(record, JobState.PREPARING)
        record = advance(record, JobState.INSTALLING_DEPENDENCIES)
        return record
    }

    /**
     * Broadcasts a provisioning [JobEvent.Progress] as an install advances.
     * Only legal while the job is [JobState.INSTALLING_DEPENDENCIES] — the
     * guard catches callers that report progress outside provisioning.
     */
    public fun emitProvisionProgress(jobId: JobId, value: Double, message: String?) {
        val record = records[jobId] ?: store?.find(jobId)
            ?: throw IllegalStateException("unknown job '${jobId.raw}'")
        require(record.state == JobState.INSTALLING_DEPENDENCIES) {
            "progress reported while job '${jobId.raw}' is ${record.state} (not INSTALLING_DEPENDENCIES)"
        }
        emit(JobEvent.Progress(jobId, value, message, clock()))
    }

    /** Broadcasts a diagnostic line from the provisioning toolchain (apt/pip/npm). */
    public fun emitProvisionLog(jobId: JobId, line: String, channel: String = "provision") {
        emit(JobEvent.LogLine(jobId, line, channel, clock()))
    }

    /**
     * Terminates a provisioning-only job: COMPLETED on [success], else FAILED
     * carrying [error]. Verified against the state machine — both edges are
     * legal from INSTALLING_DEPENDENCIES.
     */
    public fun finishProvision(jobId: JobId, success: Boolean, error: String? = null): JobRecord {
        var record = records[jobId] ?: store?.find(jobId)
            ?: throw IllegalStateException("unknown job '${jobId.raw}'")
        if (record.state.terminal) return record
        return if (success) {
            record = advance(record, JobState.COMPLETED)
            emit(JobEvent.Completed(jobId, output = mapOf("provisioned" to "ok"), clock()))
            record
        } else {
            fail(record, error ?: "dependency provisioning failed", null)
        }
    }

    // ---- cancel ------------------------------------------------------------------

    /**
     * Requests cancellation: CANCELLING → runtime §53 escalation
     * (SIGTERM → grace → SIGKILL) → CANCELLED.
     */
    public suspend fun cancel(jobId: JobId): JobRecord {
        val record = records[jobId] ?: store?.find(jobId)
        ?: throw IllegalStateException("unknown job '${jobId.raw}'")
        if (record.state.terminal) return record

        val cancelling = advance(record, JobState.CANCELLING)
        pendingPrompts.remove(jobId)
        record.processId?.let { runtime.terminate(it) }
        val cancelled = mutate(cancelling) {
            it.copy(state = JobState.CANCELLED, completedAtMillis = clock())
        }
        emit(JobEvent.StateChanged(jobId, JobState.CANCELLING, JobState.CANCELLED, clock()))
        emit(JobEvent.Cancelled(jobId, clock()))
        return cancelled
    }

    // ---- queries --------------------------------------------------------------------

    public fun get(jobId: JobId): JobRecord? = records[jobId] ?: store?.find(jobId)

    /**
     * Removes one finished job from memory and durable history. A live job is refused —
     * cancel it first — so no process or provisioning run loses its record mid-flight.
     * Returns false when [jobId] is unknown.
     */
    public fun delete(jobId: JobId): Boolean {
        val record = get(jobId) ?: return false
        check(record.terminal) { "job '${jobId.raw}' is ${record.state}; cancel it before deleting" }
        records.remove(jobId)
        specs.remove(jobId)
        pendingPrompts.remove(jobId)
        return store?.delete(jobId) ?: true
    }

    public fun active(): List<JobRecord> =
        records.values.filter { !it.terminal }

    /** Returns the live prompt for [jobId], if that job is waiting on the host. */
    public fun pendingPrompt(jobId: JobId): JobPrompt? = pendingPrompts[jobId]

    /**
     * Delivers one validated response to a PAUSED process. The response value is
     * written directly to stdin and is intentionally absent from JobEvent/store.
     */
    public fun respondToPrompt(jobId: JobId, promptId: String, response: JobPromptResponse): JobRecord {
        val prompt = pendingPrompts[jobId]
            ?: throw IllegalStateException("job '${jobId.raw}' has no pending prompt")
        require(prompt.id == promptId) {
            "prompt '$promptId' does not match pending prompt '${prompt.id}'"
        }
        validateResponse(prompt, response)
        val record = records[jobId] ?: store?.find(jobId)
            ?: throw IllegalStateException("unknown job '${jobId.raw}'")
        check(record.state == JobState.PAUSED) {
            "job '${jobId.raw}' cannot accept a prompt response while ${record.state}"
        }
        val processId = record.processId
            ?: throw IllegalStateException("paused job '${jobId.raw}' has no live process")
        val wire = ProtocolMessage.PromptResponse(
            protocol = ProtocolMessage.WIRE,
            requestId = jobId.raw,
            promptId = promptId,
            status = when (response.status) {
                JobPromptResponseStatus.SUBMITTED -> ProtocolMessage.RESPONSE_SUBMITTED
                JobPromptResponseStatus.CANCELLED -> ProtocolMessage.RESPONSE_CANCELLED
            },
            value = response.value?.let { kotlinx.serialization.json.JsonPrimitive(it) },
        )
        // Do not clear or resume until the write succeeds. A failed write leaves a
        // visible prompt that the user may retry or cancel with the job itself.
        runtime.writeStdin(processId, (codec.encode(wire) + "\n").toByteArray())
        pendingPrompts.remove(jobId, prompt)
        emit(JobEvent.PromptResolved(jobId, promptId, response.status, clock()))
        return advance(record, JobState.RUNNING)
    }

    /**
     * Fails jobs the store left in a non-terminal state (§31 recovery). A job is only
     * driven by a live in-process coroutine, so any RUNNING/STARTING/… record found at
     * startup belongs to a previous process that was killed — its runtime process died
     * with it and can never report a terminal message. Left alone it shows RUNNING
     * forever. Call once during composition-root init, before the UI reads history.
     *
     * @return the number of orphaned jobs marked FAILED.
     */
    public fun reconcileOrphans(): Int {
        val store = store ?: return 0
        var failed = 0
        for (record in store.active()) {
            if (record.terminal) continue
            // A job with a still-alive runtime child is NOT an orphan: the app
            // process may have been recreated (activity relaunch) while the child
            // kept executing. Only fail jobs whose process is provably gone —
            // auto-failing a live run would strand its real process.
            val liveProcessId = record.processId
            if (liveProcessId != null && runtime.isProcessAlive(liveProcessId)) continue
            if (!JobStateMachine.canTransition(record.state, JobState.FAILED)) continue
            val reason = "process did not survive an app restart (was ${record.state})"
            val next = record.copy(
                state = JobState.FAILED,
                error = reason,
                processId = null,
                completedAtMillis = clock(),
            )
            records[next.id] = next
            store.update(next)
            emit(JobEvent.StateChanged(record.id, record.state, JobState.FAILED, clock()))
            emit(JobEvent.Failed(record.id, reason, null, clock()))
            failed++
        }
        return failed
    }

    // ---- internals --------------------------------------------------------------------

    /** Consumes one framed stdout line: protocol event, or diagnostic log (§16). */
    private fun consumeProtocolLine(jobId: JobId, line: String, onTerminal: (Outcome) -> Unit) {
        val record = records[jobId]
            ?: throw IllegalStateException("unknown job '${jobId.raw}' while consuming output")
        val decoded = try {
            codec.decode(line)
        } catch (e: Exception) {
            // §16: non-protocol stdout is diagnostic output, never truth
            emit(JobEvent.LogLine(record.id, line, "stdout", clock()))
            return
        }
        if (decoded.requestId != jobId.raw) {
            onTerminal(protocolFailure(jobId, "requestId '${decoded.requestId}' does not match running job '${jobId.raw}'"))
            return
        }
        when (decoded) {
            is ProtocolMessage.Progress ->
                emit(JobEvent.Progress(record.id, decoded.value, decoded.message, clock()))
            is ProtocolMessage.Log ->
                emit(JobEvent.LogLine(record.id, decoded.message, "plugin:${decoded.level}", clock()))
            is ProtocolMessage.Prompt -> {
                if (record.state != JobState.RUNNING) {
                    onTerminal(protocolFailure(jobId, "prompt '${decoded.promptId}' received while job is ${record.state}"))
                    return
                }
                val prompt = decoded.toJobPrompt()
                if (pendingPrompts.putIfAbsent(jobId, prompt) != null) {
                    onTerminal(protocolFailure(jobId, "job already has a pending prompt"))
                    return
                }
                advance(record, JobState.PAUSED)
                emit(JobEvent.PromptRequested(jobId, prompt, clock()))
            }
            is ProtocolMessage.Result -> {
                if (record.state == JobState.PAUSED) {
                    onTerminal(protocolFailure(jobId, "terminal result received before pending prompt was answered"))
                } else {
                    onTerminal(Outcome.Terminal(decoded, null))
                }
            }
            is ProtocolMessage.Error_ -> onTerminal(Outcome.Terminal(decoded, null))
            is ProtocolMessage.Invoke, is ProtocolMessage.PromptResponse ->
                onTerminal(protocolFailure(jobId, "plugin emitted a host-to-plugin '${decoded::class.simpleName}' message"))
        }
    }

    private fun finish(record: JobRecord, outcome: Outcome?): JobRecord {
        // cancellation wins: cancel() already moved the record into CANCELLING
        val current = records[record.id] ?: record
        if (current.state == JobState.CANCELLING || current.state == JobState.CANCELLED) {
            return mutate(current) {
                it.copy(state = JobState.CANCELLED, completedAtMillis = clock())
            }
        }
        val finished = when (outcome) {
            is Outcome.Terminal -> when (val message = outcome.message) {
                is ProtocolMessage.Result -> {
                    val cleanExit = (outcome.exit as? ExitStatus.Exited)?.code == 0 || outcome.exit == null
                    if (message.status == ProtocolMessage.RESULT_SUCCESS && cleanExit) {
                        complete(current, message)
                    } else {
                        fail(current, "plugin reported failure status '${message.status}'", outcome.exit)
                    }
                }
                is ProtocolMessage.Error_ -> fail(current, "${message.code}: ${message.message}", outcome.exit)
                is ProtocolMessage.Progress,
                is ProtocolMessage.Log,
                is ProtocolMessage.Invoke,
                is ProtocolMessage.Prompt,
                is ProtocolMessage.PromptResponse,
                -> fail(current, "non-terminal message ended the stream", outcome.exit)
            }
            is Outcome.NoTerminal -> fail(
                current,
                when (outcome.exit) {
                    is ExitStatus.Signaled -> "process terminated by signal ${outcome.exit.signal}"
                    else -> "process ended without a terminal protocol message (§15 requires result or error)"
                },
                outcome.exit,
            )
            null -> fail(current, "job produced no output", null)
        }
        pendingPrompts.remove(record.id)
        return finished
    }

    private fun complete(record: JobRecord, result: ProtocolMessage.Result): JobRecord {
        val completed = mutate(record.copy(output = result.output.mapValues { (_, v) -> v.toString().trim('"') })) {
            it.copy(state = JobState.COMPLETED, completedAtMillis = clock())
        }
        emit(JobEvent.StateChanged(record.id, record.state, JobState.COMPLETED, clock()))
        emit(JobEvent.Completed(record.id, completed.output, clock()))
        return completed
    }

    private fun advance(record: JobRecord, to: JobState): JobRecord {
        val next = JobStateMachine.transition(record.state, to)
        return mutate(record) { it.copy(state = next) }
            .also { emit(JobEvent.StateChanged(record.id, record.state, next, clock())) }
    }

    private fun mutate(record: JobRecord, body: (JobRecord) -> JobRecord): JobRecord {
        val next = body(record)
        records[next.id] = next
        store?.update(next)
        return next
    }

    private fun fail(record: JobRecord, reason: String, exit: ExitStatus?): JobRecord {
        val current = records[record.id] ?: record
        pendingPrompts.remove(record.id)
        val failed = mutate(current) {
            it.copy(state = JobState.FAILED, error = reason, completedAtMillis = clock())
        }
        emit(JobEvent.StateChanged(record.id, current.state, JobState.FAILED, clock()))
        emit(JobEvent.Failed(record.id, reason, exit, clock()))
        return failed
    }

    private fun protocolFailure(jobId: JobId, reason: String): Outcome.Terminal =
        Outcome.Terminal(
            ProtocolMessage.Error_(
                protocol = ProtocolMessage.WIRE,
                requestId = jobId.raw,
                code = "PROTOCOL_ERROR",
                message = reason,
            ),
            null,
        )

    private fun ProtocolMessage.Prompt.toJobPrompt(): JobPrompt = JobPrompt(
        id = promptId,
        kind = when (kind) {
            ProtocolMessage.PROMPT_CONFIRM -> JobPromptKind.CONFIRM
            ProtocolMessage.PROMPT_TEXT -> JobPromptKind.TEXT
            ProtocolMessage.PROMPT_PASSWORD -> JobPromptKind.PASSWORD
            ProtocolMessage.PROMPT_SELECT -> JobPromptKind.SELECT
            else -> error("validated codec returned unsupported prompt kind '$kind'")
        },
        title = title,
        message = message,
        required = required,
        choices = choices,
        default = default,
        placeholder = placeholder,
    )

    private fun validateResponse(prompt: JobPrompt, response: JobPromptResponse) {
        if (response.status == JobPromptResponseStatus.CANCELLED) {
            require(response.value == null) { "cancelled prompt response must not include a value" }
            return
        }
        val value = response.value
        require(value == null || value.length <= MAX_PROMPT_RESPONSE_CHARS) {
            "prompt response exceeds $MAX_PROMPT_RESPONSE_CHARS characters"
        }
        if (prompt.required) require(!value.isNullOrBlank()) { "prompt '${prompt.id}' requires a value" }
        when (prompt.kind) {
            JobPromptKind.CONFIRM -> require(value in setOf("true", "false")) {
                "confirm prompt '${prompt.id}' requires true or false"
            }
            JobPromptKind.SELECT -> require(value in prompt.choices) {
                "selection for prompt '${prompt.id}' must be one of its declared choices"
            }
            JobPromptKind.TEXT, JobPromptKind.PASSWORD -> Unit
        }
    }

    private fun emit(event: JobEvent) {
        store?.appendEvent(event)
        _events.tryEmit(event)
    }

    private companion object {
        const val MAX_PROTOCOL_BUFFER_CHARS: Int = 65_536
        const val MAX_PROMPT_RESPONSE_CHARS: Int = 16_384
    }
}
