package com.forgekit.plugin.manager

import com.forgekit.core.security.PublisherKeyPair
import com.forgekit.core.security.TrustStore
import com.forgekit.plugin.api.PluginId
import com.forgekit.plugin.api.PluginStatus
import com.forgekit.plugin.api.PluginTrustLevel
import com.forgekit.plugin.installer.ForgePackageBuilder
import com.forgekit.plugin.resolver.HostRuntime
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * REAL manager tests: real signed packages, real validation, real install
 * into temp trees, real dependency planning/exec via HostRuntime.
 */
class PluginManagerTest {

    private val parser = com.forgekit.plugin.manifest.ManifestParser()

    private fun newManager(dir: Path, hostTools: Map<String, Path> = mapOf("bash" to Path.of("/bin/bash"))): PluginManager {
        val runtime = HostRuntime(dir.resolve("runtime-root"), hostTools)
        return PluginManager(
            pluginsRoot = dir.resolve("forge/plugins"),
            runtime = runtime,
            validationContext = com.forgekit.plugin.validator.ValidationContext(
                trustStore = TrustStore(),
            ),
        )
    }

    @Test
    fun `import validates and produces the review without installing`() = runBlocking {
        val dir = Files.createTempDirectory("mgr-import")
        try {
            val manager = newManager(dir)
            val archive = ForgePackageBuilder.standard().writeTo(dir.resolve("tool.forge"))
            val review = manager.import(archive)

            assertEquals("com.example.tool", review.descriptor.id.raw)
            assertEquals(PluginStatus.UNRESOLVED, review.descriptor.status)
            assertEquals(PluginTrustLevel.UNSIGNED, review.descriptor.trust)
            assertTrue(review.descriptor.packageSha256!!.length == 64)
            assertTrue(review.report.contentWarnings.any { it.code == "UNSIGNED" })
            assertTrue(review.report.requiresUserApproval)

            // nothing installed yet: tree untouched
            assertTrue(!Files.isDirectory(dir.resolve("forge/plugins/com.example.tool")))
            assertEquals(1, manager.all().size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid packages are rejected with the precise problems`() = runBlocking {
        val dir = Files.createTempDirectory("mgr-invalid")
        try {
            val manager = newManager(dir)
            val broken = ForgePackageBuilder()
                .manifest(ForgePackageBuilder.MANIFEST.replace("\"entrypoint\": \"runtime/main.py\"", "\"entrypoint\": \"runtime/absent.py\""))
                .file("ui/main.json", """{"schema":"forgekit.ui/v1"}""", 0x1A4)
                .writeTo(dir.resolve("broken.forge"))
            try {
                manager.import(broken)
                fail("entrypoint must be rejected")
            } catch (expected: com.forgekit.plugin.api.PluginError) {
                assertTrue("ENTRYPOINT_MISSING" in (expected.detail ?: ""), "detail: ${expected.detail}")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `approve installs and provisions, unresolved deps keep UNRESOLVED status`() = runBlocking {
        val dir = Files.createTempDirectory("mgr-approve")
        try {
            // host runtime WITHOUT python/pkg: deps cannot provision → UNRESOLVED
            val manager = newManager(dir, mapOf("bash" to Path.of("/bin/bash")))
            val archive = ForgePackageBuilder.standard().writeTo(dir.resolve("tool.forge"))
            manager.import(archive)

            val descriptor = manager.approveImport(PluginId.parse("com.example.tool"))
            assertEquals(PluginStatus.UNRESOLVED, descriptor.status, "python+radare2 unsatisfiable → stays UNRESOLVED")
            assertNotNull(descriptor.installedPath)
            assertTrue(Files.isRegularFile(Path.of(descriptor.installedPath!!).resolve("runtime/main.py")))

            // provision() retries resolution and stays honest
            val after = manager.provision(PluginId.parse("com.example.tool"))
            assertEquals(PluginStatus.UNRESOLVED, after.status)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `approve reaches READY when all dependencies are present`() = runBlocking {
        val dir = Files.createTempDirectory("mgr-ready")
        try {
            // EVERYTHING the plugin declares exists in the prefix: radare2 + python + pip
            val manager = newManager(
                dir,
                mapOf(
                    "bash" to Path.of("/bin/bash"),
                    "python" to Path.of("/usr/bin/python3"),
                    "radare2" to Path.of("/usr/bin/dpkg"),
                    "pip" to Path.of("/usr/bin/dpkg"),
                ),
            )
            val key = PublisherKeyPair.generate()
            val archive = ForgePackageBuilder.standard().signedBy(key, "ForgeLabs")
                .writeTo(dir.resolve("tool.forge"))
            manager.import(archive)

            val descriptor = manager.approveImport(PluginId.parse("com.example.tool"))
            assertEquals(PluginStatus.READY, descriptor.status, "all deps present → READY")
            assertTrue(Files.isRegularFile(Path.of(descriptor.installedPath!!).resolve(".forgekit-ready")))

            // disk state survives a fresh manager (registry rebuilt from disk)
            val fresh = newManager(
                dir,
                mapOf(
                    "bash" to Path.of("/bin/bash"),
                    "python" to Path.of("/usr/bin/python3"),
                    "radare2" to Path.of("/usr/bin/dpkg"),
                    "pip" to Path.of("/usr/bin/dpkg"),
                ),
            )
            assertEquals(PluginStatus.READY, fresh.descriptor(PluginId.parse("com.example.tool"))?.status)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `remove wipes the tree and clears pending imports`() = runBlocking {
        val dir = Files.createTempDirectory("mgr-remove")
        try {
            val manager = newManager(
                dir,
                mapOf(
                    "bash" to Path.of("/bin/bash"),
                    "python" to Path.of("/usr/bin/python3"),
                    "radare2" to Path.of("/usr/bin/dpkg"),
                    "pip" to Path.of("/usr/bin/dpkg"),
                ),
            )
            val archive = ForgePackageBuilder.standard().writeTo(dir.resolve("tool.forge"))
            manager.import(archive)
            val id = PluginId.parse("com.example.tool")
            manager.approveImport(id)
            assertTrue(Files.isDirectory(dir.resolve("forge/plugins/$id")))

            assertTrue(manager.remove(id))
            assertTrue(!Files.isDirectory(dir.resolve("forge/plugins/$id")))
            assertEquals(null, manager.descriptor(id))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `disable and enable keep status truthful`() = runBlocking {
        val dir = Files.createTempDirectory("mgr-disable")
        try {
            val tools = mapOf(
                "bash" to Path.of("/bin/bash"),
                "python" to Path.of("/usr/bin/python3"),
                "radare2" to Path.of("/usr/bin/dpkg"),
                "pip" to Path.of("/usr/bin/dpkg"),
            )
            val manager = newManager(dir, tools)
            val archive = ForgePackageBuilder.standard().writeTo(dir.resolve("tool.forge"))
            manager.import(archive)
            val id = PluginId.parse("com.example.tool")
            manager.approveImport(id)

            val disabled = manager.disable(id)
            assertEquals(PluginStatus.DISABLED, disabled.status)
            val enabled = manager.enable(id)
            assertEquals(PluginStatus.READY, enabled.status, "deps still present → READY on enable")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
