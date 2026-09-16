package com.forgekit.runtime.termux

import com.forgekit.core.filesystem.ForgePaths
import com.forgekit.core.model.ExitStatus
import com.forgekit.runtime.api.ExecutionRequest
import com.forgekit.runtime.api.ProcessOutput
import com.forgekit.runtime.api.RuntimeState
import com.forgekit.runtime.api.SignalRequest
import com.forgekit.termux.embedded.PtyProcess
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * REAL end-to-end tests of EmbeddedTermuxRuntime against a REAL host prefix.
 *
 * The test builds a genuine Termux-shaped prefix on the host filesystem:
 *
 *     <tmp>/termux/usr/bin/{sh, bash, dpkg, dpkg-query, env, ...} → host binaries (symlinks)
 *     <tmp>/termux/usr/var/lib/dpkg/status                            → REAL dpkg text db
 *     <tmp>/termux/home                                               → $HOME
 *
 * Every assertion below exercises the production code path: ForgePaths resolution,
 * executable policy checks, fork+openpty+execve through the JNI harness, output
 * streaming, signal delivery and the §71 health battery — no fakes anywhere.
 * On Android the same class runs against the arm64 bootstrap.
 */
class EmbeddedTermuxRuntimeTest {

    companion object {
        private lateinit var root: Path
        private lateinit var runtime: EmbeddedTermuxRuntime

        @org.junit.jupiter.api.BeforeAll
        @JvmStatic
        fun setUpPrefix() {
            PtyProcess.loadHostHarness(locateHostLibrary())
            root = Files.createTempDirectory("forgekit-runtime-test")
            buildHostPrefix(root)
            runtime = EmbeddedTermuxRuntime(
                paths = ForgePaths(root),
                bootstrapDescriptor = com.forgekit.runtime.bootstrap.BootstrapDescriptor(
                    abi = "x86_64-host",
                    source = com.forgekit.runtime.bootstrap.BootstrapSource { fail("bootstrap must not be read: prefix pre-installed") },
                    expectedSha256 = null,
                    expectedSizeBytes = null,
                    termuxSuite = "host",
                ),
            )
        }

        /** A REAL prefix: host executables reachable through Termux layout paths. */
        private fun buildHostPrefix(root: Path) {
            val prefix = root.resolve("termux/usr")
            val bin = prefix.resolve("bin")
            Files.createDirectories(bin)
            Files.createDirectories(prefix.resolve("var/lib/dpkg"))
            Files.createDirectories(prefix.resolve("var/lib/forgekit"))
            // This host fixture represents a fully initialized existing prefix. Migration and
            // interrupted-relocation behavior is covered by BootstrapOrchestratorTest.
            Files.newOutputStream(prefix.resolve("var/lib/forgekit/relocation-v2-complete")).use {
                it.write("complete\n".toByteArray())
            }
            Files.createDirectories(root.resolve("termux/home"))
            Files.createDirectories(prefix.resolve("tmp"))

            val targets = mapOf(
                "sh" to "/bin/sh",
                "bash" to "/bin/bash",
                "dash" to "/bin/sh",
                "dpkg" to "/usr/bin/dpkg",
                "dpkg-query" to "/usr/bin/dpkg-query",
                "env" to "/usr/bin/env",
                "git" to "/usr/bin/git",
                "curl" to "/usr/bin/curl",
                "tar" to "/usr/bin/tar",
                "unzip" to "/usr/bin/unzip",
            )
            for ((name, target) in targets) {
                val resolved = Path.of(target)
                if (Files.isExecutable(resolved)) {
                    Files.createSymbolicLink(bin.resolve(name), resolved)
                }
            }
            // a minimal but REAL dpkg status database
            Files.newOutputStream(prefix.resolve("var/lib/dpkg/status")).use {
                it.write(
                    """Package: forgekit-host-meta
                    |Status: install ok installed
                    |Architecture: amd64
                    |Version: 1.0.0
                    |Maintainer: ForgeKit Tests
                    |Description: ForgeKit host test prefix marker
                    |
                    """.trimMargin().toByteArray(),
                )
            }
        }

        private fun locateHostLibrary(): java.io.File {
            var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                val candidate = java.io.File(dir, "build/host-pty/libforgekit_pty_host.so")
                if (candidate.isFile) return candidate
                dir = dir.parentFile
            }
            error("host PTY library not found — run compileHostPty")
        }

        @org.junit.jupiter.api.AfterAll
        @JvmStatic
        fun tearDown() {
            if (::root.isInitialized) root.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------------

    @Test
    fun `initialize reaches READY on a real installed prefix`() = runBlocking {
        val state = runtime.initialize()
        assertEquals(RuntimeState.READY, state)
        assertEquals(RuntimeState.READY, runtime.stateChanges.value)
    }

    @Test
    fun `health battery passes all section-71 checks on the real prefix`() = runBlocking {
        runtime.initialize()
        val health = runtime.healthCheck()
        val failed = health.checks.filter { !it.passed }
        assertTrue(health.checks.size >= 6, "battery must run at least the 6 minimum checks: ${health.checks.map { it.name }}")
        assertTrue(failed.isEmpty(), "failed checks: ${failed.map { "${it.name}: ${it.detail}" }}")
        assertTrue(health.healthy)
    }

    @Test
    fun `execute runs a real process and streams output and exit`() = runBlocking {
        runtime.initialize()
        val handle = runtime.execute(
            ExecutionRequest(
                executable = "sh",
                args = listOf("-c", "echo forgekit-exec-real; exit 0"),
                label = "exec-test",
            ),
        )
        assertTrue(handle.pid > 1, "handle must carry a real pid, got ${handle.pid}")
        val events = runtime.output(handle.processId).toList()
        val stdout = events.filterIsInstance<ProcessOutput.Stdout>()
            .joinToString("") { String(it.bytes, Charsets.UTF_8) }
        val exit = events.filterIsInstance<ProcessOutput.Exited>().singleOrNull()
        assertNotNull(exit, "exactly one Exited event expected")
        assertTrue("forgekit-exec-real" in stdout, "stdout must contain the real output: '$stdout'")
        assertEquals(0, (exit!!.exitStatus as ExitStatus.Exited).code)
    }

    @Test
    fun `execute reports nonzero exit codes from real processes`() = runBlocking {
        runtime.initialize()
        val handle = runtime.execute(
            ExecutionRequest(executable = "sh", args = listOf("-c", "exit 42"), label = "exit-code-test"),
        )
        val exit = runtime.output(handle.processId)
            .toList()
            .filterIsInstance<ProcessOutput.Exited>()
            .single()
        assertEquals(42, (exit.exitStatus as ExitStatus.Exited).code)
    }

    @Test
    fun `stdin bytes reach the real process (raw pty)`() = runBlocking {
        runtime.initialize()
        val handle = runtime.execute(
            ExecutionRequest(
                executable = "sh",
                args = listOf("-c", "read line; echo \"\$line\"; exit 0"),
                stdinBytes = "HELLO-FORGE\n".toByteArray(),
                label = "stdin-test",
            ),
        )
        val stdout = runtime.output(handle.processId).toList()
            .filterIsInstance<ProcessOutput.Stdout>()
            .joinToString("") { String(it.bytes, Charsets.UTF_8) }
        assertTrue("HELLO-FORGE" in stdout, "stdin must round-trip through the raw pty: '$stdout'")
    }

    @Test
    fun `execute rejects executables outside app-owned areas`() = runBlocking {
        runtime.initialize()
        try {
            runtime.execute(ExecutionRequest(executable = "/bin/sh", args = listOf("-c", "true")))
            fail("absolute path outside the prefix/root must be rejected")
        } catch (expected: com.forgekit.runtime.api.ProcessStartError) {
            val text = (expected.message + " " + (expected.detail ?: "")).lowercase()
            assertTrue("outside" in text || "not inside" in text, "policy text was: $text")
        }
    }

    @Test
    fun `execute rejects nonexistent executables with a typed error`() = runBlocking {
        runtime.initialize()
        try {
            runtime.execute(ExecutionRequest(executable = "does-not-exist"))
            fail("missing executable must fail")
        } catch (expected: com.forgekit.runtime.api.ProcessStartError) {
            assertTrue("not found" in expected.message.lowercase() || "not exist" in (expected.detail ?: "").lowercase())
        }
    }

    @Test
    fun `inspect reports only tools that really exist with real versions`() = runBlocking {
        runtime.initialize()
        val caps = runtime.inspect()
        assertEquals("forgekit.runtime/v1", caps.contractVersion)
        assertEquals(paths().termuxPrefix.toString(), caps.prefixPath)
        // every reported tool exists on disk
        for (tool in caps.tools) {
            assertTrue(Files.isExecutable(Path.of(tool.path)), "reported tool must exist: ${tool.path}")
        }
        // bash must be probed and versioned from the real binary
        val bash = caps.tools.firstOrNull { it.name == "bash" }
        assertNotNull(bash, "bash must be reported (host prefix links it)")
        assertNotNull(bash!!.version, "bash --version must produce output")
        assertTrue(bash.version!!.isNotBlank())
    }

    @Test
    fun `terminate stops a stubborn real process via sigterm-then-sigkill`() = runBlocking {
        runtime.initialize()
        val handle = runtime.execute(
            ExecutionRequest(executable = "sh", args = listOf("-c", "trap '' TERM; read x"), label = "stubborn"),
        )
        // single output consumer: collect events while terminate() runs (§53)
        val events = kotlinx.coroutines.channels.Channel<ProcessOutput>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val collector = launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                runtime.output(handle.processId).collect { events.trySend(it) }
            } finally {
                events.close()
            }
        }
        // sh traps TERM and ignores it: the runtime MUST escalate to SIGKILL within the grace
        val started = System.currentTimeMillis()
        runtime.terminate(handle.processId)
        val elapsed = System.currentTimeMillis() - started
        collector.join()
        val collected = mutableListOf<ProcessOutput>()
        while (true) {
            val e = events.tryReceive().getOrNull() ?: break
            collected += e
        }
        events.close()
        val exit = collected.filterIsInstance<ProcessOutput.Exited>().singleOrNull()
        assertNotNull(exit, "an Exited event must be observed after terminate")
        assertTrue(
            exit!!.exitStatus is ExitStatus.Signaled,
            "stubborn process must die by signal, was ${exit.exitStatus}",
        )
        assertTrue(elapsed < 30_000, "terminate must not hang (took ${elapsed}ms)")
    }

    @Test
    fun `signal delivers SIGKILL to a live process`() = runBlocking {
        runtime.initialize()
        val handle = runtime.execute(
            ExecutionRequest(executable = "sh", args = listOf("-c", "read x"), label = "signal-test"),
        )
        runtime.signal(handle.processId, SignalRequest.SIGKILL)
        val exit = runtime.output(handle.processId).toList()
            .filterIsInstance<ProcessOutput.Exited>()
            .single()
        assertEquals(9, (exit.exitStatus as ExitStatus.Signaled).signal)
    }

    @Test
    fun `writeStdin feeds an interactive session`() = runBlocking {
        runtime.initialize()
        val handle = runtime.execute(
            ExecutionRequest(
                executable = "sh",
                sessionType = ExecutionRequest.SessionType.PIPES,
                args = listOf("-c", "read line; echo GOT-\$line"),
                label = "interactive",
            ),
        )
        runtime.writeStdin(handle.processId, "ping\n".toByteArray())
        val stdout = runtime.output(handle.processId).toList()
            .filterIsInstance<ProcessOutput.Stdout>()
            .joinToString("") { String(it.bytes, Charsets.UTF_8) }
        assertTrue("GOT-ping" in stdout, "echo must round back: '$stdout'")
    }

    @Test
    fun `execute requires initialize first`() = runBlocking {
        val freshRoot = Files.createTempDirectory("forgekit-cold-runtime")
        try {
            val cold = EmbeddedTermuxRuntime(
                paths = ForgePaths(freshRoot),
                bootstrapDescriptor = com.forgekit.runtime.bootstrap.BootstrapDescriptor(
                    abi = "x86_64-host",
                    source = com.forgekit.runtime.bootstrap.BootstrapSource { fail("no bootstrap") },
                    expectedSha256 = null,
                    expectedSizeBytes = null,
                    termuxSuite = "host",
                ),
            )
            try {
                cold.execute(ExecutionRequest(executable = "sh"))
                fail("must throw before initialize")
            } catch (expected: com.forgekit.runtime.api.RuntimeUnavailableError) {
                assertTrue("NOT_INSTALLED" in expected.message)
            }
        } finally {
            freshRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `isInstalled reads the REAL dpkg database and reports presence honestly`() = runBlocking {
        runtime.initialize()
        // forgekit-host-meta exists in the REAL status db written by buildHostPrefix
        val present = runtime.isInstalled(
            com.forgekit.runtime.api.RuntimeDependency(
                com.forgekit.runtime.api.RuntimeDependency.Kind.TERMUX_PACKAGE,
                "forgekit-host-meta",
            ),
        )
        assertTrue(present, "a package present in the real dpkg database must be reported installed")
        val absent = runtime.isInstalled(
            com.forgekit.runtime.api.RuntimeDependency(
                com.forgekit.runtime.api.RuntimeDependency.Kind.TERMUX_PACKAGE,
                "forgekit-definitely-not-installed",
            ),
        )
        assertFalse(absent, "a package absent from the real dpkg database must be reported missing")
    }

    @Test
    fun `install of an already-present package streams VERIFYING and does not re-install`() = runBlocking {
        runtime.initialize()
        val dep = com.forgekit.runtime.api.RuntimeDependency(
            com.forgekit.runtime.api.RuntimeDependency.Kind.TERMUX_PACKAGE,
            "forgekit-host-meta",
        )
        val events = mutableListOf<com.forgekit.runtime.api.InstallEvent>()
        val result = runtime.installStreaming(dep) { events += it }
        assertTrue(result.installed)
        assertTrue(result.alreadyPresent, "the already-present fast path must report alreadyPresent")
        // the fast path surfaces inspect + verification, never a fetch phase
        assertEquals(
            listOf(com.forgekit.runtime.api.InstallPhase.INSPECTING, com.forgekit.runtime.api.InstallPhase.VERIFYING),
            events.filterIsInstance<com.forgekit.runtime.api.InstallEvent.Phase>().map { it.phase },
            "emissions: $events",
        )
    }

    private fun paths(): ForgePaths = ForgePaths(root)
}
