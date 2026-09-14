package com.forgekit.integration

import com.forgekit.job.api.JobEvent
import com.forgekit.job.api.JobPromptResponse
import com.forgekit.job.api.JobPromptResponseStatus
import com.forgekit.job.api.JobState
import com.forgekit.job.manager.HostProcessRuntime
import com.forgekit.job.manager.JobManager
import com.forgekit.job.persistence.SqliteJobStore
import com.forgekit.plugin.manager.PluginManager
import com.forgekit.runtime.api.ExecutionRequest
import com.forgekit.tools.forgebuilder.ForgePackager
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The REAL §21 acceptance path, exactly as the architecture draws it:
 *
 *   source tree → forge build → .forge archive
 *   → import (strict validation) → approve → install (tree on disk)
 *   → job (real python3 process) → forgekit/1 protocol on stdio
 *   → COMPLETED with the plugin's actual output.
 *
 * Zero mocks: python3 runs for real, the archive is real, the package
 * layout lands on disk, the protocol is parsed from the process's own
 * stdout bytes.
 */
class PluginAcceptanceTest {

    private lateinit var dir: Path
    private lateinit var runtime: HostProcessRuntime
    private lateinit var manager: JobManager
    private lateinit var plugins: PluginManager
    private val exampleRoot: Path = Path.of(
        System.getProperty("FORGEKIT_EXAMPLES")
            ?: error("FORGEKIT_EXAMPLES must be supplied by the Gradle test task"),
    )

    private val python: String = System.getProperty("FORGEKIT_PYTHON")
        ?: "python3"

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("acceptance")
        runtime = HostProcessRuntime()
        manager = JobManager(runtime = runtime, store = SqliteJobStore(dir.resolve("jobs.db")))
        plugins = PluginManager(
            pluginsRoot = dir.resolve("forge/plugins"),
            runtime = runtime,
            validationContext = com.forgekit.plugin.validator.ValidationContext(
                trustStore = com.forgekit.core.security.TrustStore(),
            ),
        )
    }

    @AfterTest
    fun cleanup() {
        runtime.shutdown()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `hello plugin runs the full §21 chain`() = runBlocking {
        // 1. build the real archive from the real source tree
        val archive = ForgePackager.build(exampleRoot.resolve("hello"), dir.resolve("hello.forge")).output
        assertTrue(Files.exists(archive))

        // 2. import + approve (installed tree lands on disk)
        val review = plugins.import(archive)
        assertEquals("com.forgekit.hello", review.descriptor.id.raw)
        val installed = plugins.approveImport(review.descriptor.id)
        assertTrue(
            Files.isRegularFile(
                Path.of(installed.installedPath!!).resolve("runtime/main.py"),
            ),
            "the entrypoint must be installed on disk",
        )

        // 3. run the action through the job system — REAL python3 process
        val entrypoint = Path.of(installed.installedPath!!).resolve("runtime/main.py").toAbsolutePath()
        val job = manager.enqueue(
            JobManager.JobSpec(
                pluginId = "com.forgekit.hello",
                action = "greet",
                input = mapOf("name" to "Janak"),
                execution = ExecutionRequest(
                    executable = python,
                    args = listOf(entrypoint.toString()),
                    workingDirectory = dir.toString(),
                ),
            ),
        )

        val events = mutableListOf<JobEvent>()
        val collector = kotlinx.coroutines.GlobalScope.launch { manager.events.collect { events += it } }
        val done = manager.run(job.id)
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(150) }
        collector.cancel()

        // 4. the plugin's own words came back through the protocol
        assertEquals(JobState.COMPLETED, done.state, "error: ${done.error}")
        assertEquals("Hello Janak", done.output["greeting"])

        val progress = events.filterIsInstance<JobEvent.Progress>()
        assertEquals(2, progress.size, "the plugin emits two progress events: $progress")
        assertEquals(1.0, progress.last().value)
        assertTrue(events.any { it is JobEvent.LogLine && it.channel == "plugin:info" })
    }

    @Test
    fun `missing input fails through the protocol not the process`() = runBlocking {
        val archive = ForgePackager.build(exampleRoot.resolve("hello"), dir.resolve("hello.forge")).output
        val review = plugins.import(archive)
        val installed = plugins.approveImport(review.descriptor.id)
        val entrypoint = Path.of(installed.installedPath!!).resolve("runtime/main.py").toAbsolutePath()

        val job = manager.enqueue(
            JobManager.JobSpec(
                pluginId = "com.forgekit.hello",
                action = "greet",
                input = mapOf("name" to ""),
                execution = ExecutionRequest(
                    executable = python,
                    args = listOf(entrypoint.toString()),
                ),
            ),
        )
        val done = manager.run(job.id)
        assertEquals(JobState.FAILED, done.state)
        assertEquals("MISSING_INPUT: the 'name' input is required", done.error)
    }

    @Test
    fun `interactive package completes a real prompt response round trip`() = runBlocking {
        val archive = ForgePackager.build(
            exampleRoot.resolve("interactive"),
            dir.resolve("interactive.forge"),
        ).output
        val review = plugins.import(archive)
        val installed = plugins.approveImport(review.descriptor.id)
        val entrypoint = Path.of(installed.installedPath!!).resolve("runtime/main.py").toAbsolutePath()
        val job = manager.enqueue(
            JobManager.JobSpec(
                pluginId = installed.id.raw,
                action = "authorize",
                input = emptyMap(),
                execution = ExecutionRequest(executable = python, args = listOf(entrypoint.toString())),
            ),
        )

        val runner = async { manager.run(job.id) }
        val prompt = withTimeout(5_000) {
            manager.events.filterIsInstance<JobEvent.PromptRequested>().first { it.jobId == job.id }
        }
        assertEquals(JobState.PAUSED, manager.get(job.id)?.state)
        assertEquals("access-token", prompt.prompt.id)
        manager.respondToPrompt(
            job.id,
            prompt.prompt.id,
            JobPromptResponse(JobPromptResponseStatus.SUBMITTED, "ephemeral-token"),
        )

        val done = withTimeout(5_000) { runner.await() }
        assertEquals(JobState.COMPLETED, done.state, done.error)
        assertEquals("true", done.output["accepted"])
        assertTrue(manager.events.replayCache.none { "ephemeral-token" in it.toString() })
    }

    @Test
    fun `ssl-patcher inspects a real APK through the full chain`() = runBlocking {
        // build a REAL minimal APK-shaped zip (the tool only needs a zip with lib/)
        val apk = dir.resolve("target.apk")
        java.util.zip.ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
            zip.write("<manifest/>".toByteArray())
            zip.closeEntry()
            for (name in listOf("lib/arm64-v8a/libflutter.so", "lib/arm64-v8a/libapp.so")) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(ByteArray(4096) { 0x55 })
                zip.closeEntry()
            }
        }

        val archive = ForgePackager.build(
            exampleRoot.resolve("ssl-patcher"), dir.resolve("ssl-patcher.forge"),
        ).output
        val review = plugins.import(archive)
        val installed = plugins.approveImport(review.descriptor.id)
        val entrypoint = Path.of(installed.installedPath!!).resolve("runtime/inspect.py").toAbsolutePath()

        val job = manager.enqueue(
            JobManager.JobSpec(
                pluginId = "com.forgekit.sslpatcher",
                action = "inspect",
                input = mapOf("target" to apk.toAbsolutePath().toString(), "arch" to "Auto detect"),
                execution = ExecutionRequest(
                    executable = python,
                    args = listOf(entrypoint.toString()),
                    workingDirectory = dir.toString(),
                ),
            ),
        )
        val done = manager.run(job.id)

        assertEquals(JobState.COMPLETED, done.state, "error: ${done.error}")
        val report = done.output["report"]!!
        assertTrue("2 libs (arm64-v8a)" in report, report)
        val plan = Path.of(done.output["plan"]!!)
        assertTrue(Files.isRegularFile(plan), "the patch plan artifact must exist")
        val planText = Files.readString(plan)
        assertTrue("libflutter.so" in planText, planText)
        assertTrue("forgekit.patchplan/v1" in planText)
    }
}
