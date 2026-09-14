package com.forgekit.termux.embedded

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * REAL PTY process tests — no mocks, no fakes.
 *
 * The exact same forgekit_pty.c that ships in the APK (arm64, NDK build) is
 * compiled with the host toolchain and loaded into the JVM; every test below
 * performs real fork + openpty + execve and real terminal I/O.
 */
class PtyProcessRealTest {

    companion object {
        init {
            NativePty.loadFrom(locateHostLibrary())
        }

        /** The Gradle `compileHostPty` task writes the host .so into build/host-pty. */
        private fun locateHostLibrary(): File {
            val name = "libforgekit_pty_host.so"
            val userDir = System.getProperty("user.dir") ?: "."
            var dir: File? = File(userDir).absoluteFile
            while (dir != null) {
                val candidate = File(dir, "build/host-pty/$name")
                if (candidate.isFile) return candidate
                dir = dir.parentFile
            }
            throw IllegalStateException(
                "host PTY library not found — run the compileHostPty task (build/host-pty/$name)",
            )
        }
    }

    private val baseEnv = mapOf(
        "PATH" to "/usr/bin:/bin",
        "TERM" to "xterm-256color",
        "HOME" to "/tmp",
        "LANG" to "C.UTF-8",
    )

    /** Drains terminal output until EOF or deadline; used by most tests. */
    private fun readAll(process: PtyProcess, deadlineMs: Long = 5000): String {
        val sb = StringBuilder()
        val buf = ByteArray(8192)
        val deadline = System.nanoTime() + deadlineMs * 1_000_000
        while (System.nanoTime() < deadline) {
            when (val n = process.read(buf, timeoutMs = 200)) {
                PtyProcess.EOF -> return sb.toString()
                PtyProcess.TIMEOUT -> continue
                else -> sb.append(String(buf, 0, n, Charsets.UTF_8))
            }
        }
        return sb.toString()
    }

    @Test
    fun `exec runs a real command and reports its exit code`() {
        val p = PtyProcess.start(
            command = listOf("/bin/sh", "-c", "echo hello-pty; exit 7"),
            environment = baseEnv,
            cwd = "/tmp",
        )
        val output = readAll(p)
        assertTrue(output.contains("hello-pty"), "expected output, got: ${output.replace("\r", "\\r")}")
        val exit = p.awaitExit(5000)
        assertNotNull(exit)
        assertEquals(7, exit.exitCode)
        assertNull(exit.signal)
        p.close()
    }

    @Test
    fun `pty line discipline translates newline to crlf (real terminal semantics)`() {
        val p = PtyProcess.start(
            command = listOf("/bin/sh", "-c", "printf 'raw\\n'"),
            environment = baseEnv,
            cwd = "/tmp",
        )
        val output = readAll(p)
        assertTrue(
            output.contains("raw\r\n"),
            "ONLCR must translate \\n to \\r\\n on a real pty; got: ${output.map { it.code }}",
        )
        p.close()
    }

    @Test
    fun `interactive session - write command, read echo and result`() {
        val p = PtyProcess.start(
            command = listOf("/bin/sh"),
            environment = baseEnv,
            cwd = "/tmp",
        )
        try {
            // Enter on a pty is carriage return (ICRNL translates to \n)
            p.write("echo forgekit-interactive\r".toByteArray())
            val seen = readUntil(p, "forgekit-interactive", 5000)
            assertTrue(seen, "interactive output not observed")
            p.write("exit 3\r".toByteArray())
            val exit = p.awaitExit(5000)
            assertNotNull(exit)
            assertEquals(3, exit.exitCode)
        } finally {
            p.close()
        }
    }

    @Test
    fun `sigterm terminates a long running child and reports the signal`() {
        val p = PtyProcess.start(
            command = listOf("/bin/sh", "-c", "sleep 300"),
            environment = baseEnv,
            cwd = "/tmp",
        )
        try {
            assertTrue(p.isRunning)
            assertTrue(p.sendSignal(Signals.SIGTERM), "kill should succeed while running")
            val exit = p.awaitExit(5000)
            assertNotNull(exit, "child should die after SIGTERM")
            assertEquals(Signals.SIGTERM, exit.signal)
            assertEquals(143, exit.combinedCode())
            // after reaping, signaling reports the process as gone
            assertTrue(!p.sendSignal(Signals.SIGTERM))
        } finally {
            p.close()
        }
    }

    @Test
    fun `window size is applied and observable via stty`() {
        val p = PtyProcess.start(
            command = listOf("/bin/sh", "-c", "stty size"),
            environment = baseEnv,
            cwd = "/tmp",
            rows = 37,
            cols = 101,
        )
        val output = readAll(p)
        assertTrue(output.contains("37 101"), "stty size must report the winsize; got: $output")
        p.close()
    }

    @Test
    fun `environment is passed to the child process`() {
        val p = PtyProcess.start(
            command = listOf("/bin/sh", "-c", "printf %s \"\$FORGEKIT_PROBE\""),
            environment = baseEnv + ("FORGEKIT_PROBE" to "env-works"),
            cwd = "/tmp",
        )
        val output = readAll(p)
        assertTrue(output.contains("env-works"), "child env must be provided; got: $output")
        p.close()
    }

    @Test
    fun `cwd is applied before exec`() {
        val p = PtyProcess.start(
            command = listOf("/bin/sh", "-c", "basename \"\$PWD\""),
            environment = baseEnv,
            cwd = "/usr",
        )
        val output = readAll(p)
        assertTrue(output.contains("usr"), "child must start in the given cwd; got: $output")
        p.close()
    }

    @Test
    fun `failed exec reports exit 127 without corrupting the jvm`() {
        val p = PtyProcess.start(
            command = listOf("/nonexistent/forgekit/exec-target"),
            environment = baseEnv,
            cwd = "/tmp",
        )
        val exit = p.awaitExit(5000)
        assertNotNull(exit)
        assertEquals(127, exit.exitCode, "execve failure must exit 127")
        assertEquals(PtyProcess.EOF, p.read(ByteArray(16), timeoutMs = 300))
        p.close()
    }

    /** Reads until [needle] appears in the stream or the deadline passes. */
    private fun readUntil(p: PtyProcess, needle: String, deadlineMs: Long): Boolean {
        val buf = ByteArray(8192)
        val sb = StringBuilder()
        val deadline = System.nanoTime() + deadlineMs * 1_000_000
        while (System.nanoTime() < deadline) {
            when (val n = p.read(buf, timeoutMs = 200)) {
                PtyProcess.EOF -> return needle in sb
                PtyProcess.TIMEOUT -> Unit
                else -> {
                    sb.append(String(buf, 0, n, Charsets.UTF_8))
                    if (needle in sb) return true
                }
            }
        }
        return needle in sb
    }
    @Test
    fun `raw mode passes binary stdin through unmodified`() {
        // Payload containing bytes the cooked line discipline would mangle
        // (0x0d CR, 0x0a LF, 0x03 ETX, 0x1a SUB, 0x7f DEL, 0xff): in raw mode
        // they must reach the process VERBATIM (binary protocol requirement).
        val payload = byteArrayOf(0, 1, 2, 3, 0x0d, 0x0a, 0x1a, 0x7f, 0xff.toByte(), 0x41, 0x0d, 0x0a)
        val probe = "/tmp/forgekit_raw_probe.bin"
        val reference = "/tmp/forgekit_raw_ref.bin"
        // head -c N consumes exactly N bytes then exits: deterministic in raw
        // mode (no EOF char exists there; length-bounded reads are the norm
        // for binary protocol sessions).
        val script = "head -c " + payload.size + " > " + probe + "; cmp -s " + probe + " " + reference + " && echo BINARY-OK || echo BINARY-MISMATCH"
        val p = PtyProcess.start(
            command = listOf("/bin/sh", "-c", script),
            environment = baseEnv,
            cwd = "/tmp",
            rawMode = true,
        )
        try {
            java.nio.file.Files.write(java.nio.file.Path.of(reference), payload)
            p.write(payload)
            val out = readAll(p)
            assertTrue("BINARY-OK" in out, "raw pty must be byte-transparent; got: $out")
        } finally {
            p.close()
        }
    }
}
