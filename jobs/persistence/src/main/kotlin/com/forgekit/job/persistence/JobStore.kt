package com.forgekit.job.persistence

import com.forgekit.job.api.JobEvent
import com.forgekit.job.api.JobId
import com.forgekit.job.api.JobQuery
import com.forgekit.job.api.JobRecord

/**
 * Durability contract for jobs (ARCHITECTURE §30/§31: jobs must be
 * recoverable and observable across process death).
 *
 * Two real implementations:
 *  - [SqliteJobStore] (this module) — a real SQLite database file via JDBC,
 *    used by JVM tooling and tests;
 *  - the Android store (app layer) — same schema over the platform SQLite.
 *
 * The store is APPEND-ONLY for events and SNAPSHOT-based for records:
 * `update` rewrites the current record row; the event log preserves the
 * full history that produced it.
 */
public interface JobStore : AutoCloseable {

    /** Inserts a new job record (QUEUED). Fails if the id already exists. */
    public fun insert(record: JobRecord)

    /** Rewrites the record snapshot (state, output, error, timestamps). */
    public fun update(record: JobRecord)

    /** Appends one typed event to the job's history. */
    public fun appendEvent(event: JobEvent)

    /** Current snapshot of [jobId], or null when unknown. */
    public fun find(jobId: JobId): JobRecord?

    /** The job's full event history in chronological order. */
    public fun events(jobId: JobId, limit: Int = Int.MAX_VALUE): List<JobEvent>

    /** History query (filter by plugin / states / time, newest first). */
    public fun query(query: JobQuery): List<JobRecord>

    /** All non-terminal jobs — what a restart must recover (§31). */
    public fun active(): List<JobRecord>

    /** Counters for the home dashboard. */
    public fun counts(): JobCounts

    /** Total persisted job count. */
    public fun total(): Long

    /** Removes one job's record and its event log. Returns false when [jobId] is unknown. */
    public fun delete(jobId: JobId): Boolean

    /** Wipes the entire job history (records + event log). Used by "Clear history". */
    public fun clear()

    public data class JobCounts(
        public val running: Long,
        public val completed: Long,
        public val failed: Long,
        public val cancelled: Long,
    )
}
