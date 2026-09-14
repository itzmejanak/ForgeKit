package com.forgekit.runtime.termux

import com.forgekit.runtime.api.ExecutionHandle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks live runtime processes: processId ↔ [PtyExecutionChannel].
 *
 * Ids are stable strings (`p-000123`) independent of OS pid recycling; the
 * handle keeps the pid for diagnostics. Registration ends when the channel
 * reports exit (the runtime removes it then, not before — late signal callers
 * get a precise "already gone" error instead of a silent miss).
 */
public class RuntimeProcessRegistry {

    private val counter = AtomicLong(0)
    private val processes = ConcurrentHashMap<String, LiveProcess>()

    /** A process currently known to the runtime. */
    public class LiveProcess(
        public val handle: ExecutionHandle,
        public val channel: PtyExecutionChannel,
    ) {
        public val id: String get() = handle.processId
    }

    public fun register(channel: PtyExecutionChannel, label: String): LiveProcess {
        val id = "p-" + "%06d".format(counter.incrementAndGet())
        val live = LiveProcess(
            handle = ExecutionHandle(
                processId = id,
                pid = channel.pid,
                createdAtMillis = System.currentTimeMillis(),
            ),
            channel = channel,
        )
        processes[id] = live
        return live
    }

    public fun get(processId: String): LiveProcess? = processes[processId]

    public fun all(): List<LiveProcess> = processes.values.sortedBy { it.handle.createdAtMillis }

    public fun remove(processId: String): LiveProcess? = processes.remove(processId)

    public fun size(): Int = processes.size
}
