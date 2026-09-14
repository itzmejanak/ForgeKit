package com.forgekit.job.manager

import com.forgekit.job.api.JobEvent
import com.forgekit.job.api.JobId
import com.forgekit.job.api.JobPromptResponse
import com.forgekit.job.api.JobPromptResponseStatus
import com.forgekit.job.api.JobState
import com.forgekit.job.persistence.SqliteJobStore
import com.forgekit.runtime.api.ExecutionRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * REAL job lifecycle tests: every "plugin" is a real bash process speaking
 * the real forgekit/1 NDJSON protocol on real pipes. No mock runtime, no
 * canned output — process semantics, signal behavior and framing are the
 * OS's own.
 */
class JobManagerTest {

    private lateinit var dir: Path
    private lateinit var runtime: HostProcessRuntime

    @AfterTest
    fun cleanup() {
        if (::runtime.isInitialized) runtime.shutdown()
        if (::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    private fun setup(): JobManager {
        dir = createTempDirectory("jobs")
        runtime = HostProcessRuntime()
        return JobManager(runtime = runtime, store = SqliteJobStore(dir.resolve("jobs.db")))
    }

    /** Writes a bash "plugin" that echoes protocol lines. */
    private fun plugin(vararg protocolLines: String, prefix: String = "", exit: Int = 0): Path {
        val script = dir.resolve("plugin-${System.nanoTime()}.sh")
        val body = buildString {
            append("#!/bin/bash\n")
            append(prefix)
            append("IFS= read -r FORGE_INVOKE\n")
            append("FORGE_REQ=${'$'}(printf '%s\\n' \"${'$'}FORGE_INVOKE\" | sed -n 's/.*\"requestId\":\"\\([^\"]*\\)\".*/\\1/p')\n")
            for (line in protocolLines) {
                append("printf '%s\\n' '")
                append(line.replace("'", "'\\''"))
                append("' | sed \"s/REQ/${'$'}FORGE_REQ/g\"\n")
            }
            append("exit $exit\n")
        }
        Files.writeString(script, body)
        script.toFile().setExecutable(true)
        return script
    }

    private fun spec(script: Path, input: Map<String, String> = mapOf("name" to "Janak")) =
        JobManager.JobSpec(
            pluginId = "com.example.tool",
            action = "run",
            input = input,
            execution = ExecutionRequest(executable = script.toAbsolutePath().toString()),
        )

    private val progress = """{"protocol":"forgekit/1","type":"progress","requestId":"REQ","value":0.5,"message":"halfway"}"""
    private val result = """{"protocol":"forgekit/1","type":"result","requestId":"REQ","status":"success","output":{"greeting":"Hello Janak"}}"""

    @Test
    fun `full happy path reaches COMPLETED with protocol output`() = runBlocking {
        val manager = setup()
        val script = plugin(progress, result)
        val job = manager.enqueue(spec(script))
        assertEquals(JobState.QUEUED, job.state)

        val done = manager.run(job.id)
        assertEquals(JobState.COMPLETED, done.state)
        assertEquals("Hello Janak", done.output["greeting"])
        assertNotNull(done.startedAtMillis)
        assertNotNull(done.completedAtMillis)
    }

    @Test
    fun `state events stream in machine order`() = runBlocking {
        val manager = setup()
        val script = plugin(result)
        val job = manager.enqueue(spec(script))

        val states = mutableListOf<JobEvent.StateChanged>()
        val collector = launch {
            manager.events.collect { e -> (e as? JobEvent.StateChanged)?.let { states += it } }
        }
        manager.run(job.id)
        kotlinx.coroutines.delay(100) // let the collector drain the buffer
        collector.cancel()

        val names = states.map { it.to }
        assertEquals(
            listOf(
                JobState.PREPARING, JobState.STARTING, JobState.RUNNING, JobState.COMPLETED,
            ),
            names,
        )
    }

    @Test
    fun `progress and logs map to typed events`() = runBlocking {
        val manager = setup()
        val log = """{"protocol":"forgekit/1","type":"log","requestId":"REQ","level":"info","message":"booting"}"""
        val script = plugin(log, progress, result)
        val job = manager.enqueue(spec(script))

        val events = mutableListOf<JobEvent>()
        val collector = launch {
            manager.events.collect { e -> if (e !is JobEvent.StateChanged) events += e }
        }
        manager.run(job.id)
        kotlinx.coroutines.delay(150) // let the collector drain the buffer
        collector.cancel()

        assertTrue(events.any { it is JobEvent.LogLine && it.channel == "plugin:info" && it.line == "booting" })
        val progressEvent = events.filterIsInstance<JobEvent.Progress>().first()
        assertEquals(0.5, progressEvent.value)
        assertEquals("halfway", progressEvent.message)
    }

    @Test
    fun `protocol error message fails the job`() = runBlocking {
        val manager = setup()
        val error = """{"protocol":"forgekit/1","type":"error","requestId":"REQ","code":"PLUGIN_ERROR","message":"cannot open target"}"""
        val script = plugin(error)
        val job = manager.enqueue(spec(script))

        val done = manager.run(job.id)
        assertEquals(JobState.FAILED, done.state)
        assertEquals("PLUGIN_ERROR: cannot open target", done.error)
    }

    @Test
    fun `process crash without terminal message is an honest failure`() = runBlocking {
        val manager = setup()
        // exits 3 after printing a diagnostic, never speaks protocol
        val script = plugin(prefix = "echo 'not a protocol line'\n", exit = 3)
        val job = manager.enqueue(spec(script))

        val done = manager.run(job.id)
        assertEquals(JobState.FAILED, done.state)
        assertTrue("without a terminal protocol message" in done.error!!, done.error)
    }

    @Test
    fun `non-protocol stdout becomes diagnostic logs`() = runBlocking {
        val manager = setup()
        val script = plugin("plain stdout noise", result)
        val job = manager.enqueue(spec(script))

        val events = mutableListOf<JobEvent>()
        val collector = launch {
            manager.events.collect { e -> if (e is JobEvent.LogLine) events += e }
        }
        val done = manager.run(job.id)
        collector.cancel()

        assertEquals(JobState.COMPLETED, done.state, "diagnostic noise must not fail a job")
        val lines = events.filterIsInstance<JobEvent.LogLine>()
        assertTrue(lines.any { it.channel == "stdout" && it.line == "plain stdout noise" }, "lines: $lines")
    }

    @Test
    fun `result failure status fails even with exit 0`() = runBlocking {
        val manager = setup()
        val failure = """{"protocol":"forgekit/1","type":"result","requestId":"REQ","status":"failure","output":{}}"""
        val script = plugin(failure, exit = 0)
        val job = manager.enqueue(spec(script))

        val done = manager.run(job.id)
        assertEquals(JobState.FAILED, done.state)
        assertTrue("failure status" in done.error!!, done.error)
    }

    @Test
    fun `cancel escalates a running process to CANCELLED`() = runBlocking {
        val manager = setup()
        // long-running "plugin" that ignores nothing — sleeps 30s
        val script = plugin(prefix = "sleep 30\n")
        val job = manager.enqueue(spec(script))

        val runJob = launch { manager.run(job.id) }
        // wait until the job is actually RUNNING
        var record = manager.get(job.id)!!
        var waited = 0
        while (record.state != JobState.RUNNING && waited < 5000) {
            kotlinx.coroutines.delay(50); waited += 50
            record = manager.get(job.id)!!
        }
        assertEquals(JobState.RUNNING, record.state)

        val cancelled = manager.cancel(job.id)
        assertEquals(JobState.CANCELLED, cancelled.state)
        runJob.join()
        // the runtime process is really dead
        assertEquals(JobState.CANCELLED, manager.get(job.id)!!.state)
    }

    @Test
    fun `the invoke request reaches the plugin on stdin`() = runBlocking {
        val manager = setup()
        // plugin that captures stdin's last line into its result message
        val script = dir.resolve("stdin-echo.sh")
        Files.writeString(
            script,
            """
            #!/bin/bash
            IFS= read -r INVOKE
            REQ=${'$'}(printf '%s\n' "${'$'}INVOKE" | sed -n 's/.*"requestId":"\([^"]*\)".*/\1/p')
            LINE=${'$'}(printf '%s\n' "${'$'}INVOKE" | base64 -w 0)
            echo '{"protocol":"forgekit/1","type":"result","requestId":"'${'$'}REQ'","status":"success","output":{"saw":"'${'$'}LINE'"}}'
            """.trimIndent(),
        )
        script.toFile().setExecutable(true)

        val job = manager.enqueue(spec(script))
        val done = manager.run(job.id)
        assertEquals(JobState.COMPLETED, done.state)
        val encoded = done.output["saw"]!!
        val saw = java.util.Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
        assertTrue("\"type\":\"invoke\"" in saw, "plugin saw: $saw")
        assertTrue("\"action\":\"run\"" in saw)
        assertTrue("\"name\":\"Janak\"" in saw)
    }

    @Test
    fun `prompt pauses the job and response resumes it without persisting the value`() = runBlocking {
        val manager = setup()
        val script = dir.resolve("interactive.sh")
        Files.writeString(
            script,
            """
            #!/bin/bash
            IFS= read -r INVOKE
            REQ=${'$'}(printf '%s\n' "${'$'}INVOKE" | sed -n 's/.*"requestId":"\([^"]*\)".*/\1/p')
            echo '{"protocol":"forgekit/1","type":"prompt","requestId":"'${'$'}REQ'","promptId":"token","kind":"password","title":"Access token","message":"Enter a one-use token","required":true}'
            IFS= read -r RESPONSE
            if printf '%s' "${'$'}RESPONSE" | grep -q '"type":"prompt_response"'; then
              echo '{"protocol":"forgekit/1","type":"result","requestId":"'${'$'}REQ'","status":"success","output":{"accepted":"true"}}'
            else
              echo '{"protocol":"forgekit/1","type":"error","requestId":"'${'$'}REQ'","code":"BAD_RESPONSE","message":"missing response"}'
            fi
            """.trimIndent(),
        )
        script.toFile().setExecutable(true)
        val job = manager.enqueue(spec(script))

        val runner = async { manager.run(job.id) }
        val requested = withTimeout(5_000) {
            manager.events.filterIsInstance<JobEvent.PromptRequested>().first { it.jobId == job.id }
        }
        assertEquals("token", requested.prompt.id)
        assertEquals(JobState.PAUSED, manager.get(job.id)?.state)

        manager.respondToPrompt(
            job.id,
            "token",
            JobPromptResponse(JobPromptResponseStatus.SUBMITTED, "do-not-persist"),
        )
        val done = withTimeout(5_000) { runner.await() }
        assertEquals(JobState.COMPLETED, done.state)
        assertEquals("true", done.output["accepted"])

        val events = SqliteJobStore(dir.resolve("jobs.db")).use { it.events(job.id) }
        assertTrue(events.any { it is JobEvent.PromptRequested })
        assertTrue(events.any { it is JobEvent.PromptResolved })
        assertTrue(events.none { "do-not-persist" in it.toString() })
    }

    @Test
    fun `jobs persist across manager death`() = runBlocking {
        val manager = setup()
        val script = plugin(result)
        val job = manager.enqueue(spec(script))
        val done = manager.run(job.id)
        assertEquals(JobState.COMPLETED, done.state)

        // a NEW manager over the SAME store sees the history
        val reborn = JobManager(runtime = HostProcessRuntime(), store = SqliteJobStore(dir.resolve("jobs.db")))
        val loaded = reborn.get(job.id)
        assertEquals(JobState.COMPLETED, loaded!!.state)
        assertEquals("Hello Janak", loaded.output["greeting"])
        val persistedEvents = SqliteJobStore(dir.resolve("jobs.db")).use { store ->
            store.events(job.id)
        }
        assertTrue(persistedEvents.any { it is JobEvent.Completed }, "events: $persistedEvents")
    }

    @Test
    fun `unknown jobs are refused`() {
        val manager = setup()
        assertFailsWith<IllegalStateException> {
            runBlocking { manager.run(JobId.generate()) }
        }
        assertFailsWith<IllegalStateException> {
            runBlocking { manager.cancel(JobId.generate()) }
        }
    }

    @Test
    fun `timeout fails the job and kills the process`() = runBlocking {
        val manager = setup()
        val script = plugin(prefix = "sleep 30\n")
        val job = manager.enqueue(spec(script).copy(timeoutMillis = 400))
        val done = manager.run(job.id)
        assertEquals(JobState.FAILED, done.state)
        assertTrue("timed out" in done.error!!, done.error)
    }

    // ---- provisioning (§30 INSTALLING_DEPENDENCIES) ---------------------------

    private fun provisionSpec(action: String = "forge://provision") =
        JobManager.JobSpec(
            pluginId = "com.example.tool",
            action = action,
            input = emptyMap(),
            execution = null,
        )

    @Test
    fun `provisioning-only job reaches COMPLETED through explicit provisioning`() = runBlocking {
        val manager = setup()
        val job = manager.enqueue(provisionSpec())
        assertEquals(JobState.QUEUED, job.state)

val events = mutableListOf<JobEvent>()
        val collector = launch {
            manager.events.collect { e -> events += e }
        }
        kotlinx.coroutines.yield() // let the collector start subscribing before emissions

        assertEquals(JobState.INSTALLING_DEPENDENCIES, manager.beginProvisioning(job.id).state)
        manager.emitProvisionLog(job.id, "Setting up radare2 (5.9.0) ...", "provision")
        manager.emitProvisionProgress(job.id, 0.5, "radare2 · configure")
        val done = manager.finishProvision(job.id, success = true)
        assertEquals(JobState.COMPLETED, done.state)
        kotlinx.coroutines.delay(100) // let the collector drain the buffer
        collector.cancel()

        val names = events.filterIsInstance<JobEvent.StateChanged>().map { it.to }
        assertEquals(
            listOf(JobState.PREPARING, JobState.INSTALLING_DEPENDENCIES, JobState.COMPLETED),
            names,
        )
        assertTrue(events.any { it is JobEvent.LogLine && it.channel == "provision" && "radare2" in it.line })
        val progress = events.filterIsInstance<JobEvent.Progress>().single()
        assertEquals(0.5, progress.value)
        assertEquals("radare2 · configure", progress.message)
        assertTrue(events.any { it is JobEvent.Completed }, "a provisioning-only job still emits Completed")
        assertNull(done.processId, "no process may ever start for a provisioning-only job")
    }

    @Test
    fun `delete removes a finished job and refuses a live one`() = runBlocking {
        val manager = setup()
        val live = manager.enqueue(provisionSpec())
        manager.beginProvisioning(live.id)
        assertFailsWith<IllegalStateException> { manager.delete(live.id) }
        assertNotNull(manager.get(live.id), "a refused delete keeps the job")

        manager.finishProvision(live.id, success = true)
        assertTrue(manager.delete(live.id))
        assertNull(manager.get(live.id))
        assertEquals(false, manager.delete(live.id), "unknown jobs report false")
    }

    @Test
    fun `provisioning failure fails the job with the reason`() = runBlocking {
        val manager = setup()
        val job = manager.enqueue(provisionSpec())
        manager.beginProvisioning(job.id)
        val done = manager.finishProvision(job.id, success = false, error = "radare2 install failed")
        assertEquals(JobState.FAILED, done.state)
        assertTrue("radare2 install failed" in done.error!!, done.error)
    }

    @Test
    fun `progress outside INSTALLING_DEPENDENCIES is rejected`() = runBlocking {
        val manager = setup()
        val job = manager.enqueue(provisionSpec())
        val error = kotlin.runCatching { manager.emitProvisionProgress(job.id, 0.5, "nope") }
            .exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "expected IllegalArgumentException, was $error")
        assertTrue("INSTALLING_DEPENDENCIES" in (error!!.message ?: ""))
    }

    @Test
    fun `runWithProvisioning continues to STARTING only when provisioning succeeds`() = runBlocking {
        val manager = setup()
        val script = plugin(result)

        val okJob = manager.enqueue(spec(script))
        val ok = manager.runWithProvisioning(okJob.id) { true }
        assertEquals(JobState.COMPLETED, ok.state, "successful provisioning proceeds to execute")
        assertEquals("Hello Janak", ok.output["greeting"])

        val failJob = manager.enqueue(spec(script))
        val failed = manager.runWithProvisioning(failJob.id) { false }
        assertEquals(JobState.FAILED, failed.state, "failed provisioning must never start a process")
        assertTrue("dependency provisioning failed" in failed.error!!, failed.error)
        assertNull(failed.processId, "no process may start after a provisioning failure")
    }

    @Test
    fun `runWithProvisioning after beginProvisioning continues without re-entering PREPARING`() = runBlocking {
        val manager = setup()
        val script = plugin(result)

        val job = manager.enqueue(spec(script))
        manager.beginProvisioning(job.id)

        val record = manager.runWithProvisioning(job.id) { true }
        assertEquals(JobState.COMPLETED, record.state)
        assertEquals("Hello Janak", record.output["greeting"])
        assertNotNull(record.processId, "a job begun into INSTALLING_DEPENDENCIES still runs its process")
    }

    @Test
    fun `reconcileOrphans spares a job whose runtime child is still alive`() = runBlocking {
        val manager = setup()
        val script = plugin(result, prefix = "sleep 1\n")
        val job = manager.enqueue(spec(script))

        val runner = launch { manager.run(job.id) }
        delay(400)

        val reconciled = manager.reconcileOrphans()
        assertEquals(0, reconciled, "a live run must never be reconciled away")
        runner.join()

        val done = manager.get(job.id)
        assertEquals(JobState.COMPLETED, done?.state, "the spared run still executes to completion")
        assertEquals("Hello Janak", done?.output?.get("greeting"))
    }
}
