package com.forgekit.tools.forgebuilder

import com.forgekit.core.security.CanonicalHashManifest
import com.forgekit.core.security.PackageSigner
import com.forgekit.core.security.PublisherKeyPair
import com.forgekit.plugin.manifest.ManifestParser
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import java.util.zip.CRC32

/**
 * Production `.forge` packager: `forge build <src-dir>` (STRUCTURE.md §15).
 *
 * Assembles a plugin source directory into a real package:
 *  - manifest.json parsed and validated STRICTLY first (no broken package
 *    ever leaves the builder);
 *  - content collected from the reserved areas in deterministic order;
 *  - real Unix modes preserved in the archive (executable bits survive);
 *  - optional REAL ed25519 signing: canonical HASHES document + detached
 *    signature + publisher block — the same scheme the importer verifies.
 */
public object ForgePackager {

    /** The areas a source tree may contribute (mirrors ForgePackage.RESERVED_AREAS). */
    public val AREAS: List<String> = listOf("runtime", "ui", "dependencies", "assets", "docs", "tests")

    /** Result of one build. */
    public data class BuildResult(
        public val output: Path,
        public val fileCount: Int,
        public val sha256: String,
        public val signed: Boolean,
        public val keyFingerprint: String?,
    )

    /**
     * Builds [sourceDir] into [output].
     *
     * @param key optional publisher key; when present the package is signed
     *   for real (HASHES + SIGNATURE.json + publisher.json).
     * @param displayName optional publisher label embedded in publisher.json.
     */
    public fun build(
        sourceDir: Path,
        output: Path,
        key: PublisherKeyPair? = null,
        displayName: String? = null,
    ): BuildResult {
        require(Files.isDirectory(sourceDir)) { "source directory does not exist: $sourceDir" }
        val manifestFile = sourceDir.resolve("manifest.json")
        require(Files.isRegularFile(manifestFile)) { "source directory has no manifest.json" }

        // strict gate first — the builder never ships an invalid manifest
        val manifest = ManifestParser().parse(Files.readAllBytes(manifestFile))

        // deterministic content collection: area order, then path order
        val entries = LinkedHashMap<String, Pair<ByteArray, Int>>()
        entries["manifest.json"] = Files.readAllBytes(manifestFile) to modeOf(manifestFile)
        for (area in AREAS) {
            val areaDir = sourceDir.resolve(area)
            if (!Files.isDirectory(areaDir)) continue
            val files = mutableListOf<Path>()
            Files.walk(areaDir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach(files::add)
            }
            files.sortedBy { it.toString() }.forEach { file ->
                val relative = sourceDir.relativize(file).toString()
                entries[relative] = Files.readAllBytes(file) to modeOf(file)
            }
        }

        // real signing: canonical hashes over the CONTENT entries only
        if (key != null) {
            val content = entries.mapValues { it.value.first }
            val document = CanonicalHashManifest.build(content)
            entries["HASHES"] = document.toByteArray(Charsets.UTF_8) to 0x1A4
            val signature = PackageSigner(key).sign(document)
            entries["SIGNATURE.json"] = signatureJson(signature).toByteArray(Charsets.UTF_8) to 0x1A4
            entries["publisher.json"] = publisherJson(key, displayName).toByteArray(Charsets.UTF_8) to 0x1A4
        }

        Files.createDirectories(output.toAbsolutePath().parent)
        ModeAwareZipWriter(Files.newOutputStream(output)).use { writer ->
            for ((path, data) in entries) {
                val (bytes, mode) = data
                writer.entry(path, bytes, mode)
            }
        }
        return BuildResult(
            output = output,
            fileCount = entries.size,
            sha256 = sha256Of(output),
            signed = key != null,
            keyFingerprint = key?.fingerprint,
        )
    }

    // ---- helpers ------------------------------------------------------------

    private fun modeOf(file: Path): Int {
        val permissions: Set<PosixFilePermission> = try {
            Files.getPosixFilePermissions(file)
        } catch (e: UnsupportedOperationException) {
            return 0x1A4 // 0644 default where POSIX is unavailable
        }
        var mode = 0
        if (PosixFilePermission.OWNER_READ in permissions) mode = mode or 0x100
        if (PosixFilePermission.OWNER_WRITE in permissions) mode = mode or 0x80
        if (PosixFilePermission.OWNER_EXECUTE in permissions) mode = mode or 0x40
        if (PosixFilePermission.GROUP_READ in permissions) mode = mode or 0x20
        if (PosixFilePermission.GROUP_WRITE in permissions) mode = mode or 0x10
        if (PosixFilePermission.GROUP_EXECUTE in permissions) mode = mode or 0x8
        if (PosixFilePermission.OTHERS_READ in permissions) mode = mode or 0x4
        if (PosixFilePermission.OTHERS_WRITE in permissions) mode = mode or 0x2
        if (PosixFilePermission.OTHERS_EXECUTE in permissions) mode = mode or 0x1
        return mode
    }

    private fun signatureJson(signature: com.forgekit.core.security.SignatureMetadata): String =
        """
        {"algorithm":"${signature.algorithm}","keyFingerprint":"${signature.keyFingerprint}",
         "signature":"${Base64.getEncoder().encodeToString(signature.signature)}"}
        """.trimIndent().replace("\n", " ")

    private fun publisherJson(key: PublisherKeyPair, displayName: String?): String =
        """
        {"displayName":${displayName?.let { "\"$it\"" } ?: "null"},
         "contact":null,
         "publicKey":"${Base64.getEncoder().encodeToString(key.publicKey)}"}
        """.trimIndent().replace("\n", " ")

    private fun sha256Of(file: Path): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Mode-aware STORED zip writer (production twin of the bootstrap's
 * ModeZipWriter: external attributes need byte-level writing, java.util.zip
 * cannot set Unix modes).
 */
private class ModeAwareZipWriter(private val output: OutputStream) : AutoCloseable {

    private data class Entry(val name: String, val data: ByteArray, val mode: Int, val offset: Int)

    private val entries = mutableListOf<Entry>()
    private var offset = 0

    fun entry(name: String, data: ByteArray, unixMode: Int) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(data) }
        val header = ByteArray(30 + nameBytes.size)
        put32(header, 0, 0x04034b50)
        put16(header, 4, 20)
        put16(header, 6, 0x0800)
        put16(header, 8, 0) // STORED
        put32(header, 14, crc.value.toInt())
        put32(header, 18, data.size)
        put32(header, 22, data.size)
        put16(header, 26, nameBytes.size)
        nameBytes.copyInto(header, 30)
        val entryOffset = offset
        output.write(header)
        output.write(data)
        entries += Entry(name, data, unixMode, entryOffset)
        offset += header.size + data.size
    }

    override fun close() {
        val central = ByteArray(entries.sumOf { 46 + it.name.toByteArray(Charsets.UTF_8).size })
        var p = 0
        for (e in entries) {
            val nameBytes = e.name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(e.data) }
            put32(central, p, 0x02014b50)
            put16(central, p + 4, 20)
            put16(central, p + 6, 20)
            put16(central, p + 8, 0x0800)
            put16(central, p + 10, 0)
            put16(central, p + 12, 0)
            put16(central, p + 14, 0)
            put32(central, p + 16, crc.value.toInt())
            put32(central, p + 20, e.data.size)
            put32(central, p + 24, e.data.size)
            put16(central, p + 28, nameBytes.size)
            put16(central, p + 30, 0)
            put16(central, p + 32, 0)
            put16(central, p + 34, 0)
            put16(central, p + 36, 0)
            put32(central, p + 38, ((e.mode or 0x8000) shl 16))
            put32(central, p + 42, e.offset)
            nameBytes.copyInto(central, p + 46)
            p += 46 + nameBytes.size
        }
        output.write(central)
        val eocd = ByteArray(22)
        put32(eocd, 0, 0x06054b50)
        put16(eocd, 8, entries.size)
        put16(eocd, 10, entries.size)
        put32(eocd, 12, central.size)
        put32(eocd, 16, offset)
        output.write(eocd)
        output.flush()
    }

    private fun put16(b: ByteArray, p: Int, v: Int) {
        b[p] = (v and 0xFF).toByte()
        b[p + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun put32(b: ByteArray, p: Int, v: Int) {
        b[p] = (v and 0xFF).toByte()
        b[p + 1] = ((v shr 8) and 0xFF).toByte()
        b[p + 2] = ((v shr 16) and 0xFF).toByte()
        b[p + 3] = ((v shr 24) and 0xFF).toByte()
    }
}
