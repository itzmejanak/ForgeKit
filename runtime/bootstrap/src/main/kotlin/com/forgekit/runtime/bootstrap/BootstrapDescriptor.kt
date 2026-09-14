package com.forgekit.runtime.bootstrap

import com.forgekit.core.logging.ForgeLogger
import com.forgekit.core.logging.LogCategory
import java.io.InputStream

/** Description of one architecture-specific bootstrap archive (STRUCTURE.md §8.3). */
public data class BootstrapDescriptor(
    /** Device ABI this bootstrap targets, e.g. `arm64-v8a`. */
    val abi: String,
    /** Where the bootstrap zip can be read from (APK asset, downloaded file, …). */
    val source: BootstrapSource,
    /** Expected SHA-256 of the zip; null disables hash verification (never in production). */
    val expectedSha256: String?,
    /** Expected byte size, if known. */
    val expectedSizeBytes: Long?,
    /** Termux suite the bootstrap targets (e.g. `apt-android-7`). */
    val termuxSuite: String,
    /**
     * App data root the bootstrap's packages were BUILT against, as compiled into their
     * binaries and config: `$PREFIX` is `<dataRoot>/usr`, `$HOME` is `<dataRoot>/home`.
     * Extracting anywhere else requires rewriting this string (see PrefixRewriter), so
     * the installed root must be no longer than this one.
     */
    val compiledDataRoot: String = DEFAULT_TERMUX_DATA_ROOT,
    /**
     * Cache root compiled into the bootstrap. apt's `Dir::Cache` lives here, OUTSIDE
     * `$PREFIX`, so relocating the prefix alone leaves apt trying to write into the
     * Termux app's private directory and failing with EACCES.
     */
    val compiledCacheRoot: String = DEFAULT_TERMUX_CACHE_ROOT,
) {
    public companion object {
        /** Upstream Termux app data root — 27 bytes. */
        public const val DEFAULT_TERMUX_DATA_ROOT: String = "/data/data/com.termux/files"

        /** Upstream Termux cache root — 27 bytes. */
        public const val DEFAULT_TERMUX_CACHE_ROOT: String = "/data/data/com.termux/cache"
    }
}

/** A place a bootstrap archive can be streamed from. */
public fun interface BootstrapSource {
    /** Opens the bootstrap zip stream. Must be re-openable for verify-then-extract. */
    public fun open(): InputStream
}

/** A bootstrap already staged on the filesystem. */
public class FileBootstrapSource(private val file: java.nio.file.Path) : BootstrapSource {
    override fun open(): InputStream = java.nio.file.Files.newInputStream(file)
}

/**
 * Verifies a bootstrap archive before it is trusted (ARCHITECTURE §69: "verify bootstrap").
 *
 * Verification runs on the STAGED archive file (the orchestrator copies the bootstrap
 * from its source — APK native lib, download, … — before calling this):
 *  1. raw byte size + file-level SHA-256 (the convention Termux publishes checksums in);
 *  2. zip structure via the central directory (signature walk, entry count);
 *  3. deep CRC pass — every entry is fully decompressed so java.util.zip validates CRCs.
 *
 * This is the trust gate: extraction only happens after verify() returns null.
 */
public class BootstrapVerifier(private val logger: ForgeLogger = ForgeLogger(LogCategory.RUNTIME, {})) {

    /** Returns verification failure detail, or null when the bootstrap is sound. */
    public fun verify(stagedZip: java.nio.file.Path, descriptor: BootstrapDescriptor): String? {
        // 1. raw size + file-level SHA-256
        val raw: ByteArray
        try {
            raw = java.nio.file.Files.readAllBytes(stagedZip)
        } catch (e: Exception) {
            return "bootstrap unreadable: ${e.message}"
        }
        if (raw.isEmpty()) return "bootstrap archive is empty"
        val size = raw.size.toLong()
        val actualHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw).joinToString("") { "%02x".format(it) }

        // 1b. the archive must begin AT the first local file header. Android's
        // java.util.zip is backed by libziparchive, which rejects archives with
        // prepended data ("Entry at offset zero has invalid LFH signature ..."),
        // and both this verifier's CRC pass and BootstrapExtractor read content
        // through ZipFile. Desktop JVMs and Python tolerate a prefix, so an
        // ELF-wrapped bootstrap (Termux also publishes the payload as a .so stub
        // with the zip appended) passes every host test and then fails only on
        // device — this check is what makes the host suite representative.
        prefixLength(raw)?.let { prefix ->
            return "bootstrap archive has $prefix bytes of data before the first " +
                "local file header (Android's zip reader requires a prefix-free " +
                "archive — strip the leading $prefix bytes)"
        }

        // 2. central-directory structure + entry-count sanity
        val records = try {
            ZipCentralDirectory.parse(stagedZip).allRecords()
        } catch (e: Exception) {
            return "bootstrap zip structure invalid: ${e.message}"
        }
        if (records.size < MIN_ENTRIES) {
            return "bootstrap archive suspiciously small: ${records.size} entries"
        }

        // 3. deep integrity: fully read every entry so CRCs are validated
        val entriesRead = try {
            java.util.zip.ZipFile(stagedZip.toFile()).use { zip ->
                val buf = ByteArray(65536)
                var count = 0
                val enum = zip.entries()
                while (enum.hasMoreElements()) {
                    val entry = enum.nextElement()
                    if (entry.isDirectory) continue
                    zip.getInputStream(entry).use { input ->
                        while (true) {
                            if (input.read(buf) < 0) break
                        }
                    }
                    count++
                }
                count
            }
        } catch (e: Exception) {
            return "bootstrap zip integrity check failed: ${e.message}"
        }
        if (entriesRead < MIN_ENTRIES) {
            return "bootstrap archive suspiciously small: $entriesRead entries"
        }

        // 4. expected size + hash (raw archive bytes)
        descriptor.expectedSizeBytes?.let {
            if (it != size) return "bootstrap size mismatch: expected $it, got $size"
        }
        descriptor.expectedSha256?.let { expected ->
            if (!actualHash.equals(expected, ignoreCase = true)) {
                return "bootstrap hash mismatch: expected $expected, got $actualHash"
            }
        }
        val hashNote = if (descriptor.expectedSha256 != null) "ok" else "(not pinned)"
        logger.info(
            "BootstrapVerifier",
            "bootstrap verified: $entriesRead entries, $size bytes, sha256 $hashNote",
        )
        return null
    }

    /** Bytes preceding the first local file header, or null when the archive starts at one. */
    private fun prefixLength(raw: ByteArray): Int? {
        if (raw.size >= 4 && raw.regionMatches(0, LFH_SIGNATURE)) return null
        val at = raw.indexOf(LFH_SIGNATURE)
        return if (at > 0) at else raw.size // no LFH at all: report the whole file
    }

    private fun ByteArray.regionMatches(offset: Int, signature: ByteArray): Boolean =
        signature.indices.all { this[offset + it] == signature[it] }

    private fun ByteArray.indexOf(signature: ByteArray): Int {
        outer@ for (i in 0..(size - signature.size)) {
            for (j in signature.indices) if (this[i + j] != signature[j]) continue@outer
            return i
        }
        return -1
    }

    private companion object {
        /** Real Termux bootstraps carry ~3000 entries; far fewer means a broken archive. */
        const val MIN_ENTRIES = 100

        /** "PK" — ZIP local file header. */
        val LFH_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    }
}
