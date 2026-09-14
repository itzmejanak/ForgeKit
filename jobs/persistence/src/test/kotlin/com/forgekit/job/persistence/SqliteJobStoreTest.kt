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
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * REAL SQLite tests — actual database files on disk (WAL mode), actual
 * native engine. What these tests prove is what survives an app restart.
 */
class SqliteJobStoreTest {

    private lateinit var dir: java.nio.file.Path
    private lateinit var store: SqliteJobStore

    private fun freshStore(): SqliteJobStore {
        dir = createTempDirectory("jobstore")
        return SqliteJobStore(dir.resolve("jobs.db"))
    }

    @AfterTest
    fun cleanup() {
        if (::store.isInitialized) store.close()
        if (::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    private fun queuedJob(
        plugin: String = "com.example.tool",
        action: String = "run",
        state: JobState = JobState.QUEUED,
    ) = JobRecord(
        id = JobId.generate(),
        pluginId = plugin,
        action = action,
        state = state,
        input = mapOf("target" to "/tmp/a.apk", "arch" to "arm64"),
        createdAtMillis = System.currentTimeMillis(),
    )

    @Test
    fun `insert and find round-trips the full record`() {
        store = freshStore()
        val job = queuedJob()
        store.insert(job)

        val loaded = store.find(job.id)!!
        assertEquals(job.id.raw, loaded.id.raw)
        assertEquals(job.pluginId, loaded.pluginId)
        assertEquals(job.action, loaded.action)
        assertEquals(job.state, loaded.state)
        assertEquals(job.input, loaded.input)
        assertEquals(job.output, loaded.output)
        assertNull(loaded.error)
        assertNull(loaded.processId)
        assertNull(loaded.startedAtMillis)
        assertNull(loaded.completedAtMillis)
    }

    @Test
    fun `duplicate insert is refused by the primary key`() {
        store = freshStore()
        val job = queuedJob()
        store.insert(job)
        assertFailsWith<java.sql.SQLException> { store.insert(job) }
    }

    @Test
    fun `update rewrites the lifecycle snapshot`() {
        store = freshStore()
        val job = queuedJob()
        store.insert(job)

        val running = job.copy(
            state = JobState.RUNNING,
            processId = "proc-7",
            startedAtMillis = job.createdAtMillis + 5,
        )
        store.update(running)
        assertEquals("RUNNING", store.find(job.id)!!.state.name)
        assertEquals("proc-7", store.find(job.id)!!.processId)

        val done = running.copy(
            state = JobState.COMPLETED,
            output = mapOf("patched" to "/tmp/out.apk"),
            completedAtMillis = job.createdAtMillis + 100,
        )
        store.update(done)
        val loaded = store.find(job.id)!!
        assertEquals(JobState.COMPLETED, loaded.state)
        assertEquals(mapOf("patched" to "/tmp/out.apk"), loaded.output)
        assertEquals(100L, loaded.completedAtMillis!! - loaded.createdAtMillis)
    }

    @Test
    fun `delete removes one job and its events only`() {
        store = freshStore()
        val gone = queuedJob(state = JobState.COMPLETED)
        val kept = queuedJob(state = JobState.FAILED)
        store.insert(gone)
        store.insert(kept)
        store.appendEvent(JobEvent.LogLine(gone.id, "bye", "stdout", 1))
        store.appendEvent(JobEvent.LogLine(kept.id, "stay", "stdout", 2))

        assertTrue(store.delete(gone.id))
        assertNull(store.find(gone.id))
        assertTrue(store.events(gone.id).isEmpty())
        assertEquals(kept, store.find(kept.id))
        assertEquals(1, store.events(kept.id).size)
        assertEquals(1L, store.total())
        assertFalse(store.delete(gone.id), "deleting an unknown job reports false")
    }

    @Test
    fun `events persist chronologically with full type fidelity`() {
        store = freshStore()
        val job = queuedJob()
        store.insert(job)

        store.appendEvent(JobEvent.StateChanged(job.id, JobState.QUEUED, JobState.PREPARING, 1))
        store.appendEvent(JobEvent.LogLine(job.id, "python booting", "stdout", 2))
        store.appendEvent(JobEvent.Progress(job.id, 0.5, "halfway", 3))
        store.appendEvent(JobEvent.Progress(job.id, 1.0, null, 4))
        store.appendEvent(
            JobEvent.Completed(job.id, mapOf("result" to "ok"), 5),
        )

        val events = store.events(job.id)
        assertEquals(5, events.size)
        assertEquals(JobEvent.StateChanged(job.id, JobState.QUEUED, JobState.PREPARING, 1), events[0])
        assertEquals(JobEvent.LogLine(job.id, "python booting", "stdout", 2), events[1])
        assertEquals(0.5, (events[2] as JobEvent.Progress).value)
        assertNull((events[3] as JobEvent.Progress).message)
        assertEquals(mapOf("result" to "ok"), (events[4] as JobEvent.Completed).output)

        assertEquals(2, store.events(job.id, limit = 2).size, "limit must truncate newest-kept order")
    }

    @Test
    fun `prompt events persist metadata and status without response values`() {
        store = freshStore()
        val job = queuedJob()
        store.insert(job)
        store.appendEvent(
            JobEvent.PromptRequested(
                job.id,
                JobPrompt(
                    id = "token",
                    kind = JobPromptKind.PASSWORD,
                    title = "Access token",
                    message = "One-use credential",
                    placeholder = "token",
                ),
                10,
            ),
        )
        store.appendEvent(
            JobEvent.PromptResolved(job.id, "token", JobPromptResponseStatus.SUBMITTED, 11),
        )

        val events = store.events(job.id)
        val requested = events[0] as JobEvent.PromptRequested
        val resolved = events[1] as JobEvent.PromptResolved
        assertEquals(JobPromptKind.PASSWORD, requested.prompt.kind)
        assertEquals("token", resolved.promptId)
        assertEquals(JobPromptResponseStatus.SUBMITTED, resolved.status)
    }

    @Test
    fun `failed events carry the exit status exactly`() {
        store = freshStore()
        val job = queuedJob()
        store.insert(job)
        store.appendEvent(
            JobEvent.Failed(job.id, "python exited", ExitStatus.Exited(2), 9),
        )
        store.appendEvent(
            JobEvent.Failed(job.id, "killed", ExitStatus.Signaled(9), 10),
        )
        val events = store.events(job.id)
        assertEquals(ExitStatus.Exited(2), (events[0] as JobEvent.Failed).exitStatus)
        assertEquals(ExitStatus.Signaled(9), (events[1] as JobEvent.Failed).exitStatus)
    }

    @Test
    fun `query filters by plugin, state and time`() {
        store = freshStore()
        val a1 = queuedJob(plugin = "com.a")
        val a2 = queuedJob(plugin = "com.a").copy(
            state = JobState.COMPLETED,
            completedAtMillis = System.currentTimeMillis(),
        )
        val b1 = queuedJob(plugin = "com.b")
        store.insert(a1); store.insert(a2); store.insert(b1)

        assertEquals(2, store.query(JobQuery(pluginId = "com.a")).size)
        assertEquals(1, store.query(JobQuery(states = setOf(JobState.COMPLETED))).size)
        assertEquals(2, store.query(JobQuery(states = setOf(JobState.QUEUED))).size)
        assertEquals(
            1,
            store.query(JobQuery(pluginId = "com.a", states = setOf(JobState.QUEUED))).size,
        )
        assertEquals(3, store.query(JobQuery(limit = 10)).size)
        assertEquals(1, store.query(JobQuery(limit = 1)).size, "newest-first ordering with limit")
    }

    @Test
    fun `active returns only recoverable jobs`() {
        store = freshStore()
        val live = queuedJob()
        val paused = queuedJob().copy(state = JobState.PAUSED)
        val done = queuedJob().copy(state = JobState.COMPLETED)
        val failed = queuedJob().copy(state = JobState.FAILED)
        store.insert(live); store.insert(paused); store.insert(done); store.insert(failed)

        val active = store.active()
        assertEquals(setOf(live.id.raw, paused.id.raw), active.map { it.id.raw }.toSet())
    }

    @Test
    fun `counts feed the home dashboard`() {
        store = freshStore()
        store.insert(queuedJob().copy(state = JobState.RUNNING))
        store.insert(queuedJob().copy(state = JobState.COMPLETED))
        store.insert(queuedJob().copy(state = JobState.COMPLETED))
        store.insert(queuedJob().copy(state = JobState.FAILED))
        store.insert(queuedJob().copy(state = JobState.CANCELLED))

        val counts = store.counts()
        assertEquals(1L, counts.running)
        assertEquals(2L, counts.completed)
        assertEquals(1L, counts.failed)
        assertEquals(1L, counts.cancelled)
        assertEquals(5L, store.total())
    }

    @Test
    fun `persistence survives store closure and reopening`() {
        store = freshStore()
        val job = queuedJob()
        store.insert(job)
        store.appendEvent(JobEvent.StateChanged(job.id, JobState.QUEUED, JobState.PREPARING, 1))
        store.appendEvent(JobEvent.LogLine(job.id, "hello", "stdout", 2))
        val dbFile = dir.resolve("jobs.db")
        store.close()

        val reopened = SqliteJobStore(dbFile)
        try {
            assertEquals(JobState.QUEUED, reopened.find(job.id)!!.state)
            assertEquals(2, reopened.events(job.id).size)
            assertEquals(1L, reopened.total())
        } finally {
            reopened.close()
        }
        assertTrue(Files.exists(dbFile))
    }

    @Test
    fun `events of unknown jobs are refused by the foreign key`() {
        store = freshStore()
        val ghost = JobId.generate()
        assertFailsWith<java.sql.SQLException> {
            store.appendEvent(JobEvent.Cancelled(ghost, 1))
        }
    }
}
