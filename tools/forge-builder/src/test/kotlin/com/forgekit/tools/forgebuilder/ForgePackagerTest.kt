package com.forgekit.tools.forgebuilder

import com.forgekit.core.security.PublisherKeyPair
import com.forgekit.plugin.installer.ForgePackage
import com.forgekit.plugin.manifest.ManifestParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * REAL round-trip: build with the production packager, open with the
 * production import gate, verify the REAL ed25519 signature chain. The
 * builder is the mirror of the importer — these tests prove the reflection.
 */
class ForgePackagerTest {

    private lateinit var dir: Path

    @Test
    fun `built packages open through the production import gate`() {
        dir = createTempDirectory("packager")
        val source = pluginSource()
        val result = ForgePackager.build(source, dir.resolve("tool.forge"))

        assertEquals(4, result.fileCount, "manifest + main.py + exec.sh + ui")
        assertTrue(result.signed.not())

        val pkg = ForgePackage.open(result.output, ManifestParser())
        assertEquals("com.example.tool", pkg.manifest.id)
        assertEquals("1.0.0", pkg.manifest.version)
        assertTrue(pkg.entries.any { it.path == "runtime/main.py" })
        assertTrue(pkg.entries.any { it.path == "runtime/exec.sh" })
        assertTrue(pkg.entries.any { it.path == "ui/main.json" })
    }

    @Test
    fun `exec bits survive the archive`() {
        dir = createTempDirectory("packager-modes")
        val source = pluginSource()
        val result = ForgePackager.build(source, dir.resolve("tool.forge"))

        val modes = com.forgekit.plugin.installer.ZipModeReader.readModes(result.output)
        assertEquals(0x1ED, modes["runtime/exec.sh"]!! and 0xFFF, "0755 executable must survive")
        assertEquals(0x1A4, modes["runtime/main.py"]!! and 0xFFF, "0644 must survive (the test pins it)")
    }

    @Test
    fun `signed builds verify through the real ed25519 chain`() {
        dir = createTempDirectory("packager-signed")
        val key = PublisherKeyPair.generate()
        val source = pluginSource()
        val result = ForgePackager.build(source, dir.resolve("tool.forge"), key, "ForgeLabs")
        assertTrue(result.signed)
        assertEquals(key.fingerprint, result.keyFingerprint)

        val pkg = ForgePackage.open(result.output, ManifestParser())
        assertEquals(key.fingerprint, pkg.signature?.keyFingerprint)
        assertEquals("ForgeLabs", pkg.publisher?.displayName)

        // the production verifier accepts the signature for real
        val document = java.util.zip.ZipFile(result.output.toFile()).use { zip ->
            zip.getInputStream(zip.getEntry("HASHES")).readBytes().toString(Charsets.UTF_8)
        }
        val files = pkg.readAllFiles()
        val integrity = com.forgekit.core.security.PackageVerifier()
            .verifyIntegrity(document, files.filterKeys { it != "HASHES" && it != "SIGNATURE.json" && it != "publisher.json" })
        assertEquals(null, integrity, "integrity: $integrity")
        val signatureOk = com.forgekit.core.security.PackageVerifier()
            .verifySignature(document, pkg.signature!!, pkg.publisher!!.publicKeyBytes())
        assertTrue(signatureOk, "real signature must verify")
    }

    @Test
    fun `deterministic rebuilds produce byte-identical archives`() {
        dir = createTempDirectory("packager-deterministic")
        val key = PublisherKeyPair.fromSeed(ByteArray(32) { (it + 1).toByte() })
        val source = pluginSource()
        val first = ForgePackager.build(source, dir.resolve("a.forge"), key)
        val second = ForgePackager.build(source, dir.resolve("b.forge"), key)
        assertEquals(first.sha256, second.sha256, "same input + key = same output bytes")

        val third = ForgePackager.build(source, dir.resolve("c.forge"))
        assertTrue(third.sha256 != first.sha256, "unsigned build differs from signed")
    }

    @Test
    fun `broken manifests are refused before any archive is written`() {
        dir = createTempDirectory("packager-broken")
        val source = pluginSource()
        Files.writeString(
            source.resolve("manifest.json"),
            Files.readString(source.resolve("manifest.json")).replace("\"type\": \"python\"", "\"type\": \"java\""),
        )
        assertFailsWith<com.forgekit.core.model.ForgeError> {
            ForgePackager.build(source, dir.resolve("broken.forge"))
        }
        assertTrue(!Files.exists(dir.resolve("broken.forge")), "no output on validation failure")
    }

    // ---- fixtures ------------------------------------------------------------

    private fun pluginSource(): Path {
        val source = dir.resolve("src")
        Files.createDirectories(source.resolve("runtime"))
        Files.createDirectories(source.resolve("ui"))
        Files.writeString(
            source.resolve("manifest.json"),
            """
            {
              "schema": "forgekit.plugin/v1",
              "id": "com.example.tool",
              "name": "Example Tool",
              "version": "1.0.0",
              "runtime": { "type": "python", "version": ">=3.6" },
              "entrypoint": "runtime/main.py",
              "ui": { "entry": "ui/main.json" },
              "actions": [ { "id": "run", "title": "Run" } ]
            }
            """.trimIndent(),
        )
        val mainPy = source.resolve("runtime/main.py")
        Files.writeString(mainPy, "print('hello')\n")
        Files.setPosixFilePermissions(mainPy, PosixFilePermissions.fromString("rw-r--r--"))
        Files.writeString(source.resolve("ui/main.json"), """{"schema":"forgekit.ui/v1","blocks":[]}""")
        val exec = source.resolve("runtime/exec.sh")
        Files.writeString(exec, "#!/bin/bash\n")
        Files.setPosixFilePermissions(exec, PosixFilePermissions.fromString("rwxr-xr-x"))
        return source
    }
}
