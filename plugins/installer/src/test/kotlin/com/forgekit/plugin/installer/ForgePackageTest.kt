package com.forgekit.plugin.installer

import com.forgekit.core.security.PackageVerifier
import com.forgekit.core.security.PublisherKeyPair
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * REAL package handling tests: real zip archives built byte-by-byte, real
 * ed25519 signatures, real extraction into temp plugin trees.
 */
class ForgePackageTest {

    private val parser = com.forgekit.plugin.manifest.ManifestParser()

    @Test
    fun `opens a full package and exposes review facts`() {
        val dir = Files.createTempDirectory("forge-pkg")
        try {
            val key = PublisherKeyPair.generate()
            val archive = ForgePackageBuilder.standard().signedBy(key, "ForgeLabs", "labs@example.org")
                .writeTo(dir.resolve("tool.forge"))
            val pkg = ForgePackage.open(archive, parser)

            assertEquals("com.example.tool", pkg.manifest.id)
            assertEquals(64, pkg.packageSha256.length)
            assertEquals(64, pkg.manifestSha256.length)
            assertTrue(pkg.packageSizeBytes > 0)
            assertTrue(pkg.signature != null)
            assertEquals(key.fingerprint, pkg.signature?.keyFingerprint)
            assertEquals("ForgeLabs", pkg.publisher?.displayName)

            // classification surface (import review EXPANDED CONTENT list)
            val byPath = pkg.entries.associateBy { it.path }
            assertEquals(ForgePackage.ContentClass.MANIFEST, byPath.getValue("manifest.json").classification)
            assertEquals(ForgePackage.ContentClass.RUNTIME, byPath.getValue("runtime/main.py").classification)
            assertEquals(ForgePackage.ContentClass.UI, byPath.getValue("ui/main.json").classification)
            assertEquals(ForgePackage.ContentClass.DATA, byPath.getValue("dependencies/requirements.txt").classification)
            assertEquals(ForgePackage.ContentClass.ASSET, byPath.getValue("assets/icon.txt").classification)
            assertEquals(ForgePackage.ContentClass.SIGNING, byPath.getValue("HASHES").classification)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects entries outside reserved areas`() {
        val dir = Files.createTempDirectory("forge-pkg-bad")
        try {
            val evil = dir.resolve("evil.forge")
            writeZip(evil) {
                put("manifest.json", ForgePackageBuilder.MANIFEST)
                put("runtime/main.py", "print(1)")
                put("etc/passwd", "x") // outside every reserved area
            }
            try {
                ForgePackage.open(evil, parser)
                fail("entry outside reserved areas must fail")
            } catch (expected: com.forgekit.plugin.api.PluginError) {
                assertTrue("outside a reserved area" in expected.message)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects path-escape entry names`() {
        val dir = Files.createTempDirectory("forge-pkg-slip")
        try {
            val slip = dir.resolve("slip.forge")
            writeZip(slip) {
                put("../escape.txt", "boom")
                put("manifest.json", ForgePackageBuilder.MANIFEST)
                put("runtime/main.py", "print(1)")
            }
            try {
                ForgePackage.open(slip, parser)
                fail("path escape must fail at open")
            } catch (expected: com.forgekit.plugin.api.PluginError) {
                assertTrue("escape" in expected.message || "non-canonical" in expected.message)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects signature without hashes`() {
        val dir = Files.createTempDirectory("forge-pkg-sig")
        try {
            val key = PublisherKeyPair.generate()
            val noHashes = dir.resolve("nohashes.forge")
            writeZip(noHashes) {
                put("manifest.json", ForgePackageBuilder.MANIFEST)
                put("runtime/main.py", "print(1)")
                put(
                    "SIGNATURE.json",
                    """{"algorithm":"ed25519","keyFingerprint":"${key.fingerprint}","signature":"${"A".repeat(86)}=="}""",
                )
            }
            try {
                ForgePackage.open(noHashes, parser)
                fail("signature without HASHES must fail")
            } catch (expected: com.forgekit.plugin.api.PluginError) {
                assertTrue("HASHES missing" in expected.message)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects archives without a manifest`() {
        val dir = Files.createTempDirectory("forge-pkg-nomanifest")
        try {
            val noManifest = dir.resolve("empty.forge")
            writeZip(noManifest) { put("runtime/main.py", "print(1)") }
            try {
                ForgePackage.open(noManifest, parser)
                fail("missing manifest must fail")
            } catch (expected: com.forgekit.plugin.api.PluginError) {
                assertTrue("manifest.json missing" in expected.message)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `verifying a signed package against tampered content fails`() {
        val dir = Files.createTempDirectory("forge-pkg-tamper")
        try {
            val key = PublisherKeyPair.generate()
            val archive = ForgePackageBuilder.standard().signedBy(key)
                .writeTo(dir.resolve("clean.forge"))
            val pkg = ForgePackage.open(archive, parser)

            val verifier = PackageVerifier()
            val signedFiles = pkg.readAllFiles()
                .filterKeys { it != "HASHES" && it != "SIGNATURE.json" && it != "publisher.json" }

            // pristine: integrity green + real ed25519 signature verifies
            assertEquals(null, verifier.verifyIntegrity(pkg.rawHashesDocument!!, signedFiles))
            assertTrue(verifier.verifySignature(pkg.rawHashesDocument!!, pkg.signature!!, key.publicKey))

            // tampered content: integrity must fail
            val tampered = signedFiles + ("runtime/main.py" to "print('evil')".toByteArray())
            assertTrue(verifier.verifyIntegrity(pkg.rawHashesDocument!!, tampered) != null)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ---- helper ------------------------------------------------------------

    private fun writeZip(target: Path, body: ZipSink.() -> Unit) {
        Files.createDirectories(target.parent)
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            val sink = ZipSink(zip)
            body(sink)
        }
    }

    private class ZipSink(private val zip: ZipOutputStream) {
        fun put(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }
}
