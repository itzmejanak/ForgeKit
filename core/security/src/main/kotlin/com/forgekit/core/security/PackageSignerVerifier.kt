package com.forgekit.core.security

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * ed25519 key material for package signing (ARCHITECTURE §26).
 *
 * One key pair identifies one publisher. The PRIVATE key never appears in
 * package files — only [PublisherKeyPair.publicKey] travels with signatures.
 */
public class PublisherKeyPair(
    /** Raw 32-byte private seed. */
    public val privateSeed: ByteArray,
    /** Raw 32-byte public key. */
    public val publicKey: ByteArray,
) {
    init {
        require(privateSeed.size == 32) { "ed25519 private seed must be 32 bytes" }
        require(publicKey.size == 32) { "ed25519 public key must be 32 bytes" }
    }

    /** Stable publisher identity (SHA-256 fingerprint of the public key). */
    public val fingerprint: String =
        CanonicalHashManifest.hashHex(publicKey)

    public fun identity(displayName: String? = null, contact: String? = null): PublisherIdentity =
        PublisherIdentity(
            keyFingerprint = fingerprint,
            displayName = displayName,
            contact = contact,
        )

    override fun equals(other: Any?): Boolean =
        other is PublisherKeyPair &&
            privateSeed.contentEquals(other.privateSeed) &&
            publicKey.contentEquals(other.publicKey)

    override fun hashCode(): Int = 31 * privateSeed.contentHashCode() + publicKey.contentHashCode()

    public companion object {
        /** Generates a fresh, cryptographically random publisher identity. */
        public fun generate(random: SecureRandom = SecureRandom()): PublisherKeyPair {
            val seed = ByteArray(32)
            random.nextBytes(seed)
            return fromSeed(seed)
        }

        /** Restores a key pair from its 32-byte private seed. */
        public fun fromSeed(seed: ByteArray): PublisherKeyPair {
            require(seed.size == 32) { "ed25519 private seed must be 32 bytes" }
            val private = Ed25519PrivateKeyParameters(seed, 0)
            return PublisherKeyPair(
                privateSeed = seed.copyOf(),
                publicKey = private.generatePublicKey().encoded,
            )
        }
    }
}

/**
 * Real ed25519 signer for `.forge` packages (used by `tools/forge-builder` and
 * signing tests). Produces a detached [SignatureMetadata] over the canonical
 * hash document of the package contents.
 */
public class PackageSigner(private val key: PublisherKeyPair) {

    /** Signs the canonical hash document; the signature covers every listed file. */
    public fun sign(canonicalHashDocument: String): SignatureMetadata =
        sign(canonicalHashDocument.toByteArray(Charsets.UTF_8))

    public fun sign(document: ByteArray): SignatureMetadata {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(key.privateSeed, 0))
        signer.update(document, 0, document.size)
        val signature = signer.generateSignature()
        require(signature.size == 64) { "ed25519 signature must be 64 bytes" }
        return SignatureMetadata(
            algorithm = SignatureMetadata.ALGORITHM_ED25519,
            keyFingerprint = key.fingerprint,
            signature = signature,
        )
    }

    public fun keyPair(): PublisherKeyPair = key
}

/**
 * Real ed25519 verification against the package contents (ARCHITECTURE §26:
 * integrity → signature → publisher, all BEFORE installation).
 */
public class PackageVerifier {

    /**
     * Verifies a detached signature over the canonical hash document.
     * Content mismatches must be detected BEFORE this call (the document is the
     * root of trust for file bytes).
     */
    public fun verifySignature(
        canonicalHashDocument: String,
        signature: SignatureMetadata,
        publisherKey: ByteArray,
    ): Boolean {
        if (signature.algorithm != SignatureMetadata.ALGORITHM_ED25519) return false
        if (publisherKey.size != 32) return false
        val document = canonicalHashDocument.toByteArray(Charsets.UTF_8)
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publisherKey, 0))
        verifier.update(document, 0, document.size)
        return verifier.verifySignature(signature.signature)
    }

    /**
     * Full integrity check: rebuilds the canonical hash document from the actual
     * package files and compares it byte-for-byte with the expected document the
     * signature covers. Returns null on success, or a precise failure reason.
     */
    public fun verifyIntegrity(
        expectedDocument: String,
        actualFiles: Map<String, ByteArray>,
    ): String? {
        val parsed = try {
            CanonicalHashManifest.parse(expectedDocument)
        } catch (e: PackageVerificationError) {
            return "unparseable hash manifest: ${e.message}"
        }
        val normalizedActual = actualFiles.mapKeys { it.key.replace('\\', '/').trimStart('/') }
        val listed = parsed.map { it.first }.toSet()
        val actual = normalizedActual.keys
        val missing = listed - actual
        if (missing.isNotEmpty()) return "files missing vs manifest: ${missing.sorted().take(5)}"
        val extra = actual - listed
        if (extra.isNotEmpty()) return "files not covered by manifest: ${extra.sorted().take(5)}"
        for ((path, expectedHash) in parsed) {
            val bytes = normalizedActual.getValue(path)
            val actualHash = CanonicalHashManifest.hashHex(bytes)
            if (actualHash != expectedHash) return "hash mismatch for '$path'"
        }
        // document round-trip: ordering + formatting must be canonical too
        val rebuilt = CanonicalHashManifest.build(normalizedActual)
        if (rebuilt != expectedDocument) {
            return "hash manifest is not canonical (ordering/formatting drift)"
        }
        return null
    }
}

/**
 * Pins trusted publisher keys (ARCHITECTURE §25-26): a package is TRUSTED only
 * when its signing key is pinned here; everything else requires explicit user
 * approval.
 */
public class TrustStore {

    private val pinned = LinkedHashMap<String, ByteArray>()

    /** Pins a publisher public key. Idempotent per fingerprint. */
    public fun pin(publicKey: ByteArray, displayName: String? = null, contact: String? = null): PublisherIdentity {
        require(publicKey.size == 32) { "ed25519 public key must be 32 bytes" }
        val fingerprint = CanonicalHashManifest.hashHex(publicKey)
        pinned[fingerprint] = publicKey.copyOf()
        metadata[fingerprint] = PublisherIdentity(fingerprint, displayName, contact)
        return metadata.getValue(fingerprint)
    }

    private val metadata = LinkedHashMap<String, PublisherIdentity>()

    public fun isPinned(keyFingerprint: String): Boolean = pinned.containsKey(keyFingerprint)

    public fun publisher(keyFingerprint: String): PublisherIdentity? = metadata[keyFingerprint]

    public fun publicKey(keyFingerprint: String): ByteArray? = pinned[keyFingerprint]

    public fun all(): List<PublisherIdentity> = metadata.values.toList()

    /** Classifies a signature's trust level (null signature → UNTRUSTED, always). */
    public fun classify(signature: SignatureMetadata?): TrustLevel =
        if (signature == null) TrustLevel.UNTRUSTED
        else if (isPinned(signature.keyFingerprint)) TrustLevel.TRUSTED
        else TrustLevel.UNTRUSTED
}
