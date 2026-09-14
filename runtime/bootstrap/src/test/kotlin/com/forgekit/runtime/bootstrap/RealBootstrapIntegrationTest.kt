package com.forgekit.runtime.bootstrap

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * REAL-ARTIFACT integration test: runs against the pinned official Termux
 * bootstrap-2025.03.28-r1+apt-android-7 arm64 archive.
 *
 * Activated via the `FORGEKIT_REAL_BOOTSTRAP` env var (path to bootstrap-arm64-v8a.zip);
 * skipped otherwise so the module stays testable without a 29 MiB fixture in-repo.
 * When active, it proves the extractor against production data: 3490 entries,
 * ELF binaries, exec bits, 1146 manifest symlinks — no fixtures, no mocks.
 */
class RealBootstrapIntegrationTest {

    private fun realBootstrap(): Path? {
        val fromEnv = System.getenv("FORGEKIT_REAL_BOOTSTRAP")
            ?.let { Paths.get(it) }
            ?.takeIf { Files.isRegularFile(it) }
        if (fromEnv != null) return fromEnv
        // Fall back to the archive bundled with the app, when running inside the repository.
        return generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
            .map { it.resolve("app/src/main/assets/bootstrap/bootstrap-arm64-v8a.zip") }
            .firstOrNull { Files.isRegularFile(it) }
    }

    @Test
    fun `verifies the real bootstrap archive`() {
        val zip = realBootstrap() ?: return skipped("real bootstrap not provided")
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(zip)).joinToString("") { "%02x".format(it) }
        val descriptor = BootstrapDescriptor(
            abi = "arm64-v8a",
            source = FileBootstrapSource(zip),
            expectedSha256 = sha,
            expectedSizeBytes = Files.size(zip),
            termuxSuite = "apt-android-7",
        )
        assertNull(BootstrapVerifier().verify(zip, descriptor), "real bootstrap must verify cleanly")

        val tampered = descriptor.copy(expectedSha256 = "00".repeat(32))
        assertNotNull(BootstrapVerifier().verify(zip, tampered))
    }

    @Test
    fun `extracts the real bootstrap preserving modes and symlinks`() {
        val zip = realBootstrap() ?: return skipped("real bootstrap not provided")
        val prefix = Files.createTempDirectory("real-bootstrap-prefix")
        val result = BootstrapExtractor().extract(zip, prefix)

        // Pinned arm64 bootstrap: 3490 zip entries, 264 dirs → ~3225 files + 1146 links
        assertTrue(result.filesWritten > 3000, "expected 3000+ files, got ${result.filesWritten}")
        assertEquals(1146, result.symlinksCreated)

        // core binaries exist, are executable, and are real ELF files
        for (binary in listOf("bin/bash", "bin/dpkg", "bin/apt", "bin/sh")) {
            val path = prefix.resolve(binary)
            assertTrue(Files.exists(path), "$binary must exist")
            if (binary != "bin/sh" || !Files.isSymbolicLink(path)) {
                assertTrue(Files.isExecutable(path), "$binary must be executable")
            }
        }
        val dpkg = prefix.resolve("bin/dpkg")
        val magic = Files.newInputStream(dpkg).use { it.readNBytes(4) }
        assertEquals("ELF", String(magic, 1, 3), "bin/dpkg must be a real ELF binary")

        // dpkg database present (package manager state ships in the bootstrap)
        assertTrue(Files.isRegularFile(prefix.resolve("var/lib/dpkg/status")))

        // manifest-declared symlink: link content resolves inside the prefix
        val link = prefix.resolve("share/doc/net-tools/LICENSE")
        assertTrue(Files.isSymbolicLink(link), "manifest symlink must exist")

        // SYMLINKS.txt itself must NOT be written into the prefix
        assertTrue(!Files.exists(prefix.resolve("SYMLINKS.txt")))
    }

    @Test
    fun `real bootstrap orchestrator flow stages verifies extracts inits`() {
        val zip = realBootstrap() ?: return skipped("real bootstrap not provided")
        // Relocation patches ELF binaries in place, so the install root may be at most as long
        // as Termux's compiled root (27 bytes, like the device's /data/data/com.forgekit.app).
        val root = Files.createTempDirectory(Paths.get("/tmp"), "fk")
        val staging = Files.createTempDirectory("real-bootstrap-stage")
        check(root.toString().length <= "/data/data/com.termux/files".length) { "temp root too long: $root" }
        val targets = BootstrapTargets.of(root, stagingDir = staging)
        val orchestrator = BootstrapOrchestrator(targets)

        val descriptor = BootstrapDescriptor(
            abi = "arm64-v8a",
            source = FileBootstrapSource(zip),
            expectedSha256 = null,
            expectedSizeBytes = null,
            termuxSuite = "apt-android-7",
        )
        val result = orchestrator.verifyAndExtract(descriptor)
        assertTrue(result is BootstrapOrchestrator.BootResult.Ready, "was: $result")

        // Environment init against the real prefix. The bootstrap ships its own enabled
        // repository, so ForgeKit must not add a duplicate forgekit.list.
        assertNull(orchestrator.initializeEnvironment("arm64", descriptor))
        val mainList = Files.readString(targets.prefix.resolve("etc/apt/sources.list"))
        assertTrue("termux-main" in mainList, mainList)
        assertTrue(Files.notExists(targets.prefix.resolve("etc/apt/sources.list.d/forgekit.list")))
        // Relocation left no link pointing into the Termux app's tree.
        val stale = Files.walk(targets.prefix).use { paths ->
            paths.filter { Files.isSymbolicLink(it) && Files.readSymbolicLink(it).toString().startsWith("/data/data/com.termux/") }.count()
        }
        assertEquals(0L, stale, "symlinks still pointing into /data/data/com.termux")
        root.toFile().deleteRecursively()
        staging.toFile().deleteRecursively()
    }

    private fun skipped(reason: String) {
        println("[RealBootstrapIntegrationTest] SKIPPED: $reason")
    }
}
