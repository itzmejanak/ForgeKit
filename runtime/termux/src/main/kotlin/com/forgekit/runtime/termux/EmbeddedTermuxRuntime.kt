package com.forgekit.runtime.termux

import com.forgekit.core.filesystem.ForgePaths
import com.forgekit.core.logging.ForgeLogger
import com.forgekit.core.logging.LogCategory
import com.forgekit.core.model.ExitStatus
import com.forgekit.runtime.api.DependencyInstallResult
import com.forgekit.runtime.api.ExecutionHandle
import com.forgekit.runtime.api.ExecutionRequest
import com.forgekit.runtime.api.ForgeRuntime
import com.forgekit.runtime.api.InstallEvent
import com.forgekit.runtime.api.InstallPhase
import com.forgekit.runtime.api.ProcessCrashedError
import com.forgekit.runtime.api.ProcessOutput
import com.forgekit.runtime.api.ProcessStartError
import com.forgekit.runtime.api.RuntimeCapabilities
import com.forgekit.runtime.api.RuntimeCapabilityInfo
import com.forgekit.runtime.api.RuntimeDependency
import com.forgekit.runtime.api.RuntimeHealth
import com.forgekit.runtime.api.RuntimeHealthCheck
import com.forgekit.runtime.api.RuntimeRepairResult
import com.forgekit.runtime.api.RuntimeState
import com.forgekit.runtime.api.RuntimeUnavailableError
import com.forgekit.runtime.api.SignalRequest
import com.forgekit.runtime.api.StreamingInstaller
import com.forgekit.runtime.bootstrap.BootstrapDescriptor
import com.forgekit.runtime.bootstrap.BootstrapOrchestrator
import com.forgekit.runtime.bootstrap.BootstrapTargets
import com.forgekit.runtime.bootstrap.RuntimeRepairer
import com.forgekit.termux.embedded.PtyProcess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path

/**
 * The REAL ForgeKit runtime: [ForgeRuntime] implemented over the embedded Termux
 * bootstrap area and the native PTY harness (ARCHITECTURE §33, §69-71).
 *
 * Execution model:
 *  - every process is a real fork+openpty+execve via [PtyProcess] (termux/embedded);
 *  - PIPES sessions run on a RAW pty (binary-safe stdin/stdout);
 *  - PTY sessions run COOKED (terminal semantics: echo, line discipline, ISIG);
 *  - exec paths are policy-checked: only $PREFIX and app-owned directories.
 *
 * Host/device symmetry: the identical class runs in JVM tests against a REAL host
 * prefix (real binaries, real exec, real signals) and on Android against the real
 * arm64 bootstrap — no code branch distinguishes the two.
 */
public class EmbeddedTermuxRuntime(
    /** ForgeKit filesystem contract (§11); root is injected by the platform layer. */
    public val paths: ForgePaths,
    /** Bootstrap archive descriptor (bundled APK asset on device, file in tests). */
    private val bootstrapDescriptor: BootstrapDescriptor,
    private val logger: ForgeLogger = ForgeLogger(LogCategory.RUNTIME, {}),
    /** Grace period before SIGKILL escalation in terminate() (§53). */
    private val terminateGraceMillis: Long = 5_000,
    /** Timeout for prefix command runs (health checks, install). */
    private val commandTimeoutMillis: Long = 120_000,
) : ForgeRuntime, StreamingInstaller {

    private val state = MutableStateFlow(RuntimeState.NOT_INSTALLED)
    override val stateChanges: StateFlow<RuntimeState> = state.asStateFlow()

    private val registry = RuntimeProcessRegistry()

    private val orchestrator = BootstrapOrchestrator(
        BootstrapTargets.of(paths.termuxDir, stagingDir = paths.metadataDir),
        logger,
    )
    private val repairer = RuntimeRepairer(orchestrator, logger)

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override suspend fun initialize(): RuntimeState {
        when (state.value) {
            RuntimeState.READY -> return RuntimeState.READY
            RuntimeState.INSTALLING, RuntimeState.INITIALIZING, RuntimeState.REPAIRING ->
                return state.value // single-flight: the active run reports via stateChanges
            else -> Unit
        }
        setState(RuntimeState.INSTALLING)

        // Fast path: prefix already valid (previous install survived).
        if (isPrefixInstalled()) {
            setState(RuntimeState.INITIALIZING)
            val initError = initializePackageManager()
            if (initError == null) {
                setState(RuntimeState.READY)
                logger.info("init", "runtime ready (existing prefix)")
                return RuntimeState.READY
            }
            logger.warn("init", "existing prefix failed package-manager init: $initError")
            setState(RuntimeState.DEGRADED)
            return RuntimeState.DEGRADED
        }

        // Cold path: verify → extract the real bootstrap (§69).
        val boot = orchestrator.verifyAndExtract(bootstrapDescriptor)
        when (boot) {
            is BootstrapOrchestrator.BootResult.VerificationFailed -> {
                setState(RuntimeState.FAILED)
                throw RuntimeUnavailableError("bootstrap verification failed", boot.reason)
            }
            is BootstrapOrchestrator.BootResult.InitializationFailed -> {
                setState(RuntimeState.FAILED)
                throw RuntimeUnavailableError("bootstrap extraction failed", boot.reason)
            }
            is BootstrapOrchestrator.BootResult.Ready -> {
                logger.info(
                    "init",
                    "bootstrap extracted: ${boot.extraction.filesWritten} files, " +
                        "${boot.extraction.symlinksCreated} symlinks",
                )
            }
        }

        setState(RuntimeState.INITIALIZING)
        val initError = initializePackageManager()
        if (initError != null) {
            setState(RuntimeState.FAILED)
            throw RuntimeUnavailableError("package manager initialization failed", initError)
        }
        setState(RuntimeState.READY)
        logger.info("init", "runtime ready (fresh bootstrap)")
        return RuntimeState.READY
    }

    private suspend fun initializePackageManager(): String? {
        paths.ensureSkeleton()
        // §69: initialize environment BEFORE the package manager — it creates $HOME,
        // $TMPDIR and the apt config/state directories the bootstrap does not ship.
        orchestrator.initializeEnvironment(runtimeArchitecture(), bootstrapDescriptor)?.let { return it }
        val initializer = prefixCommandRunner()
        orchestrator.initializePackageManager(bootstrapDescriptor, initializer)?.let { return it }
        // A package transaction from a previous process (or the interactive terminal) may have
        // completed in dpkg but not in ForgeKit's full relocation/configuration phase. Reconcile
        // that durable state before advertising the runtime as READY.
        return orchestrator.reconcileRelocationIfPending(bootstrapDescriptor, initializer)
    }

    private fun isPrefixInstalled(): Boolean =
        Files.isRegularFile(paths.termuxPrefix.resolve("var/lib/dpkg/status")) &&
            Files.isExecutable(paths.termuxPrefix.resolve("bin/sh"))

    private fun setState(next: RuntimeState) {
        state.value = next
    }

    // ------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------

    override suspend fun execute(request: ExecutionRequest): ExecutionHandle {
        ensureReadyForExecution()
        val executable = resolveExecutable(request.executable)
        val cwd = resolveWorkingDirectory(request.workingDirectory)

        val channel = try {
            val process = PtyProcess.start(
                command = listOf(executable) + request.args,
                environment = environmentFor(request),
                cwd = cwd,
                rows = request.terminalRows,
                cols = request.terminalColumns,
                rawMode = request.sessionType == ExecutionRequest.SessionType.PIPES,
            )
            PtyExecutionChannel(process, terminal = request.sessionType == ExecutionRequest.SessionType.PTY)
        } catch (e: Exception) {
            throw ProcessStartError(
                "failed to start '${request.executable}'",
                "${e.message} (prefix=${paths.termuxPrefix})",
            )
        }

        request.stdinBytes?.let { channel.writeStdin(it) }

        val live = registry.register(channel, request.label)
        logger.info(
            "exec",
            "started ${request.label}: pid=${live.handle.pid} exe=$executable",
            mapOf("processId" to live.id),
        )
        return live.handle
    }

    override fun output(processId: String): Flow<ProcessOutput> {
        val live = registry.get(processId)
            ?: throw ProcessCrashedError("unknown process", "no live process with id '$processId'")
        return live.channel.output
    }

    override fun writeStdin(processId: String, bytes: ByteArray) {
        val live = registry.get(processId)
            ?: throw ProcessCrashedError("unknown process", "no live process with id '$processId'")
        live.channel.writeStdin(bytes)
    }

    /** Terminal session for PTY-backed processes (terminal UI surface). */
    /**
     * The interactive terminal shell: bash in a PTY at `$HOME`, started with ForgeKit's
     * [InteractiveShellProfile] (colored prompt and color aliases, user `~/.bashrc` last)
     * and color-capable environment. Writes the profile first; call off the main thread.
     */
    public fun interactiveShellRequest(columns: Int, rows: Int): ExecutionRequest {
        val profile = InteractiveShellProfile.install(paths.shellDir)
        return ExecutionRequest(
            executable = paths.termuxPrefix.resolve("bin/bash").toString(),
            args = listOf("--rcfile", profile.toString()),
            environment = InteractiveShellProfile.environment,
            workingDirectory = paths.termuxHome.toString(),
            interactive = true,
            sessionType = ExecutionRequest.SessionType.PTY,
            terminalColumns = columns,
            terminalRows = rows,
            label = "shell",
        )
    }

    public fun terminalSession(processId: String): com.forgekit.runtime.bridge.TerminalSession? =
        registry.get(processId)?.let { PtyTerminalSession(it.channel) }

    override suspend fun signal(processId: String, request: SignalRequest) {
        val live = registry.get(processId)
            ?: throw ProcessCrashedError("unknown process", "no live process with id '$processId'")
        live.channel.signal(request)
    }

    override suspend fun terminate(processId: String) {
        val live = registry.get(processId)
            ?: throw ProcessCrashedError("unknown process", "no live process with id '$processId'")
        // §53: SIGTERM → grace → SIGKILL. Non-cooperative processes are killed.
        live.channel.signal(SignalRequest.SIGTERM)
        val exited = runCatching {
            withTimeout(terminateGraceMillis) { live.channel.awaitExit() }
        }.isSuccess
        if (!exited) {
            logger.warn("terminate", "SIGTERM grace expired for $processId — escalating to SIGKILL")
            live.channel.signal(SignalRequest.SIGKILL)
            runCatching { withTimeout(terminateGraceMillis) { live.channel.awaitExit() } }
        }
    }

    /**
     * True while the OS process for this runtime id is still alive. The registry
     * keeps ids for the life of the app (ids are stable, not OS pids), so this
     * reads the recorded pid — the only truthful liveness signal (§31 recovery).
     */
    override fun isProcessAlive(processId: String): Boolean {
        val live = registry.get(processId) ?: return false
        return pidAlive(live.handle.pid)
    }

    /** Linux pid liveness probe: /proc/&lt;pid&gt; exists (Android/termux always has procfs). */
    private fun pidAlive(pid: Long): Boolean {
        return runCatching { java.io.File("/proc/$pid").exists() }.getOrDefault(true)
    }

    // ------------------------------------------------------------------
    // Inspection & health
    // ------------------------------------------------------------------

    override suspend fun inspect(): RuntimeCapabilities {
        val prefix = paths.termuxPrefix
        val tools = TOOLS_PROBED_ON_DISK
            .mapNotNull { name -> probeTool(prefix, name) }
        val pkg = listOf("pkg", "apt")
            .firstOrNull { Files.isExecutable(prefix.resolve("bin/$it")) }
        return RuntimeCapabilities(
            contractVersion = FORGEKIT_RUNTIME_CONTRACT,
            architecture = runtimeArchitecture(),
            prefixPath = prefix.toString(),
            homePath = paths.termuxHome.toString(),
            shellPath = prefix.resolve("bin/bash").toString(),
            packageManager = RuntimeCapabilities.PackageManagerInfo(
                name = pkg ?: "none",
                available = pkg != null,
            ),
            tools = tools,
        )
    }

    override suspend fun healthCheck(): RuntimeHealth {
        val checks = mutableListOf<RuntimeHealthCheck>()
        val prefix = paths.termuxPrefix
        val env = paths.termuxEnvironment()

        // §71 battery — every check probes the REAL filesystem/process state.
        checks += check("shell-executable") {
            val bash = prefix.resolve("bin/bash")
            require(Files.isExecutable(bash)) { "$bash missing or not executable" }
            "$bash"
        }
        checks += check("environment-variables") {
            val path = env.getValue("PATH")
            require(Files.isDirectory(Path.of(path))) { "PATH=$path does not exist" }
            val required = listOf("HOME", "PREFIX", "PATH", "LD_LIBRARY_PATH", "TMPDIR", "TERM", "LANG", "SHELL")
            val missing = required.filter { env[it].isNullOrBlank() }
            require(missing.isEmpty()) { "missing: $missing" }
            "8 vars resolved"
        }
        checks += check("filesystem-writable") {
            val probe = paths.termuxHome.resolve(".forgekit-write-probe")
            Files.newOutputStream(probe).use { it.write("probe".toByteArray()) }
            Files.deleteIfExists(probe)
            require(Files.isDirectory(paths.termuxTmp)) { "TMPDIR missing" }
            "home + tmp writable"
        }
        checks += check("package-manager-available") {
            val dpkgStatus = prefix.resolve("var/lib/dpkg/status")
            require(Files.isRegularFile(dpkgStatus)) { "dpkg database missing" }
            "dpkg status: ${Files.size(dpkgStatus)} bytes"
        }
        checks += check("dynamic-linker-works") {
            val out = runInPrefix("bin/sh", "-c", "echo linker-ok")
            require("linker-ok" in out) { "sh produced: ${out.take(200)}" }
            "sh exec ok"
        }
        checks += check("native-executable-works") {
            val out = runInPrefix("bin/env", "--version")
            require(out.isNotBlank()) { "env --version produced no output" }
            "env exec ok (${out.lineSequence().firstOrNull()?.take(60) ?: ""})"
        }

        val stateNow = state.value
        val finalState = if (stateNow == RuntimeState.READY) {
            if (checks.all { it.passed }) RuntimeState.READY else RuntimeState.DEGRADED
        } else stateNow
        if (finalState == RuntimeState.DEGRADED && stateNow == RuntimeState.READY) {
            setState(RuntimeState.DEGRADED)
        }
        return RuntimeHealth(state = finalState, checks = checks)
    }

    override suspend fun repair(): RuntimeRepairResult {
        setState(RuntimeState.REPAIRING)
        val result = repairer.repair(bootstrapDescriptor, prefixCommandRunner(), runtimeArchitecture())
        setState(result.resultingState)
        return result
    }

    // ------------------------------------------------------------------
    // Dependency installation
    // ------------------------------------------------------------------

    override suspend fun install(dependency: RuntimeDependency): DependencyInstallResult =
        installStreaming(dependency, NoOpInstallEvents)

    override suspend fun installStreaming(
        dependency: RuntimeDependency,
        events: FlowCollector<InstallEvent>,
    ): DependencyInstallResult {
        ensureReadyForExecution()
        return when (dependency.kind) {
            RuntimeDependency.Kind.TERMUX_PACKAGE -> installViaPackageManager(
                "pkg", listOf("install", "-y", dependency.name), dependency,
                fallback = "apt" to listOf("install", "-y", dependency.name),
                events = events,
            )
            RuntimeDependency.Kind.PYTHON_PACKAGE -> installViaPackageManager(
                "pip", listOf("install", dependency.name + (dependency.versionRequirement?.let { it } ?: "")), dependency,
                events = events,
            )
            RuntimeDependency.Kind.NODE_PACKAGE -> installViaPackageManager(
                "npm", listOf("install", "-g", dependency.name + (dependency.versionRequirement?.let { it } ?: "")), dependency,
                events = events,
            )
        }
    }

    private suspend fun installViaPackageManager(
        tool: String,
        args: List<String>,
        dependency: RuntimeDependency,
        fallback: Pair<String, List<String>>? = null,
        events: FlowCollector<InstallEvent>,
    ): DependencyInstallResult {
        val prefix = paths.termuxPrefix
        // Presence truth first, before any tool resolution: an already-installed
        // package needs no manager at all (dpkg-query alone decides, §10).
        events.emit(InstallEvent.Phase(dependency, InstallPhase.INSPECTING, "checking for already installed ${dependency.name}"))
        if (isInstalled(dependency)) {
            if (dependency.kind == RuntimeDependency.Kind.TERMUX_PACKAGE &&
                orchestrator.relocationReconciliationPending()
            ) {
                events.emit(
                    InstallEvent.Phase(
                        dependency,
                        InstallPhase.VERIFYING,
                        "finishing an interrupted runtime relocation",
                    ),
                )
                val failure = orchestrator.reconcileRelocationIfPending(
                    bootstrapDescriptor,
                    prefixCommandRunner(),
                )
                val confirmed = isInstalled(dependency)
                return DependencyInstallResult(
                    dependency = dependency,
                    installed = confirmed && failure == null,
                    alreadyPresent = true,
                    detail = when {
                        failure != null -> "present in package database, but $failure"
                        confirmed -> "already installed; interrupted relocation completed"
                        else -> "package disappeared while completing interrupted relocation"
                    },
                )
            }
            events.emit(InstallEvent.Phase(dependency, InstallPhase.VERIFYING, "already installed"))
            return DependencyInstallResult(dependency, installed = true, alreadyPresent = true, detail = "already installed")
        }
        val toolPath = prefix.resolve("bin/$tool")
        if (!Files.isExecutable(toolPath) && fallback != null) {
            return installViaPackageManager(fallback.first, fallback.second, dependency, null, events)
        }
        if (!Files.isExecutable(toolPath)) {
            events.emit(InstallEvent.Phase(dependency, InstallPhase.INSPECTING, "$tool missing in \$PREFIX"))
            return DependencyInstallResult(
                dependency = dependency,
                installed = false,
                alreadyPresent = false,
                detail = "$tool is not available in \$PREFIX (install it as a Termux package first)",
            )
        }

        var phase = InstallPhase.FETCHING
        events.emit(InstallEvent.Phase(dependency, phase, "installing ${dependency.name} via $tool"))
        if (dependency.kind == RuntimeDependency.Kind.TERMUX_PACKAGE) {
            // Write before apt/dpkg changes anything. Only a successful full relocation and
            // configure pass clears it, so process death can never turn presence into readiness.
            orchestrator.markRelocationPending()
        }
        // Streams REAL package-manager output: framed lines are classified into
        // phases (apt/pip markers) and short status lines are surfaced as raw
        // diagnostics (§75 — the output says WHY, never an invented percentage).
        val outcome = runCommandOutcome(
            listOf(toolPath.toString()) + args,
            timeoutMillis = commandTimeoutMillis,
        ) { line ->
            val next = InstallOutputParser.classify(line, phase)
            if (next != phase) {
                phase = next
                events.emit(InstallEvent.Phase(dependency, phase, line.take(160)))
            }
            if (InstallOutputParser.isStatusLine(line)) {
                events.emit(InstallEvent.Line(dependency, line.take(160)))
            }
        }
        events.emit(InstallEvent.Phase(dependency, InstallPhase.VERIFYING, "verifying against the package database"))
        // Files that just arrived from a .deb still carry the bootstrap's build-time
        // paths — including the shebangs of maintainer scripts and of installed tools
        // like pip. Relocate them, then let dpkg finish whatever that blocked.
        val relocationFailure = if (dependency.kind == RuntimeDependency.Kind.TERMUX_PACKAGE) {
            orchestrator.relocateAndConfigure(bootstrapDescriptor, prefixCommandRunner())
        } else {
            null
        }
        // Verify against the package database rather than trusting the exit code (§10:
        // never report an install that was not confirmed). apt legitimately exits
        // non-zero here and still ends up complete: a maintainer script fails on the
        // stale build-time paths mid-run, and the relocation above then repairs and
        // configures the package. The database is the only honest answer.
        val confirmed = isInstalled(dependency)
        return if (confirmed && relocationFailure == null) {
            DependencyInstallResult(
                dependency,
                installed = true,
                alreadyPresent = false,
                detail = "installed (${outcome.status})",
            )
        } else {
            DependencyInstallResult(
                dependency = dependency,
                installed = false,
                alreadyPresent = false,
                detail = listOfNotNull(
                    if (confirmed) "present in package database, but post-install reconciliation failed" else null,
                    "${outcome.status} — ${outcome.output.take(400)}",
                    relocationFailure,
                ).joinToString(" | "),
            )
        }
    }

    override suspend fun isInstalled(dependency: RuntimeDependency): Boolean = when (dependency.kind) {
        RuntimeDependency.Kind.TERMUX_PACKAGE -> runCatching {
            // Query the prefix's OWN package database, never the device-wide one:
            // a Termux-built dpkg-query already defaults to $PREFIX/var/lib/dpkg,
            // but an explicit --admindir keeps the check single-rooted and
            // deterministic (this is what a self-contained prefix requires).
            runInPrefixRaw(
                "bin/dpkg-query",
                "--admindir=${paths.termuxPrefix}/var/lib/dpkg",
                "-W", "-f", "\\${'$'}{Status}",
                dependency.name,
            ).trim().endsWith("installed")
        }.getOrDefault(false)
        RuntimeDependency.Kind.PYTHON_PACKAGE -> runCatching {
            val show = runInPrefixRaw("bin/pip", "show", dependency.name)
            "Name:" in show
        }.getOrDefault(false)
        RuntimeDependency.Kind.NODE_PACKAGE -> runCatching {
            val out = runInPrefixRaw("bin/npm", "ls", "-g", "--parseable", dependency.name)
            out.isNotBlank()
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun ensureReadyForExecution() {
        when (state.value) {
            RuntimeState.READY, RuntimeState.DEGRADED -> Unit
            else -> throw RuntimeUnavailableError(
                "runtime is ${state.value}",
                "call initialize() first",
            )
        }
    }

    /** Executables: $PREFIX-relative or absolute-but-inside-app-owned-area (§11/§42). */
    private fun resolveExecutable(executable: String): String {
        val prefix = paths.termuxPrefix
        val candidate = if (executable.startsWith("/")) {
            Path.of(executable)
        } else {
            prefix.resolve("bin/$executable")
        }
        val absolute = candidate.toAbsolutePath().normalize()
        val allowed = paths.isInside(prefix, absolute) || paths.isInside(paths.root, absolute)
        if (!allowed) {
            throw ProcessStartError(
                "executable outside app-owned area",
                "$absolute is not inside \$PREFIX or the ForgeKit root",
            )
        }
        if (!Files.isExecutable(absolute)) {
            throw ProcessStartError(
                "executable not found",
                "$absolute does not exist or is not executable (prefix=${paths.termuxPrefix})",
            )
        }
        return absolute.toString()
    }

    private fun resolveWorkingDirectory(requested: String?): String {
        val dir = requested?.let { Path.of(it).toAbsolutePath().normalize() }
            ?: paths.termuxHome.toAbsolutePath().normalize()
        if (!Files.isDirectory(dir)) {
            throw ProcessStartError("working directory does not exist", dir.toString())
        }
        if (!paths.isInside(paths.root, dir) && !paths.isInside(paths.termuxDir, dir)) {
            throw ProcessStartError(
                "working directory outside app-owned area",
                dir.toString(),
            )
        }
        return dir.toString()
    }

    private fun environmentFor(request: ExecutionRequest): Map<String, String> =
        paths.termuxEnvironment() + request.environment

    /** REAL exec inside the prefix (used by health checks + environment init). */
    private fun prefixCommandRunner() = BootstrapOrchestrator.EnvironmentInitializer { _, command ->
        runCommand(command, timeoutMillis = commandTimeoutMillis, capture = true)
    }

    /** Runs bin/<tool> from $PREFIX and returns trimmed stdout. */
    private suspend fun runInPrefix(binRelative: String, vararg args: String): String =
        runInPrefixRaw(binRelative, *args)

    private suspend fun runInPrefixRaw(binRelative: String, vararg args: String): String {
        val bin = paths.termuxPrefix.resolve(binRelative)
        // Guard before handing a path to the native fork/exec harness: a failed execve()
        // in the forked child is not a safe Kotlin-catchable failure (the child only
        // retains the forking thread — continuing ART/JVM machinery post-fork is
        // undefined behavior and has been observed to crash natively instead of
        // returning an error). Never attempt exec against a path we know is missing.
        if (!Files.isExecutable(bin)) {
            throw IllegalStateException("$bin missing or not executable (prefix=${paths.termuxPrefix})")
        }
        val cmd = listOf(bin.toString()) + args.toList()
        return runCommand(cmd, timeoutMillis = commandTimeoutMillis, capture = true)
    }

    /** Real outcome of a command: captured output + true exit status (never throws on exit code). */
    private class CommandOutcome(val output: String, val status: ExitStatus)

    /**
     * Spawns a real process (raw pty, binary-safe), captures stdout, and returns
     * trimmed output — while [capture] is true. Exit failures throw IllegalStateException.
     */
    private suspend fun runCommand(
        command: List<String>,
        timeoutMillis: Long,
        capture: Boolean = false,
    ): String {
        val outcome = runCommandOutcome(command, timeoutMillis, capture)
        if (outcome.status !is ExitStatus.Exited || !outcome.status.successful) {
            // The process output is the only thing that says WHY it failed; an exit
            // code alone is not diagnosable from a log (§75).
            val detail = outcome.output.takeIf { it.isNotBlank() }
                ?.let { " — ${it.take(400)}" }
                ?: " — (no output)"
            throw IllegalStateException(
                "command failed (${outcome.status}): ${command.joinToString(" ").take(120)}$detail",
            )
        }
        return outcome.output
    }

    /**
     * Spawns a real process (raw pty, binary-safe) and returns its captured output
     * plus the TRUE waitpid exit status — non-zero exits do NOT throw here.
     * While draining, framed output lines (split on '\n') are forwarded to
     * [onLine] when supplied — the streaming install path uses this to emit
     * [com.forgekit.runtime.api.InstallEvent]s from real package-manager output.
     */
    private suspend fun runCommandOutcome(
        command: List<String>,
        timeoutMillis: Long,
        capture: Boolean = true,
        onLine: (suspend (String) -> Unit)? = null,
    ): CommandOutcome {
        val process = PtyProcess.start(
            command = command,
            environment = paths.termuxEnvironment(),
            cwd = paths.termuxHome.toString(),
            rawMode = true,
        )
        try {
            val sb = StringBuilder()
            val lineBuffer = StringBuilder()
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            val buffer = ByteArray(8192)
            // direct drain (single consumer contract: we own this process)
            while (System.nanoTime() < deadline) {
                val n = runCatching { process.read(buffer, timeoutMs = 100) }.getOrDefault(PtyProcess.TIMEOUT)
                if (n == PtyProcess.EOF) break
                if (n > 0) {
                    val text = String(buffer, 0, n, Charsets.UTF_8)
                    if (capture) sb.append(text)
                    if (onLine != null) {
                        lineBuffer.append(text)
                        var newline: Int
                        while (lineBuffer.indexOf('\n').also { newline = it } >= 0) {
                            val line = lineBuffer.substring(0, newline)
                            lineBuffer.delete(0, newline + 1)
                            if (line.isNotBlank()) onLine(line.trim())
                        }
                    }
                }
                val exit = process.exitStatus()
                if (exit != null && n == PtyProcess.TIMEOUT) break
            }
            if (onLine != null && lineBuffer.isNotBlank()) onLine(lineBuffer.trim().toString())
            val exit = process.awaitExit(2_000) ?: process.exitStatus()
            val status: ExitStatus = when {
                exit == null -> ExitStatus.Unknown("no exit after timeout")
                exit.signal != null -> ExitStatus.Signaled(exit.signal!!)
                else -> ExitStatus.Exited(exit.exitCode)
            }
            return CommandOutcome(sb.toString().trim(), status)
        } finally {
            runCatching { process.close() }
        }
    }

    private suspend fun probeTool(prefix: Path, name: String): RuntimeCapabilityInfo? {
        val exe = prefix.resolve("bin/$name")
        if (!Files.isExecutable(exe)) return null
        val version = runCatching {
            val out = runCommand(listOf(exe.toString(), "--version"), timeoutMillis = 15_000, capture = true)
            out.lineSequence().firstOrNull { it.isNotBlank() }?.take(80)
        }.getOrNull()
        return RuntimeCapabilityInfo(name = name, version = version, path = exe.toString())
    }

    private suspend fun check(name: String, body: suspend () -> String): RuntimeHealthCheck = try {
        RuntimeHealthCheck(name = name, passed = true, detail = body())
    } catch (e: Throwable) {
        RuntimeHealthCheck(name = name, passed = false, detail = e.message ?: e.toString())
    }

    private fun runtimeArchitecture(): String =
        System.getProperty("os.arch") ?: "unknown"

    private companion object {
        const val FORGEKIT_RUNTIME_CONTRACT: String = "forgekit.runtime/v1"

        /** Discards streaming install events — the plain [ForgeRuntime.install] path. */
        private val NoOpInstallEvents: FlowCollector<InstallEvent> = object : FlowCollector<InstallEvent> {
            override suspend fun emit(value: InstallEvent) = Unit
        }

        /**
         * Probe PLAN (not an assertion — every entry is verified on disk and by a
         * real --version exec before being reported as a capability; ARCHITECTURE §18).
         */
        val TOOLS_PROBED_ON_DISK: List<String> = listOf(
            "bash", "zsh", "sh", "dash", "python", "python3", "pip", "pip3",
            "node", "npm", "corepack", "git", "curl", "wget", "ffmpeg",
            "ruby", "perl", "pkg", "apt", "dpkg", "rsync", "tar", "unzip",
        )
    }
}
