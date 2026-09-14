package com.forgekit.runtime.termux

import com.forgekit.core.model.ExitStatus
import com.forgekit.runtime.api.ProcessOutput
import com.forgekit.runtime.api.SignalRequest
import com.forgekit.runtime.bridge.ExecutionChannel
import com.forgekit.termux.embedded.PtyProcess
import com.forgekit.termux.embedded.Signals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [ExecutionChannel] over a REAL [PtyProcess] (fork + openpty + execve via the
 * NDK harness `forgekit_pty.c`).
 *
 * On a pty, stdout and stderr are ONE stream (the terminal). Chunks are surfaced as
 * [ProcessOutput.Stdout]; the exit event carries the true waitpid status. This is
 * the same model Termux itself uses — there is no separate stderr channel on a terminal.
 *
 * Exactly ONE reader thread drains the master fd and broadcasts chunks to every
 * registered subscriber channel. Any number of collectors may attach (jobs, the
 * terminal UI, loggers) — each gets a private bounded mailbox, so one consumer
 * reading slowly never corrupts another's stream. When the reader's own dispatch
 * buffer fills it parks, which back-pressures the child through the kernel pty
 * buffer — bounded memory, no data loss.
 *
 * Collectors that attach AFTER the process finished receive exactly one
 * [ProcessOutput.Exited] and complete immediately (the "recently finished process"
 * contract of ForgeRuntime.output).
 */
public class PtyExecutionChannel(
    private val process: PtyProcess,
    /** true → interactive terminal semantics (resize supported); false → protocol session. */
    public val terminal: Boolean,
) : ExecutionChannel {

    private val closed = AtomicBoolean(false)
    private val exited = CompletableDeferred<ExitStatus>()

    /** Live subscriber mailboxes; `null` chunk = end marker. */
    private val mailboxes = CopyOnWriteArrayList<Channel<ByteArray?>>()

    /** Set once, at drain end, under [terminalLock]; gates late-subscriber behavior. */
    @Volatile
    private var terminalStatus: ExitStatus? = null

    private val terminalLock = Any()

    /** Lazily started by the first subscriber. */
    private val reader: Thread = Thread({ drainLoop() }, "pty-reader-${process.pid}").apply {
        isDaemon = true
    }
    private val readerStartLock = Any()

    // ---- ExecutionChannel --------------------------------------------------

    override fun writeStdin(bytes: ByteArray) {
        if (closed.get() || bytes.isEmpty()) return
        runCatching { process.write(bytes) }
            .onFailure { /* slave gone: exit flow surfaces the status */ }
    }

    override fun closeStdin() {
        // A pty master cannot half-close: there is no EOF write side. Protocol
        // sessions are length-bounded (the plugin protocol frames requests), so
        // stdin "close" is a no-op by design; processes terminate on their own.
    }

    override val output: Flow<ProcessOutput> = flow {
        val mailbox = subscribe() ?: run {
            // stream already finished: deliver the final status and complete
            emit(ProcessOutput.Exited(exited.await()))
            return@flow
        }
        try {
            for (chunk in mailbox) {
                if (chunk == null) break
                emit(ProcessOutput.Stdout(chunk))
            }
            emit(ProcessOutput.Exited(exited.await()))
        } finally {
            unregister(mailbox)
        }
    }

    /** Raw terminal byte stream (ANSI included) — the [PtyTerminalSession] surface. */
    public val rawOutput: Flow<ByteArray> = flow {
        val mailbox = subscribe() ?: return@flow // finished: nothing to stream
        try {
            for (chunk in mailbox) {
                if (chunk == null) break
                emit(chunk)
            }
        } finally {
            unregister(mailbox)
        }
    }

    override suspend fun awaitExit(): ExitStatus = exited.await()

    override fun signal(request: SignalRequest) {
        val sig = when (request) {
            SignalRequest.SIGTERM -> Signals.SIGTERM
            SignalRequest.SIGINT -> Signals.SIGINT
            SignalRequest.SIGKILL -> Signals.SIGKILL
        }
        runCatching { process.sendSignal(sig) }
    }

    /** The backing OS process id. */
    public val pid: Long = process.pid.toLong()

    /** Terminal resize (only meaningful for PTY sessions). */
    public fun resize(columns: Int, rows: Int) {
        if (terminal) runCatching { process.resize(rows, columns) }
    }

    /** Hard kill — SIGKILL. */
    public fun kill() {
        runCatching { process.sendSignal(Signals.SIGKILL) }
    }

    /** Releases the master fd and stops the reader loop. */
    public fun close() {
        if (closed.compareAndSet(false, true)) {
            finishStream(ExitStatus.Unknown("channel closed"))
            runCatching { process.close() }
        }
    }

    // ---- internals ----------------------------------------------------------

    /**
     * Registers a fresh mailbox for a new collector.
     * Returns null when the stream already terminated (caller synthesizes the
     * tail event itself); the registration is race-free against stream end.
     */
    private fun subscribe(): Channel<ByteArray?>? {
        synchronized(terminalLock) {
            terminalStatus?.let { return null }
            val mailbox: Channel<ByteArray?> = Channel(SUBSCRIBER_CAPACITY)
            mailboxes.add(mailbox)
            startReaderLocked()
            return mailbox
        }
    }

    private fun unregister(mailbox: Channel<ByteArray?>) {
        mailboxes.remove(mailbox)
        mailbox.close()
    }

    private fun startReaderLocked() {
        // called under terminalLock; reader start is idempotent via its own lock
        synchronized(readerStartLock) {
            if (!reader.isAlive && !closed.get()) reader.start()
        }
    }

    /** Broadcasts one chunk to every live mailbox with parking backpressure. */
    private fun broadcast(chunk: ByteArray) {
        outer@ while (true) {
            var retry = false
            for (mailbox in mailboxes) {
                val result = mailbox.trySend(chunk)
                if (result.isFailure && !mailbox.isClosedForSend) retry = true
            }
            if (!retry) return
            if (closed.get()) return
            // some consumer's mailbox is full: park briefly — the pty kernel buffer
            // applies backpressure to the child instead of unbounded memory growth
            Thread.sleep(BACKPRESSURE_PARK_MS)
            continue@outer
        }
    }

    /** Marks the stream finished: end-markers out, late subscribers short-circuit. */
    private fun finishStream(status: ExitStatus) {
        val toEnd: List<Channel<ByteArray?>>
        synchronized(terminalLock) {
            if (terminalStatus != null) return
            terminalStatus = status
            toEnd = mailboxes.toList()
            mailboxes.clear()
        }
        for (mailbox in toEnd) {
            mailbox.trySend(null)
            mailbox.close()
        }
        exited.complete(status)
    }

    /**
     * The single master-fd drain loop: broadcasts chunks until EOF (all slave
     * writers gone) or close(); then reaps the true waitpid status and finishes
     * the stream.
     */
    private fun drainLoop() {
        val buffer = ByteArray(READ_CHUNK)
        while (!closed.get()) {
            val n = try {
                process.read(buffer, timeoutMs = READ_POLL_MS)
            } catch (_: Exception) {
                break
            }
            when {
                n == PtyProcess.EOF -> break
                n == PtyProcess.TIMEOUT ->
                    if (terminalStatus != null) break else continue
                n > 0 -> broadcast(buffer.copyOf(n))
            }
        }
        // EOF (or close/error): the child closed its output — reap the true status.
        val exit = process.awaitExit(EXIT_REAP_TIMEOUT_MS)?.let { raw ->
            val sig = raw.signal
            if (sig != null) ExitStatus.Signaled(sig) else ExitStatus.Exited(raw.exitCode)
        } ?: process.exitStatus()?.let { raw ->
            val sig = raw.signal
            if (sig != null) ExitStatus.Signaled(sig) else ExitStatus.Exited(raw.exitCode)
        } ?: ExitStatus.Unknown("process ${process.pid} still running after channel close")
        finishStream(exit)
        if (!closed.compareAndSet(false, true)) Unit
        runCatching { process.close() }
    }

    private companion object {
        const val READ_CHUNK = 8192
        const val READ_POLL_MS = 100
        const val EXIT_REAP_TIMEOUT_MS = 10_000L
        const val BACKPRESSURE_PARK_MS = 5L
        const val SUBSCRIBER_CAPACITY = 256 // ~2 MiB in-flight per subscriber
    }
}

/**
 * [com.forgekit.runtime.bridge.TerminalSession] view over a [PtyExecutionChannel] —
 * the surface the terminal UI drives. Separate type because [ExecutionChannel.output]
 * (typed events) and `TerminalSession.output` (raw bytes) are different streams.
 */
public class PtyTerminalSession(
    private val channel: PtyExecutionChannel,
) : com.forgekit.runtime.bridge.TerminalSession {
    override val output: Flow<ByteArray> = channel.rawOutput
    override fun write(bytes: ByteArray) = channel.writeStdin(bytes)
    override fun resize(columns: Int, rows: Int) = channel.resize(columns, rows)
    override suspend fun awaitExit(): ExitStatus = channel.awaitExit()
    override fun kill() = channel.kill()
}
