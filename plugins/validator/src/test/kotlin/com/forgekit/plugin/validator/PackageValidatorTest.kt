package com.forgekit.plugin.validator

import com.forgekit.core.security.PublisherKeyPair
import com.forgekit.core.security.TrustLevel
import com.forgekit.plugin.installer.ForgePackage
import com.forgekit.plugin.installer.ForgePackageBuilder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * REAL validator tests: real signed/tampered/unsigned packages, real trust
 * decisions, real content findings — mirroring the import review facts.
 */
class PackageValidatorTest {

    private val parser = com.forgekit.plugin.manifest.ManifestParser()

    @Test
    fun `signed package from a trusted publisher validates cleanly`() {
        val dir = Files.createTempDirectory("forge-validate")
        try {
            val key = PublisherKeyPair.generate()
            val context = ValidationContext(
                trustStore = com.forgekit.core.security.TrustStore().also { it.pin(key.publicKey, "ForgeLabs") },
                availableRuntimes = mapOf("python" to "3.11.4"),
            )
            val archive = ForgePackageBuilder.standard().signedBy(key, "ForgeLabs")
                .writeTo(dir.resolve("tool.forge"))
            val report = PackageValidator(context).validate(ForgePackage.open(archive, parser))

            assertTrue(report.valid, "errors: ${report.errors}")
            assertEquals(TrustLevel.TRUSTED, report.trustLevel)
            assertEquals("ForgeLabs", report.publisher?.displayName)
            assertTrue(report.signaturePresent)
            assertEquals(64, report.packageSha256.length)
            assertEquals(64, report.manifestSha256.length)
            assertEquals(2, report.permissionClassification?.known?.size)
            // dependency facts for the provisioning card
            assertTrue(report.dependencyFacts.any { it.manager == "PIP" && it.name == "r2pipe" && it.version == "==1.9.8" })
            assertTrue(report.dependencyFacts.any { it.manager == "TERMUX" && it.name == "python" })
            // trusted publisher, but 'network' is a DANGEROUS permission →
            // §25: user approval is still mandatory for risky packages
            assertTrue(report.requiresUserApproval)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unsigned package is untrusted and requires user approval`() {
        val dir = Files.createTempDirectory("forge-unsigned")
        try {
            val archive = ForgePackageBuilder.standard().writeTo(dir.resolve("tool.forge"))
            val report = PackageValidator(ValidationContext()).validate(ForgePackage.open(archive, parser))

            assertEquals(TrustLevel.UNTRUSTED, report.trustLevel)
            assertNull(report.publisher)
            assertTrue(report.contentWarnings.any { it.code == "UNSIGNED" })
            assertTrue(report.requiresUserApproval)
            assertTrue(report.valid, "unsigned is a warning, not an error: ${report.errors}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `tampered content fails integrity and invalidates the signature`() {
        val dir = Files.createTempDirectory("forge-tamper")
        try {
            val key = PublisherKeyPair.generate()
            val archive = ForgePackageBuilder.standard().signedBy(key).writeTo(dir.resolve("tool.forge"))
            val pkg = ForgePackage.open(archive, parser)

            // rewrite the archive with modified content, keeping HASHES/SIGNATURE
            val entries = pkg.readAllFiles().toMutableMap()
            entries["runtime/main.py"] = "print('evil')".toByteArray()
            val tampered = dir.resolve("tampered.forge")
            java.util.zip.ZipOutputStream(Files.newOutputStream(tampered)).use { zip ->
                for ((name, data) in entries) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
            val report = PackageValidator(ValidationContext()).validate(ForgePackage.open(tampered, parser))
            assertTrue(!report.valid)
            assertTrue(report.errors.any { it.code == "INTEGRITY_FAILED" }, "errors: ${report.errors.map { it.code }}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing entrypoint and ui entry are hard errors`() {
        val dir = Files.createTempDirectory("forge-missing")
        try {
            val archive = dir.resolve("tool.forge")
            java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                zip.write(ForgePackageBuilder.MANIFEST.toByteArray())
                zip.closeEntry()
                // NO runtime/main.py, NO ui/main.json
            }
            val report = PackageValidator(ValidationContext()).validate(ForgePackage.open(archive, parser))
            assertTrue(!report.valid)
            assertTrue(report.errors.any { it.code == "ENTRYPOINT_MISSING" })
            assertTrue(report.errors.any { it.code == "UI_ENTRY_MISSING" })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unknown permission is a hard error and system permissions warn`() {
        val dir = Files.createTempDirectory("forge-perm")
        try {
            val manifest = ForgePackageBuilder.MANIFEST.replace("\"network\", \"files.read\"", "\"network\", \"kernel.root\"")
            val archive = ForgePackageBuilder().manifest(manifest)
                .file("runtime/main.py", "print(1)", 0x1A4)
                .file("ui/main.json", """{"schema":"forgekit.ui/v1"}""", 0x1A4)
                .writeTo(dir.resolve("tool.forge"))
            val report = PackageValidator(ValidationContext()).validate(ForgePackage.open(archive, parser))
            assertTrue(!report.valid)
            assertNotNull(report.errors.firstOrNull { it.code == "PERMISSION_UNKNOWN" && it.detail == "kernel.root" })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `incompatible runtime version is an error, missing runtime is a warning`() {
        val dir = Files.createTempDirectory("forge-runtime")
        try {
            val archive = ForgePackageBuilder.standard().writeTo(dir.resolve("tool.forge"))
            // python 3.8.2 satisfies >=3.6 — must NOT error
            val oldPython = ValidationContext(availableRuntimes = mapOf("python" to "3.8.2"))
            val report = PackageValidator(oldPython).validate(ForgePackage.open(archive, parser))
            assertNull(report.errors.firstOrNull { it.code == "RUNTIME_INCOMPATIBLE" }, "${report.errors}")

            // truly incompatible: requirement >= 3.11, installed 3.8.2
            val strict = ForgePackageBuilder.MANIFEST.replace(">=3.6", ">=3.11")
            val strictArchive = ForgePackageBuilder().manifest(strict)
                .file("runtime/main.py", "print(1)")
                .file("ui/main.json", """{"schema":"forgekit.ui/v1"}""")
                .writeTo(dir.resolve("strict.forge"))
            val report1 = PackageValidator(oldPython).validate(ForgePackage.open(strictArchive, parser))
            assertNotNull(report1.errors.firstOrNull { it.code == "RUNTIME_INCOMPATIBLE" }, "${report1.errors}")

            // missing runtime entirely → provisioning warning
            val noPython = ValidationContext(availableRuntimes = mapOf("node" to "20.0.0"))
            val report2 = PackageValidator(noPython).validate(ForgePackage.open(archive, parser))
            assertTrue(report2.contentWarnings.any { it.code == "RUNTIME_NOT_INSTALLED" })
            assertTrue(report2.valid, "missing runtime is resolvable, not fatal: ${report2.errors}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `packages over the size limit are rejected`() {
        val dir = Files.createTempDirectory("forge-size")
        try {
            val archive = ForgePackageBuilder.standard().writeTo(dir.resolve("tool.forge"))
            val tinyLimit = ValidationContext(maxPackageBytes = 10)
            val report = PackageValidator(tinyLimit).validate(ForgePackage.open(archive, parser))
            assertNotNull(report.errors.firstOrNull { it.code == "PACKAGE_TOO_LARGE" })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `ui schema mismatch is an error`() {
        val dir = Files.createTempDirectory("forge-ui")
        try {
            val archive = ForgePackageBuilder()
                .manifest(ForgePackageBuilder.MANIFEST)
                .file("runtime/main.py", "print(1)", 0x1A4)
                .file("ui/main.json", """{"schema":"something.else/v9"}""", 0x1A4)
                .writeTo(dir.resolve("tool.forge"))
            val report = PackageValidator(ValidationContext()).validate(ForgePackage.open(archive, parser))
            assertNotNull(report.errors.firstOrNull { it.code == "UI_SCHEMA_UNKNOWN" })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `symlink entries in plugin packages are prohibited`() {
        val dir = Files.createTempDirectory("forge-link")
        try {
            val archive = ForgePackageBuilder()
                .manifest(ForgePackageBuilder.MANIFEST)
                .file("runtime/main.py", "print(1)")
                .file("ui/main.json", """{"schema":"forgekit.ui/v1"}""")
                .rawEntry("runtime/sneaky", "/etc/passwd", 0xA000 or 0x1FF) // S_IFLNK | 0777
                .writeTo(dir.resolve("link.forge"))

            val report = PackageValidator(ValidationContext()).validate(ForgePackage.open(archive, parser))
            assertNotNull(
                report.errors.firstOrNull { it.code == "PROHIBITED_SYMLINK" },
                "symlink entry must be rejected: ${report.errors.map { it.code }}",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
