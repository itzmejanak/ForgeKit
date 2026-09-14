package com.forgekit.job.persistence

import com.forgekit.core.model.ExitStatus
import com.forgekit.job.api.JobEvent
import com.forgekit.job.api.JobId
import com.forgekit.job.api.JobQuery
import com.forgekit.job.api.JobRecord
import com.forgekit.job.api.JobPrompt
import com.forgekit.job.api.JobPromptKind
import com.forgekit.job.api.JobPromptResponseStatus
import com.forgekit.job.api.JobState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * REAL SQLite-backed [JobStore] (engine = xerial sqlite-jdbc native SQLite —
 * the same engine family Termux/dpkg-class tooling trusts, not an
 * in-memory imitation). One database file, two tables, WAL mode.
 *
 * Schema is versioned by PRAGMA user_version; upgrades are additive
 * migrations (§47 update strategy at the metadata level).
 */
public class SqliteJobStore(
    private val databaseFile: Path,
) : JobStore {

    private val connection: Connection
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    init {
        databaseFile.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        connection = DriverManager.getConnection("jdbc:sqlite:${databaseFile}")
        connection.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL")
            stmt.execute("PRAGMA foreign_keys=ON")
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS jobs (
                    id            TEXT PRIMARY KEY,
                    plugin_id     TEXT NOT NULL,
                    action        TEXT NOT NULL,
                    state         TEXT NOT NULL,
                    input_json    TEXT NOT NULL,
                    output_json   TEXT NOT NULL DEFAULT '{}',
                    error         TEXT,
                    process_id    TEXT,
                    created_at    INTEGER NOT NULL,
                    started_at    INTEGER,
                    completed_at  INTEGER
                )
                """.trimIndent(),
            )
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS job_events (
                    seq          INTEGER PRIMARY KEY AUTOINCREMENT,
                    job_id       TEXT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
                    type         TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    ts_millis    INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_jobs_plugin ON jobs(plugin_id, created_at DESC)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_events_job ON job_events(job_id, seq)")
            stmt.execute("PRAGMA user_version=1")
        }
    }

    override fun insert(record: JobRecord) {
        connection.prepareStatement(
            "INSERT INTO jobs (id, plugin_id, action, state, input_json, output_json, error, process_id, created_at, started_at, completed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, record.id.raw)
            ps.setString(2, record.pluginId)
            ps.setString(3, record.action)
            ps.setString(4, record.state.name)
            ps.setString(5, json.encodeToString(MapSerializer, record.input))
            ps.setString(6, json.encodeToString(MapSerializer, record.output))
            ps.setString(7, record.error)
            ps.setString(8, record.processId)
            ps.setLong(9, record.createdAtMillis)
            setNullableLong(ps, 10, record.startedAtMillis)
            setNullableLong(ps, 11, record.completedAtMillis)
            ps.executeUpdate()
        }
    }

    override fun update(record: JobRecord) {
        connection.prepareStatement(
            "UPDATE jobs SET state=?, output_json=?, error=?, process_id=?, started_at=?, completed_at=? WHERE id=?",
        ).use { ps ->
            ps.setString(1, record.state.name)
            ps.setString(2, json.encodeToString(MapSerializer, record.output))
            ps.setString(3, record.error)
            ps.setString(4, record.processId)
            setNullableLong(ps, 5, record.startedAtMillis)
            setNullableLong(ps, 6, record.completedAtMillis)
            ps.setString(7, record.id.raw)
            check(ps.executeUpdate() == 1) { "no job row '${record.id.raw}' to update" }
        }
    }

    override fun appendEvent(event: JobEvent) {
        val wire = json.encodeToString(WireEvent.serializer(), WireEvent.of(event))
        connection.prepareStatement(
            "INSERT INTO job_events (job_id, type, payload_json, ts_millis) VALUES (?,?,?,?)",
        ).use { ps ->
            ps.setString(1, event.jobId.raw)
            ps.setString(2, event::class.simpleName)
            ps.setString(3, wire)
            ps.setLong(4, event.timestampMillis)
            ps.executeUpdate()
        }
    }

    override fun find(jobId: JobId): JobRecord? {
        connection.prepareStatement("SELECT * FROM jobs WHERE id=?").use { ps ->
            ps.setString(1, jobId.raw)
            ps.executeQuery().use { rs -> return if (rs.next()) rowToRecord(rs) else null }
        }
    }

    override fun events(jobId: JobId, limit: Int): List<JobEvent> {
        connection.prepareStatement(
            "SELECT payload_json FROM job_events WHERE job_id=? ORDER BY seq ASC LIMIT ?",
        ).use { ps ->
            ps.setString(1, jobId.raw)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                val result = mutableListOf<JobEvent>()
                while (rs.next()) {
                    result += json.decodeFromString(WireEvent.serializer(), rs.getString(1)).toDomain()
                }
                return result
            }
        }
    }

    override fun query(query: JobQuery): List<JobRecord> {
        val sql = StringBuilder("SELECT * FROM jobs WHERE 1=1")
        val args = mutableListOf<Any>()
        query.pluginId?.let { sql.append(" AND plugin_id=?"); args += it }
        if (query.states.isNotEmpty()) {
            sql.append(" AND state IN (${query.states.joinToString(",") { "?" }})")
            args.addAll(query.states.map { it.name })
        }
        query.sinceMillis?.let { sql.append(" AND created_at >= ?"); args += it }
        sql.append(" ORDER BY created_at DESC LIMIT ?")
        args += query.limit

        connection.prepareStatement(sql.toString()).use { ps ->
            bindArgs(ps, args)
            ps.executeQuery().use { rs ->
                val result = mutableListOf<JobRecord>()
                while (rs.next()) result += rowToRecord(rs)
                return result
            }
        }
    }

    override fun active(): List<JobRecord> {
        connection.prepareStatement(
            "SELECT * FROM jobs WHERE state NOT IN ('COMPLETED','FAILED','CANCELLED') ORDER BY created_at ASC",
        ).use { ps ->
            ps.executeQuery().use { rs ->
                val result = mutableListOf<JobRecord>()
                while (rs.next()) result += rowToRecord(rs)
                return result
            }
        }
    }

    override fun counts(): JobStore.JobCounts {
        connection.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT " +
                    "SUM(state IN ('QUEUED','PREPARING','INSTALLING_DEPENDENCIES','STARTING','RUNNING','PAUSED','CANCELLING')) AS running, " +
                    "SUM(state='COMPLETED') AS completed, " +
                    "SUM(state='FAILED') AS failed, " +
                    "SUM(state='CANCELLED') AS cancelled FROM jobs",
            ).use { rs ->
                rs.next()
                return JobStore.JobCounts(
                    running = rs.getLong("running"),
                    completed = rs.getLong("completed"),
                    failed = rs.getLong("failed"),
                    cancelled = rs.getLong("cancelled"),
                )
            }
        }
    }

    override fun total(): Long =
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) AS n FROM jobs").use { rs ->
                rs.next(); rs.getLong("n")
            }
        }

    /** Events first, then the record, in one transaction so a job never loses only half its history. */
    override fun delete(jobId: JobId): Boolean {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.prepareStatement("DELETE FROM job_events WHERE job_id=?").use { ps ->
                ps.setString(1, jobId.raw)
                ps.executeUpdate()
            }
            val removed = connection.prepareStatement("DELETE FROM jobs WHERE id=?").use { ps ->
                ps.setString(1, jobId.raw)
                ps.executeUpdate()
            }
            connection.commit()
            return removed == 1
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    /** Wipes records + events; the autoincrement event log keeps its counter (§47-safe). */
    override fun clear() {
        connection.createStatement().use { stmt ->
            stmt.execute("DELETE FROM job_events")
            stmt.execute("DELETE FROM jobs")
        }
    }

    override fun close() {
        connection.close()
    }

    // ---- wire format ----------------------------------------------------------

    @Serializable
    private data class WireEvent(
        @SerialName("jobId") val jobId: String,
        @SerialName("type") val type: String,
        @SerialName("ts") val ts: Long,
        @SerialName("from") val from: String? = null,
        @SerialName("to") val to: String? = null,
        @SerialName("line") val line: String? = null,
        @SerialName("channel") val channel: String? = null,
        @SerialName("value") val value: Double? = null,
        @SerialName("message") val message: String? = null,
        @SerialName("output") val output: Map<String, String>? = null,
        @SerialName("reason") val reason: String? = null,
        @SerialName("exitKind") val exitKind: String? = null,
        @SerialName("exitCode") val exitCode: Int? = null,
        @SerialName("exitSignal") val exitSignal: Int? = null,
        @SerialName("promptId") val promptId: String? = null,
        @SerialName("promptKind") val promptKind: String? = null,
        @SerialName("promptTitle") val promptTitle: String? = null,
        @SerialName("promptRequired") val promptRequired: Boolean? = null,
        @SerialName("promptChoices") val promptChoices: List<String>? = null,
        @SerialName("promptDefault") val promptDefault: String? = null,
        @SerialName("promptPlaceholder") val promptPlaceholder: String? = null,
        @SerialName("responseStatus") val responseStatus: String? = null,
    ) {
        companion object {
            fun of(event: JobEvent): WireEvent = WireEvent(
                jobId = event.jobId.raw,
                type = event::class.simpleName ?: "unknown",
                ts = event.timestampMillis,
                from = (event as? JobEvent.StateChanged)?.from?.name,
                to = (event as? JobEvent.StateChanged)?.to?.name,
                line = (event as? JobEvent.LogLine)?.line,
                channel = (event as? JobEvent.LogLine)?.channel,
                value = (event as? JobEvent.Progress)?.value,
                message = when (event) {
                    is JobEvent.Progress -> event.message
                    is JobEvent.PromptRequested -> event.prompt.message
                    else -> null
                },
                output = (event as? JobEvent.Completed)?.output,
                reason = (event as? JobEvent.Failed)?.reason,
                exitKind = when ((event as? JobEvent.Failed)?.exitStatus) {
                    is ExitStatus.Exited -> "exited"
                    is ExitStatus.Signaled -> "signaled"
                    is ExitStatus.Unknown -> "unknown"
                    null -> null
                },
                exitCode = ((event as? JobEvent.Failed)?.exitStatus as? ExitStatus.Exited)?.code,
                exitSignal = ((event as? JobEvent.Failed)?.exitStatus as? ExitStatus.Signaled)?.signal,
                promptId = when (event) {
                    is JobEvent.PromptRequested -> event.prompt.id
                    is JobEvent.PromptResolved -> event.promptId
                    else -> null
                },
                promptKind = (event as? JobEvent.PromptRequested)?.prompt?.kind?.name,
                promptTitle = (event as? JobEvent.PromptRequested)?.prompt?.title,
                promptRequired = (event as? JobEvent.PromptRequested)?.prompt?.required,
                promptChoices = (event as? JobEvent.PromptRequested)?.prompt?.choices,
                promptDefault = (event as? JobEvent.PromptRequested)?.prompt?.default,
                promptPlaceholder = (event as? JobEvent.PromptRequested)?.prompt?.placeholder,
                responseStatus = (event as? JobEvent.PromptResolved)?.status?.name,
            )
        }

        fun toDomain(): JobEvent {
            val id = JobId(jobId)
            return when (type) {
                "StateChanged" -> JobEvent.StateChanged(
                    id, JobState.valueOf(from ?: error("StateChanged without from")), JobState.valueOf(to ?: error("StateChanged without to")), ts,
                )
                "LogLine" -> JobEvent.LogLine(id, line ?: "", channel ?: "stdout", ts)
                "Progress" -> JobEvent.Progress(id, value ?: 0.0, message, ts)
                "PromptRequested" -> JobEvent.PromptRequested(
                    id,
                    JobPrompt(
                        id = promptId ?: error("PromptRequested without promptId"),
                        kind = JobPromptKind.valueOf(promptKind ?: error("PromptRequested without kind")),
                        title = promptTitle ?: error("PromptRequested without title"),
                        message = message,
                        required = promptRequired ?: true,
                        choices = promptChoices ?: emptyList(),
                        default = promptDefault,
                        placeholder = promptPlaceholder,
                    ),
                    ts,
                )
                "PromptResolved" -> JobEvent.PromptResolved(
                    id,
                    promptId ?: error("PromptResolved without promptId"),
                    JobPromptResponseStatus.valueOf(responseStatus ?: error("PromptResolved without status")),
                    ts,
                )
                "Completed" -> JobEvent.Completed(id, output ?: emptyMap(), ts)
                "Failed" -> JobEvent.Failed(
                    id, reason ?: "unknown",
                    when (exitKind) {
                        "exited" -> ExitStatus.Exited(exitCode ?: -1)
                        "signaled" -> ExitStatus.Signaled(exitSignal ?: -1)
                        "unknown" -> ExitStatus.Unknown(reason ?: "unknown")
                        else -> null
                    },
                    ts,
                )
                "Cancelled" -> JobEvent.Cancelled(id, ts)
                else -> error("unknown persisted event type '$type'")
            }
        }
    }

    private val MapSerializer: kotlinx.serialization.KSerializer<Map<String, String>> =
        kotlinx.serialization.builtins.MapSerializer(
            kotlinx.serialization.serializer(),
            kotlinx.serialization.serializer(),
        )

    // ---- helpers ------------------------------------------------------------

    private fun rowToRecord(rs: java.sql.ResultSet): JobRecord = JobRecord(
        id = JobId(rs.getString("id")),
        pluginId = rs.getString("plugin_id"),
        action = rs.getString("action"),
        state = JobState.valueOf(rs.getString("state")),
        input = json.decodeFromString(MapSerializer, rs.getString("input_json")),
        output = json.decodeFromString(MapSerializer, rs.getString("output_json")),
        error = rs.getString("error"),
        processId = rs.getString("process_id"),
        createdAtMillis = rs.getLong("created_at"),
        startedAtMillis = getNullableLong(rs, "started_at"),
        completedAtMillis = getNullableLong(rs, "completed_at"),
    )

    private fun setNullableLong(ps: java.sql.PreparedStatement, index: Int, value: Long?) {
        if (value == null) ps.setNull(index, java.sql.Types.INTEGER) else ps.setLong(index, value)
    }

    private fun getNullableLong(rs: java.sql.ResultSet, column: String): Long? {
        val v = rs.getLong(column)
        return if (rs.wasNull()) null else v
    }

    private fun bindArgs(ps: java.sql.PreparedStatement, args: List<Any>) {
        args.forEachIndexed { i, arg ->
            when (arg) {
                is String -> ps.setString(i + 1, arg)
                is Long -> ps.setLong(i + 1, arg)
                is Int -> ps.setInt(i + 1, arg)
                else -> error("unsupported bind arg ${arg::class.simpleName}")
            }
        }
    }
}
