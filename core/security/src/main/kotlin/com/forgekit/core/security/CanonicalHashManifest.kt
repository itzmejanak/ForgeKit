package com.forgekit.core.security

import java.security.MessageDigest
import java.util.Base64

/**
 * The canonical hash manifest of a `.forge` package: one line per file,
 * `sha256  <relative-path>` with paths sorted bytewise (UTF-8). Deterministic
 * across platforms and zip tools — this document is what signatures sign.
 *
 * Format (v1):
 * ```text
 * forgekit.hashes/v1
 * sha256 <64-hex> runtime/main.py
 * sha256 <64-hex> manifest.json
 * ...
 * ```
 */
public object CanonicalHashManifest {

    public const val HEADER: String = "forgekit.hashes/v1"

    /** Builds the canonical document from relative paths and their contents. */
    public fun build(files: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val lines = files.entries
            .sortedWith(compareBy({ byteOrderKey(it.key) }))
            .joinToString("\n") { (path, bytes) ->
                "sha256 ${hashHex(bytes, digest)} ${normalizePath(path)}"
            }
        return "$HEADER\n$lines\n"
    }

    /** Builds from lazy file contents (streams are read exactly once, closed). */
    public fun buildStreaming(files: Map<String, () -> java.io.InputStream>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val entries = files.entries
            .map { (path, opener) ->
                val hash = opener().use { input ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        digest.update(buf, 0, n)
                    }
                    hex(digest.digest())
                }
                normalizePath(path) to hash
            }
        val lines = entries
            .sortedWith(compareBy({ (path, _) -> byteOrderKey(path) }))
            .joinToString("\n") { (path, hash) -> "sha256 $hash $path" }
        return "$HEADER\n$lines\n"
    }

    /**
     * Parses a previously written document into path→sha256 pairs.
     * Structural failures throw [PackageVerificationError].
     */
    public fun parse(document: String): List<Pair<String, String>> {
        val lines = document.lineSequence().toList()
        if (lines.isEmpty() || lines.first() != HEADER) {
            throw PackageVerificationError("hash manifest header missing", "expected $HEADER")
        }
        val result = mutableListOf<Pair<String, String>>()
        val seen = HashSet<String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val match = Regex("^sha256 ([0-9a-f]{64}) (.+)$").find(line)
                ?: throw PackageVerificationError("malformed hash manifest line", line.take(120))
            val (hash, path) = match.destructured
            if (!seen.add(path)) {
                throw PackageVerificationError("duplicate file in hash manifest", path)
            }
            result += path to hash
        }
        if (result.isEmpty()) {
            throw PackageVerificationError("hash manifest lists no files")
        }
        return result
    }

    /** SHA-256 of a small byte array (used for key fingerprints and tests). */
    public fun hashHex(bytes: ByteArray, digest: MessageDigest = MessageDigest.getInstance("SHA-256")): String =
        digest.let { d -> d.reset(); hex(d.digest(bytes)) }

    /** SHA-256 of a streaming document (e.g. the manifest text itself). */
    public fun documentSha256(document: String): String =
        hashHex(document.toByteArray(Charsets.UTF_8))

    private fun normalizePath(path: String): String =
        path.replace('\\', '/').trimStart('/')

    /** Bytewise (UTF-8) ordering key: fixed-width hex per byte. */
    private fun byteOrderKey(path: String): String =
        path.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
