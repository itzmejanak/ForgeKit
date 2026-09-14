package com.forgekit.core.filesystem

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForgePathsTest {
    private val tmp = Files.createTempDirectory("forgepaths-test")
    private val paths = ForgePaths(tmp)

    @Test
    fun `logical layout maps under root`() {
        assertEquals(tmp.resolve("app"), paths.appDir)
        assertEquals(tmp.resolve("forge/plugins"), paths.pluginsDir)
        assertEquals(tmp.resolve("forge/jobs"), paths.jobsDir)
        assertEquals(tmp.resolve("termux/usr"), paths.termuxPrefix)
        assertEquals(tmp.resolve("termux/home"), paths.termuxHome)
    }

    @Test
    fun `plugin dirs are sandboxed by id`() {
        assertEquals(tmp.resolve("forge/plugins/com.example.tool"), paths.pluginDir("com.example.tool"))
    }

    @Test
    fun `rejects unsafe ids`() {
        assertFailsWith<IllegalArgumentException> { paths.pluginDir("../escape") }
        assertFailsWith<IllegalArgumentException> { paths.pluginDir("a/b") }
    }

    @Test
    fun `termux environment is complete`() {
        val termuxExec = paths.termuxPrefix.resolve("lib/libtermux-exec.so")
        Files.createDirectories(termuxExec.parent)
        Files.write(termuxExec, byteArrayOf())
        val env = paths.termuxEnvironment()
        assertEquals(tmp.resolve("termux/home").toString(), env["HOME"])
        assertEquals(tmp.resolve("termux/usr").toString(), env["PREFIX"])
        assertEquals(tmp.resolve("termux").toString(), env["TERMUX_APP__DATA_DIR"])
        assertEquals(tmp.resolve("termux").toString(), env["TERMUX__ROOTFS"])
        assertEquals(tmp.resolve("termux/home").toString(), env["TERMUX__HOME"])
        assertEquals(tmp.resolve("termux/usr").toString(), env["TERMUX__PREFIX"])
        assertEquals(tmp.resolve("termux/usr/bin").toString(), env["PATH"])
        assertEquals(tmp.resolve("termux/usr/lib").toString(), env["LD_LIBRARY_PATH"])
        assertEquals(termuxExec.toString(), env["LD_PRELOAD"])
        assertEquals("xterm-256color", env["TERM"])
        // ncurses lookups (clear/tput/less) fail without this after prefix relocation.
        assertEquals(tmp.resolve("termux/usr/share/terminfo").toString(), env["TERMINFO"])
    }

    @Test
    fun `skeleton is idempotent`() {
        paths.ensureSkeleton()
        paths.ensureSkeleton()
        assertTrue(Files.isDirectory(paths.termuxHome))
        assertTrue(Files.isDirectory(paths.termuxTmp))
    }

    @Test
    fun `isInside guards boundaries`() {
        assertTrue(paths.isInside(tmp.resolve("forge"), tmp.resolve("forge/plugins/x/manifest.json")))
        assertFalse(paths.isInside(tmp.resolve("forge/plugins/a"), tmp.resolve("forge/plugins/b")))
    }
}

class PluginLayoutTest {
    private val tmp = Files.createTempDirectory("pluginlayout-test")

    @Test
    fun `layout creates all dirs and ready marker works`() {
        val layout = PluginLayout(tmp.resolve("com.example.tool"))
        layout.ensureDirectories()
        assertTrue(Files.isDirectory(layout.pythonVenv.parent))
        assertFalse(layout.isReady())
        layout.markReady()
        assertTrue(layout.isReady())
    }
}

class FileAccessGrantTest {
    @Test
    fun `expiry logic`() {
        val g = FileAccessGrant("p", java.nio.file.Paths.get("/x"), FileAccessGrant.Mode.READ, 1000)
        assertFalse(g.isExpired(999))
        assertTrue(g.isExpired(1001))
    }
}
