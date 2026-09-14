package com.forgekit.termux.embedded

import java.io.Closeable
import java.io.IOException

/**
 * A REAL process attached to a pseudo-terminal, created by fork + openpty +
 * execve in the native harness (`forgekit_pty.c`).
 *
 * The [masterFd] is the pty master kept in the JVM; the child's stdio is the
 * slave side, so terminal semantics (line discipline, ONLCR, ISIG, window
 * size, job control) behave exactly like a real terminal. This is the same
 * mechanism the ForgeKit runtime uses on-device; on the host JVM the identical
 * C code backs the tests, so behavior is proven, not simulated.
 *
 * Contract (ARCHITECTURE §33 execution model):
 *  - [read] is cancellation-friendly: bounded poll timeout, retry-safe.
 *  - [exitStatus] polls waitpid (non-blocking); [awaitExit] polls with deadline.
 *  - [close] releases the master fd. SIGKILL is always available via
 *    [sendSignal] as the last resort for termination.
 */
public class PtyProcess private constructor(
    /** OS process id of the executed child. */
    public val pid: Int,
    /** Pty master descriptor (owned by this object). */
    public val masterFd: Int,
) : Closeable {

    /** Final state of a reaped child. Exactly one of the fields carries meaning. */
    public data class Exit(
        /** WEXITSTATUS — meaningful when [signal] is null. */
        val exitCode: Int,
        /** WTERMSIG when the child was killed by a signal, else null. */
        val signal: Int?,
    ) {
        /** Shell-convention combined code (128+N for signals). */
        public fun combinedCode(): Int = signal?.let { 128 + it } ?: exitCode

        override fun toString(): String =
            signal?.let { "killed by SIG${sigName(it)}" } ?: "exit $exitCode"
    }

    @Volatile
    private var closed = false

    /** Final waitpid result, cached after the first successful reap. */
    @Volatile
    private var finalExit: Exit? = null

    /** Writes bytes to the child's terminal input. */
    @Throws(IOException::class)
    public fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
        val n = NativePty.nativeWrite(masterFd, bytes, offset, length)
        check(n == length) { "nativeWrite must write fully (got $n of $length)" }
        return n
    }

    /**
     * Reads up to [length] bytes from the terminal output.
     * @return bytes read (n > 0), [EOF] when the child closed all output, or
     * [TIMEOUT] when nothing arrived within [timeoutMs] (retry / check liveness).
     */
    @Throws(IOException::class)
    public fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset, timeoutMs: Int = 250): Int =
        NativePty.nativeRead(masterFd, buffer, offset, length, timeoutMs)

    /**
     * Non-blocking waitpid; null while the child still runs. Idempotent after exit:
     * the first successful poll reaps the child and the result is cached — later
     * polls return the cached status (ECHILD after a reap is expected, not an error).
     */
    public fun exitStatus(): Exit? {
        finalExit?.let { return it }
        val raw = try {
            NativePty.nativeWaitPid(pid, blocking = false)
        } catch (_: java.io.IOException) {
            // ECHILD: already reaped by an earlier poll — keep the cached truth.
            return finalExit
        }
        if (raw == -1) return null
        finalExit = decode(raw)
        return finalExit
    }

    /** Polls waitpid until the child exits or [timeoutMs] elapses. */
    public fun awaitExit(timeoutMs: Long): Exit? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            exitStatus()?.let { return it }
            if (System.nanoTime() >= deadline) return null
            Thread.sleep(10)
        }
    }

    /**
     * Sends a signal ([Signals] constants). Returns false when the process is
     * already gone; throws on permission errors.
     */
    public fun sendSignal(signal: Int): Boolean = NativePty.nativeKill(pid, signal) == 0

    /** Resizes the child's terminal; the kernel relays SIGWINCH to the child. */
    public fun resize(rows: Int, cols: Int) {
        NativePty.nativeSetWindowSize(masterFd, rows, cols)
    }

    /** True while the child has not been reaped. */
    public val isRunning: Boolean
        get() = exitStatus() == null

    /** Releases the master fd (the child keeps running; use [sendSignal] to stop it). */
    override fun close() {
        if (!closed) {
            closed = true
            NativePty.nativeClose(masterFd)
        }
    }

    public companion object {
        /**
         * Loads the host-compiled PTY harness (the same `forgekit_pty.c` built with
         * the host toolchain) instead of the packaged Android `.so`.
         *
         * Host JVM tests use this to exercise REAL fork/exec/pty semantics; on
         * Android [start] loads the packaged `libforgekit_pty.so` automatically,
         * so production code never calls this.
         */
        @JvmStatic
        public fun loadHostHarness(file: java.io.File) {
            NativePty.loadFrom(file)
        }

        /** [read] result: the child closed its output (all slave fds gone). */
        public const val EOF: Int = -1

        /** [read] result: nothing arrived within the poll timeout. */
        public const val TIMEOUT: Int = -2

        /**
         * Starts a real process on a real pty.
         *
         * @param command argv; [command][0] is the executable path (absolute,
         *        or resolvable by the host OS — on Android always absolute
         *        inside $PREFIX, since PATH lookup is NOT performed)
         * @param environment full KEY=VALUE environment for the child (e.g.
         *        from ForgePaths.termuxEnvironment())
         * @param cwd absolute working directory (chdir happens before exec)
         * @param rows initial terminal rows
         * @param cols initial terminal columns
         * @param rawMode true for binary-safe sessions (no echo, no line
         *        editing, no signal chars); false for interactive terminals
         */
        @Throws(IOException::class)
        public fun start(
            command: List<String>,
            environment: Map<String, String>,
            cwd: String,
            rows: Int = DEFAULT_ROWS,
            cols: Int = DEFAULT_COLS,
            rawMode: Boolean = false,
        ): PtyProcess {
            require(command.isNotEmpty()) { "command must not be empty" }
            require(command[0].startsWith("/")) { "command[0] must be an absolute path: ${command[0]}" }
            NativePty.ensureLoaded()
            val argv = command.toTypedArray()
            val env = environment.entries.map { "${it.key}=${it.value}" }.toTypedArray()
            val ids = NativePty.nativeForkExec(argv, env, cwd, rows, cols, rawMode)
            return PtyProcess(pid = ids[0], masterFd = ids[1])
        }

        /** Linux wait-status decoding (WIFEXITED / WIFSIGNALED). */
        internal fun decode(rawStatus: Int): Exit {
            val low7 = rawStatus and 0x7F
            return if (low7 == 0) {
                Exit(exitCode = (rawStatus shr 8) and 0xFF, signal = null)
            } else {
                Exit(exitCode = -1, signal = low7)
            }
        }

        private const val DEFAULT_ROWS = 24
        private const val DEFAULT_COLS = 80

        private fun sigName(n: Int): String = when (n) {
            1 -> "HUP"; 2 -> "INT"; 3 -> "QUIT"; 6 -> "ABRT"; 9 -> "KILL"
            13 -> "PIPE"; 15 -> "TERM"; 19 -> "STOP"; 18 -> "CONT"
            else -> n.toString()
        }
    }
}

/** POSIX signal numbers (Linux/Android). */
public object Signals {
    public const val SIGHUP: Int = 1
    public const val SIGINT: Int = 2
    public const val SIGQUIT: Int = 3
    public const val SIGABRT: Int = 6
    public const val SIGKILL: Int = 9
    public const val SIGPIPE: Int = 13
    public const val SIGTERM: Int = 15
    public const val SIGSTOP: Int = 19
    public const val SIGCONT: Int = 18
}
