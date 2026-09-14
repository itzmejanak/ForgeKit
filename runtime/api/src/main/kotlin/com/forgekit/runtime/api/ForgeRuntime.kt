package com.forgekit.runtime.api

import com.forgekit.core.model.ExitStatus
import com.forgekit.core.model.ForgeError
import kotlinx.coroutines.flow.Flow

/**
 * The frozen ForgeKit runtime contract (ARCHITECTURE §3-R3, §89).
 *
 * Application layer code talks ONLY to this interface. It must never touch
 * `EmbeddedTermuxRuntime` or any Termux internals directly (STRUCTURE.md §6.2-1).
 */
public interface ForgeRuntime {

    /** Bootstraps/starts the embedded runtime. Idempotent: READY runtimes return immediately. */
    public suspend fun initialize(): RuntimeState

    /** Starts a process inside the runtime. Never returns a fake handle — a real pid exists or this throws. */
    public suspend fun execute(request: ExecutionRequest): ExecutionHandle

    /** Sends a signal to a live process (SIGTERM/SIGINT/SIGKILL per §53). */
    public suspend fun signal(processId: String, request: SignalRequest)

    /** Terminate: SIGTERM → grace → SIGKILL escalation (ARCHITECTURE §53). */
    public suspend fun terminate(processId: String)

    /** Installs a runtime-level dependency (Termux package / language runtime). */
    public suspend fun install(dependency: RuntimeDependency): DependencyInstallResult

    /** Reports the capabilities currently available (which runtimes/tools actually exist). */
    public suspend fun inspect(): RuntimeCapabilities

    /** Runs the health check battery (§71). Returns REAL check results — never synthetic. */
    public suspend fun healthCheck(): RuntimeHealth

    /** Attempts repair of a broken runtime (§32). */
    public suspend fun repair(): RuntimeRepairResult

    /** Streams state changes (NOT_INSTALLED → INSTALLING → … → READY / FAILED). */
    public val stateChanges: Flow<RuntimeState>

    /**
     * Streams the output of a live (or recently finished) process identified by
     * [ExecutionHandle.processId]: stdout chunks followed by exactly one
     * [ProcessOutput.Exited]. Unknown/finished-and-evicted ids throw
     * [ProcessCrashedError] — never a silent empty flow.
     */
    public fun output(processId: String): Flow<ProcessOutput>

    /** Writes bytes to a live process's stdin (protocol requests, keystrokes). */
    public fun writeStdin(processId: String, bytes: ByteArray)

    /**
     * Whether a process previously returned by [execute] is still alive in the
     * runtime's process tree. Job recovery (§31) uses this to avoid failing a
     * run whose child survived an app restart.
     *
     * Unknown/unverifiable processes and runtimes without process tracking
     * default to `true`: failing a RECONCILE should never destroy a process that
     * may still be executing, so implementations that cannot answer conservatively
     * report "alive".
     */
    public fun isProcessAlive(processId: String): Boolean = true
}

// ---------------------------------------------------------------------------
// Execution types
// ---------------------------------------------------------------------------

/** One process execution request. */
public data class ExecutionRequest(
    /** Executable path inside the runtime ($PREFIX-relative or absolute). */
    val executable: String,
    /** Arguments (argument zero convention: excluding the executable itself). */
    val args: List<String> = emptyList(),
    /** Working directory (absolute, inside app-controlled storage). */
    val workingDirectory: String? = null,
    /** Extra environment layered on top of the Termux base environment. */
    val environment: Map<String, String> = emptyMap(),
    /** Raw stdin bytes written to the process after start (request payloads). */
    val stdinBytes: ByteArray? = null,
    /** Interactive sessions keep stdin open (PTY-backed terminal sessions). */
    val interactive: Boolean = false,
    /** Session type: PLAIN pipes or PTY (terminal). */
    val sessionType: SessionType = SessionType.PIPES,
    /** Initial terminal columns/rows for PTY sessions. */
    val terminalColumns: Int = 80,
    val terminalRows: Int = 24,
    /** Human label used in logs/jobs. */
    val label: String = "execution",
) {
    public enum class SessionType { PIPES, PTY }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Handle to a started process. */
public data class ExecutionHandle(
    val processId: String,
    /** Real OS pid as reported by the harness. */
    val pid: Long,
    val createdAtMillis: Long,
) {
    override fun toString(): String = "ExecutionHandle(id=$processId, pid=$pid)"
}

/** Unix signal requests (ARCHITECTURE §53). */
public enum class SignalRequest { SIGTERM, SIGINT, SIGKILL }

/** A streamed output chunk from a running process. */
public sealed interface ProcessOutput {
    /** stdout bytes (diagnostic / runtime output — §16). */
    public data class Stdout(val bytes: ByteArray) : ProcessOutput

    /** stderr bytes (diagnostic / error output). */
    public data class Stderr(val bytes: ByteArray) : ProcessOutput

    /** Process finished. */
    public data class Exited(val exitStatus: com.forgekit.core.model.ExitStatus) : ProcessOutput
}

// ---------------------------------------------------------------------------
// State / capabilities / health
// ---------------------------------------------------------------------------

/** Runtime state machine (ARCHITECTURE §70). */
public enum class RuntimeState {
    NOT_INSTALLED,
    INSTALLING,
    INITIALIZING,
    READY,
    DEGRADED,
    REPAIRING,
    FAILED,
}

/** Description of a capability found by REAL runtime inspection (never hardcoded; ARCHITECTURE §18). */
public data class RuntimeCapabilityInfo(
    /** e.g. "python", "node", "bash", "ffmpeg". */
    val name: String,
    /** Detected version string or null when the tool does not report one. */
    val version: String?,
    /** Absolute executable path inside $PREFIX. */
    val path: String,
)

/** Capabilities contract: forgekit.runtime/v1. */
public data class RuntimeCapabilities(
    val contractVersion: String,
    val architecture: String,
    val prefixPath: String,
    val homePath: String,
    val shellPath: String,
    val packageManager: PackageManagerInfo,
    val tools: List<RuntimeCapabilityInfo>,
) {
    public data class PackageManagerInfo(
        /** e.g. "pkg"/"apt". */
        val name: String,
        val available: Boolean,
    )
}

/** Result of one health check (§71). */
public data class RuntimeHealthCheck(
    val name: String,
    val passed: Boolean,
    val detail: String,
)

public data class RuntimeHealth(
    val state: RuntimeState,
    val checks: List<RuntimeHealthCheck>,
) {
    public val healthy: Boolean get() = state == RuntimeState.READY && checks.all { it.passed }
}

/** Result of a repair attempt (§32). */
public data class RuntimeRepairResult(
    val repaired: Boolean,
    val actions: List<String>,
    val resultingState: RuntimeState,
)

// ---------------------------------------------------------------------------
// Dependency installation
// ---------------------------------------------------------------------------

/** Runtime-level dependency (Termux package or language runtime requirement). */
public data class RuntimeDependency(
    public val kind: Kind,
    public val name: String,
    public val versionRequirement: String? = null,
) {
    public enum class Kind { TERMUX_PACKAGE, PYTHON_PACKAGE, NODE_PACKAGE }
}

public data class DependencyInstallResult(
    val dependency: RuntimeDependency,
    val installed: Boolean,
    val alreadyPresent: Boolean,
    val detail: String,
)

// ---------------------------------------------------------------------------
// Typed runtime errors
// ---------------------------------------------------------------------------

/** Runtime is unavailable for the requested operation. */
public class RuntimeUnavailableError(message: String, detail: String? = null) :
    ForgeError(message, detail)

/** Process could not be started. */
public class ProcessStartError(message: String, detail: String? = null) :
    ForgeError(message, detail)

/** Process is gone (crashed, reaped, or unknown pid). */
public class ProcessCrashedError(message: String, detail: String? = null) :
    ForgeError(message, detail)
