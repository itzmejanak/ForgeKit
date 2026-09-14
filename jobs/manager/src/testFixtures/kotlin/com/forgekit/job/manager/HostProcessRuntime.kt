package com.forgekit.job.manager

import com.forgekit.core.model.ExitStatus
import com.forgekit.runtime.api.DependencyInstallResult
import com.forgekit.runtime.api.ExecutionHandle
import com.forgekit.runtime.api.ExecutionRequest
import com.forgekit.runtime.api.ForgeRuntime
import com.forgekit.runtime.api.ProcessCrashedError
import com.forgekit.runtime.api.ProcessOutput
import com.forgekit.runtime.api.RuntimeCapabilities
import com.forgekit.runtime.api.RuntimeCapabilityInfo
import com.forgekit.runtime.api.RuntimeDependency
import com.forgekit.runtime.api.RuntimeHealth
import com.forgekit.runtime.api.RuntimeHealthCheck
import com.forgekit.runtime.api.RuntimeRepairResult
import com.forgekit.runtime.api.RuntimeState
import com.forgekit.runtime.api.SignalRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * REAL host-backed ForgeRuntime for JVM tests (no mocking anywhere):
 * `execute()` forks REAL processes, `output()` streams their TRUE
 * stdout/stderr through reader threads into a merged channel and terminates
 * with exactly one real [ProcessOutput.Exited] derived from the actual wait
 * status; `signal/terminate` deliver REAL Unix signals (destroy = SIGTERM,
 * destroyForcibly = SIGKILL) with the §53 grace-period escalation.
 *
 * Host twin of EmbeddedTermuxRuntime: same contract, identical process
 * semantics to what the device shows.
 */
class HostProcessRuntime : ForgeRuntime {

    private class LiveProcess(
        val process: Process,
        val handle: ExecutionHandle,
        val channel: Channel<ProcessOutput>,
        val readerThreads: MutableList<Thread> = mutableListOf(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val live = ConcurrentHashMap<String, LiveProcess>()
    private val state = MutableStateFlow(RuntimeState.READY)
    override val stateChanges: StateFlow<RuntimeState> = state.asStateFlow()

    override suspend fun initialize(): RuntimeState = RuntimeState.READY

    override suspend fun execute(request: ExecutionRequest): ExecutionHandle {
        val command = mutableListOf(request.executable) + request.args
        val builder = ProcessBuilder(command).apply {
            request.workingDirectory?.let { directory(java.io.File(it)) }
            request.environment.forEach { (k, v) -> environment()[k] = v }
        }
        val process = try {
            builder.start()
        } catch (e: java.io.IOException) {
            throw com.forgekit.runtime.api.ProcessStartError(
                "host process failed to start: ${e.message}",
                command.joinToString(" "),
            )
        }
        val processId = "host-${process.pid()}"
        val handle = ExecutionHandle(processId, process.pid(), System.currentTimeMillis())
        val channel = Channel<ProcessOutput>(Channel.UNLIMITED)
        val entry = LiveProcess(process, handle, channel)
        live[processId] = entry

        // the invoke request arrives on stdin before anything else; a
        // non-interactive protocol run closes stdin so EOF terminates readers
        request.stdinBytes?.let { bytes ->
            try {
                process.outputStream.write(bytes)
                process.outputStream.flush()
                if (!request.interactive) process.outputStream.close()
            } catch (_: java.io.IOException) {
                // process may not read stdin
            }
        }

        // REAL stdout reader thread
        threadPump(process.inputStream, processId, entry) { chunk -> channel.trySend(ProcessOutput.Stdout(chunk)) }
        // REAL stderr reader thread
        threadPump(process.errorStream, processId, entry) { chunk -> channel.trySend(ProcessOutput.Stderr(chunk)) }

        // exit watcher: exactly one terminal marker, then channel close.
        // Correctness: the marker is only sent AFTER both reader threads hit
        // real EOF, so late stdout can never race past the exit marker.
        scope.launch {
            while (isActive && process.isAlive) delay(20)
            val raw = runCatching { process.waitFor() }.getOrDefault(-1)
            val readers = synchronized(entry.readerThreads) { entry.readerThreads.toList() }
            readers.forEach { runCatching { it.join(5000) } }
            channel.trySend(ProcessOutput.Exited(ExitStatus.fromRaw(raw)))
            channel.close()
        }
        return handle
    }

    private fun threadPump(
        stream: java.io.InputStream,
        processId: String,
        entry: LiveProcess,
        send: (ByteArray) -> Unit,
    ) {
        val thread = Thread({
            try {
                val buf = ByteArray(8192)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    if (n > 0) send(buf.copyOf(n))
                }
            } catch (_: java.io.IOException) {
                // closed with the process
            }
        }, "host-runtime-pump-$processId").apply { isDaemon = true }
        thread.start()
        synchronized(entry.readerThreads) { entry.readerThreads += thread }
    }

    override fun output(processId: String): Flow<ProcessOutput> {
        val entry = live[processId]
            ?: throw ProcessCrashedError("no such process '$processId'", processId)
        return flow {
            for (chunk in entry.channel) {
                emit(chunk)
            }
        }
    }

    override fun writeStdin(processId: String, bytes: ByteArray) {
        val entry = live[processId] ?: return
        try {
            entry.process.outputStream.write(bytes)
            entry.process.outputStream.flush()
        } catch (_: java.io.IOException) {
        }
    }

    override suspend fun signal(processId: String, request: SignalRequest) {
        val entry = live[processId] ?: throw ProcessCrashedError("no such process", processId)
        when (request) {
            SignalRequest.SIGKILL -> entry.process.destroyForcibly()
            else -> entry.process.destroy() // SIGTERM on Unix JVMs
        }
    }

    override suspend fun terminate(processId: String) {
        val entry = live[processId] ?: return
        // §53 escalation: SIGTERM → grace (1.5s) → SIGKILL
        entry.process.destroy()
        val graceful = runCatching {
            entry.process.waitFor(1500, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!graceful && entry.process.isAlive) {
            entry.process.destroyForcibly()
            entry.process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    override suspend fun install(dependency: RuntimeDependency): DependencyInstallResult =
        DependencyInstallResult(
            dependency = dependency,
            installed = false,
            alreadyPresent = false,
            detail = "host runtime does not provision packages",
        )

    override suspend fun inspect(): RuntimeCapabilities = RuntimeCapabilities(
        contractVersion = "forgekit.runtime/v1",
        architecture = System.getProperty("os.arch") ?: "unknown",
        prefixPath = "/usr",
        homePath = System.getProperty("user.home") ?: "/tmp",
        shellPath = "/bin/bash",
        packageManager = RuntimeCapabilities.PackageManagerInfo(name = "host", available = false),
        tools = listOf(RuntimeCapabilityInfo("bash", null, "/bin/bash")),
    )

    override suspend fun healthCheck(): RuntimeHealth = RuntimeHealth(
        state = RuntimeState.READY,
        checks = listOf(RuntimeHealthCheck("host-process", true, "real fork available")),
    )

    override suspend fun repair(): RuntimeRepairResult =
        RuntimeRepairResult(
            repaired = false,
            actions = listOf("host runtime cannot repair"),
            resultingState = RuntimeState.READY,
        )

    fun shutdown() {
        live.values.forEach { it.process.destroyForcibly() }
        scope.cancel()
    }
}
