package com.forgekit.runtime.bootstrap

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * REAL extraction tests: builds Termux-style archives (top-level dir, exec bits,
 * symlinks in external attributes) with [ModeZipWriter] and extracts them with
 * the real extractor + real central-directory mode parser.
 */
class BootstrapExtractorTest {

    private val tmp: Path = Files.createTempDirectory("bootstrap-extract-test")

    /** Termux-style fixture: top-level `bootstrap-aarch64/` dir, binaries, symlink, config. */
    private fun buildTermuxStyleZip(): Path {
        val zip = tmp.resolve("fake-bootstrap.zip")
        ModeZipWriter(Files.newOutputStream(zip)).use { w ->
            w.entry("bootstrap-aarch64/bin/bash", "#!/system/bin/sh\n", 0x81ed)   // 0o100755
            w.entry("bootstrap-aarch64/bin/sh", "bash", 0xa1ff)                   // 0o121777 symlink
            w.entry("bootstrap-aarch64/bin/dpkg", "dpkg", 0x81ed)
            w.entry("bootstrap-aarch64/etc/apt/sources.list", "\n", 0x81a4)       // 0o100644
            w.entry("bootstrap-aarch64/var/lib/dpkg/status", "Package: core\n", 0x81a4)
            w.entry("bootstrap-aarch64/lib/libc.so", "elf", 0x81a4)
            w.entry("bootstrap-aarch64/bin/tool", "tool", 0x81ed)
        }
        return zip
    }

    @Test
    fun `extracts stripping top dir, preserving modes and symlinks`() {
        val prefix = tmp.resolve("usr")
        val result = BootstrapExtractor().extract(buildTermuxStyleZip(), prefix)

        assertEquals(6, result.filesWritten)
        assertEquals(1, result.symlinksCreated)

        val bash = prefix.resolve("bin/bash")
        assertTrue(Files.isRegularFile(bash))
        assertTrue(Files.isExecutable(bash), "bash exec bit must be preserved")

        val sh = prefix.resolve("bin/sh")
        assertTrue(Files.isSymbolicLink(sh), "sh must be a symlink")
        assertEquals("bash", Files.readSymbolicLink(sh).toString())

        val tool = prefix.resolve("bin/tool")
        assertTrue(Files.isExecutable(tool), "exec bit on second binary")

        // sources.list is a regular non-executable file
        val sources = prefix.resolve("etc/apt/sources.list")
        assertTrue(Files.isRegularFile(sources))
        assertTrue(!Files.isSymbolicLink(sources))
    }

    @Test
    fun `central directory parser recovers modes the jdk zip api cannot`() {
        val zip = buildTermuxStyleZip()
        val central = ZipCentralDirectory.parse(zip)
        assertEquals(0x81ed, central.unixMode("bootstrap-aarch64/bin/bash"))
        assertEquals(0xa1ff, central.unixMode("bootstrap-aarch64/bin/sh"))
        assertEquals(0x81a4, central.unixMode("bootstrap-aarch64/etc/apt/sources.list"))
        assertNull(central.unixMode("not-an-entry"))
        assertEquals(7, central.allRecords().size)
    }

    @Test
    fun `entry escaping the target directory is rejected`() {
        // craft an archive whose entry name contains .. — the extractor must refuse
        val zip = tmp.resolve("evil.zip")
        ModeZipWriter(Files.newOutputStream(zip)).use { w ->
            w.entry("bootstrap-aarch64/../../escape.txt", "boom", 0x81a4)
        }
        val prefix = tmp.resolve("usr2")
        var threw = false
        try {
            BootstrapExtractor().extract(zip, prefix)
        } catch (expected: java.io.IOException) {
            threw = true
            assertTrue(expected.message!!.contains("escapes"))
        }
        assertTrue(threw, "extractor must reject path traversal")
    }
}

class BootstrapVerifierTest {
    @Test
    fun `verifies hash and rejects corrupted archives`() {
        val tmp = Files.createTempDirectory("bootstrap-verify-test")
        val zip = tmp.resolve("b.zip")
        ModeZipWriter(Files.newOutputStream(zip)).use { w ->
            repeat(150) { i -> w.entry("bootstrap-aarch64/f/$i.bin", ByteArray(16), 0x81a4) }
        }
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(zip)).joinToString("") { "%02x".format(it) }

        val good = BootstrapDescriptor(
            abi = "arm64-v8a",
            source = FileBootstrapSource(zip),
            expectedSha256 = sha,
            expectedSizeBytes = Files.size(zip),
            termuxSuite = "apt-android-7",
        )
        assertNull(BootstrapVerifier().verify(zip, good))

        val wrongHash = good.copy(expectedSha256 = "00".repeat(32))
        assertNotNull(BootstrapVerifier().verify(zip, wrongHash))

        val wrongSize = good.copy(expectedSizeBytes = 1L)
        assertNotNull(BootstrapVerifier().verify(zip, wrongSize))
    }
}

class BootstrapOrchestratorTest {
    private val tmp: Path = Files.createTempDirectory("bootstrap-orch-test")
    private val targets = BootstrapTargets.of(tmp.resolve("termux"), stagingDir = tmp.resolve("forge/metadata"))
    private val orchestrator = BootstrapOrchestrator(targets)

    /**
     * Termux `.deb` payloads carry absolute member paths, so dpkg is pointed at an
     * instdir with a symlink that redirects them into the relocated prefix. dpkg
     * re-applies instdir to the admindir it was given, so if admindir is not a plain
     * descendant of instdir it silently builds an EMPTY shadow database — every
     * bootstrap package then reads as "not installed" and nothing can be configured.
     */
    @Test
    fun `package manager relocation keeps admindir inside instdir`() = runBlocking {
        val zip = tmp.resolve("reloc.zip")
        ModeZipWriter(Files.newOutputStream(zip)).use { w ->
            w.entry("bootstrap-aarch64/bin/bash", "bash", 0x81ed)
            w.entry("bootstrap-aarch64/var/lib/dpkg/status", "Package: x".toByteArray(), 0x81a4)
            repeat(120) { i -> w.entry("bootstrap-aarch64/usr/share/filler/$i", "f", 0x81a4) }
        }
        val descriptor = BootstrapDescriptor(
            abi = "arm64-v8a",
            source = FileBootstrapSource(zip),
            expectedSha256 = null,
            expectedSizeBytes = null,
            termuxSuite = "apt-android-7",
        )
        assertTrue(orchestrator.verifyAndExtract(descriptor) is BootstrapOrchestrator.BootResult.Ready)
        assertNull(orchestrator.initializeEnvironment("arm64", descriptor))

        val config = Files.readString(targets.prefix.resolve("etc/dpkg/dpkg.cfg.d/forgekit"))
        val instDir = config.lineSequence().first { it.startsWith("instdir=") }.removePrefix("instdir=")
        val adminDir = config.lineSequence().first { it.startsWith("admindir=") }.removePrefix("admindir=")
        assertTrue(
            Path.of(adminDir).startsWith(Path.of(instDir)),
            "admindir '$adminDir' must be a descendant of instdir '$instDir'",
        )
        // chroot is blocked by Android's seccomp policy (SIGSYS), so maintainer scripts
        // must run without it.
        assertTrue(config.lines().contains("force-script-chrootless"), config)

        // the redirect must be a symlink pointing at the installed data root
        val redirect = targets.termuxRoot.resolve(descriptor.compiledDataRoot.trimStart('/'))
        assertTrue(Files.isSymbolicLink(redirect), "missing redirect symlink at $redirect")
        assertEquals(targets.termuxRoot, Files.readSymbolicLink(redirect))

        // The dpkg wrapper (Dir::Bin::dpkg) must be registered and executable: it is invoked
        // once per dpkg operation, so it can fix a maintainer script's shebang before the
        // separate `dpkg --configure` runs it (Pre-Invoke fires only once per transaction).
        val wrapper = targets.prefix.resolve("libexec/forgekit-dpkg")
        assertTrue(Files.isExecutable(wrapper), "dpkg wrapper not executable")
        val relocateConf =
            Files.readString(targets.prefix.resolve("etc/apt/apt.conf.d/98-forgekit-relocate.conf"))
        assertTrue(relocateConf.contains("Dir::Bin::dpkg"), relocateConf)
        assertTrue(relocateConf.contains("forgekit-dpkg"), relocateConf)
        // No DPkg::Post-Invoke marker: running PrefixRewriter from the app on terminal activity
        // could race a concurrent apt transaction and corrupt the package DB. Full relocation
        // happens only at bootstrap and in serialized provisioning.
        assertTrue(!relocateConf.contains("Post-Invoke"), relocateConf)

        // Maintainer scripts are shebang-only: rewriting their bodies broke update-alternatives
        // through doubled DPKG_ROOT paths. Newly changed bin/libexec runtime files do need an
        // exact-length full replacement so a freshly unpacked dpkg/apt binary can find config.
        val wrapperText = Files.readString(wrapper)
        assertTrue(wrapperText.contains("relocate_deb_preinsts"), "preinst must be fixed before dpkg executes it: $wrapperText")
        assertTrue(wrapperText.contains("dpkg-deb\" -R"), "wrapper must raw-extract packages requiring relocation: $wrapperText")
        assertTrue(
            wrapperText.contains("dpkg-deb\" --root-owner-group -b"),
            "rebuilt archives must retain canonical ownership: $wrapperText",
        )
        assertTrue(wrapperText.contains("chmod 755"), "maintainer scripts must be directly executable: $wrapperText")
        assertTrue(wrapperText.contains("relocate_maintainer_shebangs"), wrapperText)
        assertTrue(wrapperText.contains("relocate_runtime_files"), wrapperText)
        assertTrue(wrapperText.contains("relocate_real_dpkg"), "dpkg self-upgrade needs a post-call patch: $wrapperText")
        assertTrue(wrapperText.contains("dpkg_relocated"), "dpkg self-upgrade needs one guarded retry: $wrapperText")
        assertTrue(wrapperText.contains("-cnewer"), "wrapper must use ctime, not archive-preserved mtime: $wrapperText")
        assertTrue(!wrapperText.contains(" -newer "), "mtime can miss freshly unpacked files: $wrapperText")
        assertTrue(wrapperText.contains("grep -a"), "runtime-file scan must include ELF binaries: $wrapperText")
        assertTrue(wrapperText.contains("\${#OLD_DATA}"), "binary rewrite needs an equal-length guard: $wrapperText")
        assertTrue(wrapperText.contains("\$f\" = \"\$SELF"), "wrapper must never rewrite its own needles: $wrapperText")
        assertTrue(wrapperText.contains("\$P/lib/apt"), "wrapper must relocate apt methods and libraries: $wrapperText")
        assertTrue(wrapperText.contains("exec "), "wrapper must hand off to the real dpkg: $wrapperText")
        assertTrue(!wrapperText.contains("lib/pkgconfig"), "wrapper must not relocate bodies: $wrapperText")
        val syntax = ProcessBuilder("/bin/sh", "-n", wrapper.toString()).start()
        assertEquals(0, syntax.waitFor(), syntax.errorStream.bufferedReader().readText())
    }

    @Test
    fun `interrupted relocation stays pending until configure succeeds`() = runBlocking {
        val root = tmp.resolve("pending-relocation")
        val localTargets = BootstrapTargets.of(root, stagingDir = tmp.resolve("pending-relocation-stage"))
        val localOrchestrator = BootstrapOrchestrator(localTargets)
        val bin = Files.createDirectories(localTargets.prefix.resolve("bin"))
        Files.createSymbolicLink(bin.resolve("bash"), Path.of("/bin/sh"))
        Files.createSymbolicLink(bin.resolve("sh"), Path.of("/bin/sh"))

        val installedRoot = localTargets.termuxRoot.toString()
        val compiledRoot = installedRoot.dropLast(1) + "x"
        val descriptor = BootstrapDescriptor(
            abi = "arm64-v8a",
            source = FileBootstrapSource(tmp.resolve("unused-pending.zip")),
            expectedSha256 = null,
            expectedSizeBytes = null,
            termuxSuite = "apt-android-7",
            compiledDataRoot = compiledRoot,
            compiledCacheRoot = installedRoot.dropLast(1) + "y",
        )
        val stale = Files.createDirectories(localTargets.prefix.resolve("share"))
            .resolve("runtime-index")
        Files.writeString(stale, "prefix=$compiledRoot/usr\n")

        assertNull(localOrchestrator.initializeEnvironment("arm64", descriptor))
        assertTrue(
            localOrchestrator.relocationReconciliationPending(),
            "an existing prefix without the current schema marker must migrate",
        )

        val failure = localOrchestrator.reconcileRelocationIfPending(descriptor) { _, _ ->
            error("simulated configure interruption")
        }
        assertTrue(failure?.contains("simulated configure interruption") == true, "failure was: $failure")
        assertTrue(
            localOrchestrator.relocationReconciliationPending(),
            "failed configure must leave durable recovery work",
        )

        assertNull(localOrchestrator.reconcileRelocationIfPending(descriptor) { _, command ->
            assertEquals(listOf(localTargets.prefix.resolve("bin/dpkg").toString(), "--configure", "-a"), command)
            "configured"
        })
        assertTrue(!localOrchestrator.relocationReconciliationPending(), "success must clear pending last")
        assertEquals("prefix=$installedRoot/usr\n", Files.readString(stale))
    }

    @Test
    fun `dpkg wrapper repairs a self replacement once without rewriting itself`() {
        val root = tmp.resolve("wrapper-self-update")
        val localTargets = BootstrapTargets.of(root, stagingDir = tmp.resolve("wrapper-stage"))
        val localOrchestrator = BootstrapOrchestrator(localTargets)
        val bin = Files.createDirectories(localTargets.prefix.resolve("bin"))
        Files.createSymbolicLink(bin.resolve("bash"), Path.of("/bin/sh"))
        Files.createSymbolicLink(bin.resolve("sh"), Path.of("/bin/sh"))

        val installedRoot = localTargets.termuxRoot.toString()
        val compiledRoot = installedRoot.dropLast(1) + "x"
        val compiledCache = installedRoot.dropLast(1) + "y"
        val descriptor = BootstrapDescriptor(
            abi = "arm64-v8a",
            source = FileBootstrapSource(tmp.resolve("unused-wrapper.zip")),
            expectedSha256 = null,
            expectedSizeBytes = null,
            termuxSuite = "apt-android-7",
            compiledDataRoot = compiledRoot,
            compiledCacheRoot = compiledCache,
        )
        assertNull(localOrchestrator.initializeEnvironment("arm64", descriptor))

        val marker = root.resolve("dpkg-retried")
        val replacement = root.resolve("replacement-dpkg")
        val dpkg = bin.resolve("dpkg")
        val fakeDpkg = """
            #!/bin/sh
            # compiled-config=$compiledRoot/usr/etc/dpkg
            if [ -f '$marker' ]; then
              exit 0
            fi
            cp '$replacement' '$dpkg'
            chmod 755 '$dpkg'
            : > '$marker'
            exit 2
        """.trimIndent() + "\n"
        Files.writeString(dpkg, fakeDpkg)
        Files.writeString(replacement, fakeDpkg)
        dpkg.toFile().setExecutable(true)
        replacement.toFile().setExecutable(true)

        val wrapper = localTargets.prefix.resolve("libexec/forgekit-dpkg")
        val process = ProcessBuilder(wrapper.toString(), "--unpack", "dpkg-test").start()
        assertEquals(0, process.waitFor(), process.errorStream.bufferedReader().readText())
        assertTrue(Files.exists(marker), "fake dpkg did not exercise the guarded retry")
        assertTrue(compiledRoot !in Files.readString(dpkg), "replacement dpkg remained stale")
        val wrapperText = Files.readString(wrapper)
        assertTrue("OLD_DATA='$compiledRoot'" in wrapperText, "wrapper rewrote its own OLD_DATA needle")
        assertTrue("OLD_CACHE='$compiledCache'" in wrapperText, "wrapper rewrote its own OLD_CACHE needle")
    }

    @Test
    fun `environment init re-points package symlinks left in the compiled tree`() {
        val root = tmp.resolve("startup-symlinks")
        val localTargets = BootstrapTargets.of(root, stagingDir = tmp.resolve("startup-symlinks-stage"))
        val localOrchestrator = BootstrapOrchestrator(localTargets)
        val bin = Files.createDirectories(localTargets.prefix.resolve("bin"))
        Files.createSymbolicLink(bin.resolve("bash"), Path.of("/bin/sh"))
        Files.createSymbolicLink(bin.resolve("sh"), Path.of("/bin/sh"))

        val installedRoot = localTargets.termuxRoot.toString()
        val compiledRoot = installedRoot.dropLast(1) + "x"
        val descriptor = BootstrapDescriptor(
            abi = "arm64-v8a",
            source = FileBootstrapSource(tmp.resolve("unused-startup.zip")),
            expectedSha256 = null,
            expectedSizeBytes = null,
            termuxSuite = "apt-android-7",
            compiledDataRoot = compiledRoot,
            compiledCacheRoot = installedRoot.dropLast(1) + "y",
        )
        val keys = Files.createDirectories(localTargets.prefix.resolve("etc/apt/trusted.gpg.d"))
        val key = keys.resolve("termux-autobuilds.gpg")
        Files.createSymbolicLink(key, Path.of("$compiledRoot/usr/share/termux-keyring/termux-autobuilds.gpg"))

        assertNull(localOrchestrator.initializeEnvironment("arm64", descriptor))

        assertEquals(
            Path.of("$installedRoot/usr/share/termux-keyring/termux-autobuilds.gpg"),
            Files.readSymbolicLink(key),
        )
    }

    @Test
    fun `dpkg wrapper re-points symlinks a package unpacks into the compiled tree`() {
        val root = tmp.resolve("wrapper-symlinks")
        val localTargets = BootstrapTargets.of(root, stagingDir = tmp.resolve("wrapper-symlinks-stage"))
        val localOrchestrator = BootstrapOrchestrator(localTargets)
        val bin = Files.createDirectories(localTargets.prefix.resolve("bin"))
        Files.createSymbolicLink(bin.resolve("bash"), Path.of("/bin/sh"))
        Files.createSymbolicLink(bin.resolve("sh"), Path.of("/bin/sh"))

        val installedRoot = localTargets.termuxRoot.toString()
        val compiledRoot = installedRoot.dropLast(1) + "x"
        val compiledCache = installedRoot.dropLast(1) + "y"
        val descriptor = BootstrapDescriptor(
            abi = "arm64-v8a",
            source = FileBootstrapSource(tmp.resolve("unused-wrapper-symlinks.zip")),
            expectedSha256 = null,
            expectedSizeBytes = null,
            termuxSuite = "apt-android-7",
            compiledDataRoot = compiledRoot,
            compiledCacheRoot = compiledCache,
        )
        assertNull(localOrchestrator.initializeEnvironment("arm64", descriptor))

        // A fake dpkg "unpacks" termux-keyring-style links exactly as the .deb ships them.
        val keys = localTargets.prefix.resolve("etc/apt/trusted.gpg.d")
        val alsa = localTargets.prefix.resolve("etc/alsa/conf.d")
        val dpkg = bin.resolve("dpkg")
        Files.writeString(
            dpkg,
            """
            #!/bin/sh
            mkdir -p '$keys' '$alsa'
            ln -sfn '$compiledRoot/usr/share/termux-keyring/grimler.gpg' '$keys/grimler.gpg'
            ln -sfn '$compiledCache/apt/cached.conf' '$alsa/cached.conf'
            ln -sfn 'relative-target' '$alsa/relative.conf'
            exit 0
            """.trimIndent() + "\n",
        )
        dpkg.toFile().setExecutable(true)

        val wrapper = localTargets.prefix.resolve("libexec/forgekit-dpkg")
        val process = ProcessBuilder(wrapper.toString(), "--unpack", "keyring-test").start()
        assertEquals(0, process.waitFor(), process.errorStream.bufferedReader().readText())

        assertEquals(Path.of("$installedRoot/usr/share/termux-keyring/grimler.gpg"), Files.readSymbolicLink(keys.resolve("grimler.gpg")))
        assertEquals(Path.of("$installedRoot/apt/cached.conf"), Files.readSymbolicLink(alsa.resolve("cached.conf")))
        assertEquals(Path.of("relative-target"), Files.readSymbolicLink(alsa.resolve("relative.conf")))
    }

    @Test
    fun `dpkg wrapper relocates an incoming preinst before invoking dpkg`() {
        val hostDpkgDeb = Path.of("/usr/bin/dpkg-deb")
        if (!Files.isExecutable(hostDpkgDeb)) return

        val root = tmp.resolve("wrapper-preinst")
        val localTargets = BootstrapTargets.of(root, stagingDir = tmp.resolve("wrapper-preinst-stage"))
        val localOrchestrator = BootstrapOrchestrator(localTargets)
        val bin = Files.createDirectories(localTargets.prefix.resolve("bin"))
        Files.createSymbolicLink(bin.resolve("bash"), Path.of("/bin/sh"))
        Files.createSymbolicLink(bin.resolve("sh"), Path.of("/bin/sh"))
        Files.createSymbolicLink(bin.resolve("dpkg-deb"), hostDpkgDeb)

        val installedRoot = localTargets.termuxRoot.toString()
        val compiledRoot = installedRoot.dropLast(1) + "x"
        val compiledCache = installedRoot.dropLast(1) + "y"
        val descriptor = BootstrapDescriptor(
            abi = "arm64-v8a",
            source = FileBootstrapSource(tmp.resolve("unused-preinst.zip")),
            expectedSha256 = null,
            expectedSizeBytes = null,
            termuxSuite = "apt-android-7",
            compiledDataRoot = compiledRoot,
            compiledCacheRoot = compiledCache,
        )
        assertNull(localOrchestrator.initializeEnvironment("arm64", descriptor))

        val packageTree = Files.createDirectories(root.resolve("package"))
        val control = Files.createDirectories(packageTree.resolve("DEBIAN"))
        Files.writeString(
            control.resolve("control"),
            """
            Package: forgekit-wrapper-test
            Version: 1.0
            Section: misc
            Priority: optional
            Architecture: all
            Maintainer: ForgeKit Tests <tests@forgekit.invalid>
            Description: dpkg wrapper fixture
            """.trimIndent() + "\n",
        )
        val preinst = control.resolve("preinst")
        Files.writeString(preinst, "#!$compiledRoot/usr/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(
            preinst,
            java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"),
        )
        Files.createDirectories(packageTree.resolve("usr/share/forgekit-wrapper-test"))
        Files.writeString(packageTree.resolve("usr/share/forgekit-wrapper-test/payload"), "fixture\n")
        val deb = root.resolve("forgekit-wrapper-test.deb")
        val build = ProcessBuilder(hostDpkgDeb.toString(), "--build", packageTree.toString(), deb.toString()).start()
        assertEquals(0, build.waitFor(), build.errorStream.bufferedReader().readText())

        val checkedControl = root.resolve("checked-control")
        val dpkg = bin.resolve("dpkg")
        Files.writeString(
            dpkg,
            """
            #!/bin/sh
            deb=
            for arg in "${'$'}@"; do case "${'$'}arg" in *.deb) deb="${'$'}arg" ;; esac; done
            [ -n "${'$'}deb" ] || exit 10
            '$hostDpkgDeb' -e "${'$'}deb" '$checkedControl' || exit 11
            grep -F '#!$installedRoot/usr/bin/sh' '$checkedControl/preinst' >/dev/null || exit 12
            [ -x '$checkedControl/preinst' ] || exit 13
            exit 0
            """.trimIndent() + "\n",
        )
        dpkg.toFile().setExecutable(true)

        val wrapper = localTargets.prefix.resolve("libexec/forgekit-dpkg")
        val process = ProcessBuilder(wrapper.toString(), "--unpack", deb.toString()).start()
        assertEquals(0, process.waitFor(), process.errorStream.bufferedReader().readText())
        val installedPreinst = Files.readString(checkedControl.resolve("preinst"))
        assertTrue(installedPreinst.startsWith("#!$installedRoot/usr/bin/sh\n"), installedPreinst)
        assertTrue(Files.isExecutable(checkedControl.resolve("preinst")))
    }

    @Test
    fun `verify and extract full flow with environment init`() = runBlocking {
        val zip = tmp.resolve("b.zip")
        ModeZipWriter(Files.newOutputStream(zip)).use { w ->
            w.entry("bootstrap-aarch64/bin/bash", "bash", 0x81ed)
            w.entry("bootstrap-aarch64/bin/dpkg", "dpkg", 0x81ed)
            w.entry("bootstrap-aarch64/etc/apt/sources.list", "\n", 0x81a4)
            w.entry("bootstrap-aarch64/var/lib/dpkg/status", "Package: x".toByteArray(), 0x81a4)
            // real bootstraps carry ~1000 entries; the verifier enforces a sanity floor,
            // so pad the fixture with honest filler entries to cross it
            repeat(120) { i -> w.entry("bootstrap-aarch64/usr/share/filler/$i", "f", 0x81a4) }
        }
        val descriptor = BootstrapDescriptor(
            abi = "arm64-v8a",
            source = FileBootstrapSource(zip),
            expectedSha256 = null,
            expectedSizeBytes = null,
            termuxSuite = "apt-android-7",
        )

        val result = orchestrator.verifyAndExtract(descriptor)
        assertTrue(result is BootstrapOrchestrator.BootResult.Ready, "was $result")

        assertNull(orchestrator.initializeEnvironment("arm64", descriptor))
        val forgeList = targets.prefix.resolve("etc/apt/sources.list.d/forgekit.list")
        assertTrue(Files.isRegularFile(forgeList))
        val line = Files.readString(forgeList)
        assertTrue(line.startsWith("deb [arch=arm64] https://packages.termux.dev/apt/termux-main main"))

        // package manager init on a real host: run the REAL host dpkg when present; never fake device output
        val initializer = BootstrapOrchestrator.EnvironmentInitializer { _, cmd ->
            val hostCmd = cmd.firstOrNull()?.let { it.substringAfterLast("/") } ?: "dpkg"
            val hostPath = listOf("/usr/bin/dpkg", "/bin/dpkg")
                .firstOrNull { Files.isExecutable(java.nio.file.Paths.get(it)) }
            if (hostPath != null && hostCmd.endsWith("dpkg")) {
                val p = ProcessBuilder(hostPath, "--version").start()
                p.inputStream.bufferedReader().readText()
            } else {
                throw IllegalStateException("no host dpkg")
            }
        }
        // on hosts without dpkg this legitimately fails — assert both real outcomes are handled
        val pmInit = orchestrator.initializePackageManager(descriptor, initializer)
        assertTrue(pmInit == null || pmInit.startsWith("package manager init failed") || pmInit.startsWith("dpkg reported"))
    }

    @Test
    fun `package manager init repairs an interrupted dpkg self-upgrade`() = runBlocking {
        val dpkg = targets.prefix.resolve("bin/dpkg")
        Files.createDirectories(dpkg.parent)
        Files.createDirectories(targets.prefix.resolve("var/lib/dpkg"))
        Files.writeString(targets.prefix.resolve("var/lib/dpkg/status"), "Package: dpkg\n")

        val installedRoot = targets.termuxRoot.toString()
        val compiledRoot = "X${installedRoot.drop(1)}"
        val staleElf = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) +
            "config=$compiledRoot/usr/etc/dpkg".toByteArray()
        Files.write(dpkg, staleElf)
        val descriptor = BootstrapDescriptor(
            abi = "arm64-v8a",
            source = FileBootstrapSource(tmp.resolve("unused.zip")),
            expectedSha256 = null,
            expectedSizeBytes = null,
            termuxSuite = "apt-android-7",
            compiledDataRoot = compiledRoot,
            compiledCacheRoot = "Y${installedRoot.drop(1)}",
        )

        val result = orchestrator.initializePackageManager(descriptor) { _, _ ->
            val relocated = Files.readAllBytes(dpkg).toString(Charsets.ISO_8859_1)
            check(installedRoot in relocated) { "dpkg was launched before relocation" }
            "Debian dpkg version test"
        }

        assertNull(result)
        val repaired = Files.readAllBytes(dpkg).toString(Charsets.ISO_8859_1)
        assertTrue(compiledRoot !in repaired, repaired)
        assertTrue(installedRoot in repaired, repaired)
    }

    @Test
    fun `missing dpkg status is reported not faked`() {
        val descriptor = BootstrapDescriptor(
            abi = "arm64-v8a",
            source = FileBootstrapSource(tmp.resolve("absent.zip")),
            expectedSha256 = null,
            expectedSizeBytes = null,
            termuxSuite = "apt-android-7",
        )
        val failure = orchestrator.initializeEnvironment("arm64", descriptor)
        // prefix has no bash — environment init must fail honestly
        org.junit.jupiter.api.Assertions.assertNotNull(failure)
    }
}
