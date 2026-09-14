package com.forgekit.runtime.bridge

import com.forgekit.core.model.ExitStatus
import com.forgekit.runtime.api.ProcessOutput
import com.forgekit.runtime.api.SignalRequest
import kotlinx.coroutines.flow.Flow

/**
 * Streaming channel over one running process (STRUCTURE.md §8.3).
 *
 * Implementations exist for both java.lang.Process (JVM/host, tests, CLI tools) and
 * the native fd-based harness (Android, termux/embedded). Consumers never know which.
 */
public interface ExecutionChannel {
    /** Writes bytes to the process stdin. Safe to call repeatedly while the process runs. */
    public fun writeStdin(bytes: ByteArray)

    /** Closes stdin (EOF). Plugins reading stdin until EOF rely on this. */
    public fun closeStdin()

    /** Merged, ordered output: stdout/stderr chunks followed by exactly one [ProcessOutput.Exited]. */
    public val output: Flow<ProcessOutput>

    /** Suspends until the process exits and returns its real status. */
    public suspend fun awaitExit(): ExitStatus

    /** Sends a signal to the process. */
    public fun signal(request: SignalRequest)
}

/** Terminal session over a PTY (STRUCTURE.md §8.3, ARCHITECTURE §38). */
public interface TerminalSession {
    /** Raw bytes emitted by the pty (UTF-8 text + ANSI control sequences). */
    public val output: Flow<ByteArray>

    /** Sends raw bytes (keystrokes, control sequences) to the pty. */
    public fun write(bytes: ByteArray)

    /** Notifies the pty of a terminal size change (SIGWINCH). */
    public fun resize(columns: Int, rows: Int)

    /** Real exit status once the shell exits. */
    public suspend fun awaitExit(): ExitStatus

    /** Hard kill (SIGKILL) — the escape hatch for uncooperative sessions. */
    public fun kill()
}
