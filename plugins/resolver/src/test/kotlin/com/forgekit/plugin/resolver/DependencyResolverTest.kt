package com.forgekit.plugin.resolver

import com.forgekit.plugin.manifest.ManifestParser
import com.forgekit.runtime.api.DependencyInstallResult
import com.forgekit.runtime.api.ForgeRuntime
import com.forgekit.runtime.api.InstallEvent
import com.forgekit.runtime.api.RuntimeDependency
import com.forgekit.runtime.api.StreamingInstaller
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * REAL resolver tests: real on-disk capabilities (host binaries symlinked into
 * a Termux-shaped prefix), real exec during install attempts.
 */
class DependencyResolverTest {

    private val parser = ManifestParser()

    private val manifest = parser.parse(
        """
        {
          "schema": "forgekit.plugin/v1",
          "id": "com.example.tool",
          "name": "Example Tool",
          "version": "1.0.0",
          "runtime": { "type": "python", "version": ">=3.6" },
          "entrypoint": "runtime/main.py",
          "dependencies": {
            "termux": ["radare2"],
            "python": [ {"name": "r2pipe", "version": "==1.9.8"} ]
          },
          "permissions": ["network"]
        }
        """.trimIndent().toByteArray(),
    )

    @Test
    fun `plan derives the full provisioning set from real inspection`() = runBlocking {
        val dir = Files.createTempDirectory("resolver-plan")
        try {
            // prefix has bash+sh only: python, radare2, pip are all missing
            val runtime = HostRuntime(dir, mapOf("bash" to Path.of("/bin/bash"), "sh" to Path.of("/bin/sh")))
            val plans = DependencyResolver(runtime).plan(manifest)

            val byKey = plans.associateBy { "${it.manager}:${it.name}" }
            assertEquals(3, plans.size, "plans: $plans")
            assertTrue(byKey.getValue("TERMUX:radare2").manager == "TERMUX")
            assertTrue(!byKey.getValue("TERMUX:radare2").alreadyPresent)
            assertTrue(byKey.containsKey("PIP:r2pipe"))
            assertEquals("==1.9.8", byKey.getValue("PIP:r2pipe").versionRequirement)
            // python runtime itself is planned as a TERMUX dependency (absent)
            assertTrue(byKey.containsKey("TERMUX:python"), "language runtime must be planned: $byKey")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `plan skips already-present tools and the language runtime`() = runBlocking {
        val dir = Files.createTempDirectory("resolver-present")
        try {
            val runtime = HostRuntime(
                dir,
                mapOf(
                    "bash" to Path.of("/bin/bash"),
                    "python" to Path.of("/usr/bin/python3"),
                    "radare2" to Path.of("/usr/bin/dpkg"), // any real executable stands in for presence
                    "pip" to Path.of("/usr/bin/dpkg"),
                ),
            )
            val plans = DependencyResolver(runtime).plan(manifest)
            val byKey = plans.associateBy { "${it.manager}:${it.name}" }
            assertTrue(byKey.getValue("TERMUX:radare2").alreadyPresent, "radare2 present on disk")
            assertTrue(byKey.getValue("PIP:r2pipe").alreadyPresent, "pip present → python deps considered satisfiable")
            assertTrue(!byKey.containsKey("TERMUX:python"), "python runtime already present")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resolve reports real install results including honest failures`() = runBlocking {
        val dir = Files.createTempDirectory("resolver-resolve")
        try {
            // no pkg, no pip: the runtime honestly reports unavailability
            val runtime = HostRuntime(dir, mapOf("bash" to Path.of("/bin/bash")))
            val report = DependencyResolver(runtime).resolve(manifest)

            assertEquals("com.example.tool", report.pluginId)
            assertTrue(!report.verified, "unresolvable in this environment must be reported")
            assertTrue(report.missingAfterResolution.isNotEmpty())
            val first = report.missingAfterResolution.first()
            assertEquals(RuntimeDependency.Kind.TERMUX_PACKAGE, first.dependency.kind)
            assertTrue("not available" in first.detail, "detail: ${first.detail}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resolve succeeds when the package manager is really present and runnable`() = runBlocking {
        val dir = Files.createTempDirectory("resolver-ok")
        try {
            // pkg → symlink to a REAL runnable binary (dpkg answers --version, exit 0)
            val runtime = HostRuntime(
                dir,
                mapOf(
                    "bash" to Path.of("/bin/bash"),
                    "pkg" to Path.of("/usr/bin/dpkg"),
                ),
            )
            val report = DependencyResolver(runtime).resolve(manifest)
            // every TERMUX install ran the real binary and got exit 0
            val termuxResults = report.installResults.filter { it.dependency.kind == RuntimeDependency.Kind.TERMUX_PACKAGE }
            assertTrue(termuxResults.isNotEmpty())
            termuxResults.forEach { result ->
                assertTrue(result.installed, "real exec must succeed: ${result.detail}")
            }
            // pip deps fail honestly (no pip in this prefix)
            assertTrue(report.installResults.any { !it.installed && "not available" in it.detail })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resolveStreaming emits Planned then Resolving and Completed per missing dep in plan order`() = runBlocking {
        val dir = Files.createTempDirectory("resolver-stream")
        try {
            // bash only: radare2, python runtime and r2pipe are all missing
            val runtime = HostRuntime(dir, mapOf("bash" to Path.of("/bin/bash")))
            val events = mutableListOf<ResolutionEvent>()
            val report = DependencyResolver(runtime).resolveStreaming(manifest) { events += it }

            val planned = events.filterIsInstance<ResolutionEvent.Planned>().single()
            assertEquals(3, planned.plans.size, "plans: ${planned.plans}")
            val resolving = events.filterIsInstance<ResolutionEvent.Resolving>().map { it.dependency.name }
            val completed = events.filterIsInstance<ResolutionEvent.Completed>().map { it.dependency.name }
            // plan order: termux (radare2), termux runtime (python), pip (r2pipe)
            assertEquals(listOf("radare2", "python", "r2pipe"), resolving)
            assertEquals(listOf("radare2", "python", "r2pipe"), completed)
            assertTrue(events.first() is ResolutionEvent.Planned, "Planned must be the first event")
            assertTrue(!report.verified, "nothing can install in a bash-only prefix")
            assertEquals(resolving.size, completed.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `already-present deps are reported without any Resolving event`() = runBlocking {
        val dir = Files.createTempDirectory("resolver-present-install")
        try {
            val binaryManifest = parser.parse(
                """
                {
                  "schema": "forgekit.plugin/v1",
                  "id": "com.example.patch",
                  "name": "Patch Tool",
                  "version": "1.0.0",
                  "runtime": { "type": "binary" },
                  "entrypoint": "runtime/tool",
                  "dependencies": { "termux": ["radare2"], "python": [], "node": [] }
                }
                """.trimIndent().toByteArray(),
            )
            val runtime = PresentRuntime(HostRuntime(dir, mapOf("bash" to Path.of("/bin/bash"))))
            val events = mutableListOf<ResolutionEvent>()
            val report = DependencyResolver(runtime).resolveStreaming(binaryManifest) { events += it }

            val planned = events.filterIsInstance<ResolutionEvent.Planned>().single()
            assertTrue(planned.plans.all { it.alreadyPresent }, "all present in the runtime db: $planned")
            assertTrue(events.none { it is ResolutionEvent.Resolving }, "present deps must not be re-installed")
            assertEquals(1, events.filterIsInstance<ResolutionEvent.Completed>().size)
            assertTrue(report.verified)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a plugin with no dependency work is already satisfied`() = runBlocking {
        val dir = Files.createTempDirectory("resolver-empty")
        try {
            val manifest = parser.parse(
                """
                {
                  "schema": "forgekit.plugin/v1",
                  "id": "com.example.standalone",
                  "name": "Standalone",
                  "version": "1.0.0",
                  "runtime": { "type": "binary" },
                  "entrypoint": "runtime/tool"
                }
                """.trimIndent().toByteArray(),
            )
            val events = mutableListOf<ResolutionEvent>()
            val report = DependencyResolver(HostRuntime(dir, emptyMap())).resolveStreaming(manifest) {
                events += it
            }

            assertTrue(report.plans.isEmpty())
            assertTrue(report.installResults.isEmpty())
            assertTrue(report.verified)
            assertTrue(report.satisfied, "an empty dependency set requires no provisioning")
            assertTrue(events.single() is ResolutionEvent.Planned)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * A runtime that reports every dependency as already present — simulates a
     * REAL StreamingInstaller (the EmbeddedTermuxRuntime path) so the resolver
     * takes the package-database presence branch instead of the tool heuristic.
     */
    private class PresentRuntime(private val base: HostRuntime) : ForgeRuntime by base, StreamingInstaller {
        override suspend fun isInstalled(dependency: RuntimeDependency): Boolean = true

        override suspend fun installStreaming(
            dependency: RuntimeDependency,
            events: FlowCollector<InstallEvent>,
        ): DependencyInstallResult =
            DependencyInstallResult(dependency, installed = true, alreadyPresent = true, detail = "present")
    }
}
