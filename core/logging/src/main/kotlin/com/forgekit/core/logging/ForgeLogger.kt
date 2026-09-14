package com.forgekit.core.logging

/** Log channels (ARCHITECTURE §41): application, runtime, plugin, job, security are never mixed. */
public enum class LogCategory {
    APP,
    RUNTIME,
    PLUGIN,
    JOB,
    SECURITY,
    ;

    public val fileName: String get() = name.lowercase()
}

/** Severity levels for structured entries. */
public enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** One structured log entry. */
public data class LogEntry(
    val timestamp: Long,
    val category: LogCategory,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val data: Map<String, String> = emptyMap(),
)

/** Sink contract: log storage is pluggable (in-memory ring for tests, file-based in production). */
public fun interface LogSink {
    public fun write(entry: LogEntry)
}

/**
 * ForgeKit structured logger (STRUCTURE.md §8.1: `ForgeLogger`).
 * All production logging flows through this type — never `println` (§7.3).
 */
public class ForgeLogger(
    private val category: LogCategory,
    private val sink: LogSink,
    private val redactor: SecretRedactor = SecretRedactor.default(),
    private val minLevel: LogLevel = LogLevel.DEBUG,
) {
    public fun debug(tag: String, message: String, data: Map<String, String> = emptyMap()): Unit =
        log(LogLevel.DEBUG, tag, message, data)

    public fun info(tag: String, message: String, data: Map<String, String> = emptyMap()): Unit =
        log(LogLevel.INFO, tag, message, data)

    public fun warn(tag: String, message: String, data: Map<String, String> = emptyMap()): Unit =
        log(LogLevel.WARN, tag, message, data)

    public fun error(tag: String, message: String, data: Map<String, String> = emptyMap()): Unit =
        log(LogLevel.ERROR, tag, message, data)

    private fun log(level: LogLevel, tag: String, message: String, data: Map<String, String>) {
        if (level.ordinal < minLevel.ordinal) return
        val safeMessage = redactor.redact(message)
        val safeData = data.mapValues { redactor.redact(it.value.toString()) }
        sink.write(LogEntry(System.currentTimeMillis(), category, level, tag, safeMessage, safeData))
    }

    public companion object {
        /** Creates a logger writing to an in-memory ring buffer (tests, diagnostics view). */
        public fun inMemory(category: LogCategory, capacity: Int = 2048): Pair<ForgeLogger, List<LogEntry>> {
            val buffer = ArrayDeque<LogEntry>(capacity)
            val logger = ForgeLogger(category, sink = { buffer.addLast(it); if (buffer.size > capacity) buffer.removeFirst() })
            return logger to buffer
        }
    }
}
