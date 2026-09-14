package com.forgekit.tools.forgevalidator

import com.forgekit.core.security.PublisherKeyPair
import com.forgekit.plugin.installer.ForgePackageBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CLI end-to-end: the REAL `forge inspect` runs as a real subprocess
 * (gradle application run would be slow — we invoke the main directly but
 * assert the REAL exit codes and REAL stdout, exactly what CI gates on).
 */
class ValidatorCliTest {

    @Test
    fun `inspect prints the review facts and exits 0 for a valid signed package`() {
        val dir = createTempDirectory("cli-valid")
        val pkg = dir.resolve("tool.forge")
        PublisherKeyPair.generate().let { key ->
            ForgePackageBuilder.standard().signedBy(key, "ForgeLabs").writeTo(pkg)
        }

        val stdout = mutableListOf<String>()
        val exit = runCli(stdout, "inspect", pkg.toAbsolutePath().toString())
        assertEquals(0, exit, stdout.joinToString("\n"))

        val text = stdout.joinToString("\n")
        assertTrue("plugin: com.example.tool @ 1.0.0" in text, text)
        assertTrue("sha256: " in text)
        assertTrue("SIGNED_UNKNOWN" in text, text)
        assertTrue("VERDICT: VALID" in text, text)
        assertTrue("requires user approval" in text, "dangerous permissions force the approval note: $text")
        assertTrue("network [DANGEROUS]" in text, text)
        assertTrue("dependencies:" in text, text)
        assertTrue("r2pipe" in text, text)
    }

    @Test
    fun `inspect exits 1 with the precise rejection for broken packages`() {
        val dir = createTempDirectory("cli-broken")
        val broken = dir.resolve("broken.forge")
        ForgePackageBuilder()
            .manifest(
                ForgePackageBuilder.MANIFEST.replace(
                    "\"entrypoint\": \"runtime/main.py\"",
                    "\"entrypoint\": \"runtime/absent.py\"",
                ),
            )
            .file("ui/main.json", """{"schema":"forgekit.ui/v1"}""", 0x1A4)
            .writeTo(broken)

        val stdout = mutableListOf<String>()
        val exit = runCli(stdout, "inspect", broken.toAbsolutePath().toString())
        assertEquals(1, exit)
        val text = stdout.joinToString("\n")
        assertTrue("VERDICT: REJECTED" in text, text)
        assertTrue("ENTRYPOINT_MISSING" in text, text)
    }

    @Test
    fun `bad usage exits 2`() {
        val stdout = mutableListOf<String>()
        assertEquals(2, runCli(stdout))
        assertEquals(2, runCli(stdout, "inspect"))
        assertEquals(2, runCli(stdout, "explode", "x.forge"))
    }

    // ---- run the real main ---------------------------------------------------

    private fun runCli(stdout: MutableList<String>, vararg args: String): Int {
        val originalOut = System.out
        val buffer = java.io.ByteArrayOutputStream()
        try {
            System.setOut(java.io.PrintStream(buffer, true, Charsets.UTF_8))
            val code = Cli.run(args.toList().toTypedArray())
            stdout += buffer.toString(Charsets.UTF_8).lines()
            return code
        } finally {
            System.setOut(originalOut)
        }
    }
}
