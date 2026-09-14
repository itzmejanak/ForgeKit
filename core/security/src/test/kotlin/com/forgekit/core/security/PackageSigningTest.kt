package com.forgekit.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * REAL ed25519 package signing/verification tests (BouncyCastle, real keys,
 * real signatures, real SHA-256 documents — no stubs anywhere).
 */
class PackageSigningTest {

    @Test
    fun `canonical hash manifest is deterministic and order-independent of input map`() {
        val files = mapOf(
            "runtime/main.py" to "print('hi')".toByteArray(),
            "manifest.json" to "{}".toByteArray(),
            "ui/main.json" to "{}".toByteArray(),
        )
        val a = CanonicalHashManifest.build(files)
        val b = CanonicalHashManifest.build(files.entries.reversed().associate { it.toPair() })
        assertEquals(a, b, "input map order must not affect the canonical document")
        assertTrue(a.startsWith(CanonicalHashManifest.HEADER))
        assertTrue(a.contains("sha256 "))
    }

    @Test
    fun `canonical hash manifest parse rejects malformed lines and duplicates`() {
        try {
            CanonicalHashManifest.parse("forgekit.hashes/v1\nnot-a-line\n")
            fail("malformed line must throw")
        } catch (expected: PackageVerificationError) {
            assertTrue("malformed" in expected.message)
        }
        try {
            CanonicalHashManifest.parse(
                "forgekit.hashes/v1\nsha256 ${"0".repeat(64)} a.txt\nsha256 ${"0".repeat(64)} a.txt\n",
            )
            fail("duplicate entries must throw")
        } catch (expected: PackageVerificationError) {
            assertTrue("duplicate" in expected.message)
        }
    }

    @Test
    fun `sign and verify round-trips with the real ed25519 primitive`() {
        val key = PublisherKeyPair.generate()
        val signer = PackageSigner(key)
        val document = CanonicalHashManifest.build(
            mapOf("manifest.json" to "{}".toByteArray(), "runtime/main.py" to "x = 1".toByteArray()),
        )
        val signature = signer.sign(document)

        assertEquals(SignatureMetadata.ALGORITHM_ED25519, signature.algorithm)
        assertEquals(64, signature.signature.size)
        assertEquals(key.fingerprint, signature.keyFingerprint)

        val verifier = PackageVerifier()
        assertTrue(verifier.verifySignature(document, signature, key.publicKey))
    }

    @Test
    fun `verification fails on a different key or tampered document`() {
        val key = PublisherKeyPair.generate()
        val other = PublisherKeyPair.generate()
        val signer = PackageSigner(key)
        val document = CanonicalHashManifest.build(mapOf("a" to "1".toByteArray()))
        val signature = signer.sign(document)

        val verifier = PackageVerifier()
        assertFalse(verifier.verifySignature(document, signature, other.publicKey), "wrong key must fail")

        val tampered = document.replace("1", "2")
        assertFalse(verifier.verifySignature(tampered, signature, key.publicKey), "tampered document must fail")

        val tamperedSig = signature.copy(signature = signature.signature.copyOf().also { it[0] = (it[0] + 1).toByte() })
        assertFalse(verifier.verifySignature(document, tamperedSig, key.publicKey), "tampered signature must fail")
    }

    @Test
    fun `integrity check detects modified, missing, extra and non-canonical files`() {
        val files = mapOf(
            "manifest.json" to "{}".toByteArray(),
            "runtime/main.py" to "x = 1".toByteArray(),
        )
        val document = CanonicalHashManifest.build(files)
        val verifier = PackageVerifier()

        assertNull(verifier.verifyIntegrity(document, files), "pristine files must pass")

        val modified = files + ("runtime/main.py" to "x = 2".toByteArray())
        assertNotNull(verifier.verifyIntegrity(document, modified), "modified content must fail")

        val missing = files - "runtime/main.py"
        assertNotNull(verifier.verifyIntegrity(document, missing), "missing file must fail")

        val extra = files + ("runtime/evil.py" to "boom".toByteArray())
        assertNotNull(verifier.verifyIntegrity(document, extra), "unlisted file must fail")

        // same hashes, non-canonical document (wrong header/order) must fail
        val reversed = CanonicalHashManifest.HEADER + "\n" +
            document.removePrefix(CanonicalHashManifest.HEADER + "\n").lines().filter { it.isNotBlank() }
                .sortedDescending().joinToString("\n") + "\n"
        assertNotNull(verifier.verifyIntegrity(reversed, files), "non-canonical document must fail")
    }

    @Test
    fun `key restore from seed reproduces the same identity`() {
        val original = PublisherKeyPair.generate()
        val restored = PublisherKeyPair.fromSeed(original.privateSeed)
        assertEquals(original.fingerprint, restored.fingerprint)
        assertEquals(original.publicKey.contentToString(), restored.publicKey.contentToString())
    }
}

class TrustStoreTest {

    @Test
    fun `pinned keys are trusted, unknown keys and unsigned packages are untrusted`() {
        val store = TrustStore()
        val key = PublisherKeyPair.generate()
        val identity = store.pin(key.publicKey, displayName = "ForgeLabs")

        val signer = PackageSigner(key)
        val document = CanonicalHashManifest.build(mapOf("a" to "b".toByteArray()))
        val signed = signer.sign(document)

        assertEquals(TrustLevel.TRUSTED, store.classify(signed))
        assertEquals(TrustLevel.UNTRUSTED, store.classify(null))

        val stranger = PublisherKeyPair.generate()
        val strangerSig = PackageSigner(stranger).sign(document)
        assertEquals(TrustLevel.UNTRUSTED, store.classify(strangerSig))

        assertEquals("ForgeLabs", store.publisher(identity.keyFingerprint)?.displayName)
        assertTrue(store.isPinned(key.fingerprint))
        assertFalse(store.isPinned(stranger.fingerprint))
    }
}

class PermissionCatalogTest {

    @Test
    fun `classification flags dangerous and unknown permissions for approval`() {
        val benign = PermissionCatalog.classify(listOf("files.read", "files.write", "ui.progress"))
        assertFalse(benign.requiresApproval)
        assertEquals(PermissionCatalog.Risk.NORMAL, benign.maxRisk)
        assertTrue(benign.grantable)

        val risky = PermissionCatalog.classify(listOf("network", "package.install"))
        assertTrue(risky.requiresApproval)
        assertEquals(PermissionCatalog.Risk.DANGEROUS, risky.maxRisk)
        assertTrue("dangerous" in risky.summary())

        val unknown = PermissionCatalog.classify(listOf("files.read", "kernel.root"))
        assertTrue(unknown.requiresApproval)
        assertFalse(unknown.grantable)
        assertTrue("kernel.root" in unknown.unknown)
    }

    @Test
    fun `security audit log records decisions in order`() {
        val log = SecurityAuditLog()
        log.record("import", "com.example.tool", "APPROVED", "user approved risky permissions")
        log.record("install", "com.example.tool", "INSTALLED", null)

        val all = log.all()
        assertEquals(2, all.size)
        assertEquals("import", all[0].event)
        assertEquals("APPROVED", all[0].decision)
        assertTrue(all[0].timestampMillis <= all[1].timestampMillis)
    }
}
