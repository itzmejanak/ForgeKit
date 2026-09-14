package com.forgekit.plugin.installer

import com.forgekit.core.security.CanonicalHashManifest
import com.forgekit.core.security.PackageSigner
import com.forgekit.core.security.PublisherKeyPair
import com.forgekit.core.security.TrustStore
import com.forgekit.plugin.api.PluginId
import com.forgekit.plugin.manifest.ManifestParser
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.CRC32

/**
 * TEST-ONLY `.forge` package builder producing REAL zip archives — content
 * files via a mode-aware low-level writer, signing via the REAL ed25519
 * [PackageSigner]. Same zip-writing technique as runtime/bootstrap's
 * ModeZipWriter (external attributes need byte-level writing).
 */
class ForgePackageBuilder(private val entries: MutableMap<String, Pair<ByteArray, Int>> = LinkedHashMap()) {

    fun file(path: String, content: String, mode: Int = 0x1A4): ForgePackageBuilder =
        apply { entries[path] = Pair(content.toByteArray(StandardCharsets.UTF_8), mode) }

    fun manifest(content: String): ForgePackageBuilder =
        file("manifest.json", content, 0x1A4)

    /** Adds an entry with an arbitrary raw mode (fixture for symlink/prohibited-content tests). */
    fun rawEntry(path: String, content: String, rawMode: Int): ForgePackageBuilder =
        apply { entries[path] = Pair(content.toByteArray(StandardCharsets.UTF_8), rawMode) }

    /** Signs the package with a REAL ed25519 key: HASHES + SIGNATURE.json + publisher.json. */
    fun signedBy(key: PublisherKeyPair, displayName: String? = null, contact: String? = null): ForgePackageBuilder {
        val files = entries.mapValues { entry -> entry.value.first }
        val document = CanonicalHashManifest.build(files)
        entries["HASHES"] = Pair(document.toByteArray(StandardCharsets.UTF_8), 0x1A4)
        val signature = PackageSigner(key).sign(document)
        entries["SIGNATURE.json"] = Pair(signatureJson(signature).toByteArray(StandardCharsets.UTF_8), 0x1A4)
        entries["publisher.json"] = Pair(publisherJson(key, displayName, contact).toByteArray(StandardCharsets.UTF_8), 0x1A4)
        return this
    }

    private fun signatureJson(signature: com.forgekit.core.security.SignatureMetadata): String = """
        {"algorithm":"${signature.algorithm}","keyFingerprint":"${signature.keyFingerprint}",
         "signature":"${Base64.getEncoder().encodeToString(signature.signature)}"}
    """.trimIndent().replace("\n", " ")

    private fun publisherJson(key: PublisherKeyPair, displayName: String?, contact: String?): String = """
        {"displayName":${displayName?.let { "\"$it\"" } ?: "null"},
         "contact":${contact?.let { "\"$it\"" } ?: "null"},
         "publicKey":"${Base64.getEncoder().encodeToString(key.publicKey)}"}
    """.trimIndent().replace("\n", " ")

    /** Writes the .forge archive to [target]. */
    fun writeTo(target: Path): Path {
        Files.createDirectories(target.parent)
        ModeAwareZipWriter(Files.newOutputStream(target)).use { writer ->
            for (entry in entries) {
                writer.entry(entry.key, entry.value.first, entry.value.second)
            }
        }
        return target
    }

    /** Opens the written package through the production gate. */
    fun open(target: Path, parser: ManifestParser = ManifestParser()): ForgePackage =
        ForgePackage.open(target, parser)

    companion object {
        val MANIFEST: String = """
            {
              "schema": "forgekit.plugin/v1",
              "id": "com.example.tool",
              "name": "Example Tool",
              "version": "1.0.0",
              "description": "demo",
              "runtime": { "type": "python", "version": ">=3.6" },
              "entrypoint": "runtime/main.py",
              "ui": { "entry": "ui/main.json" },
              "dependencies": { "termux": ["python"], "python": [ {"name":"r2pipe","version":"==1.9.8"} ] },
              "permissions": ["network", "files.read"],
              "actions": [
                { "id": "run", "title": "Run", "inputs": [ {"id":"target","label":"Target","type":"text","required":true} ] }
              ]
            }
        """.trimIndent()

        /** Full standard package: manifest + runtime + ui + deps + assets. */
        fun standard(): ForgePackageBuilder = ForgePackageBuilder()
            .manifest(MANIFEST)
            .file("runtime/main.py", "print('forgekit plugin')\n", 0x1A4)
            .file("runtime/helper.py", "def helper():\n    return 42\n", 0x1A4)
            .file("runtime/exec.sh", "#!/usr/bin/env bash\necho exec-ok\n", 0x1ED)
            .file("ui/main.json", """{"schema":"forgekit.ui/v1"}""", 0x1A4)
            .file("dependencies/requirements.txt", "r2pipe==1.9.8\n", 0x1A4)
            .file("assets/icon.txt", "icon", 0x1A4)
    }
}

/**
 * Minimal mode-aware STORED zip writer for tests (external attributes require
 * byte-level writing; mirrors runtime/bootstrap's ModeZipWriter).
 */
private class ModeAwareZipWriter(private val output: OutputStream) : AutoCloseable {

    private data class Entry(val name: String, val data: ByteArray, val mode: Int, val offset: Int)

    private val entries = mutableListOf<Entry>()
    private var offset = 0

    fun entry(name: String, data: ByteArray, unixMode: Int) {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
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
        val central = ByteArray(entries.sumOf { 46 + it.name.toByteArray(StandardCharsets.UTF_8).size })
        var p = 0
        for (e in entries) {
            val nameBytes = e.name.toByteArray(StandardCharsets.UTF_8)
            put32(central, p, 0x02014b50)
            put16(central, p + 4, 20)          // version made by
            put16(central, p + 6, 20)          // version needed
            put16(central, p + 8, 0x0800)      // flags: UTF-8
            put16(central, p + 10, 0)          // STORED
            put16(central, p + 12, 0)          // time
            put16(central, p + 14, 0)          // date
            val crc = CRC32().apply { update(e.data) }
            put32(central, p + 16, crc.value.toInt())
            put32(central, p + 20, e.data.size)
            put32(central, p + 24, e.data.size)
            put16(central, p + 28, nameBytes.size)
            put16(central, p + 30, 0)          // extra len
            put16(central, p + 32, 0)          // comment len
            put16(central, p + 34, 0)          // disk number
            put16(central, p + 36, 0)          // internal attrs
            // external attrs: (S_IFREG | mode) << 16 — S_IFREG = 0o100000 = 0x8000
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
