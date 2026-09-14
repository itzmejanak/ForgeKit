package com.forgekit.plugin.installer

import com.forgekit.plugin.api.PluginId
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** REAL install/remove/rollback tests against temp plugin trees. */
class PackageInstallerTest {

    private val parser = com.forgekit.plugin.manifest.ManifestParser()

    @Test
    fun `installs a package preserves exec bits and freezes the manifest`() {
        val dir = Files.createTempDirectory("forge-install")
        try {
            val installer = PackageInstaller(dir)
            val archive = ForgePackageBuilder.standard()
                .writeTo(dir.parent.resolve(dir.fileName.toString() + "-src/tool.forge"))
            val pkg = ForgePackage.open(archive, parser)

            val result = installer.install(pkg)

            assertEquals("com.example.tool", result.pluginId.raw)
            assertEquals("1.0.0", result.version)
            assertFalse(result.replacedPrevious)
            assertTrue(result.filesInstalled >= 7, "all package files must be installed: ${result.filesInstalled}")

            val layout = installer.layoutOf(PluginId.parse("com.example.tool"))
            assertEquals(
                "print('forgekit plugin')\n",
                layout.runtimeDir.resolve("main.py").readText(),
            )
            assertTrue(Files.isRegularFile(layout.uiDir.resolve("main.json")))
            assertTrue(Files.isDirectory(layout.dataDir))
            assertTrue(Files.isDirectory(layout.cacheDir))

            // exec bit survived extraction (runtime/exec.sh was 0x1ED)
            val execPerms = Files.getPosixFilePermissions(layout.runtimeDir.resolve("exec.sh"))
            assertTrue(PosixFilePermission.OWNER_EXECUTE in execPerms, "exec.sh must stay executable: $execPerms")

            // frozen manifest at tree root
            assertEquals(pkg.manifest.id, parser.parse(Files.readAllBytes(layout.manifestFile)).id)
        } finally {
            dir.toFile().deleteRecursively()
            dir.parent.resolve(dir.fileName.toString() + "-src").toFile().deleteRecursively()
        }
    }

    @Test
    fun `reinstall replaces atomically and keeps the tree consistent`() {
        val dir = Files.createTempDirectory("forge-reinstall")
        try {
            val installer = PackageInstaller(dir)
            val src = dir.resolve("src")
            val v1 = ForgePackageBuilder.standard()
                .writeTo(src.resolve("v1.forge"))
            installer.install(ForgePackage.open(v1, parser))

            // v2 with different content
            val v2Manifest = ForgePackageBuilder.MANIFEST.replace("\"1.0.0\"", "\"1.1.0\"")
            val v2 = ForgePackageBuilder()
                .manifest(v2Manifest)
                .file("runtime/main.py", "print('v2')\n", 0x1A4)
                .file("runtime/exec.sh", "#!/usr/bin/env bash\necho v2\n", 0x1ED)
                .writeTo(src.resolve("v2.forge"))
            val result = installer.install(ForgePackage.open(v2, parser))

            assertTrue(result.replacedPrevious)
            assertEquals("1.1.0", result.version)
            val layout = installer.layoutOf(PluginId.parse("com.example.tool"))
            assertEquals("print('v2')\n", layout.runtimeDir.resolve("main.py").readText())
            // no staging leftovers
            val leftovers = Files.list(dir).use { s ->
                s.filter { it.fileName.toString().startsWith(".staging") || it.fileName.toString().startsWith(".old") }
                    .count()
            }
            assertEquals(0, leftovers, "staging/backup dirs must be cleaned up")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `markReady writes the marker and remove wipes the tree`() {
        val dir = Files.createTempDirectory("forge-lifecycle")
        try {
            val id = PluginId.parse("com.example.tool")
            val installer = PackageInstaller(dir)
            val archive = ForgePackageBuilder.standard().writeTo(dir.resolve("tool.forge"))
            installer.install(ForgePackage.open(archive, parser))

            assertFalse(installer.layoutOf(id).isReady())
            installer.markReady(id)
            assertTrue(installer.layoutOf(id).isReady(), "ready marker must exist after initialize")

            assertTrue(installer.remove(id))
            assertFalse(installer.isInstalled(id))
            assertTrue(installer.remove(id).not(), "remove is idempotent-false for absent plugin")
            try {
                installer.layoutOf(id)
                fail("layoutOf must throw for removed plugin")
            } catch (expected: com.forgekit.plugin.api.PluginError) {
                assertTrue("not installed" in expected.message)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `layoutOf throws a precise error for unknown plugins`() {
        val dir = Files.createTempDirectory("forge-unknown")
        try {
            try {
                PackageInstaller(dir).layoutOf(PluginId.parse("com.absent.plugin"))
                fail("must throw")
            } catch (expected: com.forgekit.plugin.api.PluginError) {
                assertTrue("not installed" in expected.message)
                assertTrue(expected.detail!!.contains("com.absent.plugin"))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
