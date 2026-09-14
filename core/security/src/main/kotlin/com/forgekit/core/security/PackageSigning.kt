package com.forgekit.core.security

import com.forgekit.core.model.ForgeError

/** Security-domain errors (ARCHITECTURE §74 taxonomy). */
public class PackageVerificationError(message: String, detail: String? = null) :
    ForgeError(message, detail)

public class TrustDecisionError(message: String, detail: String? = null) :
    ForgeError(message, detail)

/**
 * Identity of a package publisher (ARCHITECTURE §26).
 *
 * A publisher is identified by the ed25519 public key fingerprint — the display
 * name is metadata, never identity. Unsigned packages carry a `null` publisher
 * and are by definition [TrustLevel.UNTRUSTED].
 */
public data class PublisherIdentity(
    /** Stable identifier: lowercase hex SHA-256 of the ed25519 public key. */
    public val keyFingerprint: String,
    /** Human-readable publisher label (not part of trust). */
    public val displayName: String?,
    /** Optional homepage/contact for the review UI. */
    public val contact: String?,
) {
    init {
        require(keyFingerprint.matches(Regex("^[0-9a-f]{64}$"))) {
            "key fingerprint must be 64 lowercase hex chars: '$keyFingerprint'"
        }
    }

    override fun toString(): String = displayName ?: keyFingerprint.take(16)
}

/** Trust classification for an imported plugin package. */
public enum class TrustLevel {
    /** Publisher key pinned in the trust store — packages can still be rejected on content. */
    TRUSTED,

    /** No signature or unknown publisher: user approval is mandatory (§25). */
    UNTRUSTED,
}

/**
 * Detached signature block for a `.forge` package (ARCHITECTURE §26).
 *
 * Signs the CANONICAL HASH MANIFEST — an ordered, deterministic text document
 * listing every file's SHA-256 — so the signature covers the whole archive
 * content, not just the manifest.json.
 */
public data class SignatureMetadata(
    /** Signature algorithm family. Only `ed25519` exists in v1. */
    public val algorithm: String,
    /** Fingerprint of the signing public key. */
    public val keyFingerprint: String,
    /** Raw ed25519 signature bytes (64 bytes). */
    public val signature: ByteArray,
) {
    init {
        require(algorithm == ALGORITHM_ED25519) { "unsupported signature algorithm '$algorithm'" }
        require(signature.size == 64) { "ed25519 signature must be 64 bytes, was ${signature.size}" }
        require(keyFingerprint.matches(Regex("^[0-9a-f]{64}$"))) {
            "key fingerprint must be 64 lowercase hex chars"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is SignatureMetadata &&
            algorithm == other.algorithm &&
            keyFingerprint == other.keyFingerprint &&
            signature.contentEquals(other.signature)

    override fun hashCode(): Int =
        31 * (31 * algorithm.hashCode() + keyFingerprint.hashCode()) + signature.contentHashCode()

    public companion object {
        public const val ALGORITHM_ED25519: String = "ed25519"
    }
}
