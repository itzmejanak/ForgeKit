package com.forgekit.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.forgekit.core.model.ExitStatus
import com.forgekit.job.api.JobEvent
import com.forgekit.job.api.JobId
import com.forgekit.job.api.JobQuery
import com.forgekit.job.api.JobRecord
import com.forgekit.job.api.JobPrompt
import com.forgekit.job.api.JobPromptKind
import com.forgekit.job.api.JobPromptResponseStatus
import com.forgekit.job.api.JobState
import com.forgekit.job.persistence.JobStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The Android [JobStore]: the same schema as the JVM SqliteJobStore, over
 * the platform's own SQLite engine (android.database.sqlite — the real
 * engine Termux-class tooling trusts, not a shim). WAL mode, FK-enforced
 * events, typed wire format shared with the JVM twin.
 */
public class AndroidSqliteJobStore(
    context: Context,
) : JobStore {

    private class Helper(context: Context) : SQLiteOpenHelper(
        context, "forgekit-jobs.db", null, SCHEMA_VERSION,
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE jobs (
                    id TEXT PRIMARY KEY,
                    plugin_id TEXT NOT NULL,
                    action TEXT NOT NULL,
                    state TEXT NOT NULL,
                    input_json TEXT NOT NULL,
                    output_json TEXT NOT NULL DEFAULT '{}',
                    error TEXT,
                    process_id TEXT,
                    created_at INTEGER NOT NULL,
                    started_at INTEGER,
                    completed_at INTEGER
                )""",
            )
            db.execSQL(
                """CREATE TABLE job_events (
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    job_id TEXT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
                    type TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    ts_millis INTEGER NOT NULL
                )""",
            )
            db.execSQL("CREATE INDEX idx_jobs_plugin ON jobs(plugin_id, created_at DESC)")
            db.execSQL("CREATE INDEX idx_events_job ON job_events(job_id, seq)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // additive migrations land here (§47)
        }
    }

    private val helper = Helper(context.applicationContext)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override fun insert(record: JobRecord) {
        val values = ContentValues().apply {
            put("id", record.id.raw)
            put("plugin_id", record.pluginId)
            put("action", record.action)
            put("state", record.state.name)
            put("input_json", json.encodeMap(record.input))
            put("output_json", json.encodeMap(record.output))
            put("error", record.error)
            put("process_id", record.processId)
            put("created_at", record.createdAtMillis)
            putNullable("started_at", record.startedAtMillis)
            putNullable("completed_at", record.completedAtMillis)
        }
        helper.writableDatabase.insertOrThrow("jobs", null, values)
    }

    override fun update(record: JobRecord) {
        val values = ContentValues().apply {
            put("state", record.state.name)
            put("output_json", json.encodeMap(record.output))
            put("error", record.error)
            put("process_id", record.processId)
            putNullable("started_at", record.startedAtMillis)
            putNullable("completed_at", record.completedAtMillis)
        }
        val changed = helper.writableDatabase.update("jobs", values, "id=?", arrayOf(record.id.raw))
        check(changed == 1) { "no job row '${record.id.raw}' to update" }
    }

    override fun appendEvent(event: JobEvent) {
        val values = ContentValues().apply {
            put("job_id", event.jobId.raw)
            put("type", event::class.simpleName)
            put("payload_json", json.encodeEvent(event))
            put("ts_millis", event.timestampMillis)
        }
        helper.writableDatabase.insertOrThrow("job_events", null, values)
    }

    override fun find(jobId: JobId): JobRecord? {
        helper.readableDatabase.query(
            "jobs", null, "id=?", arrayOf(jobId.raw), null, null, null,
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toRecord() else null
        }
    }

    override fun events(jobId: JobId, limit: Int): List<JobEvent> {
        helper.readableDatabase.query(
            "job_events", arrayOf("payload_json"), "job_id=?", arrayOf(jobId.raw),
            null, null, "seq ASC", "$limit",
        ).use { cursor ->
            val result = mutableListOf<JobEvent>()
            while (cursor.moveToNext()) {
                // one malformed/legacy row must never crash the whole log console: skip it.
                runCatching { json.decodeEvent(cursor.getString(0)) }.getOrNull()?.let { result += it }
            }
            return result
        }
    }

    override fun query(query: JobQuery): List<JobRecord> {
        val where = StringBuilder("1=1")
        val args = mutableListOf<String>()
        query.pluginId?.let { where.append(" AND plugin_id=?"); args += it }
        if (query.states.isNotEmpty()) {
            where.append(" AND state IN (${query.states.joinToString(",") { "?" }})")
            args += query.states.map { it.name }
        }
        query.sinceMillis?.let { where.append(" AND created_at >= ?"); args += it.toString() }
        helper.readableDatabase.query(
            "jobs", null, where.toString(), args.toTypedArray(),
            null, null, "created_at DESC", "${query.limit}",
        ).use { cursor ->
            val result = mutableListOf<JobRecord>()
            while (cursor.moveToNext()) result += cursor.toRecord()
            return result
        }
    }

    override fun active(): List<JobRecord> {
        helper.readableDatabase.query(
            "jobs", null,
            "state NOT IN ('COMPLETED','FAILED','CANCELLED')",
            null, null, null, "created_at ASC",
        ).use { cursor ->
            val result = mutableListOf<JobRecord>()
            while (cursor.moveToNext()) result += cursor.toRecord()
            return result
        }
    }

    override fun counts(): JobStore.JobCounts {
        val columns = arrayOf(
            "SUM(state IN ('QUEUED','PREPARING','INSTALLING_DEPENDENCIES','STARTING','RUNNING','PAUSED','CANCELLING')) AS running",
            "SUM(state='COMPLETED') AS completed",
            "SUM(state='FAILED') AS failed",
            "SUM(state='CANCELLED') AS cancelled",
        )
        helper.readableDatabase.query("jobs", columns, null, null, null, null, null).use { cursor ->
            cursor.moveToFirst()
            return JobStore.JobCounts(
                running = cursor.getLong(0),
                completed = cursor.getLong(1),
                failed = cursor.getLong(2),
                cancelled = cursor.getLong(3),
            )
        }
    }

    override fun total(): Long {
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM jobs", null).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    /** Events first, then the record, in one transaction (foreign keys are not relied on). */
    override fun delete(jobId: JobId): Boolean {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("job_events", "job_id = ?", arrayOf(jobId.raw))
            val removed = db.delete("jobs", "id = ?", arrayOf(jobId.raw))
            db.setTransactionSuccessful()
            return removed == 1
        } finally {
            db.endTransaction()
        }
    }

    /** Wipes records + events; job_events is FK-cascaded from jobs, so deleting
     *  jobs first covers both tables (the autoincrement seq is intentionally left). */
    override fun clear() {
        helper.writableDatabase.delete("job_events", null, null)
        helper.writableDatabase.delete("jobs", null, null)
    }

    override fun close() {
        helper.close()
    }

    public companion object {
        public const val SCHEMA_VERSION: Int = 1
    }

    // ---- cursor + wire helpers ------------------------------------------------

    private fun android.database.Cursor.toRecord(): JobRecord = JobRecord(
        id = JobId(getString(getColumnIndexOrThrow("id"))),
        pluginId = getString(getColumnIndexOrThrow("plugin_id")),
        action = getString(getColumnIndexOrThrow("action")),
        state = JobState.valueOf(getString(getColumnIndexOrThrow("state"))),
        input = json.decodeMap(getString(getColumnIndexOrThrow("input_json"))),
        output = json.decodeMap(getString(getColumnIndexOrThrow("output_json"))),
        error = getString(getColumnIndexOrThrow("error")),
        processId = getString(getColumnIndexOrThrow("process_id")),
        createdAtMillis = getLong(getColumnIndexOrThrow("created_at")),
        startedAtMillis = longOrNull("started_at"),
        completedAtMillis = longOrNull("completed_at"),
    )

    private fun android.database.Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private fun ContentValues.putNullable(column: String, value: Long?) {
        if (value == null) putNull(column) else put(column, value)
    }

    private fun Json.encodeMap(map: Map<String, String>): String =
        encodeToString(kotlinx.serialization.json.JsonObject.serializer(), buildJsonObject {
            map.forEach { (k, v) -> put(k, v) }
        })

    private fun Json.decodeMap(text: String): Map<String, String> =
        parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun Json.encodeEvent(event: JobEvent): String =
        encodeToString(kotlinx.serialization.json.JsonObject.serializer(), buildJsonObject {
            put("jobId", event.jobId.raw)
            put("type", event::class.simpleName ?: "unknown")
            put("ts", event.timestampMillis)
            when (event) {
                is JobEvent.StateChanged -> {
                    put("from", event.from.name)
                    put("to", event.to.name)
                }
                is JobEvent.LogLine -> {
                    put("line", event.line)
                    put("channel", event.channel)
                }
                is JobEvent.Progress -> {
                    put("value", event.value)
                    put("message", event.message)
                }
                is JobEvent.PromptRequested -> {
                    put("promptId", event.prompt.id)
                    put("promptKind", event.prompt.kind.name)
                    put("promptTitle", event.prompt.title)
                    put("message", event.prompt.message)
                    put("promptRequired", event.prompt.required)
                    put("promptChoices", buildJsonArray { event.prompt.choices.forEach { add(JsonPrimitive(it)) } })
                    put("promptDefault", event.prompt.default)
                    put("promptPlaceholder", event.prompt.placeholder)
                }
                is JobEvent.PromptResolved -> {
                    put("promptId", event.promptId)
                    put("responseStatus", event.status.name)
                }
                is JobEvent.Completed -> {
                    put("output", json.encodeMap(event.output))
                }
                is JobEvent.Failed -> {
                    put("reason", event.reason)
                    val exit = event.exitStatus
                    put("exitKind", when (exit) {
                        is ExitStatus.Exited -> "exited"
                        is ExitStatus.Signaled -> "signaled"
                        is ExitStatus.Unknown -> "unknown"
                        null -> "null"
                    })
                    when (exit) {
                        is ExitStatus.Exited -> put("exitCode", exit.code)
                        is ExitStatus.Signaled -> put("exitSignal", exit.signal)
                        else -> Unit
                    }
                }
                is JobEvent.Cancelled -> Unit
            }
        })

    private fun Json.decodeEvent(text: String): JobEvent {
        val obj = parseToJsonElement(text).jsonObject
        val jobId = JobId(obj.getValue("jobId").jsonPrimitive.content)
        val ts = obj.getValue("ts").jsonPrimitive.content.toLong()
        return when (val type = obj.getValue("type").jsonPrimitive.content) {
            "StateChanged" -> JobEvent.StateChanged(
                jobId,
                JobState.valueOf(obj.getValue("from").jsonPrimitive.content),
                JobState.valueOf(obj.getValue("to").jsonPrimitive.content),
                ts,
            )
            "LogLine" -> JobEvent.LogLine(
                jobId,
                obj.getValue("line").jsonPrimitive.content,
                obj.getValue("channel").jsonPrimitive.content,
                ts,
            )
            "Progress" -> JobEvent.Progress(
                jobId,
                obj.getValue("value").jsonPrimitive.content.toDouble(),
                obj["message"]?.jsonPrimitive?.content,
                ts,
            )
            "PromptRequested" -> JobEvent.PromptRequested(
                jobId,
                JobPrompt(
                    id = obj.getValue("promptId").jsonPrimitive.content,
                    kind = JobPromptKind.valueOf(obj.getValue("promptKind").jsonPrimitive.content),
                    title = obj.getValue("promptTitle").jsonPrimitive.content,
                    message = obj["message"]?.jsonPrimitive?.content,
                    required = obj["promptRequired"]?.jsonPrimitive?.content?.toBoolean() ?: true,
                    choices = obj["promptChoices"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    default = obj["promptDefault"]?.jsonPrimitive?.content,
                    placeholder = obj["promptPlaceholder"]?.jsonPrimitive?.content,
                ),
                ts,
            )
            "PromptResolved" -> JobEvent.PromptResolved(
                jobId,
                obj.getValue("promptId").jsonPrimitive.content,
                JobPromptResponseStatus.valueOf(obj.getValue("responseStatus").jsonPrimitive.content),
                ts,
            )
            "Completed" -> JobEvent.Completed(
                jobId,
                // output was stored via encodeMap() as a JSON *string*, so parse that string
                // back into a map (it is not a nested JsonObject on the event).
                decodeMap(obj.getValue("output").jsonPrimitive.content),
                ts,
            )
            "Failed" -> JobEvent.Failed(
                jobId,
                obj.getValue("reason").jsonPrimitive.content,
                when (obj.getValue("exitKind").jsonPrimitive.content) {
                    "exited" -> ExitStatus.Exited(obj["exitCode"]?.jsonPrimitive?.content?.toInt() ?: -1)
                    "signaled" -> ExitStatus.Signaled(obj["exitSignal"]?.jsonPrimitive?.content?.toInt() ?: -1)
                    "unknown" -> ExitStatus.Unknown(obj.getValue("reason").jsonPrimitive.content)
                    else -> null
                },
                ts,
            )
            "Cancelled" -> JobEvent.Cancelled(jobId, ts)
            else -> error("unknown persisted event type '$type'")
        }
    }
}
