package com.forgekit.runtime.bridge

import com.forgekit.core.model.ExitStatus
import com.forgekit.runtime.api.ProcessOutput
import com.forgekit.runtime.api.SignalRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [ExecutionChannel] over a `java.lang.Process` (PIPE mode).
 *
 * Used by the host CLI tools (`forge-test` against a host runtime), JVM integration tests,
 * and as the reference streaming semantics for the Android native harness.
 */
public class ProcessExecutionChannel(
    private val process: Process,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ExecutionChannel {

    override fun writeStdin(bytes: ByteArray) {
        try {
            process.outputStream.use { it.write(bytes); it.flush() }
        } catch (_: IOException) {
            // process died — exit flow will surface the status
        }
    }

    override fun closeStdin() {
        try {
            process.outputStream.close()
        } catch (_: IOException) {
        }
    }

    override val output: Flow<ProcessOutput> = callbackFlow {
        val stdoutThread = readerThread("process-stdout-${process.pid()}") {
            emitLoop(process.inputStream) { trySend(ProcessOutput.Stdout(it)) }
        }
        val stderrThread = readerThread("process-stderr-${process.pid()}") {
            emitLoop(process.errorStream) { trySend(ProcessOutput.Stderr(it)) }
        }
        stdoutThread.join()
        stderrThread.join()
        // both pipes closed — process has exited; waitpid for the real code
        val raw = runCatching { process.waitFor() }.getOrDefault(-1)
        trySend(ProcessOutput.Exited(ExitStatus.fromRaw(raw)))
        close()
        awaitClose { stdoutThread.interrupt(); stderrThread.interrupt() }
    }.flowOn(ioDispatcher)

    override suspend fun awaitExit(): ExitStatus = withContext(ioDispatcher) {
        val raw = runCatching { process.waitFor() }.getOrDefault(-1)
        ExitStatus.fromRaw(raw)
    }

    override fun signal(request: SignalRequest) {
        when (request) {
            SignalRequest.SIGTERM -> process.destroy()       // JVM: graceful == TERM
            SignalRequest.SIGINT -> process.destroy()        // best effort: no Ctrl-C API in java.lang.Process
            SignalRequest.SIGKILL -> process.destroyForcibly()
        }
    }

    private fun readerThread(name: String, block: () -> Unit): Thread = Thread(block, name).apply {
        isDaemon = true
        start()
    }

    private inline fun emitLoop(stream: InputStream, emit: (ByteArray) -> Unit) {
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) emit(buffer.copyOf(read))
            }
        } catch (_: IOException) {
            // pipe closed / process killed mid-read
        } finally {
            runCatching { stream.close() }
        }
    }
}

/**
 * Splits a byte stream into UTF-8 lines without losing partial trailing data.
 * Shared framing utility: the plugin protocol (newline-delimited JSON) and log tailing use it.
 */
public class LineFramer(
    private val maxLineBytes: Int = 8 * 1024 * 1024,
) {
    // Byte-level buffering: UTF-8 multi-byte sequences can split across feeds,
    // and 0x0A can never appear inside a multi-byte sequence, so byte-splitting on
    // newline is safe and decoded lines are always complete sequences.
    private var pending: ByteArray = ByteArray(0)

    /** Feed raw bytes; returns the complete lines decoded as UTF-8. */
    public fun feed(bytes: ByteArray): List<String> {
        val merged = ByteArray(pending.size + bytes.size)
        pending.copyInto(merged)
        bytes.copyInto(merged, pending.size)
        val lines = mutableListOf<ByteArray>()
        var start = 0
        var i = 0
        while (i < merged.size) {
            if (merged[i] == NEWLINE) {
                lines += merged.copyOfRange(start, i)
                start = i + 1
            }
            i++
        }
        pending = merged.copyOfRange(start, merged.size)
        while (pending.size > maxLineBytes) pending = ByteArray(0) // defensive overflow guard
        return lines.map { String(it, Charsets.UTF_8).trimEnd('\r') }
    }

    /** Flushes any trailing partial line (at EOF). */
    public fun flush(): String? =
        if (pending.isEmpty()) null else String(pending, Charsets.UTF_8).also { pending = ByteArray(0) }

    private companion object { private const val NEWLINE: Byte = 0x0A }
}

/** Small helper to drain an OutputStream in tests/tools. */
public fun OutputStream.writeAndFlush(bytes: ByteArray) {
    write(bytes)
    flush()
}
