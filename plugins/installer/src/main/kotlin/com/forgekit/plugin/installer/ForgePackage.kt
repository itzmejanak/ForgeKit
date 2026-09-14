package com.forgekit.plugin.installer

import com.forgekit.core.security.CanonicalHashManifest
import com.forgekit.core.security.SignatureMetadata
import com.forgekit.plugin.api.PluginError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * An opened `.forge` package: a ZIP archive following the v1 layout.
 *
 * ```text
 * plugin.forge
 * ├── manifest.json        ← required (forgekit.plugin/v1)
 * ├── HASHES               ← optional: canonical hash manifest (what signatures sign)
 * ├── SIGNATURE.json       ← optional: detached ed25519 signature over HASHES
 * ├── publisher.json       ← optional: {displayName, contact, publicKey(base64)}
 * ├── runtime/…            ← required area (entrypoint lives here)
 * ├── ui/…  dependencies/…  assets/…  docs/…  tests/…
 * ```
 *
 * Opening performs the structural gate only (manifest parses, no path escapes,
 * no prohibited entries): cryptographic and policy validation is the
 * validator's job (§72 order: parse → verify → validate).
 */
public class ForgePackage private constructor(
    /** The archive file on disk (already copied out of any picker/stream). */
    public val file: Path,
    public val manifest: com.forgekit.plugin.manifest.PluginManifest,
    public val rawManifestBytes: ByteArray,
    public val signature: SignatureMetadata?,
    public val publisher: PublisherBlock?,
    public val rawHashesDocument: String?,
    public val entries: List<PackagedFile>,
) {

    /** publisher.json block (identity metadata + the signing public key). */
    @Serializable
    public data class PublisherBlock(
        public val displayName: String? = null,
        public val contact: String? = null,
        /** Base64 ed25519 public key (32 bytes decoded). */
        public val publicKey: String,
    ) {
        public fun publicKeyBytes(): ByteArray =
            runCatching { Base64.getDecoder().decode(publicKey) }.getOrElse {
                throw PluginError("publisher.json publicKey is not valid base64")
            }
    }

    /** One regular file inside the package, classified for the review UI. */
    public data class PackagedFile(
        public val path: String,
        public val sizeBytes: Long,
        public val uncompressedCrc: Long,
        public val classification: ContentClass,
    )

    /** Content classification shown as `<class> · <kind>` in the import review. */
    public enum class ContentClass(public val label: String) {
        MANIFEST("REGULAR · MANIFEST"),
        RUNTIME("REGULAR · RUNTIME"),
        UI("REGULAR · UI"),
        DATA("REGULAR · DATA"),
        ASSET("REGULAR · ASSET"),
        DOC("REGULAR · DOC"),
        TEST("REGULAR · TEST"),
        SIGNING("REGULAR · SIGNING"),
        UNCLASSIFIED("REGULAR · DATA"),
    }

    /** SHA-256 of the whole archive file (the import review's PACKAGE SHA-256). */
    public val packageSha256: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** SHA-256 of the raw manifest.json bytes (CANONICAL MANIFEST SHA-256). */
    public val manifestSha256: String by lazy {
        CanonicalHashManifest.hashHex(rawManifestBytes)
    }

    /** Size of the archive in bytes. */
    public val packageSizeBytes: Long get() = Files.size(file)

    /**
     * Reads every file's bytes (small packages; the validator needs full
     * contents for hash/integrity checks anyway).
     */
    public fun readAllFiles(): Map<String, ByteArray> {
        ZipFile(file.toFile()).use { zip ->
            val result = LinkedHashMap<String, ByteArray>()
            for (entry in entries) {
                val zipEntry = zip.getEntry(entry.path) ?: continue
                result[entry.path] = zip.getInputStream(zipEntry).use { it.readBytes() }
            }
            return result
        }
    }

    public fun findEntry(path: String): PackagedFile? = entries.firstOrNull { it.path == path }

    public companion object {
        public const val MANIFEST_ENTRY: String = "manifest.json"
        public const val HASHES_ENTRY: String = "HASHES"
        public const val SIGNATURE_ENTRY: String = "SIGNATURE.json"
        public const val PUBLISHER_ENTRY: String = "publisher.json"

        /** Package areas recognized by the v1 layout. */
        public val RESERVED_AREAS: List<String> =
            listOf("runtime", "ui", "dependencies", "assets", "docs", "tests")

        /**
         * Opens and structurally gates a .forge archive.
         * Throws [PluginError] with the precise problem — never a silent pass.
         */
        public fun open(file: Path, parser: com.forgekit.plugin.manifest.ManifestParser): ForgePackage {
            if (!Files.isRegularFile(file)) {
                throw PluginError("package file does not exist", file.toString())
            }
            val zip = try {
                ZipFile(file.toFile())
            } catch (e: Exception) {
                throw PluginError("package is not a readable ZIP archive", e.message)
            }
            zip.use { z ->
                val entries = mutableListOf<PackagedFile>()
                val zipEntries = z.entries()
                while (zipEntries.hasMoreElements()) {
                    val entry: ZipEntry = zipEntries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name.removePrefix("./")
                    validateEntryName(name)
                    if (!isAllowedLocation(name)) {
                        throw PluginError(
                            "package entry '$name' outside a reserved area",
                            "allowed: ${RESERVED_AREAS.joinToString(", ")} + root signing files",
                        )
                    }
                    entries += PackagedFile(
                        path = name,
                        sizeBytes = entry.size,
                        uncompressedCrc = entry.crc,
                        classification = classify(name),
                    )
                }
                if (entries.isEmpty()) throw PluginError("package contains no files")

                val manifestBytes = readEntry(z, MANIFEST_ENTRY)
                    ?: throw PluginError("manifest.json missing from package")
                val manifest = parser.parse(manifestBytes)

                val hashes = readEntryText(z, HASHES_ENTRY)
                val signature = readEntryText(z, SIGNATURE_ENTRY)?.let { parseSignature(it) }
                val publisher = readEntryText(z, PUBLISHER_ENTRY)?.let { parsePublisher(it) }

                // consistency: a signature without hashes document is meaningless
                if (signature != null && hashes == null) {
                    throw PluginError("SIGNATURE.json present but HASHES missing")
                }

                return ForgePackage(
                    file = file,
                    manifest = manifest,
                    rawManifestBytes = manifestBytes,
                    signature = signature,
                    publisher = publisher,
                    rawHashesDocument = hashes,
                    entries = entries,
                )
            }
        }

        private fun validateEntryName(name: String) {
            if (name.isBlank() || name.startsWith("/") || name.endsWith("/")) {
                throw PluginError("package entry has an invalid name", name)
            }
            if (name.split('/').any { it == ".." || it == "." || it.isBlank() }) {
                throw PluginError("package entry escapes or is non-canonical", name)
            }
            if (name.contains('\\')) throw PluginError("package entry uses backslash", name)
        }

        private fun isAllowedLocation(name: String): Boolean {
            val area = name.substringBefore('/')
            return area in RESERVED_AREAS ||
                name == MANIFEST_ENTRY || name == HASHES_ENTRY ||
                name == SIGNATURE_ENTRY || name == PUBLISHER_ENTRY ||
                name == "forge.json" // v1 app compatibility alias for manifest.json
        }

        private fun classify(name: String): ContentClass = when {
            name == MANIFEST_ENTRY || name == "forge.json" -> ContentClass.MANIFEST
            name == HASHES_ENTRY || name == SIGNATURE_ENTRY || name == PUBLISHER_ENTRY -> ContentClass.SIGNING
            name.startsWith("runtime/") -> ContentClass.RUNTIME
            name.startsWith("ui/") -> ContentClass.UI
            name.startsWith("dependencies/") -> ContentClass.DATA
            name.startsWith("assets/") -> ContentClass.ASSET
            name.startsWith("docs/") -> ContentClass.DOC
            name.startsWith("tests/") -> ContentClass.TEST
            else -> ContentClass.UNCLASSIFIED
        }

        private fun readEntry(zip: ZipFile, name: String): ByteArray? =
            zip.getEntry(name)?.let { zip.getInputStream(it).use { s -> s.readBytes() } }

        private fun readEntryText(zip: ZipFile, name: String): String? =
            readEntry(zip, name)?.toString(Charsets.UTF_8)

        @Serializable
        private data class SignatureBlock(
            val algorithm: String,
            val keyFingerprint: String,
            val signature: String,
        )

        private fun parseSignature(text: String): SignatureMetadata {
            val block = try {
                Json { ignoreUnknownKeys = false }.decodeFromString(SignatureBlock.serializer(), text)
            } catch (e: Exception) {
                throw PluginError("SIGNATURE.json malformed", e.message)
            }
            val bytes = try {
                Base64.getDecoder().decode(block.signature)
            } catch (e: IllegalArgumentException) {
                throw PluginError("SIGNATURE.json signature is not valid base64")
            }
            return try {
                SignatureMetadata(
                    algorithm = block.algorithm,
                    keyFingerprint = block.keyFingerprint,
                    signature = bytes,
                )
            } catch (e: IllegalArgumentException) {
                throw PluginError("SIGNATURE.json invalid", e.message)
            }
        }

        private fun parsePublisher(text: String): PublisherBlock = try {
            Json { ignoreUnknownKeys = false }.decodeFromString(PublisherBlock.serializer(), text)
        } catch (e: Exception) {
            throw PluginError("publisher.json malformed", e.message)
        }
    }
}
